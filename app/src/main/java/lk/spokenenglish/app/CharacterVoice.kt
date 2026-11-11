package lk.spokenenglish.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * The characters' voices: Google text-to-speech, American English.
 * Uses Google's natural online ("network") voices at normal pitch (there is always internet);
 * the offline voice is only a safety net if an online voice fails.
 */
class CharacterVoice(ctx: Context, private val onReady: (Boolean) -> Unit) {

    var onSpeaking: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val chosen = mutableMapOf<String, Voice?>()
    private var networkFailed = false
    private var lastText = ""
    private var lastCharacter: Character? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx.applicationContext, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        main.post { onSpeaking?.invoke(true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        main.post { onSpeaking?.invoke(false) }
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
                                lastCharacter?.let { speak(lastText, it) }
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

    fun speak(text: String, character: Character) {
        if (!ready) return
        lastText = text
        lastCharacter = character
        chooseVoice(character)
        tts.setPitch(1.0f)   // natural pitch: Sam and Maya differ by voice, not by pitch
        tts.setSpeechRate(0.92f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + SystemClock.uptimeMillis())
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
}
