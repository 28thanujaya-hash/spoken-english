package lk.spokenenglish.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.sqrt

/**
 * Shake the phone on any screen = report a problem, a lesson mistake or an idea to the mentor on WhatsApp.
 * He picks what it is, says it by voice (English, Sinhala or both), listens back, and sends it.
 * Sent with it: the voice message, plus week/day/screen and the app version as text.
 */
class ShakeReport(private val a: Activity, private val where: () -> String) : SensorEventListener {

    private val sensors = a.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastShake = 0L
    private var showing = false

    fun start() { sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = SystemClock.elapsedRealtime()
        if (g > 2.7f && now - lastShake > 2500 && !showing) { lastShake = now; open() }
    }
    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    fun open() {
        if (showing) return
        showing = true
        val c = a
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0) }
        box.addView(Ui.label(c, "1. What is it?", 14f, Ui.muted, true))
        val kinds = listOf("Not working", "Lesson mistake", "An idea")
        var kind = kinds[0]
        val tiles = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val tileViews = mutableListOf<TextView>()
        fun paint() = tileViews.forEach { t ->
            val on = t.text == kind
            t.background = Ui.rounded(c, if (on) Ui.tealLight else android.graphics.Color.WHITE, 12, if (on) Ui.teal else Ui.line, if (on) 2 else 1)
        }
        kinds.forEach { k ->
            val t = Ui.label(c, k, 13.5f, Ui.ink, true).apply {
                gravity = Gravity.CENTER; setPadding(Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12))
                setOnClickListener { Ui.tap(it); kind = k; paint() }
            }
            tileViews.add(t)
            tiles.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(c, 6) })
        }
        paint()
        box.addView(tiles, Ui.lp(c, 6))
        box.addView(Ui.label(c, "2. Tell us (English, Sinhala or both)", 14f, Ui.muted, true), Ui.lp(c, 14))

        val file = File(c.cacheDir, "report.m4a")
        var recorder: MediaRecorder? = null
        var startedAt = 0L
        val state = Ui.label(c, "Tap ● to record your message.", 14f, Ui.ink)
        box.addView(state, Ui.lp(c, 6))
        val rec = Ui.button(c, "●  Record") {}
        val play = Ui.link(c, "▶  Listen to it") {}
        play.visibility = android.view.View.GONE
        rec.setOnClickListener {
            Ui.tap(it)
            if (recorder == null) {
                if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    c.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return@setOnClickListener
                }
                try {
                    @Suppress("DEPRECATION")
                    val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
                    r.setAudioSource(MediaRecorder.AudioSource.MIC)
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setOutputFile(file.absolutePath)
                    r.prepare(); r.start()
                    recorder = r; startedAt = SystemClock.elapsedRealtime()
                    rec.text = "■  Stop"; state.text = "Listening… speak now."; play.visibility = android.view.View.GONE
                } catch (e: Exception) { state.text = "The microphone couldn't start." }
            } else {
                runCatching { recorder?.stop() }; recorder?.release(); recorder = null
                val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
                rec.text = "●  Say it again"; state.text = "Your message, 0:%02d".format(s); play.visibility = android.view.View.VISIBLE
            }
        }
        play.setOnClickListener {
            Ui.tap(it)
            runCatching { MediaPlayer().apply { setDataSource(file.absolutePath); prepare(); start(); setOnCompletionListener { p -> p.release() } } }
        }
        box.addView(rec, Ui.lp(c, 8))
        box.addView(play, Ui.lp(c, 2, match = false))

        AlertDialog.Builder(c)
            .setTitle("Problem or idea?")
            .setView(box)
            .setPositiveButton("Send on WhatsApp") { _, _ ->
                runCatching { recorder?.stop() }; recorder?.release(); recorder = null
                send(kind, if (file.exists() && file.length() > 0) file else null)
            }
            .setNegativeButton("Cancel") { _, _ -> runCatching { recorder?.stop() }; recorder?.release(); recorder = null }
            .setOnDismissListener { showing = false }
            .show()
    }

    private fun send(kind: String, audio: File?) {
        val text = "Spoken English app · $kind\nWhere: ${where()}\nApp version: ${BuildConfig.VERSION_NAME}" +
            (if (audio == null) "\n(no voice message)" else "\n(voice message attached)")
        val uri: Uri? = if (audio != null && Build.VERSION.SDK_INT >= 29) runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "report-${System.currentTimeMillis()}.m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SpokenEnglish")
            }
            val u = a.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            a.contentResolver.openOutputStream(u)!!.use { out -> audio.inputStream().use { it.copyTo(out) } }
            u
        }.getOrNull() else null
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (uri != null) { type = "audio/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            else type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("com.whatsapp")
        }
        try { a.startActivity(intent) } catch (e: Exception) {
            intent.setPackage(null)
            runCatching { a.startActivity(Intent.createChooser(intent, "Send to your mentor")) }
        }
        AlertDialog.Builder(a).setTitle("✓  Sent, thank you!").setMessage("Your mentor will get it on WhatsApp.").setPositiveButton("OK", null).show()
    }
}
