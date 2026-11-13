package lk.spokenenglish.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Voice files live OUTSIDE the APK (decided 13 Nov), so app updates stay small on a 4 GB data pack.
 * They come from a release of the public updates repo (never marked "latest", so the app updater ignores it).
 * Each file: downloaded gzipped, resumed if cut off, unpacked, checked against its sha256, and only then
 * put in place (the old file stays until the new one is verified).
 */
object VoicePack {
    const val PACK = "voice-0"
    const val MANIFEST = "https://github.com/28thanujaya-hash/spoken-english-updates/releases/download/$PACK/voice.json"
    const val ENGINE = "libonnxruntime.so"
    const val MODEL = "paradee_int8.onnx"
    const val LEXICON = "misaki-en-us.lex"
    const val CONFIG = "config.json"

    fun dir(c: Context) = File(c.filesDir, "voice").apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences("voicepack", Context.MODE_PRIVATE)

    /** True when every file of the pack is on the phone and was verified after download. */
    fun ready(c: Context): Boolean {
        val p = prefs(c)
        val names = p.getString("files", null)?.split(',') ?: return false
        return names.all { n -> val f = File(dir(c), n); f.exists() && p.getString("sha:$n", null) != null && p.getLong("len:$n", -1) == f.length() }
    }

    fun summary(c: Context): String {
        val p = prefs(c)
        return if (ready(c)) "Voice files ready (pack ${p.getString("pack", "?")}, ${p.getString("files", "")})" else "Voice files not downloaded yet"
    }

    /** Blocking; run on a background thread. progress(text) is called often. Returns null on success, else the problem. */
    fun download(c: Context, progress: (String) -> Unit): String? = try {
        progress("Checking the list of voice files…")
        val m = JSONObject(get(MANIFEST))
        val files = m.getJSONArray("files")
        val total = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("gzsize") }
        var doneBefore = 0L
        val p = prefs(c)
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.getString("name"); val sha = f.getString("sha256"); val gz = f.getLong("gzsize")
            val dest = File(dir(c), name)
            if (dest.exists() && p.getString("sha:$name", null) == sha && dest.length() == f.getLong("size")) { doneBefore += gz; continue }
            val part = File(dir(c), "$name.gz.part")
            fetch(f.getString("url"), part, gz) { got -> progress("Downloading $name… ${mb(doneBefore + got)} of ${mb(total)}") }
            progress("Checking $name…")
            val tmp = File(dir(c), "$name.tmp")
            val md = MessageDigest.getInstance("SHA-256")
            GZIPInputStream(part.inputStream().buffered(1 shl 16)).use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != sha) { tmp.delete(); part.delete(); return "$name failed its check (sha256 differs). Try again." }
            if (dest.exists()) { dest.setWritable(true); dest.delete() }
            if (!tmp.renameTo(dest)) return "could not put $name in place"
            if (name.endsWith(".so")) dest.setReadOnly()   // Android asks for read-only code files
            part.delete()
            p.edit().putString("sha:$name", sha).putLong("len:$name", dest.length()).apply()
            doneBefore += gz
        }
        val names = (0 until files.length()).joinToString(",") { files.getJSONObject(it).getString("name") }
        p.edit().putString("files", names).putString("pack", m.optString("version", PACK)).apply()
        progress("Voice files ready ✓")
        null
    } catch (e: Exception) {
        "download stopped: ${e.message ?: e.javaClass.simpleName} (tap again to continue where it stopped)"
    }

    private fun mb(b: Long) = "%.1f MB".format(b / 1_048_576.0)

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 30000; con.instanceFollowRedirects = true
        try {
            if (con.responseCode !in 200..299) throw IllegalStateException("server said ${con.responseCode}")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }
    }

    /** Downloads into `part`, continuing from what is already there. */
    private fun fetch(url: String, part: File, size: Long, onBytes: (Long) -> Unit) {
        if (part.exists() && part.length() == size) return
        if (part.exists() && part.length() > size) part.delete()
        val have = if (part.exists()) part.length() else 0L
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 30000; con.instanceFollowRedirects = true
        if (have > 0) con.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = con.responseCode
            if (code !in 200..299) throw IllegalStateException("server said $code")
            val append = code == 206
            var got = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                con.inputStream.use { ins ->
                    val buf = ByteArray(1 shl 16); var last = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got - last > 256 * 1024) { last = got; onBytes(got) }
                    }
                }
            }
            onBytes(got)
            if (got != size) throw IllegalStateException("got ${mb(got)} of ${mb(size)}")
        } finally { con.disconnect() }
    }
}
