package lk.spokenenglish.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Colours and building blocks shared by the screens: the WhatsApp-style dark look agreed on 11 Nov
 * (colour map in claude/start-here.md). Names kept from the light version so every screen changed at once.
 */
object Ui {
    val cream = Color.parseColor("#0B141A")        // page background
    val surface = Color.parseColor("#1F2C34")      // cards
    val surface2 = Color.parseColor("#26343C")     // inner dividers, pale fills
    val teal = Color.parseColor("#00A884")         // WhatsApp green: buttons, mic, ticks, links
    val tealDark = Color.parseColor("#005C4B")     // his bubbles, top "Continue" cards
    val tealLight = Color.parseColor("#103A33")    // tip boxes
    val tealText = Color.parseColor("#9DE6D3")     // text on tip boxes
    val tealRow = Color.parseColor("#13302B")      // highlighted row
    val orange = Color.parseColor("#E39A5C")       // Sinhala
    val orangeLight = Color.parseColor("#3A2A1D")
    val orangeDark = Color.parseColor("#FFB27A")
    val orangeLine = Color.parseColor("#6B4A2F")
    val ink = Color.parseColor("#E9EDEF")
    val muted = Color.parseColor("#8696A0")
    val line = Color.parseColor("#2A3942")
    val green = Color.parseColor("#53D18A")
    val greenLight = Color.parseColor("#153B2A")
    val blue = Color.parseColor("#8AB4F8")
    val blueLight = Color.parseColor("#1D2F4A")
    val red = Color.parseColor("#E5484D")
    val callRed = Color.parseColor("#B92525")
    val bars = Color.parseColor("#3C8F80")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun label(c: Context, text: CharSequence, size: Float, color: Int = ink, bold: Boolean = false) = TextView(c).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(c, 2).toFloat(), 1f)
    }

    fun rounded(c: Context, fill: Int, radius: Int, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, radius).toFloat()
        if (stroke != null) setStroke(dp(c, strokeDp), stroke)
    }

    fun ripple(c: Context, bg: android.graphics.drawable.Drawable?, radius: Int, color: Int = Color.parseColor("#33FFFFFF")) =
        RippleDrawable(ColorStateList.valueOf(color), bg, GradientDrawable().apply { cornerRadius = dp(c, radius).toFloat(); setColor(Color.WHITE) })

    fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    /** A big filled button (outline = the drawing's "Back to Home" / "Finish and go Home" style). */
    fun button(c: Context, text: String, fill: Int = teal, textColor: Int = Color.WHITE, outline: Boolean = false, onClick: () -> Unit) =
        label(c, text, 16f, if (outline) teal else textColor, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 52)
            setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10))
            background = ripple(c, if (outline) rounded(c, surface, 16, teal) else rounded(c, fill, 16), 16)
            setOnClickListener { tap(it); onClick() }
        }

    /** A light text button. */
    fun link(c: Context, text: String, color: Int = teal, onClick: () -> Unit) =
        label(c, text, 14.5f, color, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(c, 44)
            setPadding(dp(c, 10), 0, dp(c, 10), 0)
            background = ripple(c, null, 10)
            setOnClickListener { tap(it); onClick() }
        }

    fun card(c: Context, border: Int = line, strokeDp: Int = 1, fill: Int = surface) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, fill, 16, border, strokeDp)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
    }

    /** A small grey caps heading inside a card or above a list ("THE SITUATION", "TODAY'S 4 SESSIONS"). */
    fun caps(c: Context, text: String, color: Int = muted) = label(c, text.uppercase(), 12f, color, true).apply { letterSpacing = 0.06f }

    /** A green tip box (drawing: "Stuck at the start? …"). */
    fun tip(c: Context, text: CharSequence) = label(c, text, 14f, tealText).apply {
        background = rounded(c, tealLight, 14)
        setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11))
    }

    /** A small rounded chip (badges, phrase chips, "Came back 3 times"). */
    fun chip(c: Context, text: String, fg: Int, bg: Int, size: Float = 12.5f) = label(c, text, size, fg, true).apply {
        background = rounded(c, bg, 8)
        setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3))
    }

    fun lp(c: Context, top: Int = 0, match: Boolean = true) = LinearLayout.LayoutParams(
        if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top) }

    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** The small EN | සිං switch. show(sinhala) is called on every tap. */
    fun enSiSwitch(c: Context, show: (Boolean) -> Unit): LinearLayout {
        val sw = LinearLayout(c).apply { background = rounded(c, surface2, 12); setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2)) }
        val enT = label(c, "EN", 12.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3)) }
        val siT = label(c, "සිං", 14f, muted, true).apply { gravity = Gravity.CENTER; setPadding(dp(c, 10), dp(c, 1), dp(c, 10), dp(c, 1)) }
        fun set(sinhala: Boolean) {
            enT.background = if (!sinhala) rounded(c, teal, 10) else null
            enT.setTextColor(if (!sinhala) Color.WHITE else muted)
            siT.background = if (sinhala) rounded(c, teal, 10) else null
            siT.setTextColor(if (sinhala) Color.WHITE else muted)
            show(sinhala)
        }
        enT.setOnClickListener { tap(it); set(false) }
        siT.setOnClickListener { tap(it); set(true) }
        sw.addView(enT); sw.addView(siT)
        set(false)
        return sw
    }

    /**
     * A card with the small EN | සිං switch in its corner: tapping සිං replaces the English text in place with Sinhala.
     */
    fun enSiCard(c: Context, title: String, en: String, si: String, border: Int = line, capsTitle: Boolean = false): LinearLayout {
        val card = card(c, border, if (border == line) 1 else 2)
        val r = row(c)
        r.addView(if (capsTitle) caps(c, title) else label(c, title, 17f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val body = label(c, en, 15.5f, ink)
        if (si.isNotBlank()) r.addView(enSiSwitch(c) { s -> body.text = if (s) si else en })
        if (title.isNotBlank() || si.isNotBlank()) card.addView(r)
        card.addView(body, lp(c, if (title.isNotBlank() || si.isNotBlank()) 8 else 0))
        return card
    }

    /**
     * The screen header from the drawing: ← in a rounded box, title + small subtitle, the partner's
     * round avatar and name on the right. Returns the header and the subtitle view (so it can change).
     */
    fun header(a: Activity, title: String, subtitle: String, who: Character?, onBack: () -> Unit, extra: View? = null): Pair<LinearLayout, TextView> {
        val c: Context = a
        val h = row(c).apply { setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 6)) }
        val back = label(c, "‹", 26f, teal, true).apply {
            gravity = Gravity.CENTER
            background = ripple(c, rounded(c, tealLight, 12), 12)
            setOnClickListener { tap(it); onBack() }
        }
        h.addView(back, LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)).apply { marginEnd = dp(c, 12) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(c, title, 17f, ink, true))
        val sub = label(c, subtitle, 12.5f, muted)
        col.addView(sub)
        h.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (extra != null) h.addView(extra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(c, 10) })
        if (who != null) {
            h.addView(AvatarView(c, who), LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)))
            h.addView(label(c, who.name, 15f, ink, true).apply { setPadding(dp(c, 8), 0, 0, 0) })
        }
        return h to sub
    }

    /** A thin bar made of n segments: done ones green, the current one lighter, the rest grey. */
    fun segments(c: Context, n: Int, done: Int, current: Int = -1): LinearLayout {
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until n) {
            val col = when { i < done -> teal; i == current -> bars; else -> line }
            bar.addView(View(c).apply { background = rounded(c, col, 3) },
                LinearLayout.LayoutParams(0, dp(c, 5), 1f).apply { if (i > 0) marginStart = dp(c, if (n > 12) 3 else 5) })
        }
        return bar
    }

    /** Keeps a screen clear of the status bar and navigation bar (Android 15 draws edge to edge) and makes both dark. */
    fun fitSystemBars(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.systemBars()) else null
            @Suppress("DEPRECATION")
            v.setPadding(0, sys?.top ?: insets.systemWindowInsetTop, 0, sys?.bottom ?: insets.systemWindowInsetBottom)
            insets
        }
        (root.context as? Activity)?.let { darkBars(it) }
    }

    @Suppress("DEPRECATION")
    fun darkBars(a: Activity) {
        runCatching {
            a.window.statusBarColor = cream
            a.window.navigationBarColor = surface
            a.window.decorView.setBackgroundColor(cream)
            if (Build.VERSION.SDK_INT >= 30) {
                a.window.insetsController?.setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            }
        }
    }

    fun name(c: Context): String =
        c.getSharedPreferences("progress", Context.MODE_PRIVATE).getString("name", null)?.takeIf { it.isNotBlank() } ?: "me"

    fun character(who: String, alt: Int): Character = when (who.lowercase()) {
        "sam" -> Characters.SAM
        "maya" -> Characters.MAYA
        else -> if (alt % 2 == 0) Characters.SAM else Characters.MAYA
    }

    fun toast(a: Activity, text: String) = android.widget.Toast.makeText(a, text, android.widget.Toast.LENGTH_SHORT).show()

    /** "Again, please", "Sorry?", "Could you repeat that?": he wants to hear it again, not an answer to judge. */
    fun isAgainRequest(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.split(Regex("\\s+")).size > 9) return false
        return Regex("\\b(again|repeat|one more time|didn'?t catch|did not catch|slow(ly| down)|pardon|play (that|it) again)\\b").containsMatchIn(t) ||
            t.matches(Regex("^(sorry|what|huh)\\W*$"))
    }
}

