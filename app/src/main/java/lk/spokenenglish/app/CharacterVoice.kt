package lk.spokenenglish.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** The characters' voices: Google text-to-speech on the phone, American English. */
class CharacterVoice(ctx: Context, private val onReady: (Boolean) -> Unit) {

    var onSpeaking: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val chosen = mutableMapOf<String, Voice?>()
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
        chooseVoice(character)
        tts.setPitch(character.pitch)
        tts.setSpeechRate(0.92f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + SystemClock.uptimeMillis())
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    /** Picks a male voice for Sam and a female one for Maya, preferring voices that work offline. */
    private fun chooseVoice(character: Character) {
        val voice = chosen.getOrPut(character.name) {
            val all = runCatching { tts.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == "en" && it.locale.country == "US" }
            val local = all.filter { !it.isNetworkConnectionRequired }.ifEmpty { all }
            val codes = if (character.male) listOf("iom", "iol", "tpd") else listOf("sfg", "tpf", "tpc", "iog")
            codes.firstNotNullOfOrNull { code -> local.firstOrNull { it.name.contains("-x-$code") } }
                ?: local.firstOrNull()
        }
        if (voice != null) runCatching { tts.voice = voice }
    }
}
