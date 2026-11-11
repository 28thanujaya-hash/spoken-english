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

    /** modelVersion = the model Google actually used (aliases like "flash-lite-latest" change over time). */
    class Reply(
        val mood: String, val text: String, val laugh: Boolean, val ms: Long, val modelVersion: String,
        /** When his turn had Sinhala: his Sinhala words, their English, and the whole sentence in English. */
        val coachSi: String = "", val coachEn: String = "", val coachSentence: String = "",
        /** A grammar correction ("Better English" card), or empty. */
        val betterSaid: String = "", val betterWrong: String = "", val betterRight: String = "",
        val betterSentence: String = "", val betterWhy: String = "",
        /** The reply split into sentences, each with a tone (from the [tone] marks), for the expressive voice. */
        val lines: List<Line> = emptyList()
    )

    class HttpError(val code: Int, message: String, val body: String = "") : Exception(message) {
        /** A free-tier daily limit (as opposed to the per-minute one). */
        val perDay: Boolean get() = code == 429 && body.contains("PerDay", ignoreCase = true)
    }

    companion object {
        const val READ_TIMEOUT_MS = 30000

        /** Laughs and sounds the voice would read out like words, plus emojis. */
        private val SOUNDS = Regex(
            "(?i)\\b(?:a?ha(?:ha)+h?|he(?:he)+|hihi+|lol|lmao|rofl)\\b[!.,]*|" +
                "\\*[^*]{1,30}\\*|\\((?:laughs?|giggles?|chuckles?|smiles?)\\)|" +
                "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"
        )

        fun stripSounds(text: String): String =
            text.replace(SOUNDS, " ")
                .replace(Regex("\\s+([!?.,])"), "$1")
                .replace(Regex("^[\\s!.,]+"), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()

        fun hasSounds(text: String): Boolean =
            Regex("(?i)\\b(?:a?ha(?:ha)+h?|he(?:he)+|lol|lmao)\\b").containsMatchIn(text)
    }

    /**
     * history: pairs of role ("user" or "model") and text, oldest first.
     * No thinking setting is sent: each model runs on its own default (mentor's decision, 11 Nov).
     */
    fun reply(system: String, history: List<Pair<String, String>>): Reply {
        val t0 = SystemClock.elapsedRealtime()
        val (text, version) = try {
            call(system, history)
        } catch (e: SocketTimeoutException) {
            throw Exception("Gemini took longer than ${READ_TIMEOUT_MS / 1000} s")
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val clean = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return try {
            val j = JSONObject(clean)
            val (said, lines) = readMarks(j.optString("reply", clean))
            val coach = j.optJSONObject("coach")
            val better = j.optJSONObject("better")
            fun b(k: String) = better?.optString(k, "")?.trim().orEmpty()
            Reply(
                j.optString("mood", "neutral"),
                stripSounds(said),
                j.optBoolean("laugh", false) || hasSounds(said),
                ms, version,
                coach?.optString("si", "")?.trim().orEmpty(),
                coach?.optString("en", "")?.trim().orEmpty(),
                coach?.optString("sentence", "")?.trim().orEmpty(),
                b("said"), b("wrong"), b("right"), b("sentence"), b("why"),
                lines
            )
        } catch (e: Exception) {
            val (said, lines) = readMarks(clean)
            Reply("neutral", stripSounds(said), hasSounds(said), ms, version, lines = lines)
        }
    }

    /**
     * "[surprised] No way! [excited] I loved it." → the text without marks, and one Line per marked part.
     * Text without marks gives no lines (the voice then uses the punctuation).
     */
    private fun readMarks(reply: String): Pair<String, List<Line>> {
        val mark = Regex("\\[([a-zA-Z]+)\\]\\s*")
        val found = mark.findAll(reply).toList()
        val plain = reply.replace(mark, "").replace(Regex("\\s{2,}"), " ").trim()
        if (found.isEmpty()) return plain to emptyList()
        val lines = mutableListOf<Line>()
        val lead = reply.substring(0, found[0].range.first).trim()
        if (lead.isNotEmpty()) lines.add(Line(stripSounds(lead), "neutral"))
        found.forEachIndexed { i, m ->
            val end = if (i + 1 < found.size) found[i + 1].range.first else reply.length
            val chunk = reply.substring(m.range.last + 1, end).trim()
            // A mark belongs to ONE sentence; any unmarked sentences after it are neutral.
            chunk.split(Regex("(?<=[.!?…])\\s+")).map { stripSounds(it.trim()) }.filter { it.isNotEmpty() }
                .forEachIndexed { k, sentence -> lines.add(Line(sentence, if (k == 0) m.groupValues[1].lowercase() else "neutral")) }
        }
        return plain to lines
    }

    /** Returns (reply text, modelVersion). */
    private fun call(system: String, history: List<Pair<String, String>>): Pair<String, String> {
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
                throw HttpError(code, "HTTP $code: ${msg.take(160)}", text)
            }
            val root = JSONObject(text)
            val parts = root
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
            }
            return sb.toString() to root.optString("modelVersion", model)
        } finally {
            con.disconnect()
        }
    }
}
