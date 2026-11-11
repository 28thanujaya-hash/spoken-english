package lk.spokenenglish.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** A dense row of thin scrolling sound bars while he talks (like Claude's voice input). */
class SoundBarsView(ctx: Context) : View(ctx) {

    private val levels = FloatArray(44)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3C8F80") }

    /** Teal for English, orange while the Sinhala button is held. */
    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    /** rmsDb from the speech recogniser, roughly -2 (quiet) to 10 (loud). */
    fun push(rmsDb: Float) {
        val v = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = v
        invalidate()
    }

    /** A still "voice message" waveform (drawing: ▶ ılıllı 0:04), different for each seed. */
    fun still(seed: Int) {
        val r = java.util.Random(seed.toLong())
        for (i in levels.indices) levels[i] = if (i < levels.size * 3 / 4) 0.2f + r.nextFloat() * 0.75f else 0.08f
        invalidate()
    }

    fun reset() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = levels.size
        val slot = width / n.toFloat()
        val bw = max(2f, slot * 0.42f)
        for (i in 0 until n) {
            val bh = max(height * 0.10f, levels[i] * height)
            val x = i * slot + (slot - bw) / 2f
            val top = (height - bh) / 2f
            c.drawRoundRect(x, top, x + bw, top + bh, bw / 2f, bw / 2f, paint)
        }
    }
}
