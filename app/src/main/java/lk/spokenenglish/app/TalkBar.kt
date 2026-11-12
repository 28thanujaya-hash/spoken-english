package lk.spokenenglish.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The one talk bar of the whole app (lessons, conversation, calls; mentor 11 Nov: "same setup everywhere"):
 * EN | සිං one-tap switch, mic, ■ pause (the mic carries on after the old words), green ↑ send, ✕ discard with
 * a 3-second UNDO. His words appear live in grey italics (never editable); calls hide them (showWords = false).
 * Call buttons (camera, End call) can sit inside the bar while he isn't talking (callButtons).
 * onSend gets the pieces he said (text, was it Sinhala).
 */
class TalkBar(private val a: Activity, private val onSend: (List<Pair<String, Boolean>>) -> Unit) : SpeechLoop.Listener {

    val view: LinearLayout
    private val nudge: TextView
    private val live: TextView
    private val discard: TextView
    private val undoBar: LinearLayout
    private val langSwitch: LinearLayout
    private val enSide: TextView
    private val siSide: TextView
    private val bars = SoundBarsView(a)
    private val status: TextView
    private val spacer = View(a)
    private val pauseBox: LinearLayout
    private val mic: IconButton
    private val sendBox: LinearLayout
    private val pauseBtn: IconButton
    private val sendBtn: IconButton
    private val callBox: LinearLayout
    private val hintLine: TextView

    private val speech = SpeechLoop(a, this)
    private val main = Handler(Looper.getMainLooper())
    private val segments = mutableListOf<Pair<String, Boolean>>()
    private var partial = ""
    private var inTurn = false
    private var recording = false
    private var finishing = false
    private var sendAtEnd = false
    private var sinhala = false
    private var readyPending = false
    private var switchPending = false
    private var undo: List<Pair<String, Boolean>>? = null
    private var pulse: ObjectAnimator? = null
    private var lastVoiceAt = 0L
    private var recordNudge = false
    private var minConfidence = 1f

    /** Called first on any tap of the mic or the switch (the conversation uses it to stop the partner thinking). */
    var onBeforeTurn: (() -> Unit)? = null
    /** Called just before the mic opens (e.g. to stop the partner's voice). */
    var onStart: (() -> Unit)? = null
    /** Called when a turn is thrown away or sent, i.e. he is waiting again. */
    var onIdle: (() -> Unit)? = null
    /** Calls hide his live words (voice only). */
    var showWords = true
    /** True when Google wasn't sure about some of the words in the last sent turn. */
    var lastTurnUnsure = false
        private set

    var enabled = true
        set(v) { field = v; view.alpha = if (v) 1f else 0.4f }

    val isTalking get() = inTurn

    /** Video calls (1.1 finding 13): no mic tap; the turn is sent by itself after this much silence (0 = off). */
    var autoSendMs = 0L

    /** Starts listening without a tap (video calls). */
    fun listen() { if (!recording && !finishing) micTap() }

    /** The සිං hold button in video calls: down = Sinhala, up = back to English. */
    fun holdSinhala(on: Boolean) = switchTo(on)

