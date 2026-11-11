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

/** What he has done: kept on the phone. */
class Progress(ctx: Context) {
    private val p = ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun key(week: Int, day: Int, session: String) = "w${week}d${day}:$session"
    fun isDone(week: Int, day: Int, session: String) = p.getBoolean("done:" + key(week, day, session), false)
    fun markDone(week: Int, day: Int, session: String) {
        p.edit().putBoolean("done:" + key(week, day, session), true).putLong("lastDone", System.currentTimeMillis()).apply()
    }

    fun dayDone(week: Int, d: JSONObject) = Lessons.sessions(d).all { isDone(week, d.optInt("day"), it.optString("id")) }

    /** The day he is on: the first day not fully done. */
    fun currentDay(): Pair<Int, JSONObject>? = Lessons.allDays().firstOrNull { !dayDone(it.first, it.second) }
        ?: Lessons.allDays().lastOrNull()

    fun add(counter: String, n: Int = 1) = p.edit().putInt(counter, p.getInt(counter, 0) + n).apply()
    fun count(counter: String) = p.getInt(counter, 0)

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

    fun putString(k: String, v: String) = p.edit().putString(k, v).apply()
    fun getString(k: String): String? = p.getString(k, null)
    fun seenLessons() = p.getInt("seenLessons", 0)
    fun setSeenLessons(v: Int) = p.edit().putInt("seenLessons", v).apply()
}
