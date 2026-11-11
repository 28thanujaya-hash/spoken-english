package lk.spokenenglish.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Asks Gemini (free key) for the partner's reply. Call from a background thread. */
class GeminiClient(private val key: String, private val model: String) {

    class Reply(val mood: String, val text: String, val ms: Long)

    class HttpError(val code: Int, message: String) : Exception(message)

    /** history: pairs of role ("user" or "model") and text, oldest first. */
    fun reply(system: String, history: List<Pair<String, String>>): Reply {
        val t0 = SystemClock.elapsedRealtime()
        val raw = try {
            call(system, history, turnOffThinking = true)
        } catch (e: HttpError) {
            // Some models don't accept the "no thinking" setting; try once without it.
            if (e.code == 400) call(system, history, turnOffThinking = false) else throw e
        }
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

    private fun call(system: String, history: List<Pair<String, String>>, turnOffThinking: Boolean): String {
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
        if (turnOffThinking) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        body.put("generationConfig", config)

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent"
        )
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = 30000
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
