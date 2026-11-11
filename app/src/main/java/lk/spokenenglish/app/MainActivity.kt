package lk.spokenenglish.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Test slice: a free conversation with Sam or Maya.
 * Mic → Stop with sound bars · Sinhala press-and-hold · Google speech-to-text (text builds up at each pause)
 * · Gemini reply with a mood · Google voice · drawn face.
 */
class MainActivity : Activity(), SpeechLoop.Listener {

    private val cream = Color.parseColor("#F6F4EF")
    private val teal = Color.parseColor("#0F6B6B")
    private val tealLight = Color.parseColor("#E3F1EF")
    private val orange = Color.parseColor("#C2621A")
    private val orangeLight = Color.parseColor("#FBEEE3")
    private val ink = Color.parseColor("#1D2433")
    private val muted = Color.parseColor("#5B6475")
    private val line = Color.parseColor("#E6E1D6")

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var face: FaceView
    private lateinit var partnerName: TextView
    private lateinit var partnerStatus: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var soFarBox: LinearLayout
    private lateinit var soFarText: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var bars: SoundBarsView
    private lateinit var micButton: Button
    private lateinit var sinhalaButton: Button
    private lateinit var hint: TextView

    private lateinit var speech: SpeechLoop
    private lateinit var voice: CharacterVoice

    private var partner: Character = Characters.SAM
    private val history = mutableListOf<Pair<String, String>>()
    private val segments = mutableListOf<Pair<String, Boolean>>()
    private var partial = ""
    private var recording = false
    private var waitingReply = false
    private var recordStart = 0L

    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
            timerText.text = String.format("%d:%02d", s / 60, s % 60)
            main.postDelayed(this, 250)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        buildUi()

