package lk.spokenenglish.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Keeps Google's speech recognition running until Stop.
 *
 * Google's recogniser ends by itself whenever he pauses. Each ending is treated as a "pause":
 * the words go to the "So far" box and listening starts again straight away. The turn only
 * ends when stop() is called (the Stop button).
 *
 * English (en-US) normally; Sinhala (si-LK) while the Sinhala button is held.
 */
class SpeechLoop(private val ctx: Context, private val listener: Listener) : RecognitionListener {

    interface Listener {
        fun onPartial(text: String)
        fun onSegment(text: String, sinhala: Boolean)
        fun onLevel(rmsDb: Float)
        fun onFinished()
        fun onProblem(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var stopping = false
    private var listening = false
    private var wantSinhala = false
    private var segmentSinhala = false
    private var problemShown = false

    /** Languages for which "prefer offline" failed; for those we let Google use the internet. */
    private val onlineOnly = mutableSetOf<String>()
    private var segmentLang = "en-US"

    private val finishRunnable = Runnable { finish() }
    private val listenRunnable = Runnable { if (active && !stopping && !listening) listen() }

    val isActive: Boolean get() = active

    fun start(sinhala: Boolean = false) {
        if (active) return
        active = true
        stopping = false
        wantSinhala = sinhala
        problemShown = false
        listen()
    }

    /** Called when the Sinhala button is pressed (true) or released (false). */
    fun setSinhala(on: Boolean) {
        if (!active || stopping || on == wantSinhala) return
        wantSinhala = on
        // End the current piece in its own language; the loop restarts in the new language.
        if (listening) recognizer?.stopListening()
    }

    fun stop() {
        if (!active || stopping) return
        stopping = true
        if (listening) {
            recognizer?.stopListening()
            main.postDelayed(finishRunnable, 2500)
        } else {
            finish()
        }
    }

    fun destroy() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun finish() {
        if (!active) return
        main.removeCallbacks(finishRunnable)
        main.removeCallbacks(listenRunnable)
        active = false
        stopping = false
        listening = false
        recognizer?.cancel()
        listener.onFinished()
    }

    private fun next(delayMs: Long = 0) {
        listening = false
        if (!active) return
        if (stopping) {
            finish()
        } else {
            main.removeCallbacks(listenRunnable)
            main.postDelayed(listenRunnable, delayMs)
        }
    }

    private fun listen() {
        val r = recognizer ?: createRecognizer().also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        segmentSinhala = wantSinhala
        segmentLang = if (segmentSinhala) "si-LK" else "en-US"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, segmentLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (segmentLang !in onlineOnly) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        listening = true
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            listening = false
            listener.onProblem("Speech recognition could not start: ${e.message}")
            finish()
        }
    }

    // ---- RecognitionListener ----

    override fun onResults(results: Bundle?) {
        if (!active) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) listener.onSegment(text, segmentSinhala)
        next()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!active) return
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        listener.onPartial(text)
    }

    override fun onError(error: Int) {
        if (!active) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_CLIENT -> next()

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recognizer?.destroy()
                recognizer = null
                next(300)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                listener.onProblem("The app needs microphone permission.")
                listening = false
                finish()
            }

            12, 13 -> { // language not supported / not downloaded (Android 12+)
                if (segmentLang !in onlineOnly) {
                    onlineOnly.add(segmentLang)   // try again, allowing Google to use the internet
                    next()
                } else {
                    if (!problemShown) {
                        problemShown = true
                        listener.onProblem(problemText(error))
                    }
                    next(500)
                }
            }

            else -> {
                if (!problemShown) {
                    problemShown = true
                    listener.onProblem(problemText(error))
                }
                next(500)
            }
        }
    }

    /** Google's own recogniser (the one Gboard uses), not the phone maker's (e.g. Samsung's). */
    private fun createRecognizer(): SpeechRecognizer {
        val google = googleService(ctx)
        return if (google != null) SpeechRecognizer.createSpeechRecognizer(ctx, google)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    private fun problemText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "No internet, and this language isn't downloaded for offline use."
        12, 13 -> // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE (Android 12+)
            "This language isn't available. Download it in Google voice typing settings."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone had a problem."
        SpeechRecognizer.ERROR_SERVER -> "Google speech service had a problem."
        else -> "Speech recognition error $error."
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (active) listener.onLevel(rmsdB)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        private val PREFERRED = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox")

        /** Finds Google's speech recognition service: Speech Services by Google first, then the Google app. */
        fun googleService(ctx: Context): ComponentName? {
            val found = runCatching {
                ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            }.getOrNull().orEmpty()
            for (pkg in PREFERRED) {
                val s = found.firstOrNull { it.serviceInfo?.packageName == pkg }?.serviceInfo ?: continue
                return ComponentName(s.packageName, s.name)
            }
            return null
        }
    }
}
