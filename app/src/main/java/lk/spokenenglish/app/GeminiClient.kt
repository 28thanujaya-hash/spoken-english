package lk.spokenenglish.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** Asks Gemini (free key) for the partner's reply. Call from a background thread. */
class GeminiClient(private val key: String, private val model: String) {

    class Reply(val mood: String, val text: String, val ms: Long)

    class HttpError(val code: Int, message: String) : Exception(message)

    companion object {
        const val THINK_LEVEL = 0
        const val THINK_BUDGET = 1
        const val THINK_DEFAULT = 2
        const val READ_TIMEOUT_MS = 45000
    }

    /** history: pairs of role ("user" or "model") and text, oldest first. */
    fun reply(system: String, history: List<Pair<String, String>>): Reply {
        val t0 = SystemClock.elapsedRealtime()
        // Newer Gemini models (3.x) use "thinkingLevel"; 2.x use "thinkingBudget". Try the quickest
        // setting first; if the model rejects it (HTTP 400), try the next one.
        var raw: String? = null
        var last: Exception? = null
        for (mode in listOf(THINK_LEVEL, THINK_BUDGET, THINK_DEFAULT)) {
            try {
                raw = call(system, history, mode)
                break
            } catch (e: HttpError) {
                last = e
                if (e.code != 400) throw e
            } catch (e: SocketTimeoutException) {
                throw Exception("Gemini took longer than ${READ_TIMEOUT_MS / 1000} s")
            }
        }
        if (raw == null) throw last ?: Exception("No reply")
        val ms = SystemClock.elapsedRealtime() - t0
        val clean = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return try {
            val j = JSONObject(clean)
            Reply(j.optString("mood", "neutral"), j.optString("reply", clean).trim(), ms)
        } catch (e: Exception) {
            Reply("neutral", clean, ms)
        }
    }

    private fun call(system: String, history: List<Pair<String, String>>, thinking: Int): String {
        val body = JSONObject()
        body.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
        )
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        body.put("contents", contents)
        val config = JSONObject()
            .put("responseMimeType", "application/json")
            .put("temperature", 0.9)
            .put("maxOutputTokens", 1024)
        when (thinking) {
            THINK_LEVEL -> config.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            THINK_BUDGET -> config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        body.put("generationConfig", config)

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent"
        )
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = READ_TIMEOUT_MS
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", key)
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrDefault(text.take(200))
                throw HttpError(code, "HTTP $code: $msg")
            }
            val parts = JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
            }
            return sb.toString()
        } finally {
            con.disconnect()
        }
    }
}
