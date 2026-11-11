package lk.spokenenglish.app

import android.Manifest
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
 * The talk bar used on lesson screens (same rules as the conversation): EN | සිං one-tap switch, mic,
 * ■ pause (mic carries on after the old words), ↑ send, ✕ discard. His words appear live in grey italics and
 * can never be edited. onSend gets the pieces he said (text, was it Sinhala).
 */
class TalkBar(private val a: Activity, private val onSend: (List<Pair<String, Boolean>>) -> Unit) : SpeechLoop.Listener {

    val view: LinearLayout
    private val live: TextView
    private val discard: TextView
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

    /** Called just before the mic opens (e.g. to stop the partner's voice). */
    var onStart: (() -> Unit)? = null

    var enabled = true
        set(v) { field = v; view.alpha = if (v) 1f else 0.4f }

    init {
        val c = a
        view = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 12)) }
        live = Ui.label(c, "", 15.5f, Color.parseColor("#8A909C")).apply {
            setTypeface(typeface, Typeface.ITALIC); visibility = View.GONE; setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 6))
        }
        view.addView(live)
        discard = Ui.label(c, "✕  Discard", 13f, Ui.muted, true).apply {
            background = Ui.rounded(c, Color.WHITE, 14, Ui.line); setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6))
            visibility = View.GONE
            setOnClickListener { Ui.tap(it); discardTurn() }
        }
        view.addView(discard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(c, 8); marginStart = Ui.dp(c, 6) })

        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(c, Color.WHITE, 36, Ui.line); elevation = Ui.dp(c, 3).toFloat()
            setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8)); minimumHeight = Ui.dp(c, 72)
        }
        val sw = LinearLayout(c).apply { background = Ui.rounded(c, Color.WHITE, 24, Ui.orange, 2); setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2)) }
        enSide = Ui.label(c, "EN", 15f, Ui.muted, true).apply { gravity = Gravity.CENTER; setOnClickListener { Ui.tap(it); switchTo(false) } }
        siSide = Ui.label(c, "සිං", 19f, Ui.orangeDark, true).apply { gravity = Gravity.CENTER; setOnClickListener { Ui.tap(it); switchTo(true) } }
        sw.addView(enSide, LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 46)))
        sw.addView(siSide, LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 46)))
        pill.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(c, 10) })

        val middle = FrameLayout(c)
        middle.addView(spacer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 34)))
        middle.addView(bars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 34), Gravity.CENTER_VERTICAL))
        status = Ui.label(c, "", 13.5f, Ui.teal, true)
        middle.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        pill.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        pauseBtn = IconButton(c, Glyph.PAUSE, Ui.tealLight, Ui.teal).apply { setOnClickListener { Ui.tap(it); pause() } }
        pauseBox = labelled(pauseBtn, 42, "pause")
        pill.addView(pauseBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 6) })
        mic = IconButton(c, Glyph.MIC, Ui.teal, Color.WHITE).apply { setOnClickListener { Ui.tap(it); micTap() } }
        pill.addView(mic, LinearLayout.LayoutParams(Ui.dp(c, 54), Ui.dp(c, 54)).apply { marginStart = Ui.dp(c, 6) })
        sendBtn = IconButton(c, Glyph.SEND, Ui.orange, Color.WHITE).apply { setOnClickListener { Ui.tap(it); send() } }
        sendBox = labelled(sendBtn, 46, "send")
        pill.addView(sendBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(c, 6) })
        view.addView(pill)
        setSinhala(false)
        showIdle()
    }

    private fun labelled(b: View, size: Int, word: String) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(b, LinearLayout.LayoutParams(Ui.dp(a, size), Ui.dp(a, size)))
        addView(Ui.label(a, word, 11f, Ui.muted, true).apply { gravity = Gravity.CENTER })
    }

    fun destroy() { speech.destroy(); main.removeCallbacksAndMessages(null) }
    fun stopIfRecording() { if (recording) { sendAtEnd = false; finishing = true; speech.stop() } }

    /** Clears everything and goes back to English (after each answer). */
    fun reset() {
        if (recording) speech.cancel()
        recording = false; finishing = false; inTurn = false
        segments.clear(); partial = ""
        setSinhala(false); showIdle()
    }

    private fun micAllowed(): Boolean {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return false
        }
        return true
    }

    private fun micTap() {
        if (!enabled || finishing || recording) return
        if (!inTurn) { segments.clear(); partial = ""; inTurn = true }
        start()
    }

    private fun switchTo(si: Boolean) {
        if (!enabled || finishing) return
        if (!inTurn) {
            setSinhala(si)
            if (si) { segments.clear(); partial = ""; inTurn = true; start() }
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
        onStart?.invoke()
        recording = true; finishing = false; sendAtEnd = false; readyPending = true
        showRecording(); refreshLive()
        speech.start(sinhala)
    }

    private fun pause() { if (recording && !finishing) { finishing = true; sendAtEnd = false; status.text = "Pausing…"; speech.stop() } }

    private fun send() {
        if (!inTurn || finishing) return
        if (recording) { finishing = true; sendAtEnd = true; status.text = "Sending…"; status.visibility = View.VISIBLE; bars.visibility = View.GONE; speech.stop() }
        else deliver()
    }

    private fun discardTurn() {
        if (recording) speech.cancel()
        recording = false; finishing = false; inTurn = false
        segments.clear(); partial = ""; showIdle()
    }

    private fun deliver() {
        val said = segments.toList()
        inTurn = false; segments.clear(); partial = ""
        showIdle()
        setSinhala(false)
        if (said.isEmpty()) { Ui.toast(a, "I didn't hear anything. Tap the mic and try again."); return }
        onSend(said)
    }

    override fun onListening() {
        if (switchPending || readyPending) {
            switchPending = false; readyPending = false
            buzz(); status.visibility = View.GONE; bars.visibility = View.VISIBLE
        }
    }
    override fun onPartial(text: String) { partial = text; refreshLive() }
    override fun onSegment(text: String, sinhala: Boolean) { segments.add(text to sinhala); partial = ""; refreshLive() }
    override fun onLevel(rmsDb: Float) = bars.push(rmsDb)
    override fun onProblem(message: String) = Ui.toast(a, message)
    override fun onFinished() {
        recording = false; finishing = false
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
        bars.color = if (on) Ui.orange else Ui.teal
    }

    private fun showIdle() {
        live.visibility = View.GONE; discard.visibility = View.GONE; spacer.visibility = View.VISIBLE
        bars.visibility = View.GONE; bars.reset(); status.visibility = View.GONE
        pauseBox.visibility = View.GONE; mic.visibility = View.VISIBLE; sendBox.visibility = View.GONE
    }

    private fun showRecording() {
        live.visibility = View.VISIBLE; discard.visibility = View.VISIBLE; spacer.visibility = View.GONE
        bars.visibility = View.GONE
        status.text = if (sinhala) "Getting ready (Sinhala)…" else "Getting ready…"
        status.setTextColor(if (sinhala) Ui.orange else Ui.teal); status.visibility = View.VISIBLE
        pauseBox.visibility = View.VISIBLE; mic.visibility = View.GONE; sendBox.visibility = View.VISIBLE
    }

    private fun showPaused() {
        live.visibility = View.VISIBLE; discard.visibility = View.VISIBLE; spacer.visibility = View.GONE
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
        live.text = if (sb.isEmpty()) "Say something…" else sb
    }

    companion object {
        /** "[Sinhala: …]" around Sinhala parts, as the AI expects. */
        fun forAi(said: List<Pair<String, Boolean>>) = said.joinToString(" ") { (t, si) -> if (si) "[Sinhala: $t]" else t }
        fun plain(said: List<Pair<String, Boolean>>) = said.joinToString(" ") { it.first }
        fun hasSinhala(said: List<Pair<String, Boolean>>) = said.any { it.second }
        val clock: Long get() = SystemClock.elapsedRealtime()
    }
}
