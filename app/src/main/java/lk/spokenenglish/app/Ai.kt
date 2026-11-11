package lk.spokenenglish.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

/**
 * One-shot Gemini calls that return JSON (judging drills, listening answers, conversation results,
 * the weekly test). Same models and quiet fallback as the conversation: Flash Lite, then the backup;
 * a model that hit its free limit rests for a minute or until midnight Pacific. No thinking setting.
 * Call from a background thread.
 */
object Ai {
    const val DEFAULT_MODEL = "gemini-flash-lite-latest"
    const val BACKUP_MODEL = "gemini-flash-latest"

    fun key(ctx: Context): String {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return p.getString("key", "")?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY
    }

    /** Same as ask, with his recorded voice attached (the review recording). */
    fun askAudio(ctx: Context, system: String, user: String, audio: java.io.File): JSONObject =
        ask(ctx, system, user, VoiceRecorder.base64(audio))

    fun ask(ctx: Context, system: String, user: String, audioB64: String? = null): JSONObject {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val chosen = prefs.getString("model", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        var last: Exception = Exception("No AI model available right now")
        for (model in listOf(chosen, BACKUP_MODEL).distinct()) {
            if (System.currentTimeMillis() < prefs.getLong("limited_$model", 0L)) continue
            try {
                return parse(call(key(ctx), model, system, user, audioB64))
            } catch (e: GeminiClient.HttpError) {
                last = e
                if (e.code == 429) {
                    val until = if (e.perDay) nextPacificMidnight() else System.currentTimeMillis() + 60_000
                    prefs.edit().putLong("limited_$model", until).apply()
                    continue
                }
                throw e
            }
        }
        throw last
    }

    private fun parse(text: String): JSONObject {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(clean) } catch (e: Exception) {
            val a = clean.indexOf('{'); val b = clean.lastIndexOf('}')
            if (a >= 0 && b > a) JSONObject(clean.substring(a, b + 1)) else JSONObject().put("text", clean)
        }
    }

    private fun call(key: String, model: String, system: String, user: String, audioB64: String? = null): String {
        val parts = JSONArray().put(JSONObject().put("text", user))
        if (audioB64 != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/aac").put("data", audioB64)))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json")
                .put("temperature", 0.4).put("maxOutputTokens", 1500))
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent")
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = if (audioB64 != null) 90000 else 40000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", key)
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val text = (if (code in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw GeminiClient.HttpError(code, "HTTP $code", text)
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
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

    private fun nextPacificMidnight(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 1)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
