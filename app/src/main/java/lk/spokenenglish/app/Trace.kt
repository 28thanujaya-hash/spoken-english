package lk.spokenenglish.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The full bug report (1.2 findings 6 and 7, modelled on Inkdrift's): everything that happens is written to a
 * trace on the phone (his words, Sam's and Maya's replies, timings, cards, AI calls, mic and voice problems,
 * screens, buttons, crashes). Errors and warnings are also kept grouped (same problem = one line with a count).
 *
 * A report = header + errors/warnings since the last report + the trace since the last report (word for word,
 * with times) + the last AI request and its raw reply + today's plan + profile + error history. Text only.
 * It is sent by itself after every part and every free chat, and when the phone is shaken: as a GitHub Issue in
 * the private repo (token: Issues read/write only). Reports wait on the phone until they are sent.
 */
object Trace {

    private lateinit var app: Context
    private val lock = Any()
    private const val MAX_TRACE = 260_000          // characters kept in the trace file
    @Volatile var where: String = "starting"       // week, day, part, step (set by the screens)
    @Volatile private var uploading = false

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }
    private val ready get() = ::app.isInitialized

    private fun file(n: String) = File(app.filesDir, n)
    private val prefs get() = app.getSharedPreferences("trace", Context.MODE_PRIVATE)
    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(Date())
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(Date())

    // ---------------------------------------------------------------- writing

    fun i(kind: String, msg: String) = add("info", kind, msg)
    fun w(kind: String, msg: String) = add("warning", kind, msg)
    fun e(kind: String, msg: String) = add("error", kind, msg)

    private fun add(level: String, kind: String, msg: String) {
        if (!ready) return
        runCatching {
            synchronized(lock) {
                val f = file("trace.txt")
                val mark = when (level) { "error" -> "!! "; "warning" -> "!  "; else -> "   " }
                f.appendText("${now()} $mark[$kind] ${msg.replace("\n", "\n            ")}\n")
                if (f.length() > MAX_TRACE) { val t = f.readText(); f.writeText("…(older lines cut)\n" + t.takeLast(MAX_TRACE * 3 / 4)) }
                if (level != "info") group(level, kind, msg)
            }
        }
    }

    /** Errors and warnings: the same problem (numbers ignored) is one entry with a count, first and last time. */
    private fun group(level: String, kind: String, msg: String) {
        val a = runCatching { JSONArray(prefs.getString("issues", "[]")) }.getOrDefault(JSONArray())
        val key = "$level|$kind|" + msg.take(160).replace(Regex("\\d+"), "#")
        var found: JSONObject? = null
        for (k in 0 until a.length()) if (a.getJSONObject(k).optString("key") == key) { found = a.getJSONObject(k); break }
        if (found != null) found.put("count", found.optInt("count", 1) + 1).put("last", stamp()).put("msg", msg.take(400)).put("where", where)
        else a.put(JSONObject().put("key", key).put("level", level).put("kind", kind).put("msg", msg.take(400)).put("first", stamp()).put("last", stamp()).put("count", 1).put("where", where))
        while (a.length() > 120) a.remove(0)
        prefs.edit().putString("issues", a.toString()).apply()
    }

    /** The last AI request (full instructions + what was sent) and, after it, the raw reply. */
    fun aiRequest(what: String, model: String, system: String, user: String) {
        if (!ready) return
        runCatching { file("lastreq.txt").writeText("$what · model $model · ${stamp()}\n\nSYSTEM:\n$system\n\nSENT:\n$user\n") }
    }
    fun aiReply(raw: String) {
        if (!ready) return
        runCatching { file("lastreq.txt").appendText("\nRAW REPLY:\n$raw\n") }
    }

    // ---------------------------------------------------------------- the report

    fun report(reason: String): String {
        val sb = StringBuilder()
        val p = Progress(app)
        val cur = p.currentDay()
        val s = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sb.append("SPOKEN ENGLISH REPORT · ${BuildConfig.VERSION_NAME} · $reason\n")
        sb.append("Made: ${stamp()} · since last report: ${prefs.getString("lastReport", "first report")}\n")
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Lessons version: ${Lessons.version} · AI model: ${s.getString("model", "").orEmpty().ifBlank { "default (flash-lite, backup flash)" }}\n")
        sb.append("Where: $where · current day: week ${cur?.first} day ${cur?.second?.optInt("day")} (${cur?.second?.optString("title")})\n")
        sb.append("Settings: shorter day ${p.shortDay()} · grammar level ${s.getInt("grammarLevel", 0).takeIf { it > 0 } ?: "by day"} · camera ${s.getBoolean("cameraOn", true)} · name ${Ui.name(app)}\n")
        sb.append("Totals: practised ${p.totalMinutes()} min · conversations ${p.count("conversations")} · turns ${p.count("turns")} · answers ${p.count("answers")}\n")
        val issues = runCatching { JSONArray(prefs.getString("issues", "[]")) }.getOrDefault(JSONArray())
        for ((level, title) in listOf("error" to "ERRORS (something did not happen)", "warning" to "WARNINGS (worked after a fix, or worth a look)")) {
            val g = (0 until issues.length()).map { issues.getJSONObject(it) }.filter { it.optString("level") == level }
            sb.append("\n----- $title SINCE LAST REPORT (${g.sumOf { it.optInt("count", 1) }}) -----\n")
            if (g.isEmpty()) sb.append("(none)\n")
            g.forEach { sb.append("${it.optString("last")} [${it.optString("kind")}${if (it.optInt("count") > 1) " ×${it.optInt("count")} (first ${it.optString("first")})" else ""}] (${it.optString("where")}) ${it.optString("msg")}\n") }
        }
        p.getString("lastCrash")?.let { sb.append("\n----- LAST CRASH -----\n$it\n") }
        sb.append("\n----- EVERYTHING SINCE LAST REPORT (word for word, with times) -----\n")
        sb.append(runCatching { file("trace.txt").readText() }.getOrDefault("(empty)"))
        sb.append("\n----- LAST AI REQUEST AND RAW REPLY -----\n")
        sb.append(runCatching { file("lastreq.txt").readText() }.getOrDefault("(none yet)"))
        cur?.let { (w, d) -> Memory.planFor(p, w, d.optInt("day"))?.let { sb.append("\n----- TODAY'S PLAN (tomorrow planner) -----\n${it.toString(1)}\n") } }
        sb.append("\n----- PROFILE -----\nLife: ").append(Memory.life(p).takeLast(25).joinToString("; ") { "${it.optString("topic")}: ${it.optString("fact")}" })
        sb.append("\nEnglish: ").append(Memory.english(p).toString()).append('\n')
        sb.append("Changes made by the planner: ").append(Memory.changedLines(p).joinToString(" | ")).append('\n')
        val hist = runCatching { file("history.txt").readText() }.getOrDefault("")
        if (hist.isNotBlank()) sb.append("\n----- ERROR HISTORY (earlier reports) -----\n").append(hist.takeLast(20_000))
        return sb.toString()
    }

    /** Makes a report now and sends it in the background (it waits on the phone if offline). */
    fun send(reason: String) {
        if (!ready) return
        val text = synchronized(lock) {
            val t = runCatching { report(reason) }.getOrElse { "Report failed to build: $it" }
            // start a fresh trace; errors move to the history
            runCatching {
                val issues = runCatching { JSONArray(prefs.getString("issues", "[]")) }.getOrDefault(JSONArray())
                if (issues.length() > 0) file("history.txt").appendText("== report ${stamp()} · ${BuildConfig.VERSION_NAME} ==\n" +
                    (0 until issues.length()).joinToString("\n") { val o = issues.getJSONObject(it); "${o.optString("level")} [${o.optString("kind")} ×${o.optInt("count", 1)}] ${o.optString("msg")}" } + "\n")
                val h = file("history.txt"); if (h.length() > 60_000) h.writeText(h.readText().takeLast(40_000))
                prefs.edit().putString("issues", "[]").putString("lastReport", stamp()).apply()
                file("trace.txt").writeText("")
            }
            t
        }
        val dir = File(app.filesDir, "reports").apply { mkdirs() }
        val title = "Report · ${reason.take(60)} · ${where.take(60)} · ${stamp().take(16)}"
        runCatching { File(dir, "r-${System.currentTimeMillis()}.txt").writeText(title + "\n" + text) }
        uploadPending()
    }

    /** Sends every waiting report as a GitHub Issue (long ones split into comments). */
    fun uploadPending() {
        if (!ready || uploading) return
        val token = BuildConfig.REPORTS_TOKEN
        if (token.isBlank()) return
        uploading = true
        thread {
            try {
                val files = File(app.filesDir, "reports").listFiles()?.sortedBy { it.name } ?: emptyList()
                for (f in files) {
                    val all = f.readText()
                    val title = all.substringBefore('\n'); val body = all.substringAfter('\n')
                    val chunks = body.chunked(60_000)
                    val issue = post("/issues", JSONObject().put("title", title).put("body", "```\n" + chunks.first() + "\n```"), token) ?: break
                    val n = issue.optInt("number")
                    var ok = true
                    for (c in chunks.drop(1)) if (post("/issues/$n/comments", JSONObject().put("body", "```\n$c\n```"), token) == null) { ok = false; break }
                    if (!ok) break
                    f.delete()
                }
            } catch (_: Exception) {
            } finally { uploading = false }
        }
    }

    private fun post(path: String, body: JSONObject, token: String): JSONObject? = runCatching {
        val con = URL("https://api.github.com/repos/${BuildConfig.REPORTS_REPO}$path").openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.connectTimeout = 15000; con.readTimeout = 30000; con.doOutput = true
        con.setRequestProperty("Authorization", "Bearer $token")
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = con.responseCode
        val text = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        con.disconnect()
        if (code in 200..299) JSONObject(text) else { w("report upload", "GitHub said $code: ${text.take(200)}"); null }
    }.getOrNull()
}
