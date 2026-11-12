package lk.spokenenglish.app

import android.app.Activity
import android.app.AlertDialog
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
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * The app's start: bottom menu Home · Progress · Me, as in the mentor's drawing (dark).
 * Home shows today only (the course plan lives on Progress, mentor 11 Nov). Lessons are downloaded here.
 */
class HomeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabs: List<TextView>
    private lateinit var progress: Progress
    private lateinit var updater: Updater
    private lateinit var shake: ShakeReport
    private var tab = 0
    private var loaded = false
    private var versionTaps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the app ever closes by itself, the reason is kept and goes into the mentor's report.
        val ctx = applicationContext
        Trace.init(this)
        Trace.uploadPending()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev !is CrashKeeper) Thread.setDefaultUncaughtExceptionHandler(CrashKeeper(ctx, prev))
        progress = Progress(this)
        buildUi()
        shake = ShakeReport(this) { "Home screen (tab ${tab + 1})" }
        updater = Updater(this) { Ui.toast(this, it) }
        updater.onReady = { updater.offerIfReady() }
        updater.greetAfterUpdate()
        updater.check(force = true)
        loadLessons()
    }

    private fun loadLessons() {
        body.removeAllViews()
        body.addView(Ui.label(this, "Getting today's lessons…", 16f, Ui.muted))
        thread {
            val ok = Lessons.load(this)
            if (ok) progress.freshStartIfNeeded(Lessons.version)
            if (ok) Trace.i("lessons", "loaded, version ${Lessons.version}") else Trace.w("lessons", "could not load lessons")
            main.post {
                loaded = ok
                if (!ok) {
                    body.removeAllViews()
                    body.addView(Ui.label(this, "The lessons couldn't be downloaded. Check the internet.", 16f))
                    body.addView(Ui.button(this, "Try again") { loadLessons() }, Ui.lp(this, 12))
                } else render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shake.start()
        updater.resumeAfterPermission(); updater.check(); updater.offerIfReady()
        if (loaded) render()
    }
    override fun onPause() { super.onPause(); shake.stop() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.cream) }
        scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.surface) }
        tabs = listOf("⌂\nHome", "ılı\nProgress", "◯\nMe").mapIndexed { i, name ->
            Ui.label(this, name, 13f, Ui.muted, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(58)
                setOnClickListener { Ui.tap(it); tab = i; render() }
                nav.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        root.addView(nav)
        Ui.fitSystemBars(root)
        setContentView(root)
    }

    private fun render() {
        tabs.forEachIndexed { i, t -> t.setTextColor(if (i == tab) Ui.teal else Ui.muted) }
        body.removeAllViews()
        Trace.where = "home · tab ${listOf("Home", "Progress", "Me").getOrElse(tab) { "?" }}"
        Trace.i("screen", Trace.where)
        when (tab) { 0 -> home(); 1 -> progressTab(); else -> me() }
        scroll.scrollTo(0, 0)
    }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun add(v: View, top: Int = 12) = body.addView(v, Ui.lp(this, top))
    private fun t(s: CharSequence, size: Float = 15f, c: Int = Ui.ink, b: Boolean = false) = Ui.label(this, s, size, c, b)
    private fun hisName() = Ui.name(this).takeIf { it != "me" }?.replaceFirstChar { it.uppercase() }

    /** A list card made of rows (title, small line, optional icon tile and ›). */
    private fun listCard(rows: List<View>): LinearLayout {
        val card = Ui.card(this).apply { setPadding(0, dp(2), 0, dp(2)) }
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(Ui.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            card.addView(r)
        }
        return card
    }

    private fun listRow(icon: CharSequence?, iconFg: Int, iconBg: Int, title: String, sub: String?, arrow: Boolean, highlight: Boolean = false,
                        right: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val r = Ui.row(this).apply { setPadding(dp(14), dp(11), dp(14), dp(11)); if (highlight) setBackgroundColor(Ui.tealRow) }
        if (icon != null) r.addView(t(icon, 16f, iconFg, true).apply { gravity = Gravity.CENTER; background = Ui.rounded(this@HomeActivity, iconBg, 12) },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t(title, 15f, Ui.ink, true))
        if (!sub.isNullOrBlank()) col.addView(t(sub, 12.5f, Ui.muted))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) r.addView(right)
        if (arrow) r.addView(t("›", 22f, Ui.muted))
        if (onClick != null) { r.background = Ui.ripple(this, if (highlight) Ui.rounded(this, Ui.tealRow, 0) else null, 0); r.setOnClickListener { Ui.tap(it); onClick() } }
        return r
    }

    private fun anyTime(week: Int, dayN: Int, title: String = "Any time") {
        add(Ui.caps(this, title), 20)
        add(listCard(listOf(
            listRow("“”", Ui.ink, Ui.surface2, "Free chat", null, true) { startActivity(Intent(this, MainActivity::class.java)) },
            listRow("↻", Ui.ink, Ui.surface2, "Say them again", null, true) { SessionActivity.start(this, week, dayN, "sayagain") },
            listRow("!", Ui.ink, Ui.surface2, "Quick reactions", null, true) { SessionActivity.start(this, week, dayN, "reactions") }
        )), 6)
    }

    /** Blue phrase chips that wrap. */
    private fun chips(items: org.json.JSONArray?): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (items == null) return box
        var row = Ui.row(this); var used = 0
        for (k in 0 until items.length()) {
            val t = items.getString(k)
            if (used > 0 && used + t.length > 34) { box.addView(row, Ui.lp(this, 6)); row = Ui.row(this); used = 0 }
            row.addView(Ui.chip(this, t, Ui.blue, Ui.blueLight, 13.5f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            used += t.length + 3
        }
        box.addView(row, Ui.lp(this, 6))
        return box
    }

    // ---------------------------------------------------------------- Home (QA 12 Nov: a day overview, no session names, numbers or minutes)

    private fun home() {
        val cur = progress.currentDay() ?: return
        val (week, d) = cur
        val dayN = d.optInt("day")
        thread { Memory.makePlanIfNeeded(applicationContext, progress, week, d) }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
        val sessions = progress.sessionsFor(d)
        val next = sessions.firstOrNull { !progress.isDone(week, dayN, it.optString("id")) }
        val doneN = sessions.count { progress.isDone(week, dayN, it.optString("id")) }
        val allDone = next == null
        add(t(if (allDone) "Well done${hisName()?.let { ", $it" } ?: "!"}" else "$hello${hisName()?.let { ", $it" } ?: "!"}", 22f, Ui.ink, true), 0)
        if (Lessons.version > progress.seenLessons()) {
            if (progress.seenLessons() > 0) add(Ui.chip(this, "✓  New lessons are ready", Ui.green, Ui.greenLight, 13.5f), 8)
            progress.setSeenLessons(Lessons.version)
        }
        if (allDone) {
            val c = Ui.edgeCard(this)
            c.addView(Ui.caps(this, "Today ✓"))
            c.addView(t(d.optString("title"), 18f, Ui.ink, true), Ui.lp(this, 2))
            c.addView(t(d.optString("byTheEnd").replace("By the end you can", "You can now"), 15f), Ui.lp(this, 6))
            add(c, 14)
            Lessons.allDays().firstOrNull { (w, dd) -> w > week || (w == week && dd.optInt("day") > dayN) }?.let { (_, nd) ->
                val tc = Ui.card(this)
                tc.addView(Ui.caps(this, "Tomorrow"))
                tc.addView(t(nd.optString("title"), 17f, Ui.ink, true), Ui.lp(this, 2))
                add(tc, 10)
            }
            anyTime(week, dayN, "Want more?")
            return
        }
        // Today: the day overview
        val today = Ui.card(this)
        val hr = Ui.row(this)
        hr.addView(Ui.caps(this, "Today"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hr.addView(Ui.link(this, "Today's plan ›") { pages("plan") }.apply { minHeight = dp(32) })
        today.addView(hr)
        today.addView(t(d.optString("title"), 18f, Ui.ink, true))
        today.addView(t(d.optString("overview"), 15f), Ui.lp(this, 8))
        d.optString("byTheEnd").takeIf { it.isNotBlank() }?.let { today.addView(t(it, 14f, Ui.muted), Ui.lp(this, 8)) }
        today.addView(chips(d.optJSONArray("phrases")), Ui.lp(this, 4))
        if (dayN > 0) {
            val sr = Ui.row(this)
            sr.addView(t(if (progress.shortDay()) "Shorter day is on." else "Short on time?", 14f, Ui.muted))
            sr.addView(Ui.link(this, if (progress.shortDay()) "Back to the full day" else "Do a shorter day") { progress.setShortDay(!progress.shortDay()); render() }.apply { minHeight = dp(36) })
            today.addView(sr, Ui.lp(this, 4))
        }
        add(today, 14)
        // Continue: only what's next
        val pos = progress.pos(week, dayN, next!!.optString("id"))
        val cc = Ui.edgeCard(this)
        cc.addView(Ui.caps(this, "Continue"))
        val r = Ui.row(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t(next.optString("title"), 17f, Ui.ink, true))
        col.addView(t(if (pos > 0) "You stopped part-way" else "Not started", 13.5f, Ui.muted))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.smallButton(this, "Continue") { SessionActivity.start(this, week, dayN, next.optString("id")) })
        cc.addView(r, Ui.lp(this, 4))
        // one dot per part of the day: ✓ done · ● next · ○ to come (1.2 finding 2: no numbers, no separate card)
        val dots = android.text.SpannableStringBuilder()
        sessions.forEach { sp ->
            val done = progress.isDone(week, dayN, sp.optString("id"))
            val isNext = sp == next
            val st = dots.length
            dots.append(if (done) "✓" else if (isNext) "●" else "○").append("  ")
            dots.setSpan(android.text.style.ForegroundColorSpan(if (done) Ui.green else if (isNext) Ui.ink else Ui.muted), st, st + 1, 0)
        }
        cc.addView(t(dots, 15f), Ui.lp(this, 8))
        add(cc, 10)
        anyTime(week, dayN)
    }

    private fun practiceCard(frac: Float, msg: String): View {
        val c = Ui.card(this)
        val r = Ui.row(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t("Today's practice", 15.5f, Ui.ink, true))
        col.addView(t(msg, 13.5f, Ui.muted))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.ring(this, frac), LinearLayout.LayoutParams(dp(52), dp(52)))
        c.addView(r)
        return c
    }

    private fun pages(mode: String) = startActivity(Intent(this, PagesActivity::class.java).putExtra("mode", mode))

    // ---------------------------------------------------------------- Progress (numbers live here only)

    private fun progressTab() {
        val cur = progress.currentDay()
        val week = cur?.first ?: 1
        add(t("My progress", 22f, Ui.ink, true), 0)
        val talks = progress.list("talks")
        val results = progress.list("results")
        add(t(if (cur?.second?.optInt("day") == 0) "Day 0" else "Week $week", 13.5f, Ui.muted), 2)
        if (talks.isEmpty() && results.isEmpty()) {
            val c = Ui.card(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(26), dp(16), dp(26)) }
            c.addView(Ui.ring(this, 0f), LinearLayout.LayoutParams(dp(60), dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            c.addView(t("Your numbers start after Day 1", 17f, Ui.ink, true).apply { gravity = Gravity.CENTER }, Ui.lp(this, 12))
            c.addView(t("Your Day 0 talk is saved, so you can hear \"then vs now\" later.", 14f, Ui.muted).apply { gravity = Gravity.CENTER }, Ui.lp(this, 4))
            add(c, 12)
            add(listCard(listOf(listRow(null, 0, 0, "Course plan · 8 weeks", null, true) { pages("course") })), 10)
            return
        }
        val streak = progress.streak()
        val total = progress.totalMinutes()
        val top = Ui.edgeCard(this)
        top.addView(t(if (streak >= 1) "Right on track. Keep going!" else "Welcome back! Let's start again today.", 17f, Ui.ink, true))
        top.addView(t("$streak day${if (streak == 1) "" else "s"} in a row · ${total / 60} h ${total % 60} min practised", 13.5f, Ui.muted), Ui.lp(this, 4))
        add(top, 12)
        fun tile(label: String, value: String, sub: String?): View {
            val c = Ui.card(this).apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
            c.addView(t(label, 13.5f, Ui.muted))
            val r = Ui.row(this)
            r.addView(t(value, 19f, Ui.ink, true))
            if (sub != null) r.addView(t("  $sub", 13f, Ui.muted))
            c.addView(r)
            return c
        }
        fun pair(a: View, b: View) = Ui.row(this).apply {
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(8) })
        }
        val firstT = talks.firstOrNull(); val lastT = talks.lastOrNull()
        val wpm = lastT?.optInt("wpm") ?: 0
        val wpm0 = firstT?.optInt("wpm1") ?: 0
        val pz = lastT?.optDouble("pausesPerMin") ?: 0.0
        val pz0 = firstT?.optDouble("pauses1") ?: 0.0
        val longest = results.maxOfOrNull { it.optInt("longest") } ?: 0
        val mins = maxOf(1, progress.minutesToday())
        val siNow = (progress.countOn("sinhalaUses", progress.today()) * 10f / mins).toInt()
        val siFirst = run { val d = progress.firstDay(); val m = maxOf(1, progress.minutesOn(d)); (progress.countOn("sinhalaUses", d) * 10f / m).toInt() }
        add(pair(tile("Words/min", if (wpm > 0) "$wpm" else "–", if (wpm0 > 0 && wpm != wpm0) "${if (wpm > wpm0) "↑" else "↓"} from $wpm0" else null),
            tile("Long pauses", if (lastT != null) "%.0f/min".format(pz) else "–", if (firstT != null && pz != pz0) "${if (pz < pz0) "↓" else "↑"} from %.0f".format(pz0) else null)), 10)
        add(pair(tile("Longest turn", if (longest > 0) "$longest words" else "–", null),
            tile("Sinhala / 10 min", "$siNow", if (siFirst != siNow) "${if (siNow < siFirst) "↓" else "↑"} from $siFirst" else null)), 8)

        // Then vs now: his Day 0 voice next to today's
        val recs = VoiceRecorder.all(progress)
        val first = progress.getString("saveFile:baseline-talk")?.let { File(it) }?.takeIf { it.exists() } ?: recs.firstOrNull()?.let { File(it.optString("file")) }
        val last = recs.lastOrNull()?.let { File(it.optString("file")) }?.takeIf { it.exists() && it != first }
        val play = Ui.smallButton(this, "▶ Play", false) {
            if (first == null) { Ui.toast(this@HomeActivity, "Your Day 0 recording will play here."); return@smallButton }
            Ui.toast(this@HomeActivity, "Day 0…")
            VoiceRecorder.play(first) { if (last != null) main.post { Ui.toast(this@HomeActivity, "…and now"); VoiceRecorder.play(last) } }
        }
        add(listCard(listOf(listRow(null, 0, 0, "Then vs now", "Day 0 next to today", false, right = play))), 10)

        // Skills (from his profile)
        add(Ui.caps(this, "Skills"), 18)
        val eng = Memory.english(progress)
        val strong = eng.optJSONArray("strong"); val working = eng.optJSONArray("working")
        fun badge(level: String) = when (level) {
            "Strong" -> Ui.chip(this, level, Ui.green, Ui.greenLight)
            "Getting better" -> Ui.chip(this, level, Ui.blue, Ui.blueLight)
            else -> Ui.chip(this, level, Ui.muted, Ui.surface2)
        }
        val rows = mutableListOf<View>()
        if (strong != null) for (k in 0 until strong.length()) rows.add(listRow(null, 0, 0, strong.getString(k).replaceFirstChar { it.uppercase() }, null, false, right = badge("Strong")))
        if (working != null) for (k in 0 until working.length()) rows.add(listRow(null, 0, 0, working.getString(k).replaceFirstChar { it.uppercase() }, null, false, right = badge("Getting better")))
        if (rows.isEmpty()) {
            rows.add(listRow(null, 0, 0, "Talking about myself", null, false, right = badge(if (talks.size >= 3) "Strong" else "Getting better")))
            rows.add(listRow(null, 0, 0, "Asking back", null, false, right = badge(if (results.sumOf { it.optInt("followups") } >= 5) "Strong" else "Getting better")))
            rows.add(listRow(null, 0, 0, "Telling what happened", null, false, right = badge("From week 3")))
        }
        add(listCard(rows), 6)
        add(listCard(listOf(listRow(null, 0, 0, "Course plan · 8 weeks", null, true) { pages("course") })), 12)
    }

    // ---------------------------------------------------------------- Me

    private fun me() {
        val cur = progress.currentDay()
        val week = cur?.first ?: 1
        val dayNow = cur?.second?.optInt("day") ?: 0
        add(t("Me", 22f, Ui.ink, true), 0)
        val nc = Ui.card(this)
        nc.addView(t(hisName() ?: "Your name", 18f, if (hisName() == null) Ui.muted else Ui.ink, true))
        nc.addView(t("Week $week · started ${progress.startedText()}", 13.5f, Ui.muted))
        add(nc, 12)
        val due = progress.sayAgainItems().size
        val test = progress.getString("testScore:w$week")
        add(listCard(listOf(
            listRow("◉", Ui.ink, Ui.surface2, "My profile", "My life · My English", true) { pages("profile") },
            listRow("≡", Ui.ink, Ui.surface2, "My words and phrases", if (due == 0) "Nothing to practise right now" else "$due to practise today", true) { pages("words") },
            if (dayNow >= 7 || test != null) listRow("◎", Ui.ink, Ui.surface2, "Weekly test", test?.let { "Week $week: done" } ?: "Open today", false)
            else listRow("◎", Ui.muted, Ui.surface2, "Weekly test", "Opens on Day 7", false).apply { alpha = 0.6f }
        )), 12)

        // Report to mentor: the preview is the page itself
        add(Ui.caps(this, "Report to mentor (preview)"), 20)
        val rep = reportParts()
        add(listCard(rep.rows.map { (a, b) -> listRow(null, 0, 0, a, b, false) }), 6)
        add(Ui.button(this, "Send report on WhatsApp") { sendReport(rep) }, 12)
        add(Ui.tip(this, "Problem or idea? Shake your phone on any screen."), 12)

        // Hidden test settings: tap the version 7 times (mentor only)
        add(t("Version ${BuildConfig.VERSION_NAME}", 12f, Ui.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                versionTaps++
                if (versionTaps >= 7) { versionTaps = 0; startActivity(Intent(this@HomeActivity, MainActivity::class.java).putExtra("settingsOnly", true)) }
            }
        }, 18)
        add(t("Sounds: Maya's laugh by lmbubec (CC0); Sam's laugh \"Short male snorts and giggles\" by pfranzen, CC BY 3.0, via Wikimedia Commons.", 11f, Ui.muted).apply { gravity = Gravity.CENTER }, 2)
    }

    private fun showWords(words: List<JSONObject>) {
        val text = if (words.isEmpty()) "No words yet. When you use the Sinhala button, the English goes here."
        else words.takeLast(40).reversed().joinToString("\n") { "${it.optString("si")} → ${it.optString("en")}" }
        AlertDialog.Builder(this).setTitle("My Words").setMessage(text).setPositiveButton("OK", null).show()
    }

    private class Report(val rows: List<Pair<String, String>>, val text: String, val files: List<File>)

    private fun reportParts(): Report {
        val cur = progress.currentDay()
        val week = cur?.first ?: 1
        val days = Lessons.allDays().filter { it.first == week }
        val doneDays = days.count { progress.dayDone(it.first, it.second) }
        val sinceStart = (progress.today() - progress.firstDay() + 1).toInt().coerceAtLeast(1)
        val planned = minOf(days.size, sinceStart)
        val coach = progress.list("coachLog")
        val freezes = progress.list("results").sumOf { it.optInt("stuck") }
        val lastComm = progress.list("results").lastOrNull { it.optString("communication").isNotBlank() }?.optString("communication")
        val recs = VoiceRecorder.all(progress).takeLast(2)
        val mistakes = Memory.topMistakes(progress, 2)
        val working = Memory.english(progress).optJSONArray("working")?.optString(0)
        val changed = Memory.changedLines(progress)
        val rows = listOf(
            (if (doneDays >= planned) "On track" else "Behind by ${planned - doneDays} day${if (planned - doneDays == 1) "" else "s"}") to "Plan vs actual: $doneDays of $planned days done",
            ("Main gap: " + (working ?: mistakes.firstOrNull()?.substringBefore("→")?.trim()?.take(40) ?: "not enough data yet")) to
                (mistakes.firstOrNull()?.let { "\"${it.take(80)}\" keeps coming back" } ?: "Sinhala button: ${coach.map { it.optString("si") }.distinct().size} new words, $freezes times stuck"),
            "Next focus" to (changed.lastOrNull()?.take(100) ?: lastComm?.take(90) ?: "Asking back in conversations"),
            "${recs.size} recording${if (recs.size == 1) "" else "s"} for you" to "His latest talks (attached)"
        )
        val scores = progress.list("results").filter { it.optInt("score", -1) >= 0 }.takeLast(3)
        val sb = StringBuilder("Spoken English · progress report\n")
        sb.append("Now on: week $week day ${cur?.second?.optInt("day")} (${cur?.second?.optString("title")})\n")
        rows.forEach { (a, b) -> sb.append("• $a: $b\n") }
        sb.append("Practised: ${progress.totalMinutes()} min · ${progress.streak()} days in a row\n")
        sb.append("Conversations: ${progress.count("conversations")} · turns: ${progress.count("turns")} · drill answers: ${progress.count("answers")} (good ${progress.count("good")})\n")
        if (scores.isNotEmpty()) sb.append("Conversation scores (for you only): " + scores.joinToString(", ") { "${it.optInt("score")}" } + "\n")
        progress.getString("testScore:w1")?.let { sb.append("Weekly test: $it / 100\n") }
        if (changed.isNotEmpty()) { sb.append("\nWhat the app changed and why:\n"); changed.forEach { sb.append("• $it\n") } }
        val sums = Memory.summaries(progress).takeLast(3)
        if (sums.isNotEmpty()) { sb.append("\nLatest conversations:\n"); sums.forEach { sb.append("— ${it.optString("title")} (with ${it.optString("who")}):\n${it.optString("summary")}\n") } }
        progress.getString("lastCrash")?.let { sb.append("\nLast time the app closed by itself (for Claude):\n${it.take(1500)}\n") }
        val prof = Memory.life(progress).takeLast(12)
        if (prof.isNotEmpty()) { sb.append("\nWhat Sam and Maya know about him:\n"); prof.forEach { sb.append("• ${it.optString("fact")}\n") } }
        return Report(rows, sb.toString(), recs.map { File(it.optString("file")) }.filter { it.exists() })
    }

    /** Sending is always his choice (he taps the button); WhatsApp opens with the text and his 2 latest recordings. */
    private fun sendReport(r: Report) {
        val uris = ArrayList(r.files.map { RecordingsProvider.uri(it) })
        val i = if (uris.isEmpty()) Intent(Intent.ACTION_SEND).setType("text/plain")
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/aac").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.putExtra(Intent.EXTRA_TEXT, r.text).setPackage("com.whatsapp")
        try { startActivity(i) } catch (e: Exception) { i.setPackage(null); runCatching { startActivity(Intent.createChooser(i, "Send report")) } }
    }
}

/** Keeps the reason when the app closes by itself (stack trace in the "progress" prefs, key lastCrash). */
class CrashKeeper(private val ctx: android.content.Context, private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching {
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            ctx.getSharedPreferences("progress", android.content.Context.MODE_PRIVATE).edit()
                .putString("lastCrash", "${java.util.Date()} · ${BuildConfig.VERSION_NAME}\n" + sw.toString().take(4000)).commit()
            Trace.e("crash", sw.toString().take(4000))
            // write the report file now; it is uploaded the next time the app opens
            val dir = java.io.File(ctx.filesDir, "reports").apply { mkdirs() }
            java.io.File(dir, "r-${System.currentTimeMillis()}.txt").writeText("Report · CRASH · ${Trace.where} · ${java.util.Date()}\n" + Trace.report("crash"))
        }
        prev?.uncaughtException(t, e)
    }
}
