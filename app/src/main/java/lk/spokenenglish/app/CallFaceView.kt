package lk.spokenenglish.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The video-call picture (1.1 finding 13): Sam or Maya filling the screen, drawn by the app (free, offline,
 * no AI quota). A room behind, hair, shoulders; blinks, lips that move with the voice, moods, a laugh
 * and small nods while listening.
 */
class CallFaceView(ctx: Context) : View(ctx) {

    var character: Character = Characters.MAYA
        set(value) { field = value; invalidate() }
    var mood: String = "happy"
        set(value) { field = value; invalidate() }
    var talking = false
        set(value) { field = value; if (!value) mouth = 0f; invalidate() }
    var laughing = false
        set(value) { field = value; laughStart = SystemClock.uptimeMillis(); invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val h = Handler(Looper.getMainLooper())
    private var mouth = 0f
    private var blink = false
    private var nextBlink = SystemClock.uptimeMillis() + 2500
    private var laughStart = 0L
    private val start = SystemClock.uptimeMillis()

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (talking) mouth = (mouth * 0.4f + Random.nextFloat() * 0.6f).coerceIn(0.05f, 1f)
            if (now >= nextBlink) { blink = true; h.postDelayed({ blink = false; invalidate() }, 130); nextBlink = now + 2200 + Random.nextLong(2800) }
            invalidate()
            h.postDelayed(this, 70)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); h.post(tick) }
    override fun onDetachedFromWindow() { h.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val hh = height.toFloat()
        val male = character.male
        // room
        p.shader = LinearGradient(0f, 0f, 0f, hh, Color.parseColor("#3D4F5C"), Color.parseColor("#22303A"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, hh, p); p.shader = null
        p.color = Color.parseColor("#465966"); c.drawRect(0f, 0f, w, hh * 0.55f, p)
        p.color = Color.parseColor("#5F7380"); c.drawRect(w * 0.07f, hh * 0.08f, w * 0.33f, hh * 0.26f, p)
        p.shader = LinearGradient(0f, hh * 0.09f, 0f, hh * 0.25f, Color.parseColor("#A9C6D6"), Color.parseColor("#D7E6EE"), Shader.TileMode.CLAMP)
        c.drawRect(w * 0.085f, hh * 0.095f, w * 0.315f, hh * 0.245f, p); p.shader = null
        p.color = Color.parseColor("#6B5643"); c.drawRect(w * 0.70f, hh * 0.16f, w * 0.93f, hh * 0.17f, p)
        p.color = Color.parseColor("#4F7A52"); c.drawOval(RectF(w * 0.75f, hh * 0.115f, w * 0.85f, hh * 0.165f), p)

        // person, with a small nod / laugh bob
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val laughT = (SystemClock.uptimeMillis() - laughStart) / 1000f
        val bob = when {
            laughing -> abs(sin(laughT * 2f * Math.PI.toFloat() * 3.2f)) * hh * 0.012f
            !talking -> sin(t * 1.3f) * hh * 0.004f
            else -> sin(t * 2.4f) * hh * 0.003f
        }
        val cx = w / 2f
        val headW = w * 0.48f; val headH = headW * 1.3f
        val headTop = hh * 0.26f + bob
        val hairC = character.hair; val skin = character.skin
        // hair at the back
        p.color = hairC
        if (!male) c.drawRoundRect(RectF(cx - headW * 0.62f, headTop - headH * 0.08f, cx + headW * 0.62f, headTop + headH * 1.35f), headW * 0.5f, headW * 0.35f, p)
        // shoulders and neck
        p.color = character.shirt
        c.drawRoundRect(RectF(cx - w * 0.40f, headTop + headH * 1.05f, cx + w * 0.40f, hh + w * 0.3f), w * 0.36f, w * 0.36f, p)
        p.color = darker(skin, 0.9f)
        c.drawRoundRect(RectF(cx - headW * 0.17f, headTop + headH * 0.8f, cx + headW * 0.17f, headTop + headH * 1.15f), headW * 0.1f, headW * 0.1f, p)
        // head
        p.color = skin
        c.drawOval(RectF(cx - headW / 2f, headTop, cx + headW / 2f, headTop + headH), p)
        // hair on top
        p.color = hairC
        val top = Path()
        if (male) {
            top.addArc(RectF(cx - headW * 0.53f, headTop - headH * 0.06f, cx + headW * 0.53f, headTop + headH * 0.55f), 180f, 180f)
            c.drawPath(top, p)
            c.drawRect(cx - headW * 0.53f, headTop + headH * 0.2f, cx - headW * 0.42f, headTop + headH * 0.42f, p)
            c.drawRect(cx + headW * 0.42f, headTop + headH * 0.2f, cx + headW * 0.53f, headTop + headH * 0.42f, p)
        } else {
            top.addArc(RectF(cx - headW * 0.56f, headTop - headH * 0.05f, cx + headW * 0.56f, headTop + headH * 0.6f), 180f, 180f)
            c.drawPath(top, p)
            c.drawRect(cx - headW * 0.56f, headTop + headH * 0.25f, cx - headW * 0.44f, headTop + headH * 0.95f, p)
            c.drawRect(cx + headW * 0.44f, headTop + headH * 0.25f, cx + headW * 0.56f, headTop + headH * 0.95f, p)
        }
        // face features
        val eyeY = headTop + headH * 0.47f
        val eyeDx = headW * 0.19f
        val eyeW = headW * 0.15f; val eyeH = headW * 0.075f
        val squint = laughing
        // brows
        p.color = hairC; p.strokeWidth = headW * 0.03f; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        val browLift = when (mood) { "surprised" -> -headH * 0.04f; "sad" -> -headH * 0.01f; "annoyed" -> headH * 0.015f; else -> 0f }
        val tilt = when (mood) { "sad" -> -headH * 0.02f; "annoyed" -> headH * 0.02f; "thinking" -> headH * 0.01f; else -> 0f }
        c.drawLine(cx - eyeDx - eyeW * 0.7f, eyeY - headH * 0.08f + browLift + tilt, cx - eyeDx + eyeW * 0.7f, eyeY - headH * 0.09f + browLift - tilt, p)
        c.drawLine(cx + eyeDx - eyeW * 0.7f, eyeY - headH * 0.09f + browLift - tilt, cx + eyeDx + eyeW * 0.7f, eyeY - headH * 0.08f + browLift + tilt, p)
        // eyes
        for (sx in listOf(-1f, 1f)) {
            val ex = cx + sx * eyeDx
            if (blink || squint) {
                p.style = Paint.Style.STROKE; p.color = Color.parseColor("#2A1A10"); p.strokeWidth = headW * 0.025f
                c.drawArc(RectF(ex - eyeW / 2f, eyeY - eyeH * 0.6f, ex + eyeW / 2f, eyeY + eyeH * 0.6f), if (squint) 200f else 10f, 140f, false, p)
            } else {
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                c.drawOval(RectF(ex - eyeW / 2f, eyeY - eyeH / 2f, ex + eyeW / 2f, eyeY + eyeH / 2f), p)
                p.color = Color.parseColor("#2A1A10")
                val look = if (mood == "thinking") eyeW * 0.15f else 0f
                c.drawCircle(ex + look, eyeY - if (mood == "thinking") eyeH * 0.15f else 0f, eyeH * 0.42f, p)
            }
        }
        p.style = Paint.Style.FILL
        // nose and cheeks
        p.color = darker(skin, 0.82f); p.style = Paint.Style.STROKE; p.strokeWidth = headW * 0.018f
        c.drawArc(RectF(cx - headW * 0.05f, eyeY + headH * 0.08f, cx + headW * 0.05f, eyeY + headH * 0.16f), 20f, 140f, false, p)
        p.style = Paint.Style.FILL; p.color = Color.argb(70, 217, 120, 95)
        c.drawOval(RectF(cx - eyeDx - headW * 0.09f, eyeY + headH * 0.12f, cx - eyeDx + headW * 0.06f, eyeY + headH * 0.17f), p)
        c.drawOval(RectF(cx + eyeDx - headW * 0.06f, eyeY + headH * 0.12f, cx + eyeDx + headW * 0.09f, eyeY + headH * 0.17f), p)
        // mouth
        val my = eyeY + headH * 0.25f
        val mw = headW * (if (laughing) 0.3f else 0.24f)
        val open = when { laughing -> 0.9f; talking -> mouth; mood == "surprised" -> 0.5f; else -> 0f }
        if (open > 0.06f) {
            p.color = Color.parseColor("#7A2E35")
            val mh = headH * 0.02f + headH * 0.09f * open
            c.drawRoundRect(RectF(cx - mw / 2f, my - mh * 0.25f, cx + mw / 2f, my + mh), mw * 0.4f, mh * 0.6f, p)
            p.color = Color.WHITE
            c.drawRect(cx - mw * 0.35f, my - mh * 0.2f, cx + mw * 0.35f, my + mh * 0.12f, p)
        } else {
            p.style = Paint.Style.STROKE; p.color = Color.parseColor("#7A2E35"); p.strokeWidth = headW * 0.025f
            val curve = when (mood) { "happy" -> headH * 0.05f; "sad" -> -headH * 0.03f; "annoyed" -> -headH * 0.01f; else -> headH * 0.02f }
            val path = Path(); path.moveTo(cx - mw / 2f, my); path.quadTo(cx, my + curve, cx + mw / 2f, my)
            c.drawPath(path, p); p.style = Paint.Style.FILL
        }
    }

    private fun darker(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
}
