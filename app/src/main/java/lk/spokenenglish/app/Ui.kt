package lk.spokenenglish.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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

/** Colours and small building blocks shared by the screens (cream, teal, orange; as on the design canvas). */
object Ui {
    val cream = Color.parseColor("#F6F4EF")
    val teal = Color.parseColor("#0F6B6B")
    val tealLight = Color.parseColor("#E3F1EF")
    val orange = Color.parseColor("#C2621A")
    val orangeLight = Color.parseColor("#FBEEE3")
    val orangeDark = Color.parseColor("#8A4513")
    val ink = Color.parseColor("#1D2433")
    val muted = Color.parseColor("#5B6475")
    val line = Color.parseColor("#E6E1D6")
    val green = Color.parseColor("#1F7A3A")
    val greenLight = Color.parseColor("#E5F4E8")
    val red = Color.parseColor("#B3261E")

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

    private fun ripple(c: Context, bg: android.graphics.drawable.Drawable?, radius: Int, color: Int = Color.parseColor("#330F6B6B")) =
        RippleDrawable(ColorStateList.valueOf(color), bg, GradientDrawable().apply { cornerRadius = dp(c, radius).toFloat(); setColor(Color.WHITE) })

    fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    /** A big filled button. */
    fun button(c: Context, text: String, fill: Int = teal, textColor: Int = Color.WHITE, onClick: () -> Unit) =
        label(c, text, 16f, textColor, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(c, 52)
            setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10))
            background = ripple(c, rounded(c, fill, 26), 26, Color.parseColor("#44FFFFFF"))
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

    fun card(c: Context, border: Int = line, strokeDp: Int = 1) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, Color.WHITE, 16, border, strokeDp)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
    }

    fun lp(c: Context, top: Int = 0, match: Boolean = true) = LinearLayout.LayoutParams(
        if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top) }

    /**
     * A card with the small EN | සිං switch in its corner: tapping සිං replaces the English text in place with Sinhala.
     */
    fun enSiCard(c: Context, title: String, en: String, si: String, border: Int = line): LinearLayout {
        val card = card(c, border)
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(c, title, 17f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val body = label(c, en, 15.5f, ink)
        if (si.isNotBlank()) {
            val sw = LinearLayout(c).apply { background = rounded(c, Color.WHITE, 14, orange); setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2)) }
            val enT = label(c, "EN", 12.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3)) }
            val siT = label(c, "සිං", 14f, orangeDark, true).apply { gravity = Gravity.CENTER; setPadding(dp(c, 10), dp(c, 1), dp(c, 10), dp(c, 1)) }
            fun show(sinhala: Boolean) {
                body.text = if (sinhala) si else en
                enT.background = if (!sinhala) rounded(c, teal, 12) else null
                enT.setTextColor(if (!sinhala) Color.WHITE else muted)
                siT.background = if (sinhala) rounded(c, orange, 12) else rounded(c, orangeLight, 12)
                siT.setTextColor(if (sinhala) Color.WHITE else orangeDark)
            }
            enT.setOnClickListener { tap(it); show(false) }
            siT.setOnClickListener { tap(it); show(true) }
            sw.addView(enT); sw.addView(siT)
            show(false)
            row.addView(sw)
        }
        if (title.isNotBlank() || si.isNotBlank()) card.addView(row)
        card.addView(body, lp(c, if (title.isNotBlank() || si.isNotBlank()) 8 else 0))
        return card
    }

    /** Keeps a screen clear of the status bar and navigation bar (Android 15 draws edge to edge). */
    fun fitSystemBars(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.systemBars()) else null
            @Suppress("DEPRECATION")
            v.setPadding(0, sys?.top ?: insets.systemWindowInsetTop, 0, sys?.bottom ?: insets.systemWindowInsetBottom)
            insets
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
}
