package lk.spokenenglish.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * Small pages reached from Home, Progress and Me (QA 12 Nov, extras A, H, I, J):
 * Today's plan · My profile · Course plan (weeks only) · My words and phrases.
 */
class PagesActivity : Activity() {

    private lateinit var body: LinearLayout
    private lateinit var headerBox: LinearLayout
    private lateinit var progress: Progress
    private var mode = "plan"
    private val voice by lazy { CharacterVoice(this) { } }
    private var voiceUsed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = Progress(this)
        mode = intent.getStringExtra("mode") ?: "plan"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.cream) }
        headerBox = LinearLayout(this)
        root.addView(headerBox)
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Ui.fitSystemBars(root)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }
    override fun onDestroy() { if (voiceUsed) voice.shutdown(); super.onDestroy() }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun t(s: CharSequence, size: Float = 15f, c: Int = Ui.ink, b: Boolean = false) = Ui.label(this, s, size, c, b)
    private fun add(v: View, top: Int = 12) = body.addView(v, Ui.lp(this, top))

    private fun header(title: String, sub: String) {
        headerBox.removeAllViews()
        headerBox.addView(Ui.header(this, title, sub, null, { finish() }).first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun list(rows: List<View>): LinearLayout {
        val card = Ui.card(this).apply { setPadding(0, dp(2), 0, dp(2)) }
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(Ui.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            card.addView(r)
        }
        return card
    }

    private fun row(mark: String?, markColor: Int, title: String, sub: String?, right: View? = null, highlight: Boolean = false, onClick: (() -> Unit)? = null): LinearLayout {
        val r = Ui.row(this).apply { setPadding(dp(14), dp(12), dp(14), dp(12)); if (highlight) setBackgroundColor(Ui.tealRow) }
        if (mark != null) r.addView(t(mark, 15f, markColor, true).apply { gravity = Gravity.CENTER; background = Ui.rounded(this@PagesActivity, if (highlight) Ui.teal else Ui.surface2, 14) },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t(title, 15f, Ui.ink, true))
        if (!sub.isNullOrBlank()) col.addView(t(sub, 13.5f, Ui.muted))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) r.addView(right)
        if (onClick != null) { r.addView(t("›", 22f, Ui.muted)); r.background = Ui.ripple(this, null, 0); r.setOnClickListener { Ui.tap(it); onClick() } }
        return r
    }

    private fun render() {
        body.removeAllViews()
        when (mode) { "plan" -> plan(); "course" -> course(); "profile" -> profile(); else -> words() }
    }

    // ---- A. Today's plan: overview only, no numbers or minutes; pick any part ----
    private fun plan() {
        val (week, d) = progress.currentDay() ?: return
        val dayN = d.optInt("day")
        header("Today's plan", d.optString("title"))
        val parts = progress.sessionsFor(d)
        val next = parts.firstOrNull { !progress.isDone(week, dayN, it.optString("id")) }
        add(list(parts.map { p ->
            val id = p.optString("id")
            val done = progress.isDone(week, dayN, id)
            val isNext = p == next
            row(if (done) "✓" else if (isNext) "●" else "○", if (done) Ui.green else if (isNext) android.graphics.Color.WHITE else Ui.muted,
                p.optString("title"), if (done) "Done · tap to do again" else if (isNext) "Next" else null, highlight = isNext) {
                SessionActivity.start(this, week, dayN, id)
            }
        }), 8)
        add(Ui.tip(this, "You can pick any part. Doing them in order works best."), 12)
    }

    // ---- I. Course plan: weeks only, days stay hidden ----
    private fun course() {
        header("Course plan", "8 weeks")
        val cur = progress.currentDay()?.first ?: 1
        val weeks = listOf("Me and my day", "People and places", "What happened", "Plans and wishes", "Opinions", "Problems and help", "Stories", "Real talks")
        add(list(weeks.mapIndexed { i, w ->
            val n = i + 1
            row(if (n < cur) "✓" else if (n == cur) "●" else "○", if (n < cur) Ui.green else if (n == cur) android.graphics.Color.WHITE else Ui.muted,
                "Week $n · $w", if (n == cur) "Now" else null, highlight = n == cur)
        }), 8)
        add(t("Later weeks are written from your results, so they fit you.", 13.5f, Ui.muted), 10)
    }

    // ---- H. My profile: my life · my English; tap a line to remove it ----
    private fun profile() {
        header("My profile", "Sam and Maya use this")
        val life = Memory.life(progress)
        add(Ui.caps(this, "My life"), 6)
        if (life.isEmpty()) add(Ui.tip(this, "Empty for now. Tell Sam and Maya about yourself: they remember it here."), 6)
        else add(list(life.groupBy { it.optString("topic").ifBlank { "about me" } }.flatMap { (topic, items) ->
            items.takeLast(6).map { f ->
                row(null, 0, topic.replaceFirstChar { it.uppercase() } + " · " + f.optString("fact"), null) { confirmRemove(f) }
            }
        }), 6)
        add(Ui.caps(this, "My English"), 18)
        val e = Memory.english(progress)
        val rows = mutableListOf<View>()
        e.optJSONArray("strong")?.let { a -> if (a.length() > 0) rows.add(row(null, 0, "Strong · " + (0 until a.length()).joinToString(", ") { a.getString(it) }, null)) }
        e.optJSONArray("working")?.let { a -> if (a.length() > 0) rows.add(row(null, 0, "Working on · " + (0 until a.length()).joinToString(", ") { a.getString(it) }, null)) }
        Memory.topMistakes(progress, 3).takeIf { it.isNotEmpty() }?.let { rows.add(row(null, 0, "Comes back · " + it.joinToString("; "), null)) }
        Memory.sinhalaWords(progress).takeLast(6).takeIf { it.isNotEmpty() }?.let { rows.add(row(null, 0, "Needed Sinhala for · " + it.joinToString(", ") { p -> p.first }, null)) }
        if (rows.isEmpty()) add(Ui.tip(this, "This fills in after your first day."), 6) else add(list(rows), 6)
        add(Ui.tip(this, "Something wrong? Tap a line in My life to remove it."), 14)
    }

    private fun confirmRemove(f: JSONObject) {
        AlertDialog.Builder(this).setMessage("Remove this?\n\n${f.optString("fact")}")
            .setPositiveButton("Remove") { _, _ -> Memory.removeLife(progress, f.optString("fact")); render() }
            .setNegativeButton("Keep", null).show()
    }

    // ---- J. My words and phrases ----
    private fun words() {
        header("My words and phrases", "From your talks")
        voiceUsed = true; voice.hashCode()
        val due = progress.sayAgainItems()
        val (week, d) = progress.currentDay() ?: (1 to JSONObject())
        val c = Ui.edgeCard(this)
        val r = Ui.row(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t(if (due.isEmpty()) "Nothing to practise right now" else "${due.size} to practise today", 16f, Ui.ink, true))
        col.addView(t("From your talks this week", 13.5f, Ui.muted))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (due.isNotEmpty()) r.addView(Ui.smallButton(this, "Practise") { SessionActivity.start(this, week, d.optInt("day"), "sayagain") })
        c.addView(r)
        add(c, 6)
        val learnt = progress.list("sayThemAgain").map { it.optString("sentence") }.filter { it.isNotBlank() }.distinct()
            .filter { s -> due.none { it.optString("sentence") == s } }.takeLast(20)
        val rows = due.take(20).map { it.optString("sentence") to true } + learnt.map { it to false }
        val words = Memory.sinhalaWords(progress).takeLast(15)
        if (rows.isNotEmpty()) add(list(rows.map { (s, today) ->
            row(null, 0, s, null, right = if (today) Ui.chip(this, "Today", Ui.blue, Ui.blueLight) else Ui.chip(this, "Learnt", Ui.green, Ui.greenLight)) { voiceUsed = true; voice.speak(s, Characters.MAYA) }
        }), 10)
        if (words.isNotEmpty()) {
            add(Ui.caps(this, "Words you needed Sinhala for"), 18)
            add(list(words.map { (en, si) -> row(null, 0, en, si.ifBlank { null }) { voiceUsed = true; voice.speak(en, Characters.SAM) } }), 6)
        }
        add(Ui.tip(this, "Tap a line to hear it."), 12)
    }
}
