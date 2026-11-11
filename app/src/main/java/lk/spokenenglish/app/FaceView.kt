package lk.spokenenglish.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.min
import kotlin.random.Random

/**
 * A simple drawn face. The mood comes from the AI's reply; the mouth moves while the voice speaks.
 * Moods: happy, surprised, sad, annoyed, thinking, neutral.
 */
class FaceView(ctx: Context) : View(ctx) {

    var character: Character = Characters.SAM
        set(value) { field = value; invalidate() }

    var mood: String = "neutral"
        set(value) {
            field = if (value in MOODS) value else "neutral"
            invalidate()
        }

    var talking: Boolean = false
        set(value) {
            field = value
            ticker.removeCallbacks(mouthTick)
            if (value) ticker.post(mouthTick) else { mouthOpen = 0f; invalidate() }
        }

    private var mouthOpen = 0f
    private var blinking = false
    private val ticker = Handler(Looper.getMainLooper())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2A1B14")
    }
    private val path = Path()

    private val mouthTick = object : Runnable {
        override fun run() {
            if (!talking) return
            mouthOpen = 0.25f + Random.nextFloat() * 0.75f
            invalidate()
            ticker.postDelayed(this, 110)
        }
    }

    private val blinkTick = object : Runnable {
        override fun run() {
            blinking = true
            invalidate()
            ticker.postDelayed({ blinking = false; invalidate() }, 140)
            ticker.postDelayed(this, 2500L + Random.nextLong(3000))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.postDelayed(blinkTick, 2000)
    }

    override fun onDetachedFromWindow() {
        ticker.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.46f
        val r = min(w, h) * 0.30f
        val ch = character

        // Soft background circle
        fill.color = Color.parseColor("#E3F1EF")
        c.drawCircle(cx, h / 2f, min(w, h) * 0.48f, fill)

        // Long hair behind the head
        if (ch.longHair) {
            fill.color = ch.hair
            c.drawRoundRect(cx - r * 1.12f, cy - r * 1.05f, cx + r * 1.12f, cy + r * 1.35f, r * 0.55f, r * 0.55f, fill)
        }

        // Shoulders
        fill.color = ch.shirt
        c.drawOval(cx - r * 1.35f, cy + r * 0.85f, cx + r * 1.35f, cy + r * 2.4f, fill)

        // Head
        fill.color = ch.skin
        c.drawCircle(cx, cy, r, fill)

        // Hair on top
        fill.color = ch.hair
        c.drawArc(cx - r * 1.04f, cy - r * 1.1f, cx + r * 1.04f, cy + r * 0.55f, 180f, 180f, true, fill)

        // Eyes
        val eyeY = cy + r * 0.02f
        val eyeDx = r * 0.36f
        if (blinking) {
            line.strokeWidth = r * 0.05f
            c.drawLine(cx - eyeDx - r * 0.08f, eyeY, cx - eyeDx + r * 0.08f, eyeY, line)
            c.drawLine(cx + eyeDx - r * 0.08f, eyeY, cx + eyeDx + r * 0.08f, eyeY, line)
        } else {
            fill.color = Color.parseColor("#2A1B14")
            val eyeR = if (mood == "surprised") r * 0.1f else r * 0.08f
            c.drawCircle(cx - eyeDx, eyeY, eyeR, fill)
            c.drawCircle(cx + eyeDx, eyeY, eyeR, fill)
        }

        // Eyebrows: (inner, outer) vertical offsets as a fraction of r; negative = higher
        val (leftIn, leftOut, rightIn, rightOut) = when (mood) {
            "surprised" -> listOf(-0.10f, -0.10f, -0.10f, -0.10f)
            "annoyed" -> listOf(0.06f, -0.04f, 0.06f, -0.04f)
            "sad" -> listOf(-0.07f, 0.04f, -0.07f, 0.04f)
            "thinking" -> listOf(-0.09f, -0.09f, 0.0f, 0.0f)
            else -> listOf(0.0f, -0.01f, 0.0f, -0.01f)
        }
        val browY = cy - r * 0.18f
        line.strokeWidth = r * 0.06f
        c.drawLine(cx - eyeDx - r * 0.14f, browY + r * leftOut, cx - eyeDx + r * 0.12f, browY + r * leftIn, line)
        c.drawLine(cx + eyeDx - r * 0.12f, browY + r * rightIn, cx + eyeDx + r * 0.14f, browY + r * rightOut, line)

        // Mouth
        val my = cy + r * 0.45f
        line.strokeWidth = r * 0.06f
        if (talking) {
            fill.color = Color.parseColor("#5A1E1E")
            val mh = r * (0.06f + 0.22f * mouthOpen)
            c.drawOval(cx - r * 0.22f, my - mh / 2f, cx + r * 0.22f, my + mh / 2f, fill)
        } else {
            path.reset()
            when (mood) {
                "happy" -> { path.moveTo(cx - r * 0.3f, my - r * 0.03f); path.quadTo(cx, my + r * 0.28f, cx + r * 0.3f, my - r * 0.03f); c.drawPath(path, line) }
                "sad" -> { path.moveTo(cx - r * 0.25f, my + r * 0.08f); path.quadTo(cx, my - r * 0.14f, cx + r * 0.25f, my + r * 0.08f); c.drawPath(path, line) }
                "surprised" -> { fill.color = Color.parseColor("#5A1E1E"); c.drawOval(cx - r * 0.1f, my - r * 0.12f, cx + r * 0.1f, my + r * 0.12f, fill) }
                "annoyed" -> c.drawLine(cx - r * 0.2f, my + r * 0.02f, cx + r * 0.2f, my + r * 0.02f, line)
                "thinking" -> c.drawLine(cx - r * 0.12f, my + r * 0.03f, cx + r * 0.2f, my - r * 0.04f, line)
                else -> { path.moveTo(cx - r * 0.24f, my); path.quadTo(cx, my + r * 0.1f, cx + r * 0.24f, my); c.drawPath(path, line) }
            }
        }
    }

    companion object {
        val MOODS = setOf("happy", "surprised", "sad", "annoyed", "thinking", "neutral")
    }
}
