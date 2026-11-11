package lk.spokenenglish.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Test slice: a free conversation with Sam or Maya.
 * Claude-style pill bar: waiting = සිං + one line + mic; talking = ✕ · සිං · sound bars · ■ pause · ↑ send.
 * Google speech-to-text (text builds up at each pause, never editable) · Gemini reply with a mood and
 * an optional laugh · Google voice · drawn face · nudges when he's silent.
 */
class MainActivity : Activity(), SpeechLoop.Listener {

    private val cream = Color.parseColor("#F6F4EF")
    private val teal = Color.parseColor("#0F6B6B")
    private val tealLight = Color.parseColor("#E3F1EF")
    private val orange = Color.parseColor("#C2621A")
    private val orangeLight = Color.parseColor("#FBEEE3")
    private val orangeDark = Color.parseColor("#8A4513")
    private val ink = Color.parseColor("#1D2433")
    private val muted = Color.parseColor("#5B6475")
    private val grey = Color.parseColor("#8A909C")
    private val line = Color.parseColor("#E6E1D6")

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var face: FaceView
    private lateinit var partnerName: TextView
    private lateinit var partnerStatus: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView

    // Bottom area
    private lateinit var nudgeText: TextView
    private lateinit var liveText: TextView
    private lateinit var cancelButton: IconButton
    private lateinit var sinhalaButton: TextView
    private lateinit var waitingLine: TextView
    private lateinit var statusLine: TextView
    private lateinit var bars: SoundBarsView
    private lateinit var pauseButton: IconButton
    private lateinit var micButton: IconButton
    private lateinit var sendButton: IconButton

    private lateinit var speech: SpeechLoop
    private lateinit var voice: CharacterVoice
    private lateinit var updater: Updater
    private var vibrator: Vibrator? = null
    private var laughPlayer: MediaPlayer? = null
    private var sinhalaPulse: ObjectAnimator? = null

    private var partner: Character = Characters.SAM
    private val history = mutableListOf<Pair<String, String>>()

    // ---- the turn he is building (text is never editable) ----
    private enum class End { SEND, PAUSE }
    private val segments = mutableListOf<Pair<String, Boolean>>()
    private var partial = ""
    private var inTurn = false            // a turn is open (recording or paused)
    private var recording = false         // the recogniser loop is running
    private var finishing = false         // waiting for the last words after ■ or ↑
    private var endAction: End? = null
    private var readyPending = false      // "Getting ready…" until the recogniser really listens
    private var sinhalaHeld = false
    private var sinhalaStartedTurn = false
    private var sinhalaDownAt = 0L

    // ---- partner state ----
    private var waitingReply = false
    private var partnerTalking = false
    private var laughing = false

    // ---- nudges ----
    private var silenceStage = 0          // 0 = none yet, 1 = voice nudge given, 2 = hint shown
    private var lastVoiceAt = 0L
    private var recordNudgeShown = false

