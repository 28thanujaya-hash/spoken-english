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

    private fun minutesFor(id: String): Int {
        val short = progress.shortDay()
        return when (id) { "learn" -> if (short) 15 else 30; "talk" -> if (short) 20 else 30; "free" -> if (short) 10 else 30; else -> 30 }
    }

    // ---------------------------------------------------------------- Home (the drawing minus the plan)

    private fun home() {
        val cur = progress.currentDay() ?: return
        val (week, d) = cur
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
        add(t("$hello, ${hisName() ?: "there"}", 22f, Ui.ink, true), 0)
        if (Lessons.version > progress.seenLessons()) {
            if (progress.seenLessons() > 0) add(Ui.chip(this, "✓  New lessons are ready", Ui.green, Ui.greenLight, 13.5f), 8)
            progress.setSeenLessons(Lessons.version)
        }
        val sessions = progress.sessionsFor(d)
        val dayN = d.optInt("day")
        val next = sessions.firstOrNull { !progress.isDone(week, dayN, it.optString("id")) }

        // Continue card
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.rounded(this@HomeActivity, Ui.tealDark, 18); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        if (next != null) {
            val n = sessions.indexOf(next) + 1
            hero.addView(Ui.caps(this, "Continue · session $n of ${sessions.size}", Ui.tealText))
            hero.addView(t(continueTitle(d, next), 19f, Ui.ink, true), Ui.lp(this, 6))
            val r = Ui.row(this)
            r.addView(t(stageLine(week, dayN, next), 13.5f, Ui.tealText), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(t("Continue", 15f, Ui.teal, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9))
                background = Ui.ripple(this@HomeActivity, Ui.rounded(this@HomeActivity, Ui.cream, 12), 12)
                setOnClickListener { Ui.tap(it); SessionActivity.start(this@HomeActivity, week, dayN, next.optString("id")) }
            })
            hero.addView(r, Ui.lp(this, 10))
        } else {
            hero.addView(Ui.caps(this, "Today", Ui.tealText))
            hero.addView(t("✓  All done for today", 19f, Ui.ink, true), Ui.lp(this, 6))
            hero.addView(t("See you tomorrow. Free chat or Say them again are here any time.", 13.5f, Ui.tealText), Ui.lp(this, 6))
        }
        add(hero, 14)

        // Today: minutes practised
        val goal = if (progress.shortDay()) 45 else 120
        val mins = progress.minutesToday()
        val today = Ui.card(this)
        val tr = Ui.row(this)
        tr.addView(t("Today: $mins of $goal min practised", 15.5f, Ui.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tr.addView(t("goal", 12.5f, Ui.muted))
        today.addView(tr)
        today.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = goal; progress = minOf(goal, mins)
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.teal)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.line)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(8) })
        betterThanYesterday()?.let { today.addView(t("✓  $it", 13.5f, Ui.green), Ui.lp(this, 8)) }
        val shortBtn = t(if (progress.shortDay()) "45-minute day is on · back to the full day" else "Short on time? Do a 45-minute day", 13.5f, Ui.ink, true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = Ui.ripple(this@HomeActivity, Ui.rounded(this@HomeActivity, Ui.surface, 12, Ui.line), 12)
            setOnClickListener { Ui.tap(it); progress.setShortDay(!progress.shortDay()); render() }
        }
        today.addView(shortBtn, Ui.lp(this, 10, false))
        add(today, 12)

        // Today's sessions
        add(Ui.caps(this, "Today's ${sessions.size} sessions"), 20)
        add(listCard(sessions.mapIndexed { i, s ->
            val id = s.optString("id")
            val done = progress.isDone(week, dayN, id)
            val isNext = s == next
            val effort = when (s.optString("effort")) { "hard" -> "Hard"; "medium" -> "Medium"; "relaxed" -> "Relaxed"; else -> "Easy" }
            listRow(if (done) "✓" else "${i + 1}", if (done) Ui.green else if (isNext) Color.WHITE else Ui.teal,
                if (done) Ui.greenLight else if (isNext) Ui.teal else Ui.tealLight,
                if (id == "free") "Free chat + review recording" else s.optString("title"),
                "$effort · ${minutesFor(id)} min" + if (isNext) " · now" else "", true, isNext) {
                SessionActivity.start(this, week, dayN, id)
            }
        }), 6)

        // Any time
        add(Ui.caps(this, "Any time"), 20)
        val say = progress.sayAgainItems().size
        add(listCard(listOf(
            listRow("⚡", Ui.teal, Ui.tealLight, "Quick reactions", "Oh! · That makes sense · 5 min", true) { SessionActivity.start(this, week, dayN, "reactions") },
            listRow("↻", Ui.orangeDark, Ui.orangeLight, "Say them again", if (say == 0) "Nothing waiting right now" else "$say phrase${if (say == 1) "" else "s"} that keep coming back", true) {
                SessionActivity.start(this, week, dayN, "sayagain") },
            listRow("💬", Ui.blue, Ui.blueLight, "Free chat", "Talk about anything · no score", true) { startActivity(Intent(this, MainActivity::class.java)) }
        )), 6)
    }

    private fun continueTitle(d: JSONObject, s: JSONObject): String {
        if (s.optString("id") == "learn") {
            val steps = s.optJSONArray("steps")
            val model = steps?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("type") == "model" } }
            val line = model?.optJSONArray("lines")?.optString(0)
            val topic = d.optString("title").substringAfter(": ").substringBefore(" and ").replaceFirstChar { it.uppercase() }
            return if (!line.isNullOrBlank() && line.length < 40) "$topic: “$line”" else topic
        }
        return if (s.optString("id") == "free") "Free chat + review recording" else s.optString("title")
    }

    private fun stageLine(week: Int, day: Int, s: JSONObject): String {
        val n = s.optJSONArray("steps")?.length() ?: 1
        val pos = progress.pos(week, day, s.optString("id"))
        if (s.optString("id") == "learn") {
            val stage = when { pos <= 0 -> 1; pos <= 1 -> 2; else -> 3 }
            return "Stage $stage of 3: " + listOf("listen", "understand it", "practise")[stage - 1]
        }
        return if (pos <= 0) "Not started yet" else "Step ${pos + 1} of $n"
    }

    private fun betterThanYesterday(): String? {
        val today = progress.today()
        val tTurns = progress.countOn("turns", today); val yTurns = progress.countOn("turns", today - 1)
        val tAgain = progress.countOn("againRequests", today); val yAgain = progress.countOn("againRequests", today - 1)
        return when {
            yTurns > 0 && tTurns > yTurns -> "Better than yesterday: ${tTurns - yTurns} more turns of talking"
            yAgain > 0 && tTurns > 0 && tAgain < yAgain -> "Better than yesterday: ${yAgain - tAgain} fewer \"Again, please\""
            else -> null
        }
    }

    // ---------------------------------------------------------------- Progress (+ the course plan)

    private fun progressTab() {
        val cur = progress.currentDay()
        val week = cur?.first ?: 1
        add(t("My progress", 22f, Ui.ink, true), 0)
        add(t("Week $week", 13f, Ui.muted), 2)
        val streak = progress.streak()
        val total = progress.totalMinutes()
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.rounded(this@HomeActivity, Ui.tealDark, 16); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        top.addView(t(if (streak >= 1) "Right on track. Keep going!" else "Welcome back! Let's start again today.", 17f, Ui.ink, true))
        top.addView(t("$streak day${if (streak == 1) "" else "s"} in a row · ${total / 60} h ${total % 60} min practised", 13.5f, Ui.tealText), Ui.lp(this, 4))
        add(top, 12)

        val results = progress.list("results")
        val started = results.count { it.optBoolean("startedSelf") }
        val follow = results.sumOf { it.optInt("followups") }
        val firstFollow = results.firstOrNull()?.optInt("followups") ?: 0
        val longest = results.maxOfOrNull { it.optInt("longest") } ?: 0
        val help = results.sumOf { it.optInt("help") } + progress.count("againRequests")
        val coach = progress.list("coachLog").map { it.optString("si") }.distinct().size
        val listenGood = progress.count("listenGood")
        val today = progress.today()
        val againToday = progress.countOn("againRequests", today); val againYest = progress.countOn("againRequests", today - 1)
        add(Ui.caps(this, "This week you…"), 18)
        fun tick(title: String, sub: String, on: Boolean) = listRow("✓", if (on) Ui.green else Ui.muted, if (on) Ui.greenLight else Ui.surface2, title, sub, false)
        add(listCard(listOf(
            tick("Started $started conversation${if (started == 1) "" else "s"} yourself", "This week", started > 0),
            tick("Asked $follow follow-up question${if (follow == 1) "" else "s"}", if (results.size > 1) "Up from $firstFollow in your first chat" else "In your conversations", follow > 0),
            tick("Spoke $longest words without stopping", "Your longest turn", longest > 0),
            tick("Asked for help $help time${if (help == 1) "" else "s"}", "Good: you didn't go silent", help > 0),
            tick("Learned $coach new word${if (coach == 1) "" else "s"} with the Sinhala button", "${progress.count("coachSaid")} now said in English", coach > 0),
            tick("Answered $listenGood listening question${if (listenGood == 1) "" else "s"} well", "Listening", listenGood > 0),
            tick("\"Again, please\": $againToday today", if (againYest > 0) "$againYest yesterday · fewer means you understand more" else "Asking is good; it falls as you understand more", true)
        )), 6)

        // Then vs now: his Day 0 voice next to today's
        val recs = VoiceRecorder.all(progress)
        val first = progress.getString("saveFile:baseline-talk")?.let { File(it) }?.takeIf { it.exists() } ?: recs.firstOrNull()?.let { File(it.optString("file")) }
        val last = recs.lastOrNull()?.let { File(it.optString("file")) }?.takeIf { it.exists() && it != first }
        val play = t("Play", 14f, Ui.teal, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(7))
            background = Ui.rounded(this@HomeActivity, Ui.surface, 10, Ui.teal)
            setOnClickListener {
                Ui.tap(it)
                if (first == null) { Ui.toast(this@HomeActivity, "Your Day 0 recording will play here."); return@setOnClickListener }
                Ui.toast(this@HomeActivity, "Day 0…")
                VoiceRecorder.play(first) { if (last != null) main.post { Ui.toast(this@HomeActivity, "…and now"); VoiceRecorder.play(last) } }
            }
        }
        add(listCard(listOf(listRow("▷", Ui.teal, Ui.tealLight, "Then vs now", "Your Day 0 answer next to today's", false, right = play))), 12)

        // Skills
        add(Ui.caps(this, "Skills"), 18)
        val answers = progress.list("answers")
        val goodRatio = if (answers.isEmpty()) 0f else answers.count { it.optString("mark") == "good" }.toFloat() / answers.size
        fun badge(level: String) = when (level) {
            "Strong" -> Ui.chip(this, level, Ui.green, Ui.greenLight)
            "Getting better" -> Ui.chip(this, level, Ui.blue, Ui.blueLight)
            else -> Ui.chip(this, level, Ui.muted, Ui.surface2)
        }
        val dayNow = cur?.second?.optInt("day") ?: 0
        add(listCard(listOf(
            listRow(null, 0, 0, "Responding", null, false, right = badge(when { answers.size < 3 -> "Starting"; goodRatio >= 0.6f -> "Strong"; else -> "Getting better" })),
            listRow(null, 0, 0, "Starting conversations", null, false, right = badge(when { started >= 3 -> "Strong"; started >= 1 -> "Getting better"; else -> "Starting" })),
            listRow(null, 0, 0, "Keeping it going", null, false, right = badge(when { follow >= 8 -> "Strong"; follow >= 1 -> "Getting better"; else -> "Starting" })),
            listRow(null, 0, 0, "Telling a story", null, false, right = badge(if (dayNow < 5 && week == 1) "From Day 5" else if (longest >= 60) "Strong" else "Getting better"))
        )), 6)

        progress.getString("testScore:w1")?.let { add(listCard(listOf(listRow("◎", Ui.teal, Ui.tealLight, "Weekly test", "Week 1: $it / 100 (pass 70)", false))), 12) }
        val say = progress.sayAgainItems().size
        add(listCard(listOf(listRow("↻", Ui.orangeDark, Ui.orangeLight, "Say them again", "$say phrase${if (say == 1) "" else "s"} that keep coming back", true) {
            SessionActivity.start(this, cur?.first ?: 1, dayNow, "sayagain") })), 12)

        // The course plan (moved here from Home, mentor 11 Nov)
        add(Ui.caps(this, "Week $week"), 22)
        add(t("Later days are written from your results, so they fit you.", 12.5f, Ui.muted), 4)
        add(listCard(Lessons.allDays().filter { it.first == week }.map { (_, dd) ->
            val n = dd.optInt("day")
            val done = progress.dayDone(week, dd)
            val isCur = cur != null && n == dayNow
            val locked = !done && !isCur
            listRow(if (done) "✓" else if (isCur) "○" else "🔒", if (done) Ui.green else if (isCur) Ui.teal else Ui.muted,
                if (done) Ui.greenLight else if (isCur) Ui.tealLight else Ui.surface2,
                dd.optString("title").substringAfter(": "), if (n == 0) "Day 0" else "Day $n", isCur) {
                if (locked) Ui.toast(this, "This day opens after the one before.") else { tab = 0; render() }
            }
        }), 6)
    }

    // ---------------------------------------------------------------- Me

    private fun me() {
        val cur = progress.currentDay()
        val week = cur?.first ?: 1
        add(t("Me", 22f, Ui.ink, true), 0)
        add(t("Week $week", 13f, Ui.muted), 2)
        add(listCard(listOf(listRow("◯", Ui.teal, Ui.tealLight, hisName() ?: "Your name", "Week $week · started ${progress.startedText()}", false))), 12)

        val fav = progress.getString("save:favourites")
        val words = progress.list("coachLog").distinctBy { it.optString("si") }
        val due = progress.sayAgainItems().size
        val test = progress.getString("testScore:w$week")
        val dayNow = cur?.second?.optInt("day") ?: 0
        add(listCard(listOf(
            listRow("▤", Ui.teal, Ui.tealLight, "My Favourites", fav?.take(90)?.let { "$it · tell Sam or Maya to change" } ?: "Tell Maya about your favourite films on Day 0", false),
            listRow("◫", Ui.teal, Ui.tealLight, "My Words", "${words.size} words and phrases · $due due today", true) { showWords(words) },
            listRow("◎", Ui.teal, Ui.tealLight, "Weekly test", test?.let { "Week $week: $it / 100" } ?: if (dayNow >= 7) "Open today" else "Locked until Day 7", false)
        )), 12)

        // Report to mentor: the preview is the page itself
        add(Ui.caps(this, "Report to mentor"), 20)
        add(t("Your lesson progress for the week. Preview: this is what will be sent.", 12.5f, Ui.muted), 4)
        val rep = reportParts()
        add(listCard(rep.rows.map { (a, b) -> listRow(null, 0, 0, a, b, false) }), 6)
        add(Ui.button(this, "Send report on WhatsApp") { sendReport(rep) }, 12)

        add(Ui.caps(this, "Problem or idea?"), 20)
        add(listCard(listOf(listRow("📳", Ui.orangeDark, Ui.orangeLight, "Shake your phone, on any screen",
            "Report a bug, a mistake in a lesson, or an idea to improve the app. It goes to your mentor on WhatsApp.", false) { shake.open() })), 6)

        // Hidden test settings: tap the version 7 times (mentor only)
        add(t("Version ${BuildConfig.VERSION_NAME}", 12f, Ui.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                versionTaps++
                if (versionTaps >= 7) { versionTaps = 0; startActivity(Intent(this@HomeActivity, MainActivity::class.java).putExtra("settingsOnly", true)) }
            }
        }, 18)
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
        val rows = listOf(
            (if (doneDays >= planned) "On track" else "Behind by ${planned - doneDays} day${if (planned - doneDays == 1) "" else "s"}") to "Plan vs actual: $doneDays of $planned days done",
            ("Main gap: " + (coach.lastOrNull()?.optString("en")?.take(40) ?: "not enough data yet")) to "Sinhala button: ${coach.map { it.optString("si") }.distinct().size} new words, $freezes times stuck",
            "Next focus" to (lastComm?.take(90) ?: "Starting conversations"),
            "${recs.size} recording${if (recs.size == 1) "" else "s"} for you to score" to "His latest review recordings (attached)"
        )
        val scores = progress.list("results").filter { it.optInt("score", -1) >= 0 }.takeLast(3)
        val sb = StringBuilder("Spoken English · progress report\n")
        sb.append("Now on: week $week day ${cur?.second?.optInt("day")} (${cur?.second?.optString("title")})\n")
        rows.forEach { (a, b) -> sb.append("• $a: $b\n") }
        sb.append("Practised: ${progress.totalMinutes()} min · ${progress.streak()} days in a row\n")
        sb.append("Conversations: ${progress.count("conversations")} · turns: ${progress.count("turns")} · drill answers: ${progress.count("answers")} (good ${progress.count("good")})\n")
        if (scores.isNotEmpty()) sb.append("Conversation scores (for you only): " + scores.joinToString(", ") { "${it.optInt("score")}" } + "\n")
        progress.getString("testScore:w1")?.let { sb.append("Weekly test: $it / 100\n") }
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