    init {
        val c = a
        view = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 10)) }
        nudge = Ui.label(c, "", 13.5f, Ui.orangeDark, true).apply {
            gravity = Gravity.CENTER; background = Ui.rounded(c, Ui.orangeLight, 12)
            setPadding(Ui.dp(c, 12), Ui.dp(c, 7), Ui.dp(c, 12), Ui.dp(c, 7)); visibility = View.GONE
        }
        view.addView(nudge, Ui.lp(c, 0).apply { bottomMargin = Ui.dp(c, 6) })
        live = Ui.label(c, "", 15.5f, Color.parseColor("#9AA5AD")).apply {
            setTypeface(typeface, Typeface.ITALIC); visibility = View.GONE; setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 6))
        }
        view.addView(live)
        discard = Ui.label(c, "✕  Discard", 13f, Ui.muted, true).apply {
            background = Ui.ripple(c, Ui.rounded(c, Ui.surface, 14, Ui.line), 14); setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6))
            visibility = View.GONE
            setOnClickListener { Ui.tap(it); discardTurn() }
        }
        view.addView(discard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(c, 8); marginStart = Ui.dp(c, 6) })
        undoBar = Ui.row(c).apply { background = Ui.rounded(c, Ui.surface2, 12); setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4)); visibility = View.GONE }
        undoBar.addView(Ui.label(c, "Your words were discarded", 14.5f, Ui.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoBar.addView(Ui.label(c, "UNDO", 14.5f, Ui.teal, true).apply {
            gravity = Gravity.CENTER; minHeight = Ui.dp(c, 40); setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0)
            setOnClickListener { Ui.tap(it); undoDiscard() }
        })
        view.addView(undoBar, Ui.lp(c, 0).apply { bottomMargin = Ui.dp(c, 8) })

        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(c, Ui.surface, 36, Ui.line)
            setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8)); minimumHeight = Ui.dp(c, 72)
        }
        langSwitch = LinearLayout(c).apply { background = Ui.rounded(c, Ui.surface, 24, Ui.orange, 2); setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2)) }
        enSide = Ui.label(c, "EN", 15f, Ui.muted, true).apply { gravity = Gravity.CENTER; setOnClickListener { Ui.tap(it); switchTo(false) } }
        siSide = Ui.label(c, "සිං", 19f, Ui.orangeDark, true).apply { gravity = Gravity.CENTER; setOnClickListener { Ui.tap(it); switchTo(true) } }
        langSwitch.addView(enSide, LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 46)))
        langSwitch.addView(siSide, LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 46)))
        pill.addView(langSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(c, 10) })

        val middle = FrameLayout(c)
        middle.addView(spacer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 34)))
        middle.addView(bars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 34), Gravity.CENTER_VERTICAL))
        status = Ui.label(c, "", 13.5f, Ui.teal, true)
        middle.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        callBox = Ui.row(c).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        middle.addView(callBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        pill.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Pause: dark green circle with a bright green ring, easy to see (mentor 11 Nov).
        pauseBtn = IconButton(c, Glyph.PAUSE, Ui.tealLight, Ui.teal, stroke = Ui.teal).apply { setOnClickListener { Ui.tap(it); pause() } }
        pauseBox = labelled(pauseBtn, 42, "pause")
        pill.addView(pauseBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 6) })
        mic = IconButton(c, Glyph.MIC, Ui.teal, Color.WHITE).apply { setOnClickListener { Ui.tap(it); micTap() } }
        pill.addView(mic, LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54)).apply { marginStart = Ui.dp(c, 6) })
        // Send: WhatsApp green (orange is kept for Sinhala only).
        sendBtn = IconButton(c, Glyph.SEND, Ui.teal, Color.WHITE).apply { setOnClickListener { Ui.tap(it); send() } }
        sendBox = labelled(sendBtn, 46, "send")
        pill.addView(sendBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 6) })
        view.addView(pill)
        hintLine = Ui.label(c, "", 11.5f, Ui.muted).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 6), 0, 0); visibility = View.GONE }
        view.addView(hintLine)
        setSinhala(false)
        showIdle()
    }

    private fun labelled(b: View, size: Int, word: String) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(b, LinearLayout.LayoutParams(Ui.dp(a, size), Ui.dp(a, size)))
        addView(Ui.label(a, word, 11f, Ui.muted, true).apply { gravity = Gravity.CENTER })
    }

    /** The grey line under the bar (shown only on Day 0–1, QA 11 Nov). */
    fun setHint(text: String?) {
        hintLine.text = text ?: ""
        hintLine.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Buttons that sit in the middle of the bar while he isn't talking (camera, End call). */
    fun setCallButtons(vararg buttons: View) {
        callBox.removeAllViews()
        for (b in buttons) callBox.addView(b, LinearLayout.LayoutParams(Ui.dp(a, 46), Ui.dp(a, 46)).apply { marginStart = Ui.dp(a, 6); marginEnd = Ui.dp(a, 6) })
        showIdleOrPaused()
    }

    fun destroy() { speech.destroy(); main.removeCallbacksAndMessages(null) }
    fun stopIfRecording() { if (recording) { sendAtEnd = false; finishing = true; speech.stop() } }

    /** Clears everything and goes back to English (after each answer). */
    fun reset() {
        if (recording) speech.cancel()
        recording = false; finishing = false; inTurn = false
        segments.clear(); partial = ""
        hideNudge()
        setSinhala(false); showIdle()
    }

    /** Puts earlier words back into the bar, paused (the partner was interrupted while thinking). */
    fun loadPaused(words: List<Pair<String, Boolean>>) {
        if (recording) return
        segments.clear(); segments.addAll(words); partial = ""
        inTurn = true
        showPaused()
    }

    fun showNudge(text: String) { nudge.text = text; nudge.visibility = View.VISIBLE }
    fun hideNudge() { nudge.visibility = View.GONE; stopPulse() }

    fun pulseSinhala() {
        stopPulse()
        pulse = ObjectAnimator.ofPropertyValuesHolder(langSwitch,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f)).apply {
            duration = 550; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
    }

    private fun stopPulse() { pulse?.cancel(); pulse = null; langSwitch.scaleX = 1f; langSwitch.scaleY = 1f }

    private fun micAllowed(): Boolean {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic(a); return false
        }
        return true
    }

    private fun micTap() {
        if (!enabled || finishing || recording) return
        onBeforeTurn?.invoke()
        if (!inTurn) { segments.clear(); partial = ""; inTurn = true; minConfidence = 1f }
        start()
    }

    private fun switchTo(si: Boolean) {
        if (!enabled || finishing) return
        onBeforeTurn?.invoke()
        stopPulse()
        if (!inTurn) {
            setSinhala(si)
            if (si) { segments.clear(); partial = ""; inTurn = true; minConfidence = 1f; start() }
            return
        }
        if (si == sinhala) return
        setSinhala(si)
        if (recording) {
            switchPending = true
            bars.visibility = View.GONE
            status.text = if (si) "Switching to Sinhala…" else "Switching to English…"
            status.setTextColor(if (si) Ui.orange else Ui.teal); status.visibility = View.VISIBLE
            speech.setSinhala(si)
        } else start()
    }

    private fun start() {
        if (!micAllowed()) return
        main.removeCallbacks(hideUndo); undo = null; undoBar.visibility = View.GONE
        onStart?.invoke()
        recording = true; finishing = false; sendAtEnd = false; readyPending = true; recordNudge = false
        hideNudge()
        showRecording(); refreshLive()
        speech.start(sinhala)
    }

    private fun pause() { if (recording && !finishing) { finishing = true; sendAtEnd = false; status.text = "Pausing…"; speech.stop() } }

    private fun send() {
        if (!inTurn || finishing) return
        if (recording) { finishing = true; sendAtEnd = true; status.text = "Sending…"; status.visibility = View.VISIBLE; bars.visibility = View.GONE; speech.stop() }
        else deliver()
    }

    private val hideUndo = Runnable { undo = null; undoBar.visibility = View.GONE }

    private fun discardTurn() {
        if (partial.isNotBlank()) segments.add(partial to sinhala)
        val kept = segments.toList()
        if (recording) speech.cancel()
        recording = false; finishing = false; inTurn = false
        segments.clear(); partial = ""; showIdle()
        if (kept.isNotEmpty()) {
            undo = kept; undoBar.visibility = View.VISIBLE
            main.removeCallbacks(hideUndo); main.postDelayed(hideUndo, 3000)
        }
        onIdle?.invoke()
    }

    private fun undoDiscard() {
        val kept = undo ?: return
        main.removeCallbacks(hideUndo); undo = null; undoBar.visibility = View.GONE
        loadPaused(kept)
    }

    private fun deliver() {
        val said = segments.toList()
        lastTurnUnsure = minConfidence < UNSURE
        inTurn = false; segments.clear(); partial = ""
        showIdle()
        setSinhala(false)
        if (said.isEmpty()) { Ui.toast(a, "I didn't hear anything. Tap the mic and try again."); onIdle?.invoke(); return }
        onSend(said)
    }

    private val silenceCheck = object : Runnable {
        override fun run() {
            if (!recording || finishing || readyPending) return
            val quiet = SystemClock.elapsedRealtime() - lastVoiceAt
            if (autoSendMs > 0 && !sinhala && quiet >= autoSendMs && (segments.isNotEmpty() || partial.isNotBlank())) { send(); return }
            if (autoSendMs == 0L && !recordNudge && quiet >= 6000) {
                recordNudge = true
                showNudge("Take your time. Keep going, or tap සිං and say it in Sinhala.")
            }
            main.postDelayed(this, if (autoSendMs > 0) 400 else 1000)
        }
    }

    private fun heard() {
        lastVoiceAt = SystemClock.elapsedRealtime()
        if (recordNudge) { recordNudge = false; hideNudge() }
    }

    override fun onListening() {
        if (switchPending || readyPending) {
            val first = readyPending
            switchPending = false; readyPending = false
            buzz(); status.visibility = View.GONE; bars.visibility = View.VISIBLE
            lastVoiceAt = SystemClock.elapsedRealtime()
            if (first) { main.removeCallbacks(silenceCheck); main.postDelayed(silenceCheck, 1000) }
        }
    }
    override fun onPartial(text: String) { if (text.isNotBlank() && text != partial) heard(); partial = text; refreshLive() }
    override fun onSegment(text: String, sinhala: Boolean) { segments.add(text to sinhala); partial = ""; heard(); refreshLive() }
    override fun onConfidence(score: Float) { if (!sinhala) minConfidence = minOf(minConfidence, score) }
    override fun onLevel(rmsDb: Float) { bars.push(rmsDb); if (rmsDb > 4f) heard() }
    override fun onProblem(message: String) = Ui.toast(a, message)
    override fun onFinished() {
        recording = false; finishing = false
        main.removeCallbacks(silenceCheck)
        if (partial.isNotBlank()) { segments.add(partial to sinhala); partial = "" }
        if (sendAtEnd) deliver() else showPaused()
    }

    private fun buzz() = runCatching {
        a.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun setSinhala(on: Boolean) {
        sinhala = on
        enSide.background = if (!on) Ui.rounded(a, Ui.teal, 22) else null
        enSide.setTextColor(if (!on) Color.WHITE else Ui.muted)
        siSide.background = if (on) Ui.rounded(a, Ui.orange, 22) else Ui.rounded(a, Ui.orangeLight, 22)
        siSide.setTextColor(if (on) Color.WHITE else Ui.orangeDark)
        bars.color = if (on) Ui.orange else Ui.bars
    }

    private fun showIdleOrPaused() { if (inTurn && !recording) showPaused() else if (!inTurn) showIdle() }

    private fun showIdle() {
        live.visibility = View.GONE; discard.visibility = View.GONE
        bars.visibility = View.GONE; bars.reset(); status.visibility = View.GONE
        val hasCall = callBox.childCount > 0
        callBox.visibility = if (hasCall) View.VISIBLE else View.GONE
        spacer.visibility = if (hasCall) View.GONE else View.VISIBLE
        pauseBox.visibility = View.GONE; mic.visibility = View.VISIBLE; sendBox.visibility = View.GONE
    }

    private fun showRecording() {
        live.visibility = if (showWords) View.VISIBLE else View.GONE; discard.visibility = View.VISIBLE
        spacer.visibility = View.GONE; callBox.visibility = View.GONE
        bars.visibility = View.GONE
        status.text = if (sinhala) "Getting ready (Sinhala)…" else "Getting ready…"
        status.setTextColor(if (sinhala) Ui.orange else Ui.teal); status.visibility = View.VISIBLE
        pauseBox.visibility = View.VISIBLE; mic.visibility = View.GONE; sendBox.visibility = View.VISIBLE
    }

    private fun showPaused() {
        live.visibility = if (showWords) View.VISIBLE else View.GONE; discard.visibility = View.VISIBLE
        spacer.visibility = View.GONE; callBox.visibility = View.GONE
        bars.visibility = View.GONE
        status.text = "Paused · tap the mic to go on"; status.setTextColor(Ui.muted); status.visibility = View.VISIBLE
        pauseBox.visibility = View.GONE; mic.visibility = View.VISIBLE; sendBox.visibility = View.VISIBLE
        refreshLive()
    }

    private fun refreshLive() {
        val sb = SpannableStringBuilder()
        for ((t, si) in segments) {
            if (sb.isNotEmpty()) sb.append(" ")
            val s = sb.length; sb.append(t)
            if (si) { sb.setSpan(BackgroundColorSpan(Ui.orangeLight), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(Ui.orangeDark), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        if (partial.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(" "); sb.append(partial) }
        val shown: CharSequence = if (sb.length > 260) SpannableStringBuilder("…").append(sb.subSequence(sb.length - 260, sb.length)) else sb
        live.text = if (sb.isEmpty()) "Say something…" else shown
    }

    companion object {
        /** A line in his language before Android's own microphone question (GPT review, item 3). */
        fun askMic(a: Activity) {
            android.app.AlertDialog.Builder(a)
                .setTitle("Your microphone")
                .setMessage("Next, your phone asks to use the microphone. Tap Allow so Sam and Maya can hear you.\n\n" +
                    "ඊළඟට ඔබේ දුරකථනය මයික්‍රෆෝනය භාවිතා කිරීමට අවසර ඉල්ලයි. Sam සහ Maya ට ඔබව ඇහෙන්න Allow ඔබන්න.")
                .setPositiveButton("OK") { _, _ -> a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
                .show()
        }

        /** Below this Google confidence, Sam/Maya may ask what he meant (QA item 4, option C). */
        const val UNSURE = 0.55f
        /** "[Sinhala: …]" around Sinhala parts, as the AI expects. */
        fun forAi(said: List<Pair<String, Boolean>>) = said.joinToString(" ") { (t, si) -> if (si) "[Sinhala: $t]" else t }
        fun plain(said: List<Pair<String, Boolean>>) = said.joinToString(" ") { it.first }
        fun hasSinhala(said: List<Pair<String, Boolean>>) = said.any { it.second }
        val clock: Long get() = SystemClock.elapsedRealtime()
    }
}
