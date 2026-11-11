package lk.spokenenglish.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs one session of a day, step by step, from the lesson files (Lessons.kt), laid out as in the mentor's
 * 18-screen drawing (dark): Day 0 set-up · Lesson · Practice drill · Before you talk (→ conversation → result) ·
 * Listening · Quick reactions · Free chat · Daily review recording · Say them again · Weekly test.
 * Every speaking screen has the app's one TalkBar. ← saves the exact step; Continue comes back to it.
 */
class SessionActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var headerBox: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var bottomBox: FrameLayout
    private lateinit var talk: TalkBar
    private lateinit var voice: CharacterVoice
    private lateinit var progress: Progress
    private lateinit var shake: ShakeReport

    private var week = 1
    private var dayNo = 1
    private var sid = ""
    private var day: JSONObject? = null
    private var session: JSONObject? = null
    private var steps: List<JSONObject> = emptyList()
    private var index = 0
    private var onAnswer: ((List<Pair<String, Boolean>>) -> Unit)? = null
    private var testMode = false
    private val testLog = JSONArray()
    private var pendingChat: JSONObject? = null
    private var voiceReady = false
    private var pendingSpeak: (() -> Unit)? = null
    private var activeSince = 0L

    /** What was said last (for "Again, please"); the 2nd replay is a bit slower (GPT review, item 12). */
    private var lastWho: Character = Characters.SAM
    private var lastText = ""
    private var replays = 0
    private var onPlayed: (() -> Unit)? = null
    /** Drills that teach asking ("Sorry, could you slow down?") must be judged, not replayed. */
    private var askingDrill = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = Progress(this)
        week = intent.getIntExtra("week", 1)
        dayNo = intent.getIntExtra("day", 0)
        sid = intent.getStringExtra("session") ?: ""
        buildUi()
        shake = ShakeReport(this) { "Week $week, day $dayNo, session $sid, step ${index + 1}" }
        voice = CharacterVoice(this) { ok -> voiceReady = ok; pendingSpeak?.invoke(); pendingSpeak = null }
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        voice.expressive = sp.getBoolean("expressive", true)
        voice.tuning = sp.getString("tuning", "v0.11") ?: "v0.11"
        voice.baseRate = sp.getFloat("baseRate", 0.92f)
        voice.onSpeaking = { t -> if (!t) { onPlayed?.invoke() } }
        thread {
            if (Lessons.weeks.isEmpty()) Lessons.load(this)
            main.post { begin() }
        }
    }

    private fun begin() {
        when (sid) {
            "sayagain" -> steps = listOf(JSONObject().put("type", "sayagain"))
            "reactions" -> steps = listOf(JSONObject().put("type", "react").put("who", "both").put("items", anyTimeReactions()).put("anytime", true))
            else -> {
                day = Lessons.day(week, dayNo)
                session = day?.let { d -> Lessons.sessions(d).firstOrNull { it.optString("id") == sid } }
                val s = session
                if (s == null) { content.addView(Ui.label(this, "This lesson couldn't be loaded. Check the internet and try again.", 16f)); return }
                val arr = s.getJSONArray("steps")
                steps = (0 until arr.length()).map { arr.getJSONObject(it) }
                if (progress.shortDay()) steps = shorten(steps)
            }
        }
        show(progress.pos(week, dayNo, sid).coerceIn(0, maxOf(0, steps.size - 1)))
    }

    /** 45-minute day (QA 11 Nov): Learn about 15 min (3 drills), Talk about 20 (fewer turns), Free chat 10. */
    private fun shorten(all: List<JSONObject>): List<JSONObject> {
        var drills = 0
        return all.filter { s -> if (sid == "learn" && s.optString("type") == "drill") { drills++; drills <= 3 } else true }
            .map { s -> if (s.optString("type") == "chat") JSONObject(s.toString()).put("minTurns", maxOf(4, s.optInt("minTurns") / 2)) else s }
    }

    override fun onResume() { super.onResume(); shake.start(); activeSince = SystemClock.elapsedRealtime() }
    override fun onPause() {
        super.onPause(); shake.stop(); voice.stop(); talk.stopIfRecording(); stopRecorder()
        if (activeSince > 0) progress.addPractice(SystemClock.elapsedRealtime() - activeSince); activeSince = 0
    }
    override fun onDestroy() { talk.destroy(); voice.shutdown(); main.removeCallbacksAndMessages(null); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = leave()

    /** ← keeps the exact step, so Continue on Home brings him back here (QA 11 Nov). */
    private fun leave() {
        if (sid != "sayagain" && sid != "reactions") progress.setPos(week, dayNo, sid, index)
        finish()
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.cream) }
        headerBox = FrameLayout(this)
        root.addView(headerBox)
        scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomBox = FrameLayout(this)
        root.addView(bottomBox)
        talk = TalkBar(this) { said -> handleAnswer(said) }
        talk.onStart = { voice.stop() }
        root.addView(talk.view)
        Ui.fitSystemBars(root)
        setContentView(root)
    }

    private fun header(title: String, subtitle: String, who: Character?) {
        headerBox.removeAllViews()
        headerBox.addView(Ui.header(this, title, subtitle, who, { leave() }).first)
    }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun add(v: View, top: Int = 12) { content.addView(v, Ui.lp(this, top)); scrollDown() }
    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    private fun text(t: CharSequence, size: Float = 15.5f, color: Int = Ui.ink, bold: Boolean = false) = Ui.label(this, t, size, color, bold)
    private fun nameIn(t: String) = t.replace("[his name]", Ui.name(this).replaceFirstChar { it.uppercase() })
    private fun hint(t: String?) = talk.setHint(if (dayNo <= 1) t else null)

    private fun needVoice(on: Boolean, hintText: String? = "Tap the mic to answer · tap Sinhala to speak Sinhala") {
        talk.view.visibility = if (on) View.VISIBLE else View.GONE
        talk.reset()
        hint(if (on) hintText else null)
    }

    private fun setBottom(v: View?) {
        bottomBox.removeAllViews()
        if (v != null) bottomBox.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(6); bottomMargin = dp(4) })
    }

    private fun next() { voice.stop(); show(index + 1) }

    private fun speak(who: Character, text: String) {
        lastWho = who; lastText = nameIn(text)
        val run = { voice.speak(lastText, who) }
        if (voiceReady) run() else pendingSpeak = run
    }

    /** "Again, please": replays what was said last; from the 2nd time a little slower. */
    private fun replay() {
        if (lastText.isBlank()) return
        replays++
        progress.add("againRequests")
        val old = voice.baseRate
        if (replays >= 2) voice.baseRate = (old - 0.08f).coerceAtLeast(0.78f)
        voice.speak(lastText, lastWho)
        main.postDelayed({ voice.baseRate = old }, 300)
    }

    private fun handleAnswer(said: List<Pair<String, Boolean>>) {
        if (!askingDrill && !TalkBar.hasSinhala(said) && Ui.isAgainRequest(TalkBar.plain(said))) {
            Ui.toast(this, "Playing it again")
            replay(); talk.reset(); return
        }
        onAnswer?.invoke(said)
    }

    /** The drawing's "SAM SAYS" card: ▶ + a still waveform + its length, and the "Again, please" line. */
    private fun saysCard(who: Character, say: String, capsText: String = "${who.name} says", extra: String? = null): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.caps(this, capsText))
        val r = Ui.row(this).apply { gravity = Gravity.CENTER }
        r.addView(IconButton(this, Glyph.PLAY, Ui.teal, Color.WHITE).apply { setOnClickListener { Ui.tap(it); lastWho = who; lastText = nameIn(say); voice.speak(lastText, who) } },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        r.addView(SoundBarsView(this).apply { color = Ui.bars; still(say.hashCode()) }, LinearLayout.LayoutParams(dp(120), dp(30)).apply { marginStart = dp(12) })
        r.addView(text("0:%02d".format(maxOf(1, (say.split(" ").size / 2.6).toInt())), 12.5f, Ui.muted).apply { setPadding(dp(8), 0, 0, 0) })
        card.addView(r, Ui.lp(this, 10))
        card.addView(text("Say \"Again, please\" to hear it again", 13f, Ui.muted).apply { gravity = Gravity.CENTER }, Ui.lp(this, 8))
        if (extra != null) card.addView(text(extra, 13f, Ui.muted).apply { gravity = Gravity.CENTER }, Ui.lp(this, 6))
        return card
    }

    // ---------------------------------------------------------------- steps

    private fun show(i: Int) {
        if (i >= steps.size) { finishSession(); return }
        index = i
        if (sid != "sayagain" && sid != "reactions") progress.setPos(week, dayNo, sid, i)
        content.removeAllViews()
        setBottom(null)
        onAnswer = null
        onPlayed = null
        voice.onSpeaking = { t -> if (!t) onPlayed?.invoke() }
        askingDrill = false
        replays = 0
        val s = steps[i]
        val who = Ui.character(s.optString("who", day?.optString("companion") ?: "both"), i)
        when (s.optString("type")) {
            "info" -> if (sid == "learn" && steps.getOrNull(i + 1)?.optString("type") == "model") stepLesson(s, steps[i + 1]) else stepInfo(s)
            "model" -> stepModel(s, who)
            "drill" -> stepDrill(s, who)
            "react" -> stepReact(s, who, 0, 0)
            "listen" -> stepListen(s, who)
            "chat" -> stepBeforeYouTalk(s, who)
            "freechat" -> startChat(s, who)
            "record" -> stepRecord(s, who)
            "miccheck", "agree" -> stepVoiceCheck(s)
            "speed" -> stepSpeed(0, mutableListOf())
            "sayagain" -> stepSayAgain(0, 0)
            "test" -> stepTestIntro(s)
            else -> next()
        }
    }

    // ---- Day 0 frame: "Getting started · Day 0 · part n of 3" and the 3 short parts ----

    private fun day0Parts(): List<JSONObject> = day?.let { Lessons.sessions(it) } ?: emptyList()

    private fun day0Header() {
        val parts = day0Parts()
        val n = parts.indexOfFirst { it.optString("id") == sid }.coerceAtLeast(0)
        header("Getting started", "Day 0 · part ${n + 1} of ${parts.size}", null)
        add(Ui.segments(this, parts.size, n, n), 0)
    }

    private fun day0Footer() {
        val parts = day0Parts()
        add(Ui.caps(this, "Day 0 · ${parts.size} short parts"), 20)
        val box = Ui.card(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        parts.forEachIndexed { k, p ->
            val done = progress.isDone(week, 0, p.optString("id"))
            val cur = p.optString("id") == sid
            val r = Ui.row(this).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); if (cur) setBackgroundColor(Ui.tealRow) }
            r.addView(text(if (done) "✓" else if (cur) "◉" else "○", 16f, if (done || cur) Ui.green else Ui.muted, true).apply {
                gravity = Gravity.CENTER; background = Ui.rounded(this@SessionActivity, if (done) Ui.greenLight else Ui.surface2, 10) },
                LinearLayout.LayoutParams(dp(36), dp(36)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            col.addView(text("${k + 1}. ${p.optString("title")}", 15f, Ui.ink, true))
            p.optString("note").takeIf { it.isNotBlank() }?.let { col.addView(text(it, 12.5f, Ui.muted)) }
            r.addView(col)
            box.addView(r)
        }
        add(box, 6)
    }

    // ---- info / lesson / model ----

    private fun stepInfo(s: JSONObject) {
        needVoice(false)
        if (dayNo == 0) day0Header() else header(session?.optString("title") ?: "", sessionSub(), null)
        add(Ui.enSiCard(this, s.optString("title"), nameIn(s.optString("en")), s.optString("si")), 12)
        val ex = s.optJSONArray("examples")
        if (ex != null) for (k in 0 until ex.length()) add(text("• " + ex.getString(k)), 6)
        if (dayNo == 0) day0Footer()
        setBottom(Ui.button(this, "Next") { next() })
    }

    /** The drawing's Lesson screen: the phrase, 1. Listen · 2. Understand · 3. Practise, then Next. */
    private fun stepLesson(info: JSONObject, model: JSONObject) {
        val who = Ui.character(model.optString("who", "sam"), 0)
        val lines = model.optJSONArray("lines") ?: JSONArray()
        val say = (0 until lines.length()).joinToString(" ") { nameIn(lines.getString(it)) }
        val dayTitle = day?.optString("title")?.substringAfter(": ")?.substringBefore(" and ") ?: (session?.optString("title") ?: "Learn")
        header(dayTitle.replaceFirstChar { it.uppercase() }, "Session 1 · Learn", who)
        needVoice(true, "Say \"Again, please\" to hear ${who.name} once more")
        val first = if (lines.length() > 0) lines.getString(0) else ""
        add(text("“$first”", 26f, Ui.ink, true), 4)
        val labels = Ui.row(this)
        listOf("1. Listen", "2. Understand", "3. Practise").forEachIndexed { k, l ->
            labels.addView(text(l, 12.5f, if (k == 1) Ui.ink else Ui.muted, k == 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        add(Ui.segments(this, 3, 1, 1), 12)
        add(labels, 4)
        // 1. Listen
        val listenCard = Ui.card(this)
        listenCard.addView(Ui.caps(this, "1. Listen: ${who.name} shows you"))
        val r = Ui.row(this)
        r.addView(IconButton(this, Glyph.PLAY, Ui.teal, Color.WHITE).apply { setOnClickListener { Ui.tap(it); replay() } }, LinearLayout.LayoutParams(dp(50), dp(50)))
        var heard = 0
        val count = text("", 14f, Ui.muted).apply { setPadding(dp(12), 0, 0, 0) }
        fun upd() { count.text = "Listened ${if (heard == 1) "once" else if (heard == 2) "twice" else "$heard times"} · say \"Again, please\" to hear it once more" }
        r.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listenCard.addView(r, Ui.lp(this, 10))
        add(listenCard, 14)
        // 2. Understand
        val uc = Ui.card(this, Ui.teal, 2)
        val hr = Ui.row(this)
        hr.addView(Ui.caps(this, "2. Understand: when to use it"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val body = text(info.optString("en"), 15f, Ui.ink)
        if (info.optString("si").isNotBlank()) hr.addView(Ui.enSiSwitch(this) { si -> body.text = if (si) info.optString("si") else info.optString("en") })
        uc.addView(hr)
        uc.addView(body, Ui.lp(this, 10))
        val conn = day?.optJSONArray("connectors")
        if (conn != null && conn.length() > 0) {
            val chips = Ui.row(this)
            for (k in 0 until conn.length()) chips.addView(Ui.chip(this, conn.getString(k), Ui.blue, Ui.blueLight), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            uc.addView(chips, Ui.lp(this, 12))
        }
        add(uc, 12)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.button(this, "Next: practise saying it") { show(index + 2) })
        box.addView(text("Then you'll meet someone and talk (session 2)", 12.5f, Ui.muted).apply { gravity = Gravity.CENTER }, Ui.lp(this, 6))
        setBottom(box)
        onPlayed = { heard++; upd() }
        upd()
        onAnswer = { Ui.toast(this, "Say \"Again, please\" to hear it again, or tap Next."); talk.reset() }
        main.postDelayed({ speak(who, say) }, 400)
    }

    private fun stepModel(s: JSONObject, who: Character) {
        needVoice(true, "Say \"Again, please\" to hear ${who.name} once more")
        onAnswer = { Ui.toast(this, "Say \"Again, please\" to hear it again, or tap Next."); talk.reset() }
        if (dayNo == 0) day0Header() else header(session?.optString("title") ?: "", sessionSub(), who)
        val lines = s.optJSONArray("lines") ?: JSONArray()
        val say = (0 until lines.length()).joinToString(" ") { lines.getString(it) }
        add(saysCard(who, say), 12)
        if (s.optString("note").isNotBlank()) add(Ui.tip(this, s.optString("note")), 10)
        if (dayNo == 0) day0Footer()
        setBottom(Ui.button(this, "Next") { next() })
        speak(who, say)
    }

    private fun sessionSub(): String {
        val sessions = day?.let { progress.sessionsFor(it) } ?: return ""
        val n = sessions.indexOfFirst { it.optString("id") == sid }
        return if (n >= 0) "Session ${n + 1} · ${session?.optString("title")}" else ""
    }

    // ---- Day 0: mic check + agreement on one screen ----

    private fun stepVoiceCheck(s: JSONObject) {
        day0Header()
        needVoice(true, "Mic check: say the sentence above")
        val mic = Ui.card(this)
        mic.addView(Ui.caps(this, "Mic check"))
        mic.addView(text("Say: “My name is … and I love movies.”", 16f, Ui.ink, true), Ui.lp(this, 8))
        val heard = text("", 15f, Ui.ink).apply { background = Ui.rounded(this@SessionActivity, Ui.cream, 10); setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE }
        mic.addView(heard, Ui.lp(this, 10))
        mic.addView(text("Right? If not, just say it again. The app is learning your voice.", 13f, Ui.muted), Ui.lp(this, 10))
        add(mic, 12)
        onAnswer = { said ->
            val t = TalkBar.plain(said)
            heard.text = "We heard: “$t”"; heard.visibility = View.VISIBLE
            Regex("(?i)my name is ([A-Za-z]+)").find(t)?.groupValues?.get(1)?.let { n ->
                getSharedPreferences("progress", MODE_PRIVATE).edit().putString("name", n).apply()
            }
            talk.reset()
        }
        val agree = steps.firstOrNull { it.optString("type") == "agree" } ?: s
        val ag = Ui.card(this)
        val hr = Ui.row(this)
        hr.addView(Ui.caps(this, "Agreement"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val en = agree.optString("en").ifBlank { PRIVACY_EN }
        val si = agree.optString("si").ifBlank { PRIVACY_SI }
        val body = text(en, 15f, Ui.ink, true)
        hr.addView(Ui.enSiSwitch(this) { x -> body.text = if (x) si else en })
        ag.addView(hr)
        ag.addView(body, Ui.lp(this, 8))
        ag.addView(text("Camera: we'll ask you separately, just before your first video call. Your picture is never sent.", 13f, Ui.muted), Ui.lp(this, 10))
        ag.addView(Ui.button(this, "I agree") {
            progress.putString("agreed", System.currentTimeMillis().toString())
            val j = steps.indexOfFirst { it.optString("type") == "agree" }
            show(maxOf(index, j) + 1)
        }, Ui.lp(this, 14))
        add(ag, 14)
        day0Footer()
    }

    // ---- drills ----

    private fun drillList() = steps.filter { it.optString("type") == "drill" }

    private fun stepDrill(s: JSONObject, who: Character, tries: Int = 0) {
        val prompt = nameIn(s.optString("prompt"))
        val situation = prompt.startsWith("(")
        val models = s.optJSONArray("models") ?: JSONArray()
        val diagnostic = models.length() == 0
        askingDrill = (0 until models.length()).any { Ui.isAgainRequest(models.getString(it)) || models.getString(it).contains("slow down", true) || models.getString(it).contains("mean?", true) }
        val drills = drillList()
        val k = drills.indexOf(s)
        if (tries == 0) {
            needVoice(true)
            when {
                dayNo == 0 -> day0Header()
                testMode -> header("Weekly test", "Day $dayNo · part ${partLetter(s)} of ${testParts().size}", who)
                sid == "learn" -> header("Practise saying it", "Stage 3 of 3 · item ${k + 1} of ${drills.size}", who)
                else -> header(session?.optString("title") ?: "Practise", "Item ${k + 1} of ${drills.size}", who)
            }
            if (dayNo != 0) add(Ui.segments(this, drills.size, k, k), 0)
            if (testMode) add(partCard(s, prompt), 12)
            if (situation) add(Ui.enSiCard(this, "Your turn", prompt.trim('(', ')'), "", Ui.teal), 12)
            else add(saysCard(who, prompt), 12)
            if (s.optString("point").isNotBlank() && !testMode) add(Ui.enSiCard(this, "", s.optString("point"), s.optString("si")), 10)
            if (!situation) speak(who, prompt)
            if (testMode) testList()
        }
        onAnswer = { said ->
            talk.enabled = false
            val wait = text("Checking…", 14f, Ui.muted); add(wait, 10)
            judge(DRILL_COACH + persona(who), "The remark or situation: $prompt\nWhat to practise: ${s.optString("point")}\n" +
                    "Model answers: ${if (diagnostic) "(none: a starting test, just judge kindly)" else (0 until models.length()).joinToString(" | ") { nameIn(models.getString(it)) }}\n" +
                    "His answer: ${TalkBar.forAi(said)}") { r ->
                content.removeView(wait)
                talk.enabled = true
                r.optString("name").takeIf { it.isNotBlank() && it.length < 30 }?.let { getSharedPreferences("progress", MODE_PRIVATE).edit().putString("name", it).apply() }
                val mark = r.optString("mark", "understandable")
                logAnswer("drill", prompt, said, mark)
                if (TalkBar.hasSinhala(said)) progress.add("sinhalaUses")
                add(youSaidCard(said, mark, r), 12)
                r.optString("say").takeIf { it.isNotBlank() && !testMode }?.let { speak(who, it) }
                if (!testMode) add(marksLegend(who), 12)
                if (mark == "try_again" && tries < 2 && !diagnostic && !testMode) {
                    add(text("Try again: tap the mic.", 14f, Ui.muted), 8)
                    talk.reset()
                    val keep = onAnswer
                    stepDrill(s, who, tries + 1)
                    if (onAnswer == null) onAnswer = keep
                } else {
                    talk.reset(); needVoice(false)
                    val lastStep = index == steps.size - 1
                    setBottom(nextBlock(if (lastStep) (nextSessionLabel() ?: "Finish") else "Next",
                        if (sid == "learn" && k == drills.size - 1) "Appears when this stage is done" else null) {
                        if (lastStep && !testMode) { markSessionDone(); goNextSession() } else next() })
                }
            }
        }
    }

    private fun nextBlock(label: String, note: String? = null, onClick: () -> Unit = { next() }): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.button(this, label) { onClick() })
        if (note != null) box.addView(text(note, 12.5f, Ui.muted).apply { gravity = Gravity.CENTER }, Ui.lp(this, 6))
        return box
    }

    /** The drawing's YOU SAID card: mark badge + EN | සිං, his words, praise in green, "One thing". */
    private fun youSaidCard(said: List<Pair<String, Boolean>>, mark: String, r: JSONObject): View {
        val (label, fg, bg) = when (mark) {
            "good" -> Triple("Good", Ui.green, Ui.greenLight)
            "try_again" -> Triple("Try again", Ui.orangeDark, Ui.orangeLight)
            else -> Triple("Understandable", Ui.blue, Ui.blueLight)
        }
        val card = Ui.card(this)
        val hr = Ui.row(this)
        hr.addView(Ui.caps(this, "You said"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hr.addView(Ui.chip(this, label, fg, bg), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        val issueEn = r.optString("issue_en").ifBlank { r.optString("tip_en").ifBlank { r.optString("feedback_en") } }
        val issueSi = r.optString("issue_si").ifBlank { r.optString("tip_si").ifBlank { r.optString("feedback_si") } }
        val one = text("", 14.5f, Ui.ink)
        fun setOne(si: Boolean) {
            val t = if (si && issueSi.isNotBlank()) issueSi else issueEn
            one.text = android.text.SpannableStringBuilder("●  ").apply {
                setSpan(android.text.style.ForegroundColorSpan(Ui.orange), 0, 1, 0)
                val st = length; append("One thing: "); setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, length, 0)
                append(t)
            }
            one.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
        }
        if (issueSi.isNotBlank()) hr.addView(Ui.enSiSwitch(this) { setOne(it) }) else setOne(false)
        card.addView(hr)
        card.addView(text("“${TalkBar.plain(said)}”", 16f, Ui.ink), Ui.lp(this, 10))
        r.optString("praise").takeIf { it.isNotBlank() }?.let { card.addView(text(it, 14f, Ui.green), Ui.lp(this, 8)) }
        r.optString("si_en").takeIf { it.isNotBlank() }?.let { card.addView(text("Your Sinhala in English: $it", 14f, Ui.orangeDark, true), Ui.lp(this, 8)) }
        card.addView(View(this).apply { setBackgroundColor(Ui.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(10) })
        card.addView(one, Ui.lp(this, 10))
        r.optString("better").takeIf { it.isNotBlank() && mark != "good" }?.let { card.addView(text("→  $it", 15f, Ui.ink, true), Ui.lp(this, 8)) }
        return card
    }

    private fun marksLegend(who: Character): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text("How it's marked:", 12.5f, Ui.muted))
        val r = Ui.row(this)
        r.addView(Ui.chip(this, "Good", Ui.green, Ui.greenLight)); r.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        r.addView(Ui.chip(this, "Understandable", Ui.blue, Ui.blueLight)); r.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        r.addView(Ui.chip(this, "Try again", Ui.orangeDark, Ui.orangeLight))
        box.addView(r, Ui.lp(this, 4))
        box.addView(text("Any correct answer counts. It doesn't have to match ${who.name}'s.", 12.5f, Ui.muted), Ui.lp(this, 6))
        return box
    }

    // ---- quick reactions ----

    private fun anyTimeReactions(): JSONArray {
        val cur = progress.currentDay()
        val out = JSONArray()
        Lessons.allDays().filter { it.first < (cur?.first ?: 1) || (it.first == (cur?.first ?: 1) && it.second.optInt("day") <= (cur?.second?.optInt("day") ?: 1)) }
            .flatMap { Lessons.sessions(it.second) }.flatMap { s -> val a = s.getJSONArray("steps"); (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.optString("type") == "react" }
            .flatMap { r -> val a = r.optJSONArray("items") ?: JSONArray(); (0 until a.length()).map { a.getJSONObject(it) } }
            .shuffled().take(10).forEach { out.put(it) }
        if (out.length() == 0) out.put(JSONObject().put("news", "I got tickets for the new film!").put("feel", "good"))
        return out
    }

    private fun stepReact(s: JSONObject, who0: Character, item: Int, good: Int) {
        val items = s.optJSONArray("items") ?: JSONArray()
        val anytime = s.optBoolean("anytime", false)
        if (item >= items.length()) {
            needVoice(false)
            add(text("Good reactions: $good of ${items.length()}", 16f, Ui.green, true), 14)
            setBottom(nextBlock(if (anytime) "Done" else (nextSessionLabel() ?: "Next"), if (anytime) null else "Appears after the last item") { if (anytime) finish() else next() })
            return
        }
        val who = if (s.optString("who") == "both") Ui.character("both", item) else who0
        val it = items.getJSONObject(item)
        content.removeAllViews()
        needVoice(true, "Tap the mic to react · tap Sinhala to speak Sinhala")
        header("Quick reactions", if (anytime) "Item ${item + 1} of ${items.length()} · any time" else "Session 3 · Listen and react", who)
        add(Ui.segments(this, items.length(), item, item), 0)
        add(saysCard(who, it.optString("news"), "${who.name} says", "Take your time · no clock this week"), 12)
        if (week == 1) {
            add(Ui.caps(this, "Ways to react"), 16)
            add(text("Week 1 only: from Week 2 the ideas disappear and you find your own.", 12.5f, Ui.muted), 4)
            val ways = listOf("Good news" to "Wow! · That's great!", "Bad news" to "Oh no! · That's unfortunate.",
                "Surprise" to "No way! · Really? What happened?", "Thinking" to "That makes sense. · I see what you mean.")
            for (rowI in 0..1) {
                val r = Ui.row(this)
                for (colI in 0..1) {
                    val (t, sub) = ways[rowI * 2 + colI]
                    val tile = Ui.card(this).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
                    tile.addView(text(t, 14f, Ui.ink, true)); tile.addView(text(sub, 13f, Ui.muted))
                    r.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (colI == 1) marginStart = dp(8) })
                }
                add(r, 8)
            }
        }
        val counter = text("Good reactions so far: $good of $item", 13.5f, Ui.muted).apply { gravity = Gravity.CENTER }
        add(counter, 12)
        speak(who, it.optString("news"))
        onAnswer = { said ->
            talk.enabled = false
            judge(REACT_COACH + persona(who), "News: ${it.optString("news")}\nThe feeling of the news: ${it.optString("feel")}\nHis reaction: ${TalkBar.forAi(said)}") { r ->
                talk.enabled = true
                val ok = r.optBoolean("ok", true)
                logAnswer("react", it.optString("news"), said, if (ok) "good" else "try_again")
                add(youSaidCard(said, if (ok) "good" else "understandable", r), 12)
                r.optString("say").takeIf { x -> x.isNotBlank() }?.let { x -> speak(who, x) }
                setBottom(nextBlock("Next") { stepReact(s, who0, item + 1, good + if (ok) 1 else 0) })
                needVoice(false)
            }
        }
    }

    // ---- listening ----

    private fun stepListen(s: JSONObject, who: Character) {
        val script = s.optString("script")
        val qs = s.optJSONArray("questions") ?: JSONArray()
        header("Listening", "Session 3 · Listen and react", who)
        needVoice(true)
        val card = Ui.card(this)
        card.addView(Ui.caps(this, "Listen"))
        card.addView(text(s.optString("title"), 18f, Ui.ink, true), Ui.lp(this, 6))
        val r = Ui.row(this)
        r.addView(IconButton(this, Glyph.PLAY, Ui.teal, Color.WHITE).apply { setOnClickListener { Ui.tap(it); lastWho = who; lastText = script; voice.speak(script, who) } },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; progressTintList = android.content.res.ColorStateList.valueOf(Ui.teal)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.line)
        }
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        val played = text("", 12.5f, Ui.muted)
        val secs = maxOf(5, (script.split(" ").size / 2.4).toInt())
        val prow = Ui.row(this)
        prow.addView(played, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        prow.addView(text("0:%02d".format(secs), 12.5f, Ui.muted))
        col.addView(prow, Ui.lp(this, 4))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(r, Ui.lp(this, 10))
        card.addView(text("Natural speech, a little at a time. Answer the questions out loud.", 13f, Ui.muted), Ui.lp(this, 8))
        add(card, 12)
        var plays = 0
        voice.onSpeaking = { t ->
            if (t) { var p = 0; val tick = object : Runnable { override fun run() { if (p < 100) { p += 100 / secs; bar.progress = p; main.postDelayed(this, 1000) } } }; main.post(tick) }
            else { bar.progress = 100; plays++; played.text = if (plays == 1) "Played once" else "Played $plays times"; onPlayed?.invoke() }
        }
        add(Ui.caps(this, "${qs.length()} questions · answer out loud"), 16)
        val list = Ui.card(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        val rows = (0 until qs.length()).map { k ->
            val row = Ui.row(this).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
            val num = text("${k + 1}", 14f, Ui.muted, true).apply { gravity = Gravity.CENTER; background = Ui.rounded(this@SessionActivity, Ui.surface2, 14) }
            row.addView(num, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(text(qs.getString(k), 15f, Ui.ink).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row); row to num
        }
        add(list, 6)
        add(Ui.tip(this, "Didn't catch it? Say \"Sorry, can you play that again?\""), 12)
        fun ask(k: Int) {
            rows.forEachIndexed { j, (row, num) ->
                row.setBackgroundColor(if (j == k) Ui.tealRow else Color.TRANSPARENT)
                num.background = Ui.rounded(this, if (j == k) Ui.teal else if (j < k) Ui.greenLight else Ui.surface2, 14)
                num.setTextColor(if (j == k) Color.WHITE else if (j < k) Ui.green else Ui.muted)
                num.text = if (j < k) "✓" else "${j + 1}"
            }
            if (k >= qs.length()) {
                needVoice(false)
                setBottom(nextBlock(steps.getOrNull(index + 1)?.let { if (it.optString("type") == "react") "Next: quick reactions" else "Next" } ?: (nextSessionLabel() ?: "Next"), "Appears after question ${qs.length()}"))
                return
            }
            val q = qs.getString(k)
            onAnswer = { said ->
                talk.enabled = false
                judge(LISTEN_COACH + persona(who), "What ${who.name} said: $script\nQuestion: $q\nHis spoken answer: ${TalkBar.forAi(said)}") { r ->
                    talk.enabled = true; talk.reset()
                    val m = r.optString("mark", "good")
                    logAnswer("listen", q, said, m)
                    if (m == "good") progress.add("listenGood")
                    add(youSaidCard(said, m, r), 12)
                    (r.optString("partner_reply").ifBlank { r.optString("say") }).takeIf { it.isNotBlank() }?.let { speak(who, it) }
                    ask(k + 1)
                }
            }
        }
        ask(0)
        lastWho = who; lastText = script
        main.postDelayed({ voice.speak(script, who) }, 500)
    }

    // ---- Before you talk → conversation → result ----

    private fun stepBeforeYouTalk(s: JSONObject, who: Character) {
        needVoice(false)
        header("Before you talk", "Session 2 · conversation", who)
        val card = Ui.card(this)
        val hr = Ui.row(this)
        hr.addView(text("📍", 16f).apply { gravity = Gravity.CENTER; background = Ui.rounded(this@SessionActivity, Ui.tealLight, 10) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        hr.addView(Ui.caps(this, "The situation").apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val en = s.optString("en"); val si = s.optString("si")
        val body = text(en, 19f, Ui.ink, true)
        if (si.isNotBlank()) hr.addView(Ui.enSiSwitch(this) { x -> body.text = if (x) si else en })
        card.addView(hr)
        card.addView(body, Ui.lp(this, 12))
        card.addView(View(this).apply { setBackgroundColor(Ui.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(12) })
        fun bold(label: String, rest: String) = text(android.text.SpannableStringBuilder(label).apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0); append(rest) }, 15f, Ui.ink)
        card.addView(bold("Who: ", s.optString("whoLine").ifBlank { who.name }), Ui.lp(this, 12))
        val turns = s.optInt("minTurns", 0)
        card.addView(bold("Your goal: ", s.optString("goal") + (if (turns > 0 && !s.optString("goal").contains("turn")) " Keep it going for $turns turns or more." else "")), Ui.lp(this, 8))
        card.addView(text("You don't know what ${if (who.male) "he" else "she"}'ll say. That's the point.", 14f, Ui.muted), Ui.lp(this, 8))
        add(card, 12)
        add(Ui.tip(this, s.optString("tipStart").ifBlank { "Stuck at the start? Notice something → say it → ask." }), 12)
        val mode = when (s.optString("mode")) { "phone" -> "Phone call"; "video" -> "Video call"; else -> "Face to face" }
        setBottom(nextBlock("Start talking", "$mode · about ${if (progress.shortDay()) 10 else 20} minutes") { startChat(s, who) })
    }

    private fun startChat(s: JSONObject, who: Character) {
        pendingChat = s
        voice.stop()
        startActivityForResult(Intent(this, MainActivity::class.java)
            .putExtra("scene", s.put("title", s.optString("title").ifBlank { s.optString("where").substringBefore(":").substringBefore(",") }).toString())
            .putExtra("partner", who.name).putExtra("day", dayNo)
            .putExtra("where", "Week $week, day $dayNo, $sid"), 7)
        if (s.optString("type") == "freechat") { needVoice(false); header("Free chat", sessionSub(), who); setBottom(nextBlock("Open the free chat") { startChat(s, who) }) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7) return
        val s = pendingChat ?: return
        val transcript = data?.getStringExtra("transcript") ?: "[]"
        val turns = data?.getIntExtra("turns", 0) ?: 0
        val free = s.optString("type") == "freechat"
        if (turns > 0) progress.add("conversations")
        if (free) { next(); return }   // the drawing: Free chat → today's review recording
        content.removeAllViews()
        val who = Ui.character(s.optString("who", "sam"), index)
        header("Conversation done", "${sessionSub().substringBefore(" ·")} · with ${who.name}", who)
        needVoice(false)
        if (turns == 0) { add(text("You didn't say anything this time. Try again?", 16f), 12); setBottom(nextBlock("Back to the situation") { show(index) }); return }
        val wait = text("Looking at your conversation…", 14f, Ui.muted); add(wait, 12)
        judge(CHAT_RESULT, "Scene: ${s.optString("en")}\nHis goal: ${s.optString("goal")}\nScored: ${s.optBoolean("scored", true)}\n" +
                "Conversation (he = student):\n$transcript") { r ->
            content.removeView(wait)
            if (testMode) testLog.put(JSONObject().put("part", "E. Full conversation").put("transcript", transcript))
            progress.log("results", JSONObject().put("where", "w${week}d$dayNo").put("score", r.optInt("score", -1)).put("turns", turns)
                .put("questions", r.optInt("questions")).put("followups", r.optInt("followups")).put("longest", r.optInt("longest_turn_words"))
                .put("stuck", r.optInt("stuck_recovered")).put("help", r.optInt("asked_help")).put("startedSelf", s.optString("opener").isBlank())
                .put("communication", r.optJSONArray("communication")?.optJSONObject(0)?.optString("en") ?: ""))
            resultScreen(r, turns, who)
        }
    }

    /** The drawing's result screen: no score (mentor 11 Nov), six number tiles, two things to work on. */
    private fun resultScreen(r: JSONObject, turns: Int, who: Character) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.rounded(this@SessionActivity, Ui.tealDark, 16); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        top.addView(text(r.optString("headline").ifBlank { "Nice work!" }, 18f, Ui.ink, true))
        top.addView(text("$turns turns · ${who.name} enjoyed talking to you", 13.5f, Ui.tealText), Ui.lp(this, 4))
        add(top, 12)
        val tiles = listOf(r.optInt("questions").toString() to "questions you asked", r.optInt("followups").toString() to "follow-up questions",
            "${r.optInt("longest_turn_words")} words" to "longest connected turn", r.optInt("stuck_recovered").toString() to "times you got stuck and kept going",
            r.optInt("asked_help").toString() to "time(s) you asked for help (good)", r.optInt("sinhala_uses").toString() to "Sinhala: ${r.optString("sinhala_note").ifBlank { "words you needed" }}")
        for (row in 0..2) {
            val rr = Ui.row(this)
            for (c in 0..1) {
                val (n, l) = tiles[row * 2 + c]
                val t = Ui.card(this).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
                t.addView(text(n, 20f, if (row * 2 + c == 5) Ui.ink else Ui.teal, true)); t.addView(text(l, 12.5f, Ui.muted))
                rr.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (c == 1) marginStart = dp(8) })
            }
            add(rr, 8)
        }
        val comm = r.optJSONArray("communication")?.optJSONObject(0)
        val gram = r.optJSONArray("grammar")?.optJSONObject(0)
        if (comm != null || gram != null) {
            val hr = Ui.row(this)
            hr.addView(Ui.caps(this, "${listOfNotNull(comm, gram).size} things to work on"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val card = Ui.card(this)
            val cText = text(comm?.optString("en") ?: "", 14f, Ui.ink); val gText = text(gram?.optString("en") ?: "", 14f, Ui.ink)
            hr.addView(Ui.enSiSwitch(this) { si ->
                cText.text = if (si) comm?.optString("si")?.ifBlank { comm.optString("en") } ?: "" else comm?.optString("en") ?: ""
                gText.text = if (si) gram?.optString("si")?.ifBlank { gram.optString("en") } ?: "" else gram?.optString("en") ?: ""
            })
            add(hr, 18)
            if (comm != null) { card.addView(Ui.chip(this, "Communication", Ui.orangeDark, Ui.orangeLight), Ui.lp(this, 0, false)); card.addView(cText, Ui.lp(this, 4)) }
            if (gram != null) { card.addView(Ui.chip(this, "Small grammar slip", Ui.muted, Ui.surface2), Ui.lp(this, if (comm != null) 12 else 0, false)); card.addView(gText, Ui.lp(this, 4)) }
            add(card, 6)
            gram?.optString("sentence")?.takeIf { it.isNotBlank() }?.let { sent ->
                progress.log("sayThemAgain", JSONObject().put("si", "").put("en", sent).put("sentence", sent))
                add(text("The better sentence goes to Say them again.", 12.5f, Ui.muted), 8)
            }
        }
        val last = index == steps.size - 1
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (last) {
            markSessionDone()
            box.addView(Ui.button(this, nextSessionLabel() ?: "Back to Home") { goNextSession() })
            box.addView(Ui.button(this, "Back to Home", outline = true) { finish() }, Ui.lp(this, 10))
        } else box.addView(Ui.button(this, "Next") { next() })
        setBottom(box)
    }

    // ---- daily review recording (his voice is kept; QA 11 Nov) ----

    private var recorder: VoiceRecorder? = null
    private fun stopRecorder() { recorder?.stop(); recorder = null }

    private fun stepRecord(s: JSONObject, who: Character) {
        needVoice(false)
        val topic = s.optString("topic")
        if (dayNo == 0) day0Header() else header("Daily review recording", "2 minutes · end of the day", null)
        val prevAll = VoiceRecorder.all(progress)
        val prev = prevAll.lastOrNull { it.optString("topic") == topic } ?: prevAll.lastOrNull()
        val card = Ui.card(this)
        val hr = Ui.row(this)
        hr.addView(Ui.caps(this, "Topic"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (prev != null && prev.optString("topic") == topic) hr.addView(Ui.chip(this, "Same as last time", Ui.muted, Ui.surface2))
        card.addView(hr)
        card.addView(text(nameIn(topic), 19f, Ui.ink, true), Ui.lp(this, 8))
        card.addView(text("Take your time: pauses are fine. Tap ↑ when you finish.", 13f, Ui.muted), Ui.lp(this, 8))
        add(card, 12)
        // Big timer + bars
        val tcard = Ui.card(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val timer = text("0:00", 34f, Ui.ink, true).apply { gravity = Gravity.CENTER }
        val tr = Ui.row(this).apply { gravity = Gravity.CENTER }
        tr.addView(timer); tr.addView(text("  / 2:00", 15f, Ui.muted))
        tcard.addView(tr)
        val bars = SoundBarsView(this).apply { color = Ui.teal }
        tcard.addView(bars, LinearLayout.LayoutParams(dp(230), dp(34)).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
        add(tcard, 12)
        // Compared with last time
        add(Ui.caps(this, "Compared with last time"), 18)
        add(text("No score today: just you vs you.", 12.5f, Ui.muted), 4)
        val cmp = Ui.card(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        fun cmpRow(t: String, sub: String, f: File?): TextView {
            val r = Ui.row(this).apply { setPadding(dp(14), dp(10), dp(10), dp(10)) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(t, 15f, Ui.ink, true)); val sv = text(sub, 12.5f, Ui.muted); col.addView(sv)
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (f != null) r.addView(IconButton(this, Glyph.PLAY, Ui.tealLight, Ui.teal, Ui.teal).apply { setOnClickListener { Ui.tap(it); VoiceRecorder.play(f) } },
                LinearLayout.LayoutParams(dp(36), dp(36)))
            cmp.addView(r); return sv
        }
        val prevFile = prev?.optString("file")?.let { File(it) }?.takeIf { it.exists() }
        val pausesV: TextView; val connV: TextView; val followV: TextView
        if (prev == null) {
            add(Ui.tip(this, "First time: next time you'll see how you've grown."), 6)
            pausesV = text(""); connV = text(""); followV = text("")
        } else {
            pausesV = cmpRow("Long pauses", "Last time ${prev.optInt("pauses")} · today: when you finish", prevFile)
            connV = cmpRow("Ideas joined with connectors", "Last time ${prev.optInt("connectors")} · today: when you finish", null)
            followV = cmpRow("How easy you are to follow", "Last time: ${prev.optString("follow")} · today: when you finish", null)
            add(cmp, 6)
        }
        // Bottom: recorder bar
        val barRow = Ui.row(this).apply { background = Ui.rounded(this@SessionActivity, Ui.surface, 36, Ui.line); setPadding(dp(8), dp(8), dp(8), dp(8)); minimumHeight = dp(72) }
        val status = text("Tap the mic and talk", 13.5f, Ui.teal, true)
        val micB = IconButton(this, Glyph.MIC, Ui.teal, Color.WHITE)
        val pauseB = IconButton(this, Glyph.PAUSE, Ui.tealLight, Ui.teal, Ui.teal).apply { visibility = View.GONE }
        val sendB = IconButton(this, Glyph.SEND, Ui.teal, Color.WHITE).apply { visibility = View.GONE }
        barRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        barRow.addView(pauseB, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        barRow.addView(micB, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(8) })
        barRow.addView(sendB, LinearLayout.LayoutParams(dp(50), dp(50)))
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(barRow)
        wrap.addView(text("Tap ↑ when you finish · Sinhala is OK if you get stuck", 11.5f, Ui.muted).apply { gravity = Gravity.CENTER }, Ui.lp(this, 6))
        setBottom(wrap)
        var startAt = 0L; var total = 0L; var running = false
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val ms = total + SystemClock.elapsedRealtime() - startAt
                timer.text = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
                bars.push((recorder?.level() ?: 0f) * 12f - 2f)
                main.postDelayed(this, 80)
            }
        }
        val fname = "rec-${System.currentTimeMillis()}"
        micB.setOnClickListener {
            Ui.tap(it)
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { TalkBar.askMic(this); return@setOnClickListener }
            if (recorder == null) {
                val rec = VoiceRecorder(this)
                if (!rec.start(fname)) { Ui.toast(this, "The recorder couldn't start. Try again."); return@setOnClickListener }
                recorder = rec
            } else recorder?.resume()
            startAt = SystemClock.elapsedRealtime(); running = true; main.post(tick)
            status.text = "Recording…"; micB.visibility = View.GONE; pauseB.visibility = View.VISIBLE; sendB.visibility = View.VISIBLE
        }
        pauseB.setOnClickListener {
            Ui.tap(it); recorder?.pause(); running = false; total += SystemClock.elapsedRealtime() - startAt
            status.text = "Paused · tap the mic to go on"; pauseB.visibility = View.GONE; micB.visibility = View.VISIBLE
        }
        sendB.setOnClickListener {
            Ui.tap(it)
            if (running) total += SystemClock.elapsedRealtime() - startAt
            running = false
            val f = recorder?.stop(); recorder = null
            setBottom(null)
            if (f == null || total < 2000) { Ui.toast(this, "That was very short. Try again."); show(index); return@setOnClickListener }
            val wait = text("Listening to your recording…", 14f, Ui.muted); add(wait, 12)
            thread {
                val r = runCatching { Ai.askAudio(this, RECORD_COACH, "Topic: $topic\nLast time (may be empty): ${prev?.optString("words") ?: ""}\n" +
                        "Last time: long pauses ${prev?.optInt("pauses") ?: "-"}, connectors ${prev?.optInt("connectors") ?: "-"}", f) }
                main.post {
                    if (isFinishing) return@post
                    content.removeView(wait)
                    val j = r.getOrElse { JSONObject().put("good", "Thanks! Your recording is saved.") }
                    val words = j.optString("transcript")
                    progress.log("recordings", JSONObject().put("topic", topic).put("file", f.absolutePath).put("words", words)
                        .put("pauses", j.optInt("long_pauses")).put("connectors", j.optJSONArray("connectors")?.length() ?: 0)
                        .put("follow", j.optString("follow").ifBlank { "easy" }).put("secs", total / 1000).put("day", dayNo).put("week", week))
                    progress.putString("rec:$topic", words)
                    s.optString("saveAs").takeIf { it.isNotBlank() }?.let { progress.putString("save:$it", words); progress.putString("saveFile:$it", f.absolutePath) }
                    if (testMode) testLog.put(JSONObject().put("part", "D. Long turn").put("answer", words))
                    if (prev != null) {
                        pausesV.text = "Last time ${prev.optInt("pauses")} · today ${j.optInt("long_pauses")}"
                        connV.text = "Last time ${prev.optInt("connectors")} · today ${j.optJSONArray("connectors")?.length() ?: 0}"
                        followV.text = "Last time: ${prev.optString("follow")} · today: ${j.optString("follow")}"
                    }
                    val done = Ui.card(this, Ui.teal, 2)
                    done.addView(text("No score today. ${j.optString("good")}", 15f, Ui.green, true))
                    j.optString("compared").takeIf { it.isNotBlank() }?.let { done.addView(text(it, 14.5f), Ui.lp(this, 6)) }
                    if (j.optString("tip_en").isNotBlank()) done.addView(Ui.enSiCard(this, "One thing to try", j.optString("tip_en"), j.optString("tip_si"), Ui.orange), Ui.lp(this, 8))
                    add(done, 12)
                    val last = index == steps.size - 1
                    val lastOfDay = last && day?.let { d -> progress.sessionsFor(d).lastOrNull()?.optString("id") == sid } == true
                    setBottom(if (last) nextBlock(if (lastOfDay) "Finish and go Home" else (nextSessionLabel() ?: "Finish")) { markSessionDone(); if (lastOfDay) finish() else goNextSession() } else nextBlock("Next"))
                }
            }
        }
        if (dayNo == 0) day0Footer()
    }

    // ---- listening speed (Day 0) ----

    private fun stepSpeed(k: Int, ok: MutableList<Float>) {
        val rates = listOf(0.75f, 0.85f, 0.92f, 1.0f, 1.08f)
        val sentences = listOf("I like watching movies on the weekend.", "My friend is coming to the cinema with me.",
            "We bought popcorn before the film started.", "The ending was much better than I expected.",
            "Honestly, I think the second film was even funnier than the first one.")
        content.removeAllViews()
        day0Header()
        if (k >= rates.size) {
            val comfort = ok.maxOrNull() ?: 0.85f
            val stage = when { comfort >= 1.0f -> 1.0f; comfort >= 0.96f -> 0.96f; else -> 0.92f }
            getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("baseRate", stage).apply()
            voice.baseRate = stage
            needVoice(false)
            add(Ui.enSiCard(this, "Your listening speed", "The app starts at speed ${"%.2f".format(stage)} and goes faster as you understand more.", ""), 14)
            day0Footer()
            setBottom(nextBlock("Next"))
            return
        }
        needVoice(true, "Repeat the sentence")
        add(text("Listening speed: ${k + 1} of 5", 18f, Ui.ink, true), 12)
        add(text("Maya says 5 sentences, from slow to a little fast. Repeat each one as well as you can.", 14.5f, Ui.muted), 6)
        val who = Characters.MAYA
        add(saysCard(who, sentences[k]), 12)
        val old = voice.baseRate
        voice.baseRate = rates[k]; speak(who, sentences[k]); main.postDelayed({ voice.baseRate = old }, 300)
        day0Footer()
        onAnswer = { said ->
            if (overlap(TalkBar.plain(said), sentences[k]) >= 0.8f) ok.add(rates[k])
            stepSpeed(k + 1, ok)
        }
    }

    // ---- Say them again: Listen · Say it from memory · Use it in a chat ----

    private fun stepSayAgain(k: Int, stage: Int) {
        val items = progress.sayAgainItems().take(5)
        content.removeAllViews()
        val who = Ui.character("both", k)
        header("Say them again", "${items.size} phrases · about ${maxOf(2, items.size * 2)} minutes", who)
        if (items.isEmpty() || k >= items.size) {
            needVoice(false)
            add(text(if (items.isEmpty()) "Nothing to practise yet. Well done!" else "Done for now!", 16f, Ui.green, true), 14)
            setBottom(nextBlock("Finish") { if (sid == "sayagain") finish() else next() })
            return
        }
        val it = items[k]
        val sentence = it.optString("sentence")
        val labels = Ui.row(this)
        listOf("Listen", "Say it from memory", "Use it in a chat").forEachIndexed { j, l ->
            labels.addView(text(l, 12.5f, if (j == stage) Ui.ink else Ui.muted, j == stage), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        add(Ui.segments(this, 3, stage, stage), 0)
        add(labels, 4)
        val card = Ui.card(this)
        val hr = Ui.row(this)
        hr.addView(Ui.caps(this, "Phrase ${k + 1} of ${items.size}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val times = it.optInt("times", 1)
        if (times > 1) hr.addView(Ui.chip(this, "Came back $times times", Ui.orangeDark, Ui.orangeLight))
        card.addView(hr)
        val line = when (stage) { 0 -> sentence; 1 -> "Can you say that again, from memory?"; else -> "Use it in a sentence about you." }
        card.addView(text("${who.name} ${if (stage == 0) "says" else "asks"}:", 14f, Ui.muted), Ui.lp(this, 8))
        val r = Ui.row(this)
        r.addView(IconButton(this, Glyph.PLAY, Ui.teal, Color.WHITE).apply { setOnClickListener { Ui.tap(it); voice.speak(line, who) } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        r.addView(SoundBarsView(this).apply { color = Ui.bars; still(line.hashCode()) }, LinearLayout.LayoutParams(dp(120), dp(28)).apply { marginStart = dp(12) })
        card.addView(r, Ui.lp(this, 6))
        card.addView(text("Say \"Again, please\" to hear it again", 13f, Ui.muted), Ui.lp(this, 8))
        if (stage >= 1) {
            val key = keyWord(sentence)
            card.addView(text(android.text.SpannableStringBuilder("The answer is hidden. Say it from memory" + if (key.isNotBlank()) ", with " else ".").apply {
                if (key.isNotBlank()) { val st = length; append(key); setSpan(android.text.style.ForegroundColorSpan(Ui.teal), st, length, 0); setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, length, 0); append(".") }
            }, 15f, Ui.ink).apply { gravity = Gravity.CENTER; background = Ui.rounded(this@SessionActivity, Ui.cream, 10); setPadding(dp(12), dp(12), dp(12), dp(12)) }, Ui.lp(this, 10))
        }
        add(card, 12)
        if (items.size > k + 1) {
            add(Ui.caps(this, "Next"), 18)
            add(text("Only mistakes that keep coming back. Small one-off slips are skipped.", 12.5f, Ui.muted), 4)
            val list = Ui.card(this).apply { setPadding(0, dp(4), 0, dp(4)) }
            items.drop(k + 1).forEach { n ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
                row.addView(text(n.optString("sentence"), 15f, Ui.ink, true))
                row.addView(text(if (n.optInt("times", 1) > 1) "Came back ${n.optInt("times")} times" else "From a recent chat", 12.5f, Ui.muted))
                list.addView(row)
            }
            add(list, 6)
        }
        needVoice(true, "Tap the mic to say it · tap Sinhala to speak Sinhala")
        speak(who, line)
        onAnswer = { said ->
            val score = overlap(TalkBar.plain(said), sentence)
            val pass = if (stage == 2) score >= 0.4f else score >= 0.7f
            add(text(if (pass) "✓ Well said!" else "Good try! Listen once more and try again.", 15f, if (pass) Ui.green else Ui.muted, true), 10)
            talk.reset()
            if (pass && stage == 2) progress.sayAgainDone(sentence)
            main.postDelayed({ if (pass) { if (stage < 2) stepSayAgain(k, stage + 1) else stepSayAgain(k + 1, 0) } else { voice.speak(sentence, who) } }, 900)
        }
    }

    private fun keyWord(sentence: String): String {
        val connectors = listOf("because", "actually", "by the way", "so", "that's why", "anyway", "meanwhile", "suddenly", "in the end", "I mean", "well")
        return connectors.firstOrNull { sentence.contains(it, true) } ?: ""
    }

    // ---- weekly test ----

    private fun testParts(): List<String> = steps.firstOrNull { it.optString("type") == "test" }?.optString("parts")?.split(" · ") ?: emptyList()
    private fun partLetter(s: JSONObject): String = s.optString("point").takeIf { it.length > 1 && it[1] == '.' }?.substring(0, 1) ?: "A"

    private fun stepTestIntro(s: JSONObject) {
        testMode = true
        needVoice(false)
        header("Weekly test", "Day $dayNo · ${testParts().size} parts", null)
        add(Ui.tip(this, "Scored on understanding, asking and keeping it going, not perfect grammar. Asking never loses points."), 12)
        add(Ui.caps(this, "Weekly test · about 40 minutes"), 18)
        add(text("Pass: ${s.optInt("pass", 70)} overall, and at least \"OK\" in starting and asking.", 12.5f, Ui.muted), 4)
        testList()
        setBottom(nextBlock("Start the test"))
    }

    private fun partCard(s: JSONObject, prompt: String): View {
        val letter = partLetter(s)
        val name = testParts().firstOrNull { it.startsWith(letter) }?.substringAfter(" ") ?: ""
        return Ui.enSiCard(this, "Part $letter · $name", if (prompt.startsWith("(")) prompt.trim('(', ')') else "Listen, then answer.", "", capsTitle = true)
    }

    private fun testList() {
        val parts = testParts()
        val cur = steps.getOrNull(index)?.let { if (it.optString("type") == "drill") partLetter(it) else if (it.optString("type") == "chat") "E" else "" } ?: ""
        val box = Ui.card(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        val done = testLog.length()
        parts.forEach { p ->
            val letter = p.substring(0, 1)
            val isCur = letter == cur
            val finished = !isCur && cur.isNotBlank() && letter < cur
            val r = Ui.row(this).apply { setPadding(dp(14), dp(10), dp(14), dp(10)); if (isCur) setBackgroundColor(Ui.tealRow) }
            r.addView(text(letter, 13f, if (isCur) Color.WHITE else if (finished) Ui.green else Ui.muted, true).apply {
                gravity = Gravity.CENTER; background = Ui.rounded(this@SessionActivity, if (isCur) Ui.teal else if (finished) Ui.greenLight else Ui.surface2, 14) },
                LinearLayout.LayoutParams(dp(28), dp(28)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            col.addView(text(p.substringAfter(" ").replaceFirstChar { it.uppercase() }, 15f, Ui.ink, isCur))
            if (isCur) col.addView(text("Now", 12.5f, Ui.muted)) else if (finished) col.addView(text("Done", 12.5f, Ui.muted))
            r.addView(col)
            box.addView(r)
        }
        if (done >= 0) add(box, 12)
    }

    // ---- end of a session ----

    private fun markSessionDone() {
        if (sid != "sayagain" && sid != "reactions") progress.markDone(week, dayNo, sid)
    }

    private fun nextSession(): JSONObject? = day?.let { d -> progress.sessionsFor(d).firstOrNull { !progress.isDone(week, dayNo, it.optString("id")) && it.optString("id") != sid } }

    private fun nextSessionLabel(): String? {
        val d = day ?: return null
        val list = progress.sessionsFor(d)
        val n = nextSession() ?: return null
        val num = list.indexOf(n) + 1
        return "Next: session $num, ${n.optString("title").lowercase()}"
    }

    private fun goNextSession() {
        val n = nextSession()
        if (n == null) { finish(); return }
        startActivity(Intent(this, SessionActivity::class.java).putExtra("week", week).putExtra("day", dayNo).putExtra("session", n.optString("id")))
        finish()
    }

    private fun finishSession() {
        needVoice(false)
        content.removeAllViews()
        markSessionDone()
        if (testMode) { scoreTest(); return }
        header(session?.optString("title") ?: "Done", "Session done", null)
        add(text("✓  Session done!", 22f, Ui.green, true), 12)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nextSessionLabel()?.let { l -> box.addView(Ui.button(this, l) { goNextSession() }); box.addView(View(this), LinearLayout.LayoutParams(1, dp(10))) }
        box.addView(Ui.button(this, "Back to Home", outline = true) { finish() })
        setBottom(box)
    }

    private fun scoreTest() {
        header("Weekly test", "Result", null)
        val wait = text("Scoring your test…", 14f, Ui.muted); add(wait, 12)
        judge(TEST_SCORE, "His answers in the weekly test:\n$testLog") { r ->
            content.removeView(wait)
            val total = r.optInt("total", 0)
            val pass = total >= 70
            progress.putString("testScore:w$week", total.toString())
            add(text("$total / 100  ·  ${if (pass) "Passed ✓" else "Not yet: the two weakest days come back before week 2"}", 18f, if (pass) Ui.green else Ui.orangeDark, true), 10)
            val areas = r.optJSONArray("areas") ?: JSONArray()
            val sb = StringBuilder()
            for (k in 0 until areas.length()) { val a = areas.getJSONObject(k); sb.append("${a.optString("name")}: ${a.optInt("score")} / ${a.optInt("max")}\n") }
            progress.putString("testAreas:w$week", areas.toString())
            add(Ui.enSiCard(this, "By area", sb.toString().trim(), ""), 10)
            add(Ui.enSiCard(this, "Work on", r.optString("work_on_en"), r.optString("work_on_si"), Ui.orange), 10)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            nextSessionLabel()?.let { l -> box.addView(Ui.button(this, l) { goNextSession() }); box.addView(View(this), LinearLayout.LayoutParams(1, dp(10))) }
            box.addView(Ui.button(this, "Back to Home", outline = true) { finish() })
            setBottom(box)
        }
    }

    // ---------------------------------------------------------------- AI

    private fun persona(who: Character) = "\nYou also write \"say\": what ${who.name} says out loud right after his answer, ONE short natural sentence in " +
        "${who.name}'s own personality (${if (who.male) "Sam: enthusiastic, friendly, jokey, a big Marvel fan" else "Maya: witty, relaxed, playful, loves Batman and DC, teases about Marvel"}), " +
        "warm and supportive; never correct his grammar in it, never tease his English."

    private fun judge(system: String, user: String, done: (JSONObject) -> Unit) {
        thread {
            val r = runCatching { Ai.ask(this, system, user) }
            main.post {
                if (isFinishing) return@post
                r.onSuccess(done).onFailure {
                    talk.enabled = true
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("lastProblem", it.message ?: it.toString()).apply()
                    add(text("The coach couldn't check this right now (internet?). Let's carry on.", 14f, Ui.orangeDark), 8)
                    done(JSONObject().put("mark", "understandable"))
                }
            }
        }
    }

    private fun logAnswer(kind: String, prompt: String, said: List<Pair<String, Boolean>>, mark: String) {
        progress.add("answers")
        if (mark == "good") progress.add("good")
        progress.log("answers", JSONObject().put("kind", kind).put("prompt", prompt).put("said", TalkBar.forAi(said)).put("mark", mark).put("where", "w${week}d$dayNo"))
        if (testMode && kind == "drill") testLog.put(JSONObject().put("part", steps[index].optString("point")).put("prompt", prompt).put("answer", TalkBar.forAi(said)))
    }

    private fun overlap(heard: String, target: String): Float {
        fun words(t: String) = t.lowercase().replace(Regex("[^a-z' ]"), " ").split(" ").filter { it.length > 1 }
        val want = words(target).toSet(); if (want.isEmpty()) return 0f
        val got = words(heard).toSet()
        return want.count { it in got }.toFloat() / want.size
    }

    companion object {
        const val PRIVACY_EN = "Your voice goes to Google's speech service, which turns it into words. Your words go to Google's free Gemini AI, " +
            "so Sam and Maya can understand and help you. Google may use what you say on the free service to improve its AI, so don't say " +
            "private things like passwords or bank numbers. Your recordings, words and results stay on this phone until you remove the app."
        const val PRIVACY_SI = "ඔබේ හඬ Google හි කථන සේවාවට යයි; එය ඔබේ කතාව වචන බවට පත් කරයි. ඔබේ වචන Google හි නොමිලේ ලැබෙන Gemini AI වෙත යයි, " +
            "Sam සහ Maya ට ඔබව තේරුම් ගෙන උදව් කිරීමට. නොමිලේ සේවාවේදී ඔබ කියන දේ Google තම AI දියුණු කිරීමට භාවිතා කළ හැකියි, එබැවින් " +
            "මුරපද හෝ බැංකු අංක වැනි පුද්ගලික දේ කියන්න එපා. ඔබේ හඬ පටිගත කිරීම්, වචන සහ ප්‍රතිඵල ඔබ යෙදුම ඉවත් කරන තුරු මෙම දුරකථනයේම තිබේ."

        private const val BASE = "You are a warm spoken-English coach for a Sinhala-speaking student (about 20, Sri Lanka) who understands English but freezes when speaking. " +
            "His answers come from speech recognition: never treat a misheard word as his mistake, never comment on accent or pronunciation. Fluency first; only ONE point at a time. " +
            "Parts marked [Sinhala: ...] are what he said in Sinhala (English words may be written in Sinhala letters). Sinhala in your answers must be simple, natural Sinhala. Answer ONLY with JSON.\n"

        const val DRILL_COACH = BASE + "Judge his answer to the remark or situation. JSON: {\"mark\": \"good\" | \"understandable\" | \"try_again\", " +
            "\"praise\": \"one short sentence about what worked (e.g. Your meaning was clear, and you asked back. Well done.)\", \"issue_en\": \"one short tip, or empty\", \"issue_si\": \"the same tip in Sinhala, or empty\", " +
            "\"better\": \"a natural answer close to what he tried to say\", \"si_en\": \"the English for any [Sinhala: ...] part, or empty\", " +
            "\"name\": \"his first name if he said it, else empty\", \"say\": \"...\"}. good = natural and does what was practised (any correct answer counts, not only the models); " +
            "understandable = the meaning gets across, even with a small slip or missing the practised point (small slips are NOT a failure); " +
            "try_again ONLY when he didn't really answer, it is very unclear, or it is only Sinhala."

        const val REACT_COACH = BASE + "He heard a piece of news and reacted. Was the reaction's feeling right (e.g. not \"Wow!\" to bad news)? " +
            "JSON: {\"ok\": true | false, \"praise\": \"one short sentence\", \"tip_en\": \"one short tip or empty\", \"tip_si\": \"same in Sinhala or empty\", " +
            "\"better\": \"a natural reaction + a short follow-up question\", \"si_en\": \"English for any Sinhala part, or empty\", \"say\": \"...\"}."

        const val LISTEN_COACH = BASE + "He listened to someone and answers a question about it. JSON: {\"mark\": \"good\" | \"understandable\" | \"try_again\", " +
            "\"praise\": \"one short sentence\", \"feedback_en\": \"one short tip, or empty\", \"feedback_si\": \"same in Sinhala\", \"better\": \"a natural answer\", " +
            "\"si_en\": \"English for any Sinhala part, or empty\", \"partner_reply\": \"if he asked the speaker a question, the speaker's short friendly spoken answer (1-2 sentences); else empty\", \"say\": \"...\"}."

        const val RECORD_COACH = BASE + "The audio is his short spoken talk on the topic (no score). Listen to it. JSON: {\"transcript\": \"what he said, word for word (Sinhala parts in Sinhala)\", " +
            "\"long_pauses\": number of pauses longer than about 3 seconds, \"connectors\": [\"connectors he used, like because, so, actually\"], " +
            "\"follow\": \"easy\" | \"mostly easy\" | \"hard\" (how easy he is to follow, judged on pauses, order of ideas and words, NEVER on accent), " +
            "\"good\": \"one thing he did well, one sentence\", \"compared\": \"one short sentence comparing with last time, or empty\", " +
            "\"tip_en\": \"one thing to try next time\", \"tip_si\": \"same in Sinhala\"}."

        const val CHAT_RESULT = BASE + "Look at the whole conversation and count what HE did. JSON: {\"headline\": \"Nice work. + one short phrase about the best thing (e.g. Nice work. You started it yourself!)\", " +
            "\"questions\": n, \"followups\": n, \"longest_turn_words\": n, \"stuck_recovered\": n, \"asked_help\": n, \"sinhala_uses\": n, \"sinhala_note\": \"e.g. 1 new word, 1 freeze\", " +
            "\"goal_met\": true | false, \"communication\": [{\"en\": \"one thing that would make the conversation work better, with what happened\", \"si\": \"same in Sinhala\"}], " +
            "\"grammar\": [{\"en\": \"one small grammar slip and the right way, e.g. 'because of I have' → 'because I have'\", \"si\": \"same in Sinhala\", \"sentence\": \"his sentence said correctly\"}], " +
            "\"score\": 0-100 if Scored is true (for the mentor only; communication first), else omit, \"summary\": \"one encouraging sentence\"}."

        const val TEST_SCORE = BASE + "Score his weekly test on communication. Areas and maximum points: Fluency 25, Connecting 20, Keeping it going (bounce-backs, follow-ups, reactions, asking when lost) 20, " +
            "Starting and closing 10, Being understood 15, Grammar (main errors only) 5, Confidence 5. Asking for help is never a minus. Be fair, not strict. " +
            "JSON: {\"areas\": [{\"name\": \"…\", \"score\": n, \"max\": n}], \"total\": n, \"work_on_en\": \"2 short points\", \"work_on_si\": \"same in Sinhala\"}."

        fun start(c: Context, week: Int, day: Int, session: String) =
            c.startActivity(Intent(c, SessionActivity::class.java).putExtra("week", week).putExtra("day", day).putExtra("session", session))
    }
}
