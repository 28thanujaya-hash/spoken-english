package lk.spokenenglish.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Runs one session of a day, step by step, from the lesson files (Lessons.kt):
 * info · model · drill · react · listen · chat / freechat (→ conversation screen → result) · record · agree ·
 * speed · sayagain · test. Voice answers use the same talk bar as the conversation.
 */
class SessionActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var stepBar: ProgressBar
    private lateinit var title: TextView
    private lateinit var talk: TalkBar
    private lateinit var voice: CharacterVoice
    private lateinit var progress: Progress
    private lateinit var shake: ShakeReport
    private var face: FaceView? = null

    private var week = 1
    private var dayNo = 1
    private var sid = ""
    private var day: JSONObject? = null
    private var steps: List<JSONObject> = emptyList()
    private var index = 0
    private var onAnswer: ((List<Pair<String, Boolean>>) -> Unit)? = null
    private var testMode = false
    private val testLog = JSONArray()
    private var pendingChat: JSONObject? = null
    private var voiceReady = false
    private var pendingSpeak: (() -> Unit)? = null

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
        voice.onSpeaking = { t -> face?.talking = t }
        thread {
            if (Lessons.weeks.isEmpty()) Lessons.load(this)
            main.post { begin() }
        }
    }

    private fun begin() {
        if (sid == "sayagain") {
            title.text = "Say them again"
            steps = listOf(JSONObject().put("type", "sayagain"))
        } else {
            day = Lessons.day(week, dayNo)
            val s = day?.let { d -> Lessons.sessions(d).firstOrNull { it.optString("id") == sid } }
            if (s == null) { content.addView(Ui.label(this, "This lesson couldn't be loaded. Check the internet and try again.", 16f)); return }
            title.text = s.optString("title")
            val arr = s.getJSONArray("steps")
            steps = (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        stepBar.max = steps.size
        show(0)
    }

    override fun onResume() { super.onResume(); shake.start() }
    override fun onPause() { super.onPause(); shake.stop(); voice.stop(); talk.stopIfRecording() }
    override fun onDestroy() { talk.destroy(); voice.shutdown(); main.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.cream) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(this@SessionActivity, 8), Ui.dp(this@SessionActivity, 8), Ui.dp(this@SessionActivity, 16), 0) }
        header.addView(Ui.link(this, "✕") { finish() })
        title = Ui.label(this, "", 18f, Ui.ink, true)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        stepBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.teal)
        }
        root.addView(stepBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8)).apply { setMargins(Ui.dp(this@SessionActivity, 16), Ui.dp(this@SessionActivity, 6), Ui.dp(this@SessionActivity, 16), 0) })
        scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(this@SessionActivity, 16), Ui.dp(this@SessionActivity, 12), Ui.dp(this@SessionActivity, 16), Ui.dp(this@SessionActivity, 16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        talk = TalkBar(this) { said -> onAnswer?.invoke(said) }
        talk.onStart = { voice.stop() }
        root.addView(talk.view)
        Ui.fitSystemBars(root)
        setContentView(root)
    }

    private fun add(v: View, top: Int = 12) { content.addView(v, Ui.lp(this, top)); scrollDown() }
    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    private fun text(t: CharSequence, size: Float = 15.5f, color: Int = Ui.ink, bold: Boolean = false) = Ui.label(this, t, size, color, bold)
    private fun nameIn(t: String) = t.replace("[his name]", Ui.name(this).replaceFirstChar { it.uppercase() })

    private fun needVoice(on: Boolean) { talk.view.visibility = if (on) View.VISIBLE else View.GONE; talk.reset() }

    private fun next() {
        voice.stop()
        show(index + 1)
    }

    private fun nextButton(label: String = "Next") = Ui.button(this, label) { next() }

    private fun speak(who: Character, text: String) {
        val run = { voice.speak(nameIn(text), who) }
        if (voiceReady) run() else pendingSpeak = run
    }

    private fun faceOf(who: Character, height: Int = 130): FaceView = FaceView(this).apply {
        character = who; mood = "happy"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@SessionActivity, height))
    }

    /** A row: small face + name + "▶ Again". Words are not shown (voice only). */
    private fun partnerRow(who: Character, say: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val f = faceOf(who); face = f
        box.addView(f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(text(who.name, 16f, Ui.ink, true))
        row.addView(Ui.link(this, "  ▶ Again, please") { speak(who, say) })
        box.addView(row)
        return box
    }

    // ---------------------------------------------------------------- steps

    private fun show(i: Int) {
        if (i >= steps.size) { finishSession(); return }
        index = i
        stepBar.progress = i
        content.removeAllViews()
        face = null
        onAnswer = null
        val s = steps[i]
        val who = Ui.character(s.optString("who", "both"), i)
        when (s.optString("type")) {
            "info" -> stepInfo(s)
            "model" -> stepModel(s, who)
            "drill" -> stepDrill(s, who)
            "react" -> stepReact(s, who, 0)
            "listen" -> stepListen(s, who)
            "chat", "freechat" -> stepChat(s, who)
            "record" -> stepRecord(s, who)
            "agree" -> stepAgree(s)
            "speed" -> stepSpeed(0, mutableListOf())
            "sayagain" -> stepSayAgain(0)
            "test" -> { testMode = true; stepInfo(JSONObject().put("title", "Weekly test").put("en",
                "Parts: ${s.optString("parts")}. Pass mark: ${s.optInt("pass", 70)}. Just talk as you normally would. Asking is never a minus.")
                .put("si", "කොටස්: ${s.optString("parts")}. සමත් ලකුණ: ${s.optInt("pass", 70)}. සාමාන්‍ය විදිහට කතා කරන්න. ඇසීම කවදාවත් අඩුවක් නොවේ.")) }
            else -> next()
        }
    }

    private fun stepInfo(s: JSONObject) {
        needVoice(false)
        add(Ui.enSiCard(this, s.optString("title"), s.optString("en"), s.optString("si")), 0)
        val ex = s.optJSONArray("examples")
        if (ex != null) for (k in 0 until ex.length()) add(text("• " + ex.getString(k)), 6)
        add(nextButton(), 18)
    }

    private fun stepModel(s: JSONObject, who: Character) {
        needVoice(false)
        val lines = s.optJSONArray("lines") ?: JSONArray()
        val say = (0 until lines.length()).joinToString(" ") { lines.getString(it) }
        add(partnerRow(who, say), 0)
        if (s.optString("note").isNotBlank()) add(Ui.enSiCard(this, "Listen", s.optString("note"), ""), 10)
        add(nextButton(), 18)
        speak(who, say)
    }

    private fun stepDrill(s: JSONObject, who: Character, tries: Int = 0) {
        val prompt = s.optString("prompt")
        val situation = prompt.startsWith("(")
        val models = s.optJSONArray("models") ?: JSONArray()
        val diagnostic = models.length() == 0
        if (tries == 0) {
            needVoice(true)
            if (situation) {
                add(faceOf(who, 110).also { face = it }, 0)
                add(Ui.enSiCard(this, "Your turn", prompt.trim('(', ')'), "", Ui.teal), 6)
            } else add(partnerRow(who, prompt), 0)
            if (s.optString("point").isNotBlank()) add(Ui.enSiCard(this, if (testMode) "" else "Practise", s.optString("point"), s.optString("si")), 10)
            if (!situation) speak(who, prompt)
        }
        onAnswer = { said ->
            add(myBubble(said))
            talk.enabled = false
            val wait = text("Checking…", 14f, Ui.muted)
            add(wait, 6)
            judge(DRILL_COACH, "The remark or situation: $prompt\nWhat to practise: ${s.optString("point")}\n" +
                    "Model answers: ${if (diagnostic) "(none: a starting test, just judge kindly)" else (0 until models.length()).joinToString(" | ") { nameIn(models.getString(it)) }}\n" +
                    "His answer: ${TalkBar.forAi(said)}") { r ->
                content.removeView(wait)
                talk.enabled = true
                r.optString("name").takeIf { it.isNotBlank() && it.length < 30 }?.let { progress.putString("name", it); getSharedPreferences("progress", MODE_PRIVATE).edit().putString("name", it).apply() }
                val mark = r.optString("mark", "understandable")
                logAnswer("drill", prompt, said, mark)
                add(feedbackCard(mark, r))
                if (TalkBar.hasSinhala(said)) progress.add("sinhalaUses")
                if (mark == "try_again" && tries < 2 && !diagnostic && !testMode) {
                    add(text("Try again: tap the mic.", 14f, Ui.muted), 8)
                    stepDrillRetry(s, who, tries + 1)
                } else {
                    talk.reset(); needVoice(false)
                    add(nextButton(), 14)
                }
            }
        }
    }

    private fun stepDrillRetry(s: JSONObject, who: Character, tries: Int) {
        val keep = onAnswer
        stepDrill(s, who, tries)
        if (onAnswer == null) onAnswer = keep
    }

    private fun stepReact(s: JSONObject, who: Character, item: Int) {
        val items = s.optJSONArray("items") ?: JSONArray()
        if (item >= items.length()) { needVoice(false); add(text("Nice reacting!", 16f, Ui.green, true)); add(nextButton(), 12); return }
        val it = items.getJSONObject(item)
        if (item == 0) { content.removeAllViews(); needVoice(true) }
        add(text("News ${item + 1} of ${items.length()}: react with the right sound, then ask something.", 14f, Ui.muted), if (item == 0) 0 else 18)
        add(partnerRow(who, it.optString("news")), 6)
        speak(who, it.optString("news"))
        onAnswer = { said ->
            add(myBubble(said)); talk.enabled = false
            judge(REACT_COACH, "News: ${it.optString("news")}\nThe feeling of the news: ${it.optString("feel")}\nHis reaction: ${TalkBar.forAi(said)}") { r ->
                talk.enabled = true
                val ok = r.optBoolean("ok", true)
                logAnswer("react", it.optString("news"), said, if (ok) "good" else "try_again")
                add(feedbackCard(if (ok) "good" else "understandable", r))
                main.postDelayed({ stepReact(s, who, item + 1) }, 600)
            }
        }
    }

    private fun stepListen(s: JSONObject, who: Character) {
        needVoice(false)
        val script = s.optString("script")
        add(text(s.optString("title"), 18f, Ui.ink, true), 0)
        add(partnerRow(who, script), 8)
        add(text("Listen. If it's too fast, tap \"Again, please\". Then answer the questions.", 14f, Ui.muted), 6)
        val qs = s.optJSONArray("questions") ?: JSONArray()
        add(Ui.button(this, "I'm ready for the questions") { listenQuestion(s, who, qs, 0) }, 14)
        speak(who, script)
    }

    private fun listenQuestion(s: JSONObject, who: Character, qs: JSONArray, k: Int) {
        if (k >= qs.length()) { needVoice(false); add(nextButton(), 14); return }
        needVoice(true)
        val q = qs.getString(k)
        add(Ui.enSiCard(this, "Question ${k + 1}", q, "", Ui.teal), 16)
        onAnswer = { said ->
            add(myBubble(said)); talk.enabled = false
            judge(LISTEN_COACH, "What ${who.name} said: ${s.optString("script")}\nQuestion: $q\nHis spoken answer: ${TalkBar.forAi(said)}") { r ->
                talk.enabled = true
                logAnswer("listen", q, said, r.optString("mark", "good"))
                add(feedbackCard(r.optString("mark", "good"), r))
                r.optString("partner_reply").takeIf { it.isNotBlank() }?.let { speak(who, it) }
                listenQuestion(s, who, qs, k + 1)
            }
        }
    }

    private fun stepChat(s: JSONObject, who: Character) {
        needVoice(false)
        val free = s.optString("type") == "freechat"
        if (free) {
            add(text("Free chat · no score", 18f, Ui.ink, true), 0)
            val ideas = s.optJSONArray("ideas") ?: JSONArray()
            add(Ui.enSiCard(this, "Ideas to talk about", (0 until ideas.length()).joinToString("\n") { "• " + ideas.getString(it) } +
                "\n(Just say them; you don't have to tap anything.)", ""), 10)
        } else {
            add(text("Before you talk", 18f, Ui.ink, true), 0)
            add(text(when (s.optString("mode")) { "phone" -> "📞 Phone call"; "video" -> "📹 Video call"; else -> "Face to face" } + " · with ${who.name}", 14f, Ui.muted), 4)
            add(Ui.enSiCard(this, s.optString("where"), s.optString("en"), s.optString("si"), Ui.teal), 10)
            add(Ui.enSiCard(this, "Your goal", s.optString("goal") + "\nYou don't know exactly what they'll say.", ""), 10)
        }
        add(Ui.button(this, "Start talking") {
            pendingChat = s
            startActivityForResult(Intent(this, MainActivity::class.java)
                .putExtra("scene", s.toString()).putExtra("partner", who.name)
                .putExtra("where", "Week $week, day $dayNo, $sid"), 7)
        }, 18)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7) return
        val s = pendingChat ?: return
        val transcript = data?.getStringExtra("transcript") ?: "[]"
        val turns = data?.getIntExtra("turns", 0) ?: 0
        progress.add("conversations"); progress.add("turns", turns)
        content.removeAllViews()
        if (turns == 0) { add(text("You didn't say anything this time. Try again?", 16f)); add(Ui.button(this, "Back") { show(index) }, 12); return }
        val free = s.optString("type") == "freechat"
        add(text(if (free) "Nice chat!" else "Conversation result", 18f, Ui.ink, true), 0)
        val wait = text("Looking at your conversation…", 14f, Ui.muted); add(wait, 6)
        judge(CHAT_RESULT, "Scene: ${s.optString("en")}\nHis goal: ${s.optString("goal")}\nScored: ${s.optBoolean("scored", !free) && !free}\n" +
                "Conversation (he = student):\n$transcript") { r ->
            content.removeView(wait)
            if (testMode) testLog.put(JSONObject().put("part", "E. Full conversation").put("transcript", transcript))
            progress.log("results", JSONObject().put("where", "w${week}d$dayNo").put("score", r.optInt("score", -1)).put("turns", turns))
            add(resultCard(r, turns, free))
            add(nextButton(), 14)
        }
    }

    private fun stepRecord(s: JSONObject, who: Character) {
        needVoice(true)
        val topic = s.optString("topic")
        add(text(if (s.has("saveAs")) "Tell ${who.name}" else "Daily review recording", 18f, Ui.ink, true), 0)
        add(Ui.enSiCard(this, "Talk about this", "$topic\nTake your time: pauses are fine. Tap ↑ when you finish.", "", Ui.teal), 10)
        val prev = progress.getString("rec:$topic")
        onAnswer = { said ->
            add(myBubble(said)); talk.enabled = false; needVoice(false)
            judge(RECORD_COACH, "Topic: $topic\nLast time he said (may be empty): ${prev ?: ""}\nToday he said: ${TalkBar.forAi(said)}") { r ->
                talk.enabled = true
                progress.putString("rec:$topic", TalkBar.plain(said))
                s.optString("saveAs").takeIf { it.isNotBlank() }?.let { progress.putString("save:$it", TalkBar.plain(said)) }
                if (testMode) testLog.put(JSONObject().put("part", "D. Long turn").put("answer", TalkBar.forAi(said)))
                add(recordCard(r))
                add(nextButton(), 14)
            }
        }
    }

    private fun stepAgree(s: JSONObject) {
        needVoice(false)
        add(Ui.enSiCard(this, "Before we start", s.optString("en"), s.optString("si"), Ui.teal), 0)
        add(Ui.button(this, "I agree") { progress.putString("agreed", System.currentTimeMillis().toString()); next() }, 18)
    }

    /** Listening speed test: 5 sentences, slow to a little fast; his comfort speed = fastest he repeats well. */
    private fun stepSpeed(k: Int, ok: MutableList<Float>) {
        val rates = listOf(0.75f, 0.85f, 0.92f, 1.0f, 1.08f)
        val sentences = listOf("I like watching movies on the weekend.", "My friend is coming to the cinema with me.",
            "We bought popcorn before the film started.", "The ending was much better than I expected.",
            "Honestly, I think the second film was even funnier than the first one.")
        if (k == 0) { content.removeAllViews(); needVoice(true); add(text("Listening speed", 18f, Ui.ink, true), 0)
            add(text("Maya says 5 sentences, from slow to a little fast. Repeat each one as well as you can.", 14.5f, Ui.muted), 6) }
        if (k >= rates.size) {
            val comfort = ok.maxOrNull() ?: 0.85f
            val stage = when { comfort >= 1.0f -> 1.0f; comfort >= 0.96f -> 0.96f; else -> 0.92f }
            getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("baseRate", stage).apply()
            voice.baseRate = stage
            needVoice(false)
            add(Ui.enSiCard(this, "Your listening speed", "The app will start at speed ${"%.2f".format(stage)} and go faster as you understand more.", ""), 14)
            add(nextButton(), 12); return
        }
        val who = Characters.MAYA
        add(partnerRow(who, sentences[k]), 14)
        val old = voice.baseRate
        voice.baseRate = rates[k]; speak(who, sentences[k]); main.postDelayed({ voice.baseRate = old }, 300)
        onAnswer = { said ->
            add(myBubble(said))
            if (overlap(TalkBar.plain(said), sentences[k]) >= 0.8f) ok.add(rates[k])
            stepSpeed(k + 1, ok)
        }
    }

    /** Say them again: things he couldn't say yet. Listen, then say it from memory. */
    private fun stepSayAgain(k: Int) {
        val items = sayAgainItems()
        if (k == 0) { content.removeAllViews(); add(text("Say them again", 18f, Ui.ink, true), 0) }
        if (items.isEmpty() || k >= items.size) {
            needVoice(false)
            add(text(if (items.isEmpty()) "Nothing to practise yet. Well done!" else "Done for now!", 16f, Ui.green, true), 10)
            add(nextButton("Finish"), 12); return
        }
        needVoice(true)
        val it = items[k]
        val who = Ui.character("both", k)
        add(Ui.enSiCard(this, "${k + 1} of ${items.size}", "You wanted to say: ${it.optString("si")}\nListen, then say it in English.", "", Ui.orange), 14)
        add(partnerRow(who, it.optString("sentence")), 6)
        speak(who, it.optString("sentence"))
        onAnswer = { said ->
            add(myBubble(said))
            if (overlap(TalkBar.plain(said), it.optString("sentence")) >= 0.7f) {
                add(text("✓ Well said!", 15f, Ui.green, true), 6)
                val done = getSharedPreferences("progress", MODE_PRIVATE)
                done.edit().putStringSet("sayDone", (done.getStringSet("sayDone", emptySet()) ?: emptySet()) + it.optString("sentence")).apply()
            } else add(text("Good try! It stays on your list.", 15f, Ui.muted), 6)
            main.postDelayed({ stepSayAgain(k + 1) }, 700)
        }
    }

    private fun sayAgainItems(): List<JSONObject> {
        val done = getSharedPreferences("progress", MODE_PRIVATE).getStringSet("sayDone", emptySet()) ?: emptySet()
        val raw = runCatching { JSONArray(getSharedPreferences("settings", MODE_PRIVATE).getString("sayThemAgain", "[]")) }.getOrDefault(JSONArray())
        return (0 until raw.length()).map { raw.getJSONObject(it) }.filter { it.optString("sentence").isNotBlank() && it.optString("sentence") !in done }
            .distinctBy { it.optString("sentence") }.takeLast(8)
    }

    private fun finishSession() {
        needVoice(false)
        content.removeAllViews()
        stepBar.progress = steps.size
        if (sid != "sayagain") progress.markDone(week, dayNo, sid)
        if (testMode) { scoreTest(); return }
        add(text("✓  Session done!", 22f, Ui.green, true), 0)
        nextSessionButton()
    }

    private fun nextSessionButton() {
        val d = day
        val nextS = d?.let { dd -> Lessons.sessions(dd).firstOrNull { !progress.isDone(week, dayNo, it.optString("id")) } }
        if (nextS != null) add(Ui.button(this, "Next: ${nextS.optString("title")}") {
            startActivity(Intent(this, SessionActivity::class.java).putExtra("week", week).putExtra("day", dayNo).putExtra("session", nextS.optString("id")))
            finish()
        }, 18)
        add(Ui.button(this, "Home", Color.WHITE, Ui.teal) { finish() }, 10)
    }

    private fun scoreTest() {
        add(text("Weekly test result", 20f, Ui.ink, true), 0)
        val wait = text("Scoring your test…", 14f, Ui.muted); add(wait, 6)
        judge(TEST_SCORE, "His answers in the weekly test:\n$testLog") { r ->
            content.removeView(wait)
            val total = r.optInt("total", 0)
            val pass = total >= 70
            progress.putString("testScore:w$week", total.toString())
            add(text("$total / 100  ·  ${if (pass) "Passed ✓" else "Not yet: the two weakest days come back before week 2"}", 18f, if (pass) Ui.green else Ui.orangeDark, true), 10)
            val areas = r.optJSONArray("areas") ?: JSONArray()
            val sb = StringBuilder()
            for (k in 0 until areas.length()) { val a = areas.getJSONObject(k); sb.append("${a.optString("name")}: ${a.optInt("score")} / ${a.optInt("max")}\n") }
            add(Ui.enSiCard(this, "By area", sb.toString().trim(), ""), 10)
            add(Ui.enSiCard(this, "Work on", r.optString("work_on_en"), r.optString("work_on_si"), Ui.orange), 10)
            nextSessionButton()
        }
    }

    // ---------------------------------------------------------------- cards

    private fun myBubble(said: List<Pair<String, Boolean>>): View {
        val sb = android.text.SpannableStringBuilder()
        for ((t, si) in said) {
            if (sb.isNotEmpty()) sb.append(" ")
            val st = sb.length; sb.append(t)
            if (si) sb.setSpan(android.text.style.BackgroundColorSpan(Color.parseColor("#2F8A85")), st, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        wrap.addView(text(sb, 15.5f, Color.WHITE).apply { background = Ui.rounded(this@SessionActivity, Ui.teal, 16); setPadding(Ui.dp(this@SessionActivity, 13), Ui.dp(this@SessionActivity, 10), Ui.dp(this@SessionActivity, 13), Ui.dp(this@SessionActivity, 10)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        wrap.addView(text("What the app heard", 11.5f, Ui.muted))
        return wrap
    }

    /** Good / Understandable / Try again, one issue (EN | සිං), a better way to say it, and his Sinhala in English. */
    private fun feedbackCard(mark: String, r: JSONObject): View {
        val (label, color, bg) = when (mark) {
            "good" -> Triple("Good!", Ui.green, Ui.greenLight)
            "try_again" -> Triple("Try again", Ui.orangeDark, Ui.orangeLight)
            else -> Triple("Understandable", Ui.teal, Ui.tealLight)
        }
        val card = Ui.card(this, color, 2)
        card.addView(text(label + (r.optString("praise").takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""), 16f, color, true).apply {
            background = Ui.rounded(this@SessionActivity, bg, 10); setPadding(Ui.dp(this@SessionActivity, 10), Ui.dp(this@SessionActivity, 5), Ui.dp(this@SessionActivity, 10), Ui.dp(this@SessionActivity, 5)) })
        val issueEn = r.optString("issue_en").ifBlank { r.optString("tip_en").ifBlank { r.optString("feedback_en") } }
        val issueSi = r.optString("issue_si").ifBlank { r.optString("tip_si").ifBlank { r.optString("feedback_si") } }
        if (issueEn.isNotBlank()) card.addView(Ui.enSiCard(this, "", issueEn, issueSi).apply { background = null; setPadding(0, 0, 0, 0) }, Ui.lp(this, 8))
        r.optString("si_en").takeIf { it.isNotBlank() }?.let { card.addView(text("Your Sinhala in English: $it", 14.5f, Ui.orangeDark, true), Ui.lp(this, 8)) }
        r.optString("better").takeIf { it.isNotBlank() }?.let { card.addView(text("→  $it", 15.5f, Ui.ink, true), Ui.lp(this, 8)) }
        return card
    }

    private fun resultCard(r: JSONObject, turns: Int, free: Boolean): View {
        val card = Ui.card(this, Ui.teal, 2)
        if (!free && r.has("score") && r.optInt("score", -1) >= 0) card.addView(text("Score: ${r.optInt("score")} / 100", 18f, Ui.teal, true))
        card.addView(text("Your turns: $turns · Questions you asked: ${r.optInt("questions")} · Follow-ups: ${r.optInt("followups")}\n" +
            "Longest turn: ${r.optInt("longest_turn_words")} words · Stuck and recovered: ${r.optInt("stuck_recovered")} · Asked for help: ${r.optInt("asked_help")} · Sinhala: ${r.optInt("sinhala_uses")}", 14.5f), Ui.lp(this, 6))
        val good = r.optJSONArray("good")
        if (good != null && good.length() > 0) card.addView(text("Well done: " + (0 until good.length()).joinToString("; ") { good.getString(it) }, 14.5f, Ui.green), Ui.lp(this, 8))
        fun list(key: String, title: String, color: Int) {
            val a = r.optJSONArray(key) ?: return
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                card.addView(Ui.enSiCard(this, if (k == 0) title else "", o.optString("en"), o.optString("si"), color), Ui.lp(this, 8))
            }
        }
        if (!free) { list("communication", "Communication", Ui.orange); list("grammar", "Small grammar slip", Ui.line) }
        r.optString("summary").takeIf { it.isNotBlank() }?.let { card.addView(text(it, 15f, Ui.ink, true), Ui.lp(this, 8)) }
        return card
    }

    private fun recordCard(r: JSONObject): View {
        val card = Ui.card(this, Ui.teal, 2)
        card.addView(text("No score today. ${r.optString("good")}", 15f, Ui.green, true))
        r.optString("compared").takeIf { it.isNotBlank() }?.let { card.addView(text("Compared with last time: $it", 14.5f), Ui.lp(this, 6)) }
        val c = r.optJSONArray("connectors")
        if (c != null && c.length() > 0) card.addView(text("Connectors you used: " + (0 until c.length()).joinToString(", ") { c.getString(it) }, 14.5f, Ui.muted), Ui.lp(this, 6))
        if (r.optString("tip_en").isNotBlank()) card.addView(Ui.enSiCard(this, "One thing to try", r.optString("tip_en"), r.optString("tip_si"), Ui.orange), Ui.lp(this, 8))
        return card
    }

    // ---------------------------------------------------------------- AI

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
        private const val BASE = "You are a warm spoken-English coach for a Sinhala-speaking student (about 20, Sri Lanka) who understands English but freezes when speaking. " +
            "His answers come from speech recognition: never treat a misheard word as his mistake, never comment on accent. Fluency first; only ONE point at a time. " +
            "Parts marked [Sinhala: ...] are what he said in Sinhala (English words may be written in Sinhala letters). Sinhala in your answers must be simple, natural Sinhala. Answer ONLY with JSON.\n"

        const val DRILL_COACH = BASE + "Judge his answer to the remark or situation. JSON: {\"mark\": \"good\" | \"understandable\" | \"try_again\", " +
            "\"praise\": \"2-5 words\", \"issue_en\": \"one short tip, or empty\", \"issue_si\": \"the same tip in Sinhala, or empty\", " +
            "\"better\": \"a natural answer close to what he tried to say\", \"si_en\": \"the English for any [Sinhala: ...] part, or empty\", " +
            "\"name\": \"his first name if he said it, else empty\"}. good = natural and does what was practised (any correct answer counts, not only the models); " +
            "understandable = the meaning gets across but misses the point or has a clear mistake; try_again = doesn't answer, very unclear, or only Sinhala."

        const val REACT_COACH = BASE + "He heard a piece of news and reacted. Was the reaction's feeling right (e.g. not \"Wow!\" to bad news)? " +
            "JSON: {\"ok\": true | false, \"praise\": \"2-5 words\", \"tip_en\": \"one short tip or empty\", \"tip_si\": \"same in Sinhala or empty\", " +
            "\"better\": \"a natural reaction + a short follow-up question\", \"si_en\": \"English for any Sinhala part, or empty\"}."

        const val LISTEN_COACH = BASE + "He listened to someone and answers a question about it. JSON: {\"mark\": \"good\" | \"understandable\" | \"try_again\", " +
            "\"praise\": \"2-5 words\", \"feedback_en\": \"one short sentence\", \"feedback_si\": \"same in Sinhala\", \"better\": \"a natural answer\", " +
            "\"si_en\": \"English for any Sinhala part, or empty\", \"partner_reply\": \"if he asked the speaker a question, the speaker's short friendly spoken answer (1-2 sentences); else empty\"}."

        const val RECORD_COACH = BASE + "He recorded a short talk on a topic (no score). Compare with last time if given. JSON: {\"good\": \"one thing he did well, one sentence\", " +
            "\"compared\": \"one short sentence comparing with last time, or empty\", \"connectors\": [\"connectors he used\"], \"tip_en\": \"one thing to try next time\", \"tip_si\": \"same in Sinhala\"}."

        const val CHAT_RESULT = BASE + "Look at the whole conversation and count what HE did. JSON: {\"questions\": n, \"followups\": n, \"longest_turn_words\": n, " +
            "\"stuck_recovered\": n, \"asked_help\": n, \"sinhala_uses\": n, \"goal_met\": true | false, \"good\": [\"2 short things he did well\"], " +
            "\"communication\": [{\"en\": \"one thing that would make the conversation work better\", \"si\": \"same in Sinhala\"}], " +
            "\"grammar\": [{\"en\": \"one small grammar slip and the right way\", \"si\": \"same in Sinhala\"}], " +
            "\"score\": 0-100 if Scored is true (communication first: starting, responding, keeping it going, asking when lost; grammar counts little), else omit, " +
            "\"summary\": \"one encouraging sentence\"}."

        const val TEST_SCORE = BASE + "Score his weekly test on communication. Areas and maximum points: Fluency 25, Connecting 20, Keeping it going (bounce-backs, follow-ups, reactions, asking when lost) 20, " +
            "Starting and closing 10, Being understood 15, Grammar (main errors only) 5, Confidence 5. Asking for help is never a minus. Be fair, not strict. " +
            "JSON: {\"areas\": [{\"name\": \"…\", \"score\": n, \"max\": n}], \"total\": n, \"work_on_en\": \"2 short points\", \"work_on_si\": \"same in Sinhala\"}."

        fun start(c: Context, week: Int, day: Int, session: String) =
            c.startActivity(Intent(c, SessionActivity::class.java).putExtra("week", week).putExtra("day", day).putExtra("session", session))
    }
}