/** The drawing's round avatar: a person outline in the character's colour on a dark tinted circle. */
class AvatarView(ctx: Context, private val who: Character) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = if (who.male) Color.parseColor("#8AB4F8") else Color.parseColor("#F49AC1")
    private val bg = if (who.male) Color.parseColor("#1D2F4A") else Color.parseColor("#3D2230")

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.FILL; p.color = bg
        c.drawCircle(cx, cy, s / 2f, p)
        p.style = Paint.Style.STROKE; p.color = fg; p.strokeWidth = s * 0.06f; p.strokeCap = Paint.Cap.ROUND
        c.drawCircle(cx, cy - s * 0.12f, s * 0.13f, p)
        c.drawArc(RectF(cx - s * 0.24f, cy + s * 0.04f, cx + s * 0.24f, cy + s * 0.48f), 200f, 140f, false, p)
        if (!who.male) {   // long hair lines, as in the drawing's Maya avatar
            c.drawArc(RectF(cx - s * 0.2f, cy - s * 0.3f, cx + s * 0.2f, cy + s * 0.12f), 150f, 60f, false, p)
            c.drawArc(RectF(cx - s * 0.2f, cy - s * 0.3f, cx + s * 0.2f, cy + s * 0.12f), -30f, 60f, false, p)
        }
    }
}
