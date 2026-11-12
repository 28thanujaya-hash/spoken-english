package lk.spokenenglish.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lessons live outside the app (mentor's decision, 11 Nov): in the public repo spoken-english-updates,
 * folder lessons/. The app downloads index.json and the week files each time it opens, keeps the last
 * copy on the phone, and tells him when the lessons have changed. No app update is needed for lesson changes.
 */
object Lessons {
    private const val BASE = "https://raw.githubusercontent.com/28thanujaya-hash/spoken-english-updates/main/lessons/"

    /** Weeks in order; each week has "days" (Day 0 is inside week 1). */
    var weeks: List<JSONObject> = emptyList()
        private set
    var version = 0
        private set

    /** Loads from the internet (falls back to the copy on the phone). Returns true if anything is loaded. */
    fun load(ctx: Context): Boolean {
        val dir = File(ctx.filesDir, "lessons").apply { mkdirs() }
        try {
            val index = JSONObject(get(BASE + "index.json?t=" + System.currentTimeMillis() / 60000))
            val list = index.getJSONArray("weeks")
            val loaded = mutableListOf<JSONObject>()
            for (i in 0 until list.length()) {
                val file = list.getJSONObject(i).getString("file")
                val text = get(BASE + file + "?v=" + index.optInt("version"))
                loaded.add(JSONObject(text))
                File(dir, file).writeText(text)
            }
            File(dir, "index.json").writeText(index.toString())
            weeks = loaded
            version = index.optInt("version")
            return true
        } catch (e: Exception) {
            return loadCached(dir)
        }
    }

    private fun loadCached(dir: File): Boolean = try {
        val index = JSONObject(File(dir, "index.json").readText())
        val list = index.getJSONArray("weeks")
        weeks = (0 until list.length()).map { JSONObject(File(dir, list.getJSONObject(it).getString("file")).readText()) }
        version = index.optInt("version")
        weeks.isNotEmpty()
    } catch (e: Exception) { false }

    private fun get(url: String): String {
        val con = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 20000 }
        try {
            if (con.responseCode != 200) throw Exception("HTTP ${con.responseCode}")
            return con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { con.disconnect() }
    }

    /** All days of all weeks in order: (week, day JSON). */
    fun allDays(): List<Pair<Int, JSONObject>> = weeks.flatMap { w ->
        val days = w.getJSONArray("days")
        (0 until days.length()).map { w.optInt("week") to days.getJSONObject(it) }
    }

    fun day(week: Int, day: Int): JSONObject? = allDays().firstOrNull { it.first == week && it.second.optInt("day") == day }?.second

    fun sessions(d: JSONObject): List<JSONObject> {
        val a = d.optJSONArray("sessions") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
}

/**
 * What he has done: one learner record on the phone (QA 11 Nov: every screen reads the same numbers).
 * Older builds kept the coach / grammar / "Say them again" lists in the "settings" store; they are moved here once.
 */
class Progress(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)

    init { migrate() }

    private fun migrate() {
        if (p.getBoolean("migrated11", false)) return
        val s = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val e = p.edit()
        for (k in listOf("coachLog", "grammarLog", "sayThemAgain")) s.getString(k, null)?.let { e.putString(k, it) }
        if (!p.contains("firstDay")) e.putLong("firstDay", s.getLong("firstDay", today()))
        e.putBoolean("migrated11", true).apply()
    }

    fun key(week: Int, day: Int, session: String) = "w${week}d${day}:$session"
    fun isDone(week: Int, day: Int, session: String) = p.getBoolean("done:" + key(week, day, session), false)
    fun markDone(week: Int, day: Int, session: String) {
        p.edit().putBoolean("done:" + key(week, day, session), true).putLong("lastDone", System.currentTimeMillis())
            .remove("pos:" + key(week, day, session)).apply()
    }

    /** Parts shown for a day: a shorter day keeps Shadowing, 3-2-1 talk and the conversation (fresh plan). */
    fun sessionsFor(d: JSONObject): List<JSONObject> {
        val all = Lessons.sessions(d)
        if (!shortDay()) return all
        val keep = all.filter { it.optBoolean("short", false) }
        return keep.ifEmpty { all.filter { it.optString("id") != "listen" } }
    }

    fun dayDone(week: Int, d: JSONObject) = sessionsFor(d).all { isDone(week, d.optInt("day"), it.optString("id")) }

    /** The day he is on: the first day not fully done. */
    fun currentDay(): Pair<Int, JSONObject>? = Lessons.allDays().firstOrNull { !dayDone(it.first, it.second) }
        ?: Lessons.allDays().lastOrNull()

    /** Where he stopped inside a session (← saves it; Continue brings him back there). */
    fun pos(week: Int, day: Int, session: String) = p.getInt("pos:" + key(week, day, session), 0)
    fun setPos(week: Int, day: Int, session: String, i: Int) = p.edit().putInt("pos:" + key(week, day, session), i).apply()

    fun add(counter: String, n: Int = 1) {
        p.edit().putInt(counter, p.getInt(counter, 0) + n).putInt("$counter:${today()}", p.getInt("$counter:${today()}", 0) + n).apply()
    }
    fun count(counter: String) = p.getInt(counter, 0)
    fun countOn(counter: String, day: Long) = p.getInt("$counter:$day", 0)

    // ---- time practised, streak ----
    fun today(): Long = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L
    fun addPractice(ms: Long) {
        if (ms <= 0 || ms > 3 * 3_600_000L) return
        val k = "ms:${today()}"
        p.edit().putLong(k, p.getLong(k, 0) + ms).putLong("msTotal", p.getLong("msTotal", 0) + ms).apply()
    }
    fun minutesOn(day: Long) = (p.getLong("ms:$day", 0) / 60_000).toInt()
    fun minutesToday() = minutesOn(today())
    fun totalMinutes() = (p.getLong("msTotal", 0) / 60_000).toInt()
    fun streak(): Int {
        var d = today(); var n = 0
        if (p.getLong("ms:$d", 0) <= 0) d -= 1
        while (p.getLong("ms:$d", 0) > 0) { n++; d-- }
        return n
    }
    fun firstDay(): Long = p.getLong("firstDay", today()).also { if (!p.contains("firstDay")) p.edit().putLong("firstDay", it).apply() }
    fun startedText(): String = java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(java.util.Date(firstDay() * 86_400_000L))

    // ---- 45-minute day ----
    fun shortDay() = p.getLong("shortDay", -1L) == today()
    fun setShortDay(on: Boolean) = p.edit().putLong("shortDay", if (on) today() else -1L).apply()

    fun log(list: String, item: JSONObject) {
        val arr = runCatching { JSONArray(p.getString(list, "[]")) }.getOrDefault(JSONArray())
        arr.put(item.put("at", System.currentTimeMillis()))
        while (arr.length() > 300) arr.remove(0)
        p.edit().putString(list, arr.toString()).apply()
    }

    fun list(list: String): List<JSONObject> {
        val arr = runCatching { JSONArray(p.getString(list, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    // ---- what Sam and Maya remember about him (a few facts each) ----
    fun facts(who: String): List<String> = runCatching { JSONArray(p.getString("facts:$who", "[]")) }.getOrDefault(JSONArray())
        .let { a -> (0 until a.length()).map { a.getString(it) } }
    fun addFacts(who: String, new: List<String>) {
        val all = (facts(who) + new.map { it.trim() }.filter { it.length in 3..140 }).distinctBy { it.lowercase() }.takeLast(12)
        p.edit().putString("facts:$who", JSONArray(all).toString()).apply()
        Memory.addFacts(this, new, "chat with $who")
    }

    // ---- "Say them again": one list and one count everywhere ----
    fun sayAgainItems(): List<JSONObject> {
        val done = p.getStringSet("sayDone", emptySet()) ?: emptySet()
        val raw = list("sayThemAgain")
        val counts = raw.groupingBy { it.optString("sentence").lowercase() }.eachCount()
        return raw.filter { it.optString("sentence").isNotBlank() && it.optString("sentence") !in done }
            .distinctBy { it.optString("sentence").lowercase() }
            .map { it.put("times", counts[it.optString("sentence").lowercase()] ?: 1) }
            .sortedByDescending { it.optInt("times") }
    }
    fun sayAgainDone(sentence: String) {
        p.edit().putStringSet("sayDone", (p.getStringSet("sayDone", emptySet()) ?: emptySet()) + sentence).apply()
    }

    /** The fresh plan (lessons v3, mentor 12 Nov) starts again at Day 0: old ticks and positions go, everything else stays. */
    fun freshStartIfNeeded(lessonsVersion: Int) {
        if (lessonsVersion < 3 || p.getBoolean("fresh3", false)) return
        val e = p.edit()
        p.all.keys.filter { it.startsWith("done:") || it.startsWith("pos:") }.forEach { e.remove(it) }
        e.putBoolean("fresh3", true).apply()
    }

    fun putString(k: String, v: String) = p.edit().putString(k, v).apply()
    fun putLong(k: String, v: Long) = p.edit().putLong(k, v).apply()
    fun getLong(k: String): Long = p.getLong(k, 0L)
    fun getString(k: String): String? = p.getString(k, null)
    fun seenLessons() = p.getInt("seenLessons", 0)
    fun setSeenLessons(v: Int) = p.edit().putInt("seenLessons", v).apply()
}
