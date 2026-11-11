package lk.spokenenglish.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import kotlin.math.min

enum class Glyph { MIC, PAUSE, SEND, CLOSE }

/** Draws one icon centred in a box of size s at (x, y). */
object GlyphPainter {
    private val path = Path()

    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (g) {
            Glyph.MIC -> {
                p.style = Paint.Style.FILL
                val w = s * 0.30f
                c.drawRoundRect(cx - w / 2, cy - s * 0.42f, cx + w / 2, cy + s * 0.10f, w / 2, w / 2, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.08f
                c.drawArc(RectF(cx - s * 0.28f, cy - s * 0.25f, cx + s * 0.28f, cy + s * 0.27f), 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.27f, cx, cy + s * 0.42f, p)
            }
            Glyph.PAUSE -> {
                p.style = Paint.Style.FILL
                val h = s * 0.36f
                c.drawRoundRect(cx - h, cy - h, cx + h, cy + h, s * 0.08f, s * 0.08f, p)
            }
            Glyph.SEND -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.11f
                c.drawLine(cx, cy + s * 0.36f, cx, cy - s * 0.34f, p)
                path.reset()
                path.moveTo(cx - s * 0.30f, cy - s * 0.04f)
                path.lineTo(cx, cy - s * 0.36f)
                path.lineTo(cx + s * 0.30f, cy - s * 0.04f)
                c.drawPath(path, p)
            }
            Glyph.CLOSE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.09f
                val d = s * 0.28f
                c.drawLine(cx - d, cy - d, cx + d, cy + d, p)
                c.drawLine(cx + d, cy - d, cx - d, cy + d, p)
            }
        }
    }
}

/** A round button with a drawn icon; shows a ripple the moment it is touched. */
class IconButton(ctx: Context, glyph: Glyph, fill: Int, iconColor: Int, private val stroke: Int? = null) : View(ctx) {

    var glyph: Glyph = glyph
        set(value) { field = value; invalidate() }
    var fill: Int = fill
        set(value) { field = value; invalidate() }
    var iconColor: Int = iconColor
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        foreground = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#55000000")), null, mask)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val r = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawCircle(cx, cy, r, paint)
        if (stroke != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.06f
            paint.color = stroke
            c.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
        }
        GlyphPainter.draw(c, glyph, cx, cy, r * 1.0f, iconColor)
    }
}

/** Small round mic picture used inside the instruction line. */
class MicPicture(private val sizePx: Int, private val fill: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(c: Canvas) {
        val b = bounds
        val r = min(b.width(), b.height()) / 2f
        paint.color = fill
        c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint)
        GlyphPainter.draw(c, Glyph.MIC, b.exactCenterX(), b.exactCenterY(), r, Color.WHITE)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Small "සිං" chip picture used inside the instruction line (looks like the real button). */
class SinhalaPicture(private val heightPx: Int, private val fill: Int, private val border: Int, private val textColor: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = heightPx * 0.58f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val widthPx = (text.measureText("සිං") + heightPx * 0.8f).toInt()
    override fun getIntrinsicWidth() = widthPx
    override fun getIntrinsicHeight() = heightPx
    override fun draw(c: Canvas) {
        val b = RectF(bounds)
        val rad = b.height() / 2f
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawRoundRect(b, rad, rad, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = heightPx * 0.06f
        paint.color = border
        c.drawRoundRect(b, rad, rad, paint)
        val y = b.centerY() - (text.descent() + text.ascent()) / 2f
        c.drawText("සිං", b.centerX(), y, text)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
