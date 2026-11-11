package lk.spokenenglish.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** Moving sound bars while he talks (like Claude's voice mode). */
class SoundBarsView(ctx: Context) : View(ctx) {

    private val levels = FloatArray(24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F6B6B") }

    /** rmsDb from the speech recogniser, roughly -2 (quiet) to 10 (loud). */
    fun push(rmsDb: Float) {
        val v = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = v
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
        val bw = slot * 0.5f
        for (i in 0 until n) {
            val bh = max(height * 0.14f, levels[i] * height)
            val x = i * slot + (slot - bw) / 2f
            val top = (height - bh) / 2f
            c.drawRoundRect(x, top, x + bw, top + bh, bw / 2f, bw / 2f, paint)
        }
    }
}