        speech = SpeechLoop(this, this)
        voice = CharacterVoice(this) { ok ->
            if (!ok) addNote("The phone's Google voice isn't ready. Check Settings › Text-to-speech › Google.")
            startChat()
        }
        voice.onSpeaking = { talking ->
            face.talking = talking
            partnerStatus.text = if (talking) "${partner.name} is talking" else "${partner.name} is listening"
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

    override fun onStop() {
        super.onStop()
        if (recording) speech.stop()
        voice.stop()
    }

    override fun onDestroy() {
        speech.destroy()
        voice.shutdown()
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

        val opener = partner.openers.random()
        history.add("user" to "(We meet in class. You speak first.)")
        history.add("model" to JSONObject().put("mood", "happy").put("reply", opener).toString())
        addPartnerBubble(opener, null)
        voice.speak(opener, partner)
    }

    private fun onMicTap() {
        if (waitingReply) return
        if (!recording) beginRecording(sinhala = false) else finishRecording()
    }

    private fun beginRecording(sinhala: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        voice.stop()
        segments.clear()
        partial = ""
        recording = true
        recordStart = SystemClock.elapsedRealtime()
        showRecordingUi(true)
        refreshSoFar()
        speech.start(sinhala)
        main.post(timerTick)
    }

    private fun finishRecording() {
        micButton.isEnabled = false
        statusText.text = "Finishing…"
        speech.stop()
    }

    override fun onPartial(text: String) {
        partial = text
        refreshSoFar()
    }

    override fun onSegment(text: String, sinhala: Boolean) {
        segments.add(text to sinhala)
        partial = ""
        refreshSoFar()
    }

    override fun onLevel(rmsDb: Float) {
        bars.push(rmsDb)
    }

    override fun onFinished() {
        recording = false
        showRecordingUi(false)
        micButton.isEnabled = true
        val said = segments.toList()
        if (said.isEmpty()) {
            addNote("I didn't hear anything. Tap Talk and try again.")
            return
        }
        addMyBubble(said)
        val forAi = said.joinToString(" ") { (t, si) -> if (si) "[Sinhala: $t]" else t }
        sendToAi(forAi)
    }

    override fun onProblem(message: String) {
        addNote(message)
    }

    private fun sendToAi(text: String) {
        val key = apiKey()
        if (key.isBlank()) {
            addNote("Add the free Gemini key first: tap ⚙ at the top.")
            return
        }
        history.add("user" to text)
        waitingReply = true
        micButton.isEnabled = false
        face.mood = "thinking"
        partnerStatus.text = "${partner.name} is thinking…"

        val system = partner.persona
        val snapshot = history.toList()
        val model = modelName()
        thread {
            val result = runCatching { GeminiClient(key, model).reply(system, snapshot) }
            main.post {
                waitingReply = false
                micButton.isEnabled = true
                result.onSuccess { r ->
                    history.add("model" to JSONObject().put("mood", r.mood).put("reply", r.text).toString())
                    face.mood = r.mood
                    addPartnerBubble(r.text, r.ms)
                    voice.speak(r.text, partner)
                }.onFailure { e ->
                    if (history.isNotEmpty()) history.removeAt(history.size - 1)
                    face.mood = "neutral"
                    partnerStatus.text = "${partner.name} is listening"
                    addNote("No reply (${e.message}). Tap Talk and say it again.")
                }
            }
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
        box.addView(label("Model (empty = $DEFAULT_MODEL)", 13f, muted))
        box.addView(modelInput)
        AlertDialog.Builder(this)
            .setTitle("Test settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim())
                    .apply()
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
        titles.addView(label("Test version · Week 1", 12.5f, muted))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("New chat") { if (!recording && !waitingReply) { voice.stop(); startChat() } })
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

        // Bottom bar
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }

        soFarBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cream, dp(12), line)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        soFarBox.addView(label("SO FAR (ADDS UP AT EACH PAUSE)", 11f, muted, bold = true))
        soFarText = label("", 15f, ink)
        soFarBox.addView(soFarText)
        bottom.addView(soFarBox)

        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        statusText = label("● Listening…", 13f, teal, bold = true)
        statusRow.addView(statusText)
        bars = SoundBarsView(this)
        statusRow.addView(bars, LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
        timerText = label("0:00", 13f, muted)
        statusRow.addView(timerText)
        bottom.addView(statusRow)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        sinhalaButton = Button(this).apply {
            text = "Sinhala"
            isAllCaps = false
            setTextColor(Color.parseColor("#8A4513"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(orangeLight, dp(24), orange)
            setPadding(dp(16), 0, dp(16), 0)
        }
        sinhalaButton.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = rounded(orange, dp(24), orange)
                    sinhalaButton.setTextColor(Color.WHITE)
                    if (!recording && !waitingReply) beginRecording(sinhala = true) else speech.setSinhala(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = rounded(orangeLight, dp(24), orange)
                    sinhalaButton.setTextColor(Color.parseColor("#8A4513"))
                    speech.setSinhala(false)
                }
            }
            true
        }
        buttons.addView(sinhalaButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))

        micButton = Button(this).apply {
            text = "Talk"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(teal)
                setStroke(dp(6), tealLight)
            }
            setOnClickListener { onMicTap() }
        }
        buttons.addView(micButton, LinearLayout.LayoutParams(dp(84), dp(84)).apply { setMargins(dp(24), 0, dp(24), 0) })
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(90), dp(48)))   // keeps the mic centred
        bottom.addView(buttons)

        hint = label("Tap Talk and speak. Hold Sinhala when you're stuck. Tap Stop when you finish.", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        bottom.addView(hint)

        root.addView(bottom)
        setContentView(root)
    }

    private fun showRecordingUi(on: Boolean) {
        soFarBox.visibility = if (on) View.VISIBLE else View.GONE
        statusRow.visibility = if (on) View.VISIBLE else View.GONE
        statusText.text = "● Listening…"
        timerText.text = "0:00"
        micButton.text = if (on) "■  Stop" else "Talk"
        hint.text = if (on) "Keep talking. Pauses are fine. Tap Stop when you finish."
        else "Tap Talk and speak. Hold Sinhala when you're stuck. Tap Stop when you finish."
        if (!on) bars.reset()
    }

    private fun refreshSoFar() {
        val sb = SpannableStringBuilder()
        for ((t, si) in segments) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(t)
            if (si) sb.setSpan(BackgroundColorSpan(orangeLight), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (partial.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" ")
            val start = sb.length
            sb.append(partial)
            sb.setSpan(ForegroundColorSpan(muted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        soFarText.text = if (sb.isEmpty()) "…" else sb
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
        addToMessages(wrap, Gravity.END)
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
        row.addView(smallButton("▶  ${who.name}") { if (!recording) voice.speak(text, who) })
        if (ms != null) row.addView(label("  reply in %.1f s".format(ms / 1000f), 12f, muted))
        card.addView(row)
        val words = label(text, 15f, ink).apply { visibility = View.GONE }
        val toggle = label("Show words", 13f, teal, bold = true).apply { setPadding(0, dp(4), 0, dp(2)) }
        toggle.setOnClickListener {
            val show = words.visibility != View.VISIBLE
            words.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "Hide words" else "Show words"
        }
        card.addView(toggle)
        card.addView(words)
        addToMessages(card, Gravity.START)
    }

    private fun addNote(message: String) {
        val note = label(message, 13f, Color.parseColor("#8A4513")).apply {
            background = rounded(orangeLight, dp(12), null)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        addToMessages(note, Gravity.START)
    }

    private fun addToMessages(v: View, gravity: Int) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = gravity
            topMargin = dp(10)
        }
        messages.addView(v, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
            setOnClickListener { onClick() }
        }

    private fun rounded(fillColor: Int, radius: Int, strokeColor: Int?): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
    }
}