    private val silenceCheck = Runnable { onSilentAfterPartner() }
    private val recordingSilenceCheck = object : Runnable {
        override fun run() {
            if (!recording || finishing || readyPending) return
            if (!recordNudgeShown && SystemClock.elapsedRealtime() - lastVoiceAt >= RECORD_SILENCE_MS) {
                recordNudgeShown = true
                showNudge("Take your time. Keep going, or hold සිං and say it in Sinhala.")
            }
            main.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        vibrator = getSystemService(Vibrator::class.java)
        buildUi()
        showIdle()

        updater = Updater(this) { addNote(it) }
        updater.onReady = { maybeUpdate() }
        updater.greetAfterUpdate()
        updater.check(force = true)

        speech = SpeechLoop(this, this)
        voice = CharacterVoice(this) { ok ->
            if (!ok) addNote("The phone's Google voice isn't ready. Check Settings › Text-to-speech › Google.")
            startChat()
        }
        voice.onSpeaking = { talking ->
            partnerTalking = talking
            face.talking = talking
            partnerStatus.text = if (talking) "${partner.name} is talking" else "${partner.name} is listening"
            if (!talking) { armSilenceTimer(); maybeUpdate() } else main.removeCallbacks(silenceCheck)
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addNote("Google speech recognition isn't available on this phone.")
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            addNote("The microphone is needed for talking. Allow it in the phone's app settings.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::updater.isInitialized) { updater.check(); maybeUpdate() }
    }

    /** Installs a downloaded update only when he isn't talking and nobody is speaking. */
    private fun maybeUpdate() {
        if (!::updater.isInitialized) return
        if (inTurn || waitingReply || partnerTalking || laughing) return
        updater.installIfReady()
    }

    override fun onStop() {
        super.onStop()
        if (recording) { endAction = End.PAUSE; speech.stop() }
        stopLaugh()
        voice.stop()
        main.removeCallbacks(silenceCheck)
    }

    override fun onDestroy() {
        speech.destroy()
        voice.shutdown()
        stopLaugh()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- conversation

    private fun startChat() {
        val count = prefs.getInt("chats", 0)
        partner = if (count % 2 == 0) Characters.SAM else Characters.MAYA   // the app chooses, about half each
        prefs.edit().putInt("chats", count + 1).apply()

        history.clear()
        messages.removeAllViews()
        face.character = partner
        face.mood = "happy"
        partnerName.text = partner.name
        partnerStatus.text = "${partner.name} is listening"
        silenceStage = 0
        hideNudge()

        val opener = partner.openers.random()
        history.add("user" to "(We meet in class. You speak first.)")
        history.add("model" to JSONObject().put("mood", "happy").put("reply", opener).toString())
        addPartnerBubble(opener, null)
        voice.speak(opener, partner)
    }

    // ---- buttons ----

    private fun onMicTap() {
        if (waitingReply || finishing) return
        when {
            !inTurn -> beginTurn(sinhala = false)
            !recording -> resumeTurn(sinhala = false)   // paused: new words are added after the old ones
        }
    }

    private fun onPauseTap() {
        if (!recording || finishing) return
        endAction = End.PAUSE
        finishing = true
        statusLine.text = "Pausing…"
        speech.stop()
    }

    private fun onSendTap() {
        if (!inTurn || finishing) return
        if (recording) {
            endAction = End.SEND
            finishing = true
            showFinishing("Sending…")
            speech.stop()
        } else {
            sendTurn()
        }
    }

    private fun onCancelTap() {
        if (!inTurn) return
        speech.cancel()
        recording = false
        finishing = false
        closeTurn()
        armSilenceTimer()
    }

    private fun onSinhalaDown() {
        if (waitingReply || finishing) return
        sinhalaHeld = true
        sinhalaDownAt = SystemClock.elapsedRealtime()
        stopSinhalaPulse()
        setSinhalaLook(true)
        when {
            !inTurn -> { sinhalaStartedTurn = true; beginTurn(sinhala = true) }
            !recording -> { sinhalaStartedTurn = false; resumeTurn(sinhala = true) }
            else -> speech.setSinhala(true)
        }
    }

    private fun onSinhalaUp() {
        if (!sinhalaHeld) return
        sinhalaHeld = false
        setSinhalaLook(false)
        if (!recording || finishing) return
        if (sinhalaStartedTurn) {
            sinhalaStartedTurn = false
            if (SystemClock.elapsedRealtime() - sinhalaDownAt < 450 && segments.isEmpty() && partial.isBlank()) {
                // A quick tap, not a hold: nothing said yet, so close quietly and explain.
                onCancelTap()
                showNudge("Hold සිං while you talk, then let go.")
                return
            }
            // The turn was started by holding Sinhala: letting go sends it.
            endAction = End.SEND
            finishing = true
            showFinishing("Sending…")
            speech.stop()
        } else {
            speech.setSinhala(false)   // back to English; the turn goes on until ↑
        }
    }

    // ---- the turn ----

    private fun beginTurn(sinhala: Boolean) {
        if (!micAllowed()) return
        stopVoiceAndLaugh()
        segments.clear()
        partial = ""
        inTurn = true
        silenceStage = 0
        main.removeCallbacks(silenceCheck)
        startListening(sinhala)
    }

    private fun resumeTurn(sinhala: Boolean) {
        if (!micAllowed()) return
        stopVoiceAndLaugh()
        startListening(sinhala)
    }

    private fun startListening(sinhala: Boolean) {
        recording = true
        finishing = false
        endAction = null
        readyPending = true
        recordNudgeShown = false
        hideNudge()
        showRecording()
        refreshLive()
        speech.start(sinhala)
    }

    private fun micAllowed(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return false
        }
        return true
    }

    override fun onListening() {
        if (readyPending) {
            readyPending = false
            lastVoiceAt = SystemClock.elapsedRealtime()
            buzz()
            statusLine.visibility = View.GONE
            bars.visibility = View.VISIBLE
            main.removeCallbacks(recordingSilenceCheck)
            main.postDelayed(recordingSilenceCheck, 1000)
        }
    }

    override fun onPartial(text: String) {
        if (text.isNotBlank() && text != partial) heardVoice()
        partial = text
        refreshLive()
    }

    override fun onSegment(text: String, sinhala: Boolean) {
        segments.add(text to sinhala)
        partial = ""
        heardVoice()
        refreshLive()
    }

    override fun onLevel(rmsDb: Float) {
        bars.push(rmsDb)
        if (rmsDb > VOICE_RMS_DB) heardVoice()
    }

    private fun heardVoice() {
        lastVoiceAt = SystemClock.elapsedRealtime()
        if (recordNudgeShown) { recordNudgeShown = false; hideNudge() }
    }

    override fun onFinished() {
        recording = false
        finishing = false
        main.removeCallbacks(recordingSilenceCheck)
        if (partial.isNotBlank()) { segments.add(partial to sinhalaHeld); partial = "" }
        val action = endAction ?: End.PAUSE
        endAction = null
        if (sinhalaHeld) { sinhalaHeld = false; setSinhalaLook(false) }
        if (action == End.SEND) sendTurn() else showPaused()
    }

    override fun onProblem(message: String) {
        addNote(message)
    }

    private fun sendTurn() {
        val said = segments.toList()
        closeTurn()
        if (said.isEmpty()) {
            addNote("I didn't hear anything. Tap the mic and try again.")
            return
        }
        addMyBubble(said)
        val forAi = said.joinToString(" ") { (t, si) -> if (si) "[Sinhala: $t]" else t }
        askPartner(forAi, nudge = false)
    }

    private fun closeTurn() {
        inTurn = false
        recording = false
        finishing = false
        sinhalaStartedTurn = false
        segments.clear()
        partial = ""
        main.removeCallbacks(recordingSilenceCheck)
        hideNudge()
        showIdle()
        maybeUpdate()
    }

    // ---- the partner's reply ----

    private fun askPartner(text: String, nudge: Boolean) {
        val key = apiKey()
        if (key.isBlank()) {
            addNote("Add the free Gemini key first: tap ⚙ at the top.")
            return
        }
        history.add("user" to text)
        waitingReply = true
        setButtonsBusy(true)
        face.mood = "thinking"
        partnerStatus.text = "${partner.name} is thinking…"

        val system = partner.persona
        val snapshot = history.toList()
        val who = partner
        thread {
            val result = askWithFallback(key, system, snapshot)
            main.post {
                if (who != partner) return@post   // a new chat was started meanwhile
                waitingReply = false
                setButtonsBusy(false)
                result.onSuccess { r ->
                    history.add("model" to JSONObject().put("mood", r.mood).put("laugh", r.laugh).put("reply", r.text).toString())
                    prefs.edit().putString("lastModelVersion", r.modelVersion).apply()
                    if (!nudge) silenceStage = 0
                    face.mood = r.mood
                    addPartnerBubble(r.text, r.ms)
                    if (r.laugh) laughThenSpeak(r.text) else voice.speak(r.text, partner)
                }.onFailure { e ->
                    if (history.isNotEmpty()) history.removeAt(history.size - 1)
                    face.mood = "neutral"
                    partnerStatus.text = "${partner.name} is listening"
                    prefs.edit().putString("lastProblem", e.message ?: e.toString()).apply()
                    if (!nudge) addNote(friendlyProblem(e))
                }
            }
        }
    }

    /**
     * Tries the chosen model (Flash Lite by default), then the backup model. A model that hit its
     * free limit (HTTP 429) is skipped quietly: for a minute, or until tomorrow for the daily limit.
     * Never retries the same model on 429.
     */
    private fun askWithFallback(key: String, system: String, snapshot: List<Pair<String, String>>): Result<GeminiClient.Reply> {
        val chain = listOf(modelName(), BACKUP_MODEL).distinct()
        var lastError: Exception = Exception("All free AI models are resting")
        for (model in chain) {
            val until = prefs.getLong("limited_$model", 0L)
            if (System.currentTimeMillis() < until) {
                lastError = GeminiClient.HttpError(429, "limit reached for $model")
                continue
            }
            try {
                return Result.success(GeminiClient(key, model).reply(system, snapshot))
            } catch (e: GeminiClient.HttpError) {
                lastError = e
                if (e.code == 429) {
                    val restUntil = if (e.perDay) nextPacificMidnight() else System.currentTimeMillis() + 60_000
                    prefs.edit().putLong("limited_$model", restUntil).apply()
                    continue   // quietly switch to the next model
                }
                return Result.failure(e)
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return Result.failure(lastError)
    }

    private fun friendlyProblem(e: Throwable): String = when {
        e is GeminiClient.HttpError && e.code == 429 ->
            "${partner.name} needs a short break (the free AI limit is reached). Try again in a minute."
        (e.message ?: "").contains("longer than") ->
            "${partner.name} is slow to answer right now. Tap the mic and say it again."
        else -> "${partner.name} didn't get that. Check the internet, then tap the mic and say it again."
    }

    /** Gemini's free daily limits reset at midnight Pacific time. */
    private fun nextPacificMidnight(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 1)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ---- laughs: a real laugh sound and a laughing face, then the voice ----

    private fun laughThenSpeak(text: String) {
        val who = partner
        laughing = true
        face.laughing = true
        partnerStatus.text = "${who.name} is laughing"
        val done = Runnable {
            if (!laughing) return@Runnable
            laughing = false
            face.laughing = false
            stopLaughPlayer()
            if (who == partner && !inTurn) voice.speak(text, who)
        }
        val clip = who.laughClip
        if (clip != null) {
            val mp = runCatching { MediaPlayer.create(this, clip) }.getOrNull()
            if (mp != null) {
                laughPlayer = mp
                mp.setOnCompletionListener { main.post(done) }
                mp.start()
                main.postDelayed(done, 2500)   // safety net
                return
            }
        }
        main.postDelayed(done, 900)   // no sound for this character yet: laughing face only
    }

    private fun stopLaugh() {
        laughing = false
        if (::face.isInitialized) face.laughing = false
        stopLaughPlayer()
    }

    private fun stopLaughPlayer() {
        laughPlayer?.let { runCatching { it.stop() }; it.release() }
        laughPlayer = null
    }

    private fun stopVoiceAndLaugh() {
        stopLaugh()
        voice.stop()
    }

    // ---- nudges when he is silent ----

    private fun armSilenceTimer() {
        main.removeCallbacks(silenceCheck)
        if (inTurn || waitingReply || partnerTalking || laughing) return
        if (silenceStage >= 2) return
        main.postDelayed(silenceCheck, AFTER_PARTNER_SILENCE_MS)
    }

    private fun onSilentAfterPartner() {
        if (inTurn || waitingReply || partnerTalking || laughing) return
        when (silenceStage) {
            0 -> {
                silenceStage = 1
                // The partner nudges by voice: says it again more simply, or gives a starter.
                askPartner("(He has been silent for 8 seconds. Help him start.)", nudge = true)
            }
            1 -> {
                silenceStage = 2
                showNudge("Stuck? Hold සිං and say it in Sinhala.")
                startSinhalaPulse()
            }
        }
    }

    private fun showNudge(text: String) {
        nudgeText.text = text
        nudgeText.visibility = View.VISIBLE
    }

    private fun hideNudge() {
        if (::nudgeText.isInitialized) nudgeText.visibility = View.GONE
        stopSinhalaPulse()
    }

    private fun startSinhalaPulse() {
        stopSinhalaPulse()
        sinhalaPulse = ObjectAnimator.ofPropertyValuesHolder(
            sinhalaButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f)
        ).apply {
            duration = 550
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        sinhalaButton.background = rounded(orangeLight, dp(20), orange, strokeDp = 2)
    }

    private fun stopSinhalaPulse() {
        sinhalaPulse?.cancel()
        sinhalaPulse = null
        if (::sinhalaButton.isInitialized) {
            sinhalaButton.scaleX = 1f
            sinhalaButton.scaleY = 1f
            if (!sinhalaHeld) setSinhalaLook(false)
        }
    }

    // ---------------------------------------------------------------- settings

    private fun apiKey(): String =
        prefs.getString("key", "")?.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY

    private fun modelName(): String =
        prefs.getString("model", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    private fun openSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val keyInput = EditText(this).apply {
            hint = "Gemini API key"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val modelInput = EditText(this).apply {
            hint = DEFAULT_MODEL
            setText(prefs.getString("model", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        box.addView(label("Gemini key (empty = the one built into the app)", 13f, muted))
        box.addView(keyInput)
        box.addView(label("Model (empty = $DEFAULT_MODEL; backup = $BACKUP_MODEL)", 13f, muted))
        box.addView(modelInput)
        val actual = prefs.getString("lastModelVersion", null) ?: "no reply yet"
        box.addView(label("Last reply came from: $actual", 13f, ink, bold = true).apply { setPadding(0, dp(8), 0, 0) })
        val now = System.currentTimeMillis()
        val resting = listOf(modelName(), BACKUP_MODEL).distinct().filter { prefs.getLong("limited_$it", 0L) > now }
        if (resting.isNotEmpty()) box.addView(label("Limit reached, resting: ${resting.joinToString()}", 12f, orangeDark))
        prefs.getString("lastProblem", null)?.let {
            box.addView(label("Last problem: ${it.take(200)}", 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        }
        val service = SpeechLoop.googleService(this)?.packageName ?: "phone default (Google not found)"
        box.addView(label("Speech recognition: $service", 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        box.addView(label("App version ${BuildConfig.VERSION_NAME}", 12f, muted))
        box.addView(label("Updates: ${prefs.getString("updateLast", "not checked yet")}", 12f, muted))
        updater.check(force = true)
        AlertDialog.Builder(this)
            .setTitle("Test settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim())
                    .apply()
            }
            .setNeutralButton("Clear limits") { _, _ ->
                val e = prefs.edit()
                prefs.all.keys.filter { it.startsWith("limited_") }.forEach { e.remove(it) }
                e.apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(8), dp(6))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Free talk", 18f, ink, bold = true))
        titles.addView(label("Test version ${BuildConfig.VERSION_NAME} · Week 1", 12.5f, muted))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("New chat") {
            if (!inTurn && !waitingReply) { stopVoiceAndLaugh(); startChat() }
        })
        header.addView(smallButton("⚙") { openSettings() })
        root.addView(header)

        // Face
        face = FaceView(this)
        root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        partnerName = label("", 17f, ink, bold = true).apply { gravity = Gravity.CENTER }
        partnerStatus = label("", 13f, muted).apply { gravity = Gravity.CENTER }
        root.addView(partnerName)
        root.addView(partnerStatus)

        // Messages
        scroll = ScrollView(this)
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom: nudge line, his live words (grey italics, read-only), then the pill bar
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(14))
        }
        nudgeText = label("", 13.5f, orangeDark, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(orangeLight, dp(12), null)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
        }
        bottom.addView(nudgeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        liveText = label("", 15.5f, grey).apply {
            setTypeface(typeface, Typeface.ITALIC)
            setPadding(dp(8), dp(2), dp(8), dp(6))
            setTextIsSelectable(false)
            visibility = View.GONE
        }
        bottom.addView(liveText)

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, dp(34), line)
            elevation = dp(3).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minimumHeight = dp(68)
        }

        cancelButton = IconButton(this, Glyph.CLOSE, Color.TRANSPARENT, muted).apply {
            setOnClickListener { tap(it); onCancelTap() }
        }
        pill.addView(cancelButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(2) })

        sinhalaButton = label("සිං", 15f, orangeDark, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            minWidth = dp(52)
        }
        setSinhalaLook(false)
        sinhalaButton.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onSinhalaDown() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onSinhalaUp()
            }
            true
        }
        pill.addView(sinhalaButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })

        val middle = FrameLayout(this)
        waitingLine = TextView(this).apply {
            textSize = 13.5f
            setTextColor(muted)
            text = instructionLine()
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        middle.addView(waitingLine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        bars = SoundBarsView(this)
        middle.addView(bars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34), Gravity.CENTER_VERTICAL))
        statusLine = label("", 13.5f, teal, bold = true)
        middle.addView(statusLine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        pill.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        pauseButton = IconButton(this, Glyph.PAUSE, tealLight, teal).apply {
            setOnClickListener { tap(it); onPauseTap() }
        }
        pill.addView(pauseButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })

        micButton = IconButton(this, Glyph.MIC, teal, Color.WHITE).apply {
            setOnClickListener { tap(it); onMicTap() }
        }
        pill.addView(micButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(6) })

        sendButton = IconButton(this, Glyph.SEND, orange, Color.WHITE).apply {
            setOnClickListener { tap(it); onSendTap() }
        }
        pill.addView(sendButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(6) })

        bottom.addView(pill)
        root.addView(bottom)
        setContentView(root)
    }

    /** "Tap [mic] for English, or hold [සිං] for Sinhala." with small pictures of the buttons. */
    private fun instructionLine(): CharSequence {
        val sb = SpannableStringBuilder("Tap ")
        val align = if (Build.VERSION.SDK_INT >= 29) DynamicDrawableSpan.ALIGN_CENTER else DynamicDrawableSpan.ALIGN_BOTTOM
        val mic = MicPicture(dp(22), teal).apply { setBounds(0, 0, dp(22), dp(22)) }
        sb.append(" ")
        sb.setSpan(ImageSpan(mic, align), sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" for English, or hold ")
        val chip = SinhalaPicture(dp(22), orangeLight, orange, orangeDark).apply { setBounds(0, 0, intrinsicWidth, dp(22)) }
        sb.append(" ")
        sb.setSpan(ImageSpan(chip, align), sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" for Sinhala.")
        return sb
    }

    // ---- bar states ----

    private fun showIdle() {
        liveText.visibility = View.GONE
        cancelButton.visibility = View.GONE
        waitingLine.visibility = View.VISIBLE
        bars.visibility = View.GONE
        bars.reset()
        statusLine.visibility = View.GONE
        pauseButton.visibility = View.GONE
        micButton.visibility = View.VISIBLE
        sendButton.visibility = View.GONE
        setButtonsBusy(waitingReply)
    }

    private fun showRecording() {
        liveText.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        waitingLine.visibility = View.GONE
        bars.visibility = View.GONE
        bars.color = if (sinhalaHeld) orange else teal
        statusLine.text = "Getting ready…"
        statusLine.setTextColor(teal)
        statusLine.visibility = View.VISIBLE
        pauseButton.visibility = View.VISIBLE
        pauseButton.isEnabled = true
        micButton.visibility = View.GONE
        sendButton.visibility = View.VISIBLE
        sendButton.isEnabled = true
    }

    private fun showPaused() {
        liveText.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        waitingLine.visibility = View.GONE
        bars.visibility = View.GONE
        statusLine.text = "Paused · tap the mic to go on"
        statusLine.setTextColor(muted)
        statusLine.visibility = View.VISIBLE
        pauseButton.visibility = View.GONE
        micButton.visibility = View.VISIBLE
        micButton.isEnabled = true
        sendButton.visibility = View.VISIBLE
        sendButton.isEnabled = true
        refreshLive()
    }

    private fun showFinishing(text: String) {
        bars.visibility = View.GONE
        statusLine.text = text
        statusLine.setTextColor(teal)
        statusLine.visibility = View.VISIBLE
        pauseButton.isEnabled = false
        sendButton.isEnabled = false
    }

    /** While the partner is thinking, the mic and සිං are visibly greyed out. */
    private fun setButtonsBusy(busy: Boolean) {
        if (!::micButton.isInitialized) return
        micButton.isEnabled = !busy
        sinhalaButton.alpha = if (busy) 0.35f else 1f
        waitingLine.alpha = if (busy) 0.5f else 1f
    }

    private fun setSinhalaLook(held: Boolean) {
        sinhalaButton.background = if (held) rounded(orange, dp(20), orange) else rounded(orangeLight, dp(20), orange)
        sinhalaButton.setTextColor(if (held) Color.WHITE else orangeDark)
        if (::bars.isInitialized) bars.color = if (held) orange else teal
    }

    private fun refreshLive() {
        val sb = SpannableStringBuilder()
        for ((t, si) in segments) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(t)
            if (si) {
                sb.setSpan(BackgroundColorSpan(orangeLight), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(orangeDark), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (partial.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(partial)
            if (sinhalaHeld) sb.setSpan(ForegroundColorSpan(orangeDark), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Show the end of what he said if it gets long (the full text goes into his bubble).
        val shown: CharSequence = if (sb.length > 260) SpannableStringBuilder("…").append(sb.subSequence(sb.length - 260, sb.length)) else sb
        liveText.text = if (sb.isEmpty()) "Say something…" else shown
    }

    private fun addMyBubble(said: List<Pair<String, Boolean>>) {
        val sb = SpannableStringBuilder()
        for ((t, si) in said) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(t)
            if (si) sb.setSpan(BackgroundColorSpan(Color.parseColor("#2F8A85")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val bubble = label("", 15.5f, Color.WHITE).apply {
            text = sb
            background = rounded(teal, dp(16), null)
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        wrap.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        wrap.addView(label("What the app heard", 11.5f, muted).apply { gravity = Gravity.END })
        addToMessages(wrap, Gravity.END, fullWidth = true)
    }

    /** Partner speech is voice only; the words appear only if he taps "Show words". */
    private fun addPartnerBubble(text: String, ms: Long?) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(16), line)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val who = partner
        row.addView(smallButton("▶  ${who.name}") { if (!inTurn) { stopLaugh(); voice.speak(text, who) } })
        if (ms != null) row.addView(label("  reply in %.1f s".format(ms / 1000f), 12f, muted))
        card.addView(row)
        val words = label(text, 15f, ink).apply { visibility = View.GONE }
        val toggle = smallButton("Show words") {}.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 13f; minHeight = dp(36) }
        toggle.setOnClickListener {
            tap(it)
            val show = words.visibility != View.VISIBLE
            words.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "Hide words" else "Show words"
        }
        card.addView(toggle)
        card.addView(words)
        addToMessages(card, Gravity.START)
    }

    private fun addNote(message: String) {
        val note = label(message, 13f, orangeDark).apply {
            background = rounded(orangeLight, dp(12), null)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        addToMessages(note, Gravity.START)
    }

    private fun addToMessages(v: View, gravity: Int, fullWidth: Boolean = false) {
        val width = if (fullWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            topMargin = dp(10)
        }
        messages.addView(v, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Instant feedback on every tap: a tick you can feel (the ripple shows by itself). */
    private fun tap(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** A short buzz when the mic is really listening. */
    private fun buzz() {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun smallButton(text: String, onClick: () -> Unit): TextView =
        label(text, 14f, teal, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            minWidth = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            val mask = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#330F6B6B")), null, mask)
            setOnClickListener { tap(it); onClick() }
        }

    private fun rounded(fillColor: Int, radius: Int, strokeColor: Int?, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    companion object {
        /** Gemini 3.5 Flash Lite: 2–4 s replies, 500 free requests a day (mentor's tests, 11 Nov). */
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        /** Used only when Flash Lite hits its free limit (Gemini 3.8 Flash: slower, 20 a day). */
        const val BACKUP_MODEL = "gemini-flash-latest"
        const val AFTER_PARTNER_SILENCE_MS = 8000L
        const val RECORD_SILENCE_MS = 6000L
        const val VOICE_RMS_DB = 4f
    }
}
