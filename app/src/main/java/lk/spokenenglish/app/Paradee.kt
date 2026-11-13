package lk.spokenenglish.app

import org.json.JSONObject
import java.io.File

/**
 * The Paradee voice on the phone (run 0: the stock model, before Sam and Maya are trained).
 * Text -> TextPrep (times, our name list) -> misaki-g2p (sounds, one chunk per sentence) -> ids -> Paradee -> 24 kHz audio.
 * The engine (libonnxruntime.so), the model and the dictionary are downloaded by VoicePack, not inside the APK.
 */
object Paradee {
    init { System.loadLibrary("spokenvoice") }

    @JvmStatic external fun lastError(): String
    @JvmStatic external fun g2pOpen(lex: String): Boolean
    @JvmStatic external fun g2p(text: ByteArray): ByteArray?
    @JvmStatic external fun ortLoad(so: String): Boolean
    @JvmStatic external fun ortVersion(so: String): String
    @JvmStatic external fun open(model: String, threads: Int, spin: Boolean): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun synth(handle: Long, ids: LongArray, voice: Long, speed: Float): FloatArray?

    const val RATE = 24000
    private var vocab: Map<Int, Long> = emptyMap()
    var voices: List<String> = emptyList(); private set

    /** Loads the engine, the dictionary and the sound list. Returns null when ready, or the problem. */
    fun start(dir: File): String? {
        if (!ortLoad(File(dir, VoicePack.ENGINE).absolutePath)) return "engine: ${lastError()}"
        if (!g2pOpen(File(dir, VoicePack.LEXICON).absolutePath)) return "dictionary: ${lastError()}"
        if (vocab.isEmpty()) {
            val cfg = JSONObject(File(dir, VoicePack.CONFIG).readText())
            val v = cfg.getJSONObject("vocab")
            vocab = v.keys().asSequence().associate { it.codePointAt(0) to v.getLong(it) }
            val a = cfg.getJSONArray("voices"); voices = (0 until a.length()).map { a.getString(it) }
        }
        return null
    }

    /** Sentence chunks of sounds for one text (our name list and time fixes applied first). */
    fun phonemes(text: String): List<String> {
        val out = g2p(TextPrep.prepare(text).toByteArray(Charsets.UTF_8)) ?: throw IllegalStateException("g2p: ${lastError()}")
        return String(out, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
    }

    /** Sounds -> ids, with the 0 pad at each end (symbols not in Paradee's list are skipped, as in training). */
    fun ids(chunk: String): LongArray {
        val l = ArrayList<Long>(chunk.length + 2); l.add(0L)
        chunk.codePoints().forEach { c -> vocab[c]?.let { l.add(it) } }
        l.add(0L); return l.toLongArray()
    }
}

/**
 * Text fixes before the word -> sound step. Training must use exactly the same function (port it to Python as is).
 * 1. Times: the port without eSpeak drops "7:45" (Python misaki says "seven:forty-five"), so we write "7 45".
 * 2. Our name list: words the big dictionary doesn't have (or gets wrong) get their sounds directly,
 *    in misaki's own override form [word](/sounds/).
 */
object TextPrep {
    /** PROVISIONAL sounds (misaki American symbols: A = ay, I = eye, O = oh). The mentor checks every one before training. */
    val NAMES: Map<String, String> = linkedMapOf(
        "Thenuka" to "tˈɛnʊkə",
        "Kavi" to "kˈʌvi",
        "Nimal" to "nˈɪməl",
        "Priya" to "pɹˈiə",
        "Galle" to "ɡˈɑl",
        "kottu" to "kˈOtu",
        "Peradeniya" to "pˌɛɹədˈɛnijə",
    )
    private val time = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val names by lazy { Regex("""\b(""" + NAMES.keys.joinToString("|") { Regex.escape(it) } + """)\b""") }

    fun prepare(text: String): String {
        var t = time.replace(text) { m ->
            val h = m.groupValues[1]; val mm = m.groupValues[2]
            when {
                mm == "00" -> "$h o'clock"
                mm.startsWith("0") -> "$h oh ${mm.drop(1)}"
                else -> "$h $mm"
            }
        }
        t = names.replace(t) { m -> "[${m.value}](/${NAMES.getValue(m.value)}/)" }
        return t
    }
}
