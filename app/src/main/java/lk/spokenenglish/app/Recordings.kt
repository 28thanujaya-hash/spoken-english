package lk.spokenenglish.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * His voice in the daily review recording (QA 11 Nov): saved on the phone only (folder "recordings"),
 * turned into words by Gemini, played back for "Then vs now" and "Compared with yesterday", and attached to
 * the mentor report only when he taps Send report. Google's speech recognition can't keep the sound, so this
 * screen uses a normal recorder instead.
 */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null
        private set

    fun start(name: String): Boolean = try {
        val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
        val f = File(dir, "$name.aac")
        @Suppress("DEPRECATION")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000)
        r.setAudioEncodingBitRate(32000)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        rec = r; file = f
        true
    } catch (e: Exception) { rec = null; false }

    fun pause() = runCatching { rec?.pause() }
    fun resume() = runCatching { rec?.resume() }

    /** 0..1 loudness for the sound bars. */
    fun level(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 12000f }.getOrDefault(0f).coerceIn(0f, 1f)

    fun stop(): File? {
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        rec = null
        return file
    }

    companion object {
        fun base64(f: File): String = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)

        /** Plays a recording; returns the player so it can be stopped. */
        fun play(f: File, done: () -> Unit = {}): MediaPlayer? = runCatching {
            MediaPlayer().apply { setDataSource(f.absolutePath); setOnCompletionListener { done(); it.release() }; prepare(); start() }
        }.getOrNull()

        /** Every saved review recording, oldest first: {topic, file, at, words, pauses, connectors, follow}. */
        fun all(p: Progress): List<JSONObject> = p.list("recordings").filter { File(it.optString("file")).exists() }
    }
}

/** Hands a recording to WhatsApp (read-only, only the files in "recordings"). */
class RecordingsProvider : ContentProvider() {
    override fun onCreate() = true
    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val f = File(File(context!!.filesDir, "recordings"), name)
        return if (f.exists() && f.parentFile?.name == "recordings") f else null
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        fileFor(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun getType(uri: Uri) = "audio/aac"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { if (f != null) addRow(arrayOf(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun uri(f: File): Uri = Uri.parse("content://lk.spokenenglish.app.recordings/${f.name}")
    }
}
