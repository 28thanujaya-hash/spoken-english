package lk.spokenenglish.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** One sentence (or short phrase) to say, and how to say it. */
data class Line(val text: String, val tone: String)

/**
 * The characters' voices: Google text-to-speech, American English.
 * Uses Google's natural online ("network") voices; the offline voice is only a safety net.
 *
 * Expressive mode (mentor, 11 Nov): Google's voice can't act by itself, so the reply is spoken one
 * sentence at a time, each with a slightly different pitch and speed for its tone (excited, question,
 * surprised, calm, sad, warm), with short pauses between them. The changes are kept small on purpose:
 * big pitch jumps sound robotic.
 */
class CharacterVoice(ctx: Context, private val onReady: (Boolean) -> Unit) {

    var onSpeaking: ((Boolean) -> Unit)? = null

    /** Called as each sentence starts, with its tone (the face follows it). */
    var onLineStart: ((String) -> Unit)? = null

    /** Set from ⚙ ("Expressive voice"); on by default. */
    var expressive = true

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val chosen = mutableMapOf<String, Voice?>()
    private var networkFailed = false
    private var lastLines: List<Line> = emptyList()
    private var lastCharacter: Character? = null
    @Volatile private var lastId = ""
    @Volatile private var runPrefix = ""
    @Volatile private var runLines: List<Line> = emptyList()
    private var seq = 0
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx.applicationContext, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val tone = utteranceId?.takeIf { it.startsWith(runPrefix) }
                            ?.removePrefix(runPrefix)?.toIntOrNull()?.let { runLines.getOrNull(it)?.tone }
                        main.post {
                            onSpeaking?.invoke(true)
                            if (tone != null) onLineStart?.invoke(tone)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        // Only the last piece of a reply ends the "talking" state.
                        if (utteranceId == lastId) main.post { onSpeaking?.invoke(false) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { onSpeaking?.invoke(false) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post {
                            onSpeaking?.invoke(false)
                            // An online voice failed (slow network): switch to the offline voice and say it again.
                            if (!networkFailed && (errorCode == TextToSpeech.ERROR_NETWORK ||
                                    errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT)) {
                                networkFailed = true
                                chosen.clear()
                                lastCharacter?.let { speakLines(lastLines, it) }
                            }
                        }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        main.post { onSpeaking?.invoke(false) }
                    }
                })
            }
            main.post { onReady(ready) }
        }, "com.google.android.tts")
    }

    /** Says a reply. lines: the AI's sentence-by-sentence tones; if missing, tones come from the punctuation. */
    fun speak(text: String, character: Character, lines: List<Line>? = null) {
        val use = if (!lines.isNullOrEmpty()) lines else splitByPunctuation(text)
        speakLines(use, character)
    }

    private fun speakLines(lines: List<Line>, character: Character) {
        if (!ready || lines.isEmpty()) return
        lastLines = lines
        lastCharacter = character
        chooseVoice(character)
        val run = "u${++seq}-"
        runPrefix = run
        runLines = lines
        val pace = character.pace
        if (!expressive) {
            lastId = run + "all"
            tts.setPitch(1.0f)
            tts.setSpeechRate(BASE_RATE * pace)
            tts.speak(lines.joinToString(" ") { it.text }, TextToSpeech.QUEUE_FLUSH, null, lastId)
            return
        }
        lastId = run + (lines.size - 1)
        lines.forEachIndexed { i, line ->
            val (pitch, rate) = shape(line.tone)
            tts.setPitch(pitch)                 // captured per sentence when it is queued
            tts.setSpeechRate(BASE_RATE * rate * pace)
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, loudness(line.tone))
            }
            tts.speak(line.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, run + i)
            if (i < lines.size - 1) {
                tts.playSilentUtterance(pauseAfter(line, lines[i + 1]), TextToSpeech.QUEUE_ADD, run + "p" + i)
            }
        }
        tts.setPitch(1.0f)
        tts.setSpeechRate(BASE_RATE)
    }

    /** Small, natural changes per tone: (pitch, speed factor). */
    private fun shape(tone: String): Pair<Float, Float> = when (tone.lowercase()) {
        "excited", "happy", "joke" -> 1.10f to 1.07f
        "surprised" -> 1.12f to 1.00f
        "question" -> 1.06f to 1.00f
        "curious" -> 1.05f to 0.97f
        "warm", "kind" -> 1.02f to 0.95f
        "calm", "thinking" -> 0.97f to 0.93f
        "sad", "sorry" -> 0.93f to 0.90f
        "annoyed" -> 0.96f to 1.04f
        else -> 1.00f to 1.00f
    }

    /** Louder for excitement, softer for calm or sad (1.0 = full). */
    private fun loudness(tone: String): Float = when (tone.lowercase()) {
        "excited", "happy", "joke", "surprised", "annoyed" -> 1.0f
        "question" -> 0.95f
        "curious" -> 0.92f
        "warm", "kind" -> 0.88f
        "calm", "thinking" -> 0.84f
        "sad", "sorry" -> 0.80f
        else -> 0.92f
    }

    /** Pauses like a person: short after "Oh!", a thinking pause after "Hmm", a breath before a question. */
    private fun pauseAfter(line: Line, next: Line): Long {
        val t = line.text.trim().lowercase()
        return when {
            t.startsWith("hmm") || t.startsWith("um") || t.endsWith("...") || t.endsWith("…") -> 380L
            isInterjection(line.text) -> 140L
            next.text.trim().endsWith("?") -> 300L
            else -> 220L
        }
    }

    private fun isInterjection(t: String): Boolean =
        t.trim().split(" ").size <= 2 && t.trimEnd().lastOrNull() in listOf('!', ',', '?', '.')

    /** Fallback: one line per sentence, tone from its punctuation. */
    private fun splitByPunctuation(text: String): List<Line> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.map {
            Line(it, when {
                it.endsWith("?!") || it.endsWith("!?") -> "surprised"
                it.endsWith("!") -> "excited"
                it.endsWith("?") -> "question"
                else -> "neutral"
            })
        }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    /** Picks a male voice for Sam and a female one for Maya: Google's online voices first. */
    private fun chooseVoice(character: Character) {
        val voice = chosen.getOrPut(character.name) {
            val all = runCatching { tts.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == "en" && it.locale.country == "US" }
            val online = all.filter { it.isNetworkConnectionRequired || it.name.endsWith("-network") }
            val offline = all.filter { !it.isNetworkConnectionRequired }
            val pool = (if (networkFailed) offline else online).ifEmpty { all }
            val codes = if (character.male) listOf("iom", "iol", "tpd") else listOf("tpf", "sfg", "tpc", "iog")
            codes.firstNotNullOfOrNull { code -> pool.firstOrNull { it.name.contains("-x-$code") } }
                ?: pool.firstOrNull()
        }
        if (voice != null) runCatching { tts.voice = voice }
    }

    /** The name of the voice used last (shown in ⚙). */
    fun voiceName(character: Character): String = chosen[character.name]?.name ?: "not chosen yet"

    companion object {
        const val BASE_RATE = 0.92f
    }
}
