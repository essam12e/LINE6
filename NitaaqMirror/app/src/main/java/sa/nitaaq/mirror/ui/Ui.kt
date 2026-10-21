package sa.nitaaq.mirror.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import sa.nitaaq.mirror.App

/** Light, plain-View UI helpers (no AndroidX needed). */
object Ui {
    const val BG = 0xFFF5F7F8.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val PRIMARY = 0xFF0B6E79.toInt()
    const val PRIMARY_SOFT = 0xFFE3F2F3.toInt()
    const val DANGER = 0xFFC62828.toInt()
    const val DANGER_SOFT = 0xFFFDECEC.toInt()
    const val OK = 0xFF2E7D32.toInt()
    const val WARN = 0xFFB26A00.toInt()
    const val TEXT = 0xFF1A1C1E.toInt()
    const val TEXT2 = 0xFF5F6368.toInt()
    const val LINE = 0xFFE1E4E6.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 16, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(ctx, radiusDp.toFloat())
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    fun column(ctx: Context, gapDp: Int = 12): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(1, dp(ctx, gapDp)); setColor(Color.TRANSPARENT) }
    }

    fun row(ctx: Context, gapDp: Int = 8): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(ctx, gapDp), 1); setColor(Color.TRANSPARENT) }
    }

    fun title(ctx: Context, text: CharSequence, sizeSp: Float = 24f) = TextView(ctx).apply {
        this.text = text; textSize = sizeSp; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
    }

    fun body(ctx: Context, text: CharSequence, color: Int = TEXT2, sizeSp: Float = 15f) = TextView(ctx).apply {
        this.text = text; textSize = sizeSp; setTextColor(color); setLineSpacing(0f, 1.15f)
    }

    private fun styledButton(ctx: Context, text: CharSequence, bg: Int, fg: Int, stroke: Int?, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(ctx, 56)
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(ctx, bg, 14, stroke), null)
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        setOnClickListener { onClick() }
    }

    fun primary(ctx: Context, text: CharSequence, onClick: () -> Unit) = styledButton(ctx, text, PRIMARY, Color.WHITE, null, onClick)
    fun secondary(ctx: Context, text: CharSequence, onClick: () -> Unit) = styledButton(ctx, text, SURFACE, PRIMARY, LINE, onClick)
    fun danger(ctx: Context, text: CharSequence, onClick: () -> Unit) = styledButton(ctx, text, DANGER, Color.WHITE, null, onClick)

    fun small(ctx: Context, text: CharSequence, onClick: () -> Unit) = styledButton(ctx, text, PRIMARY_SOFT, PRIMARY, null, onClick).apply {
        textSize = 14f; minHeight = dp(ctx, 40); minimumHeight = dp(ctx, 40)
    }

    fun card(ctx: Context, vararg children: View): LinearLayout = column(ctx, 8).apply {
        background = rounded(ctx, SURFACE, 18, LINE)
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
        children.forEach { addView(it) }
    }

    /** Permission / status line: "● Title — subtitle  [Action]". */
    fun statusLine(ctx: Context, title: CharSequence, subtitle: CharSequence, ok: Boolean?, action: CharSequence?, onAction: (() -> Unit)?): View {
        val r = row(ctx, 12)
        val dot = TextView(ctx).apply {
            text = if (ok == true) "✓" else if (ok == false) "!" else "•"
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ok == true) OK else if (ok == false) WARN else TEXT2) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
        }
        val texts = column(ctx, 2).apply {
            addView(TextView(ctx).apply { text = title; textSize = 16f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD })
            addView(body(ctx, subtitle, sizeSp = 13.5f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        r.addView(dot); r.addView(texts)
        if (action != null && onAction != null) r.addView(small(ctx, action, onAction))
        return r
    }

    /** Scrollable page with insets handled (Android 15 draws edge-to-edge for targetSdk 35). */
    fun page(activity: Activity, content: LinearLayout): ScrollView {
        val p = dp(activity, 20)
        content.setPadding(p, p, p, p)
        val sv = ScrollView(activity).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        applySystemBarPadding(sv)
        activity.setContentView(sv)
        return sv
    }

    /** API 30+: windows are edge-to-edge (see [BaseActivity]); pad for bars, cutout and keyboard ourselves. */
    fun applySystemBarPadding(v: View) {
        if (Build.VERSION.SDK_INT < 30) return // legacy windows already fit the system bars
        v.setOnApplyWindowInsetsListener { view, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }
}

/** Every screen: app language (Arabic RTL by default), light system bars. */
open class BaseActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(App.localized(newBase))
    }

    protected open val darkChrome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = if (darkChrome) android.graphics.Color.BLACK else Ui.BG
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // Go through decorView: it creates the decor first. Window.getInsetsController() before the decor
            // exists throws NullPointerException on Android 11-12 (found by the emulator test on API 31).
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (darkChrome) 0 else light, light)
        } else if (!darkChrome) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}
