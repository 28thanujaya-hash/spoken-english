package lk.spokenenglish.app

import android.app.Activity
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Run 0 part 2 (mentor only; Test settings → "Paradee voice test"): the stock Paradee voice on this phone.
 * Measures time to first sound and × real time on 1 and 4 threads, plays any line, and sends the numbers to Claude.
 */
class VoiceTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var voiceGroup: RadioGroup
    private val results = StringBuilder()
    @Volatile private var busy = false
    private var speakHandle = 0L
    private var speakThreads = 0

    private val testLines = listOf(
        "Really?",
        "Oh, I didn't know that!",
        "Hi! I'm Sam. Did you see the new Avengers film?",
        "Sorry, could you slow down a bit? I didn't catch the last part.",
        "Hannibal is too scary for me, but Maya loves it. She watches it every weekend.",
        "Well, let me think. I mean, Thor is great, but Iron Man is still my favourite. What about you?",
        "Thenuka and Kavi went to Galle Face for kottu. The bus leaves at 7:45, so don't be late!",
        "Anyway, I'd better go. It was really nice talking to you. See you tomorrow, okay?",
    )

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Trace.init(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(24)) }
        box.addView(Ui.label(this, "Paradee voice test (run 0)", 22f, Ui.ink, bold = true))
        box.addView(Ui.label(this, "The stock voice, before Sam and Maya are trained. Nothing here changes the lessons.", 13f, Ui.muted), Ui.lp(this, 4))
        status = Ui.label(this, VoicePack.summary(this), 14f, Ui.tealText).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = Ui.rounded(this@VoiceTestActivity, Ui.tealLight, 12) }
        box.addView(status, Ui.lp(this, 12))
        box.addView(Ui.button(this, "1. Download voice files (about 25 MB, once)") { download() }, Ui.lp(this, 12))
        box.addView(Ui.button(this, "2. Speed test (about 1 minute)") { speedTest() }, Ui.lp(this, 10))

        voiceGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Man (am_puck)" to 2, "Woman (af_heart)" to 0).forEach { (t, id) ->
            voiceGroup.addView(RadioButton(this).apply { text = t; setTextColor(Ui.ink); this.id = 9100 + id; isChecked = id == 2 })
        }
        box.addView(Ui.label(this, "Listen", 15f, Ui.ink, bold = true), Ui.lp(this, 18))
        box.addView(voiceGroup)
        val input = EditText(this).apply {
            setText(testLines[5]); setTextColor(Ui.ink); setHintTextColor(Ui.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        box.addView(input, Ui.lp(this, 4))
        box.addView(Ui.button(this, "Speak this") { speak(input.text.toString()) }, Ui.lp(this, 8))
        box.addView(Ui.button(this, "Send results to Claude", outline = true) { sendResults() }, Ui.lp(this, 18))
        box.addView(Ui.button(this, "Close", outline = true) { finish() }, Ui.lp(this, 10))
        log = TextView(this).apply { textSize = 11f; setTextColor(Ui.muted); typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        box.addView(log, Ui.lp(this, 16))
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.cream); addView(box) })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (speakHandle != 0L) { val h = speakHandle; speakHandle = 0; thread { Paradee.close(h) } }
    }

    private fun say(s: String) = main.post { status.text = s }
    private fun note(s: String) { synchronized(results) { results.append(s).append('\n') }; main.post { log.append(s + "\n") } }
    private fun voiceId(): Long = (voiceGroup.checkedRadioButtonId - 9100).toLong().coerceAtLeast(0)

    private fun download() {
        if (busy) return; busy = true
        thread {
            val err = VoicePack.download(this) { say(it) }
            if (err != null) { say(err); Trace.w("voicetest", err) }
            busy = false
        }
    }

    /** Engine + dictionary ready? Returns false (and says why) if not. */
    private fun startEngine(): Boolean {
        if (!VoicePack.ready(this)) { say("Download the voice files first (button 1)."); return false }
        val t0 = SystemClock.elapsedRealtime()
        val err = Paradee.start(VoicePack.dir(this))
        if (err != null) { say("Could not start: $err"); note("START FAILED: $err"); Trace.e("voicetest", err); return false }
        if (!startedOnce) {
            startedOnce = true
            note("Engine + dictionary loaded in ${SystemClock.elapsedRealtime() - t0} ms (ONNX Runtime ${Paradee.ortVersion(java.io.File(VoicePack.dir(this), VoicePack.ENGINE).absolutePath)})")
        }
        return true
    }
    private var startedOnce = false

    private fun speedTest() {
        if (busy) return; busy = true
        thread {
            try {
                if (!startEngine()) return@thread
                note("== SPEED TEST · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Runtime.getRuntime().availableProcessors()} cores · app ${BuildConfig.VERSION_NAME} · voice ${Paradee.voices.getOrNull(voiceId().toInt())} ==")
                val v = voiceId()
                for ((threads, spin) in listOf(1 to false, 4 to false, 4 to true)) {
                    val label = "$threads thread${if (threads > 1) "s" else ""}${if (spin) " + spinning" else ""}"
                    say("Speed test: $label…")
                    val t0 = SystemClock.elapsedRealtime()
                    val h = Paradee.open(java.io.File(VoicePack.dir(this), VoicePack.MODEL).absolutePath, threads, spin)
                    if (h == 0L) { note("open failed: ${Paradee.lastError()}"); continue }
                    val load = SystemClock.elapsedRealtime() - t0
                    // the first run after loading is slower (cold); measured on its own
                    val c0 = SystemClock.elapsedRealtime(); Paradee.synth(h, Paradee.ids(Paradee.phonemes("Hello there.").first()), v, 1f)
                    val cold = SystemClock.elapsedRealtime() - c0
                    note("-- $label: model load $load ms, first (cold) line $cold ms")
                    var audio = 0.0; var synthMs = 0.0; var g2pMs = 0.0; val firsts = ArrayList<Long>()
                    for (line in testLines) {
                        val a = SystemClock.elapsedRealtimeNanos()
                        val chunks = Paradee.phonemes(line)
                        val g = (SystemClock.elapsedRealtimeNanos() - a) / 1e6
                        var lineAudio = 0.0; var lineSynth = 0.0; var first = -1L
                        for (ch in chunks) {
                            val s0 = SystemClock.elapsedRealtimeNanos()
                            val w = Paradee.synth(h, Paradee.ids(ch), v, 1f) ?: run { note("synth failed: ${Paradee.lastError()}"); null } ?: continue
                            val ms = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
                            if (first < 0) first = (g + ms).toLong()
                            lineAudio += w.size / Paradee.RATE.toDouble(); lineSynth += ms
                        }
                        audio += lineAudio; synthMs += lineSynth; g2pMs += g; firsts += first
                        note("  %5.2f s audio · words→sounds %4.0f ms · voice %5.0f ms · %5.1f× · first sound %4d ms · %s".format(lineAudio, g, lineSynth, lineAudio / (lineSynth / 1000), first, line.take(40)))
                    }
                    note("  TOTAL $label: %.1f s audio in %.2f s → %.1f× real time · first sound avg %d ms, worst %d ms · words→sounds %.0f ms total".format(
                        audio, synthMs / 1000, audio / (synthMs / 1000), firsts.average().toLong(), firsts.maxOrNull() ?: 0, g2pMs))
                    Paradee.close(h)
                }
                note("Sounds for the name line: ${Paradee.phonemes(testLines[6]).joinToString(" | ")}")
                say("Speed test done. Listen below, then tap “Send results to Claude”.")
            } catch (e: Throwable) {
                note("ERROR: $e"); say("Error: ${e.message}"); Trace.e("voicetest", e.toString())
            } finally { busy = false }
        }
    }

    /** Speaks sentence by sentence: the first sentence plays while the next ones are made (4 threads). */
    private fun speak(text: String) {
        if (busy || text.isBlank()) return; busy = true
        thread {
            var track: AudioTrack? = null
            try {
                if (!startEngine()) return@thread
                if (speakHandle == 0L || speakThreads != 4) {
                    speakHandle = Paradee.open(java.io.File(VoicePack.dir(this), VoicePack.MODEL).absolutePath, 4, false)
                    speakThreads = 4
                    if (speakHandle == 0L) { say("open failed: ${Paradee.lastError()}"); return@thread }
                }
                val v = voiceId()
                val t0 = SystemClock.elapsedRealtime()
                val chunks = Paradee.phonemes(text)
                val q = LinkedBlockingQueue<FloatArray>()
                val end = FloatArray(0)
                thread {
                    for (ch in chunks) { Paradee.synth(speakHandle, Paradee.ids(ch), v, 1f)?.let { q.put(it) } }
                    q.put(end)
                }
                val minBuf = AudioTrack.getMinBufferSize(Paradee.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(Paradee.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(maxOf(minBuf, Paradee.RATE * 4 / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                val gap = FloatArray(Paradee.RATE * 15 / 100)   // short pause between sentences
                track.play()   // a stream track starts sounding as soon as data is written
                var firstAt = -1L; var n = 0
                while (true) {
                    val w = q.take(); if (w === end) break
                    for (i in w.indices) w[i] = w[i].coerceIn(-1f, 1f)
                    if (firstAt < 0) { firstAt = SystemClock.elapsedRealtime() - t0; say("Speaking… (first sound after $firstAt ms)") }
                    if (n > 0) track.write(gap, 0, gap.size, AudioTrack.WRITE_BLOCKING)
                    track.write(w, 0, w.size, AudioTrack.WRITE_BLOCKING)
                    n++
                }
                track.write(FloatArray(Paradee.RATE / 5), 0, Paradee.RATE / 5, AudioTrack.WRITE_BLOCKING)   // let the end play out
                track.stop()
                note("Spoke ($n sentence${if (n == 1) "" else "s"}, voice ${Paradee.voices.getOrNull(v.toInt())}): first sound after $firstAt ms · ${text.take(50)}")
                say("Done. First sound after $firstAt ms.")
            } catch (e: Throwable) {
                note("ERROR (speak): $e"); say("Error: ${e.message}"); Trace.e("voicetest", e.toString())
            } finally { track?.release(); busy = false }
        }
    }

    private fun sendResults() {
        val body = synchronized(results) { results.toString() }
        if (body.isBlank()) { say("Run the speed test first."); return }
        Trace.sendText("Voice test (run 0) · ${Build.MODEL} · ${BuildConfig.VERSION_NAME}", "```\n$body\n```")
        say("Sent ✓ Claude can read it now.")
    }
}
