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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** The app's start: bottom menu Home · Progress · Me. Lessons are downloaded here (Lessons.kt). */
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
    private var pickedDay: Int? = null

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
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(this@HomeActivity, 16), Ui.dp(this@HomeActivity, 16), Ui.dp(this@HomeActivity, 16), Ui.dp(this@HomeActivity, 24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = Ui.dp(this@HomeActivity, 6).toFloat() }
        tabs = listOf("Home", "Progress", "Me").mapIndexed { i, name ->
            Ui.label(this, name, 15f, Ui.muted, true).apply {
                gravity = Gravity.CENTER; minHeight = Ui.dp(this@HomeActivity, 56)
                setOnClickListener { Ui.tap(it); tab = i; pickedDay = null; render() }
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

    private fun add(v: View, top: Int = 12) = body.addView(v, Ui.lp(this, top))
    private fun t(s: CharSequence, size: Float = 15f, c: Int = Ui.ink, b: Boolean = false) = Ui.label(this, s, size, c, b)

    // ---------------------------------------------------------------- Home

    private fun home() {
        val cur = progress.currentDay() ?: return
        val (week, current) = cur
        val shownNo = pickedDay ?: current.optInt("day")
        val d = Lessons.day(week, shownNo) ?: current
        add(t("Spoken English", 24f, Ui.ink, true), 0)
        add(t("Week $week · ${if (d.optInt("day") == 0) "Day 0" else "Day ${d.optInt("day")}"} · ${BuildConfig.VERSION_NAME}", 13.5f, Ui.muted), 2)
        if (Lessons.version > progress.seenLessons()) {
            if (progress.seenLessons() > 0) add(t("✓  New lessons are ready", 14.5f, Ui.green, true).apply { background = Ui.rounded(this@HomeActivity, Ui.greenLight, 10); setPadding(Ui.dp(this@HomeActivity, 12), Ui.dp(this@HomeActivity, 8), Ui.dp(this@HomeActivity, 12), Ui.dp(this@HomeActivity, 8)) })
            progress.setSeenLessons(Lessons.version)
        }
        val sessions = Lessons.sessions(d)
        val next = sessions.firstOrNull { !progress.isDone(week, d.optInt("day"), it.optString("id")) }
        val hero = Ui.card(this, Ui.teal, 2)
        hero.addView(t(d.optString("title"), 18f, Ui.ink, true))
        val conn = d.optJSONArray("connectors")
        if (conn != null && conn.length() > 0) hero.addView(t("New today: " + (0 until conn.length()).joinToString(" · ") { conn.getString(it) }, 14f, Ui.muted), Ui.lp(this, 4))
        if (next != null) hero.addView(Ui.button(this, "Continue: ${next.optString("title")}") { SessionActivity.start(this, week, d.optInt("day"), next.optString("id")) }, Ui.lp(this, 12))
        else hero.addView(t("✓  All done for this day!", 16f, Ui.green, true), Ui.lp(this, 10))
        add(hero, 14)

        add(t("Today's sessions", 16f, Ui.ink, true), 20)
        sessions.forEachIndexed { i, s ->
            val done = progress.isDone(week, d.optInt("day"), s.optString("id"))
            val row = Ui.card(this, if (done) Ui.green else Ui.line)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(t(if (done) "✓" else "${i + 1}", 18f, if (done) Ui.green else Ui.teal, true), LinearLayout.LayoutParams(Ui.dp(this, 34), ViewGroup.LayoutParams.WRAP_CONTENT))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(t(s.optString("title"), 16f, Ui.ink, true))
            col.addView(t(when (s.optString("effort")) { "hard" -> "Hard"; "medium" -> "Medium"; "relaxed" -> "Relaxed, no score"; else -> "Easy" }, 13f, Ui.muted))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(t("›", 22f, Ui.muted))
            row.setOnClickListener { Ui.tap(it); SessionActivity.start(this, week, d.optInt("day"), s.optString("id")) }
            add(row, 8)
        }

        add(t("Any time", 16f, Ui.ink, true), 22)
        val any = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        any.addView(Ui.button(this, "Say them again", Color.WHITE, Ui.teal) { SessionActivity.start(this, week, d.optInt("day"), "sayagain") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@HomeActivity, 8) })
        any.addView(Ui.button(this, "Free talk", Color.WHITE, Ui.teal) { startActivity(Intent(this, MainActivity::class.java)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(any, 8)

        add(t("Week $week", 16f, Ui.ink, true), 22)
        add(t("Later weeks are written from your results.", 13.5f, Ui.muted), 2)
        Lessons.allDays().filter { it.first == week }.forEach { (_, dd) ->
            val n = dd.optInt("day")
            val done = progress.dayDone(week, dd)
            val isCur = n == current.optInt("day")
            val row = t((if (done) "✓  " else if (isCur) "▶  " else "     ") + (if (n == 0) "Day 0" else "Day $n") + " · " + dd.optString("title"),
                14.5f, if (done) Ui.green else if (isCur) Ui.teal else Ui.ink, isCur || n == shownNo)
            row.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9))
            row.setOnClickListener { Ui.tap(it); pickedDay = n; render() }
            add(row, 2)
        }
    }

    // ---------------------------------------------------------------- Progress

    private fun progressTab() {
        add(t("Progress", 24f, Ui.ink, true), 0)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val coach = runCatching { JSONArray(sp.getString("coachLog", "[]")).length() }.getOrDefault(0)
        val grammar = runCatching { JSONArray(sp.getString("grammarLog", "[]")).length() }.getOrDefault(0)
        val stats = Ui.card(this)
        stats.addView(t("Conversations: ${progress.count("conversations")}\nYour turns in conversations: ${progress.count("turns")}\n" +
            "Answers in drills: ${progress.count("answers")} (good: ${progress.count("good")})\n" +
            "Sinhala help used: ${progress.count("sinhalaUses") + coach}\nBetter English cards: $grammar", 15.5f))
        add(stats, 12)

        val ach = mutableListOf<String>()
        if (progress.count("conversations") >= 1) ach.add("Had your first conversation")
        if (progress.count("conversations") >= 5) ach.add("5 conversations")
        if (progress.count("turns") >= 50) ach.add("50 turns of talking")
        if (progress.count("good") >= 10) ach.add("10 good answers")
        if (ach.isNotEmpty()) add(Ui.enSiCard(this, "Achievements", ach.joinToString("\n") { "★ $it" }, "", Ui.green), 12)

        progress.getString("testScore:w1")?.let { add(Ui.enSiCard(this, "Weekly test", "Week 1: $it / 100 (pass 70)", ""), 12) }

        val then = progress.getString("save:baseline-talk")
        val now = progress.getString("rec:Tell me about yourself: who you are, what you do, what you like.")
        if (then != null) add(Ui.enSiCard(this, "Then vs now", "Day 0: \"$then\"" + (now?.let { "\n\nNow: \"$it\"" } ?: "\n\n(Your Day 7 answer will appear here.)"), ""), 12)

        val results = progress.list("results").filter { it.optInt("score", -1) >= 0 }.takeLast(5)
        if (results.isNotEmpty()) add(Ui.enSiCard(this, "Recent conversation scores", results.joinToString("\n") { "${it.optString("where")}: ${it.optInt("score")} / 100 (${it.optInt("turns")} turns)" }, ""), 12)
        add(Ui.button(this, "Say them again") { val cur = progress.currentDay(); SessionActivity.start(this, cur?.first ?: 1, cur?.second?.optInt("day") ?: 0, "sayagain") }, 16)
    }

    // ---------------------------------------------------------------- Me

    private fun me() {
        add(t("Me", 24f, Ui.ink, true), 0)
        add(t("Name: ${Ui.name(this).replaceFirstChar { it.uppercase() }}", 15f, Ui.muted), 4)
        progress.getString("save:favourites")?.let { add(Ui.enSiCard(this, "My Favourites", it, ""), 12) }
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val words = runCatching { JSONArray(sp.getString("coachLog", "[]")) }.getOrDefault(JSONArray())
        if (words.length() > 0) {
            val last = (maxOf(0, words.length() - 10) until words.length()).map { words.getJSONObject(it) }
            add(Ui.enSiCard(this, "My Words (Sinhala → English)", last.joinToString("\n") { "${it.optString("si")} → ${it.optString("en")}" }, "", Ui.orange), 12)
        }
        add(Ui.button(this, "Report to mentor (WhatsApp)") { report() }, 16)
        add(Ui.button(this, "Problem or idea?", Color.WHITE, Ui.teal) { shake.open() }, 10)
        add(t("Tip: you can also shake the phone on any screen.", 13f, Ui.muted), 4)
        add(Ui.button(this, "Test settings ⚙ (in Free talk)", Color.WHITE, Ui.muted) { startActivity(Intent(this, MainActivity::class.java)) }, 16)
    }

    /** The lesson progress report for the mentor, previewed first, then sent on WhatsApp. */
    private fun report() {
        val cur = progress.currentDay()
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val coach = runCatching { JSONArray(sp.getString("coachLog", "[]")) }.getOrDefault(JSONArray())
        val gram = runCatching { JSONArray(sp.getString("grammarLog", "[]")) }.getOrDefault(JSONArray())
        val doneDays = Lessons.allDays().count { progress.dayDone(it.first, it.second) }
        val sb = StringBuilder()
        sb.append("Spoken English · progress report\n")
        sb.append("Now on: week ${cur?.first} day ${cur?.second?.optInt("day")} (${cur?.second?.optString("title")})\n")
        sb.append("Days completed: $doneDays\n")
        sb.append("Conversations: ${progress.count("conversations")} · turns: ${progress.count("turns")}\n")
        sb.append("Drill answers: ${progress.count("answers")} · good: ${progress.count("good")}\n")
        sb.append("Sinhala help: ${progress.count("sinhalaUses") + coach.length()} · grammar cards: ${gram.length()}\n")
        progress.getString("testScore:w1")?.let { sb.append("Weekly test: $it / 100\n") }
        val scores = progress.list("results").filter { it.optInt("score", -1) >= 0 }.takeLast(3)
        if (scores.isNotEmpty()) sb.append("Recent conversation scores: " + scores.joinToString(", ") { "${it.optInt("score")}" } + "\n")
        if (coach.length() > 0) {
            sb.append("Most recent Sinhala he needed:\n")
            for (k in maxOf(0, coach.length() - 5) until coach.length()) { val o = coach.getJSONObject(k); sb.append("• ${o.optString("si")} → ${o.optString("en")}\n") }
        }
        val text = sb.toString()
        AlertDialog.Builder(this).setTitle("Report preview").setMessage(text)
            .setPositiveButton("Send on WhatsApp") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.whatsapp")
                try { startActivity(i) } catch (e: Exception) { i.setPackage(null); runCatching { startActivity(Intent.createChooser(i, "Send report")) } }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
