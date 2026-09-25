package com.metavirtuoso.sundial.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ScrollView

/**
 * Hosts the instrument full screen with small "tuck" menus in its corners. Each corner button
 * unfolds a panel from that corner and tucks it away again; only one is open at a time, and a tap
 * outside, the button again or Back closes it. Bottom panels rise above the keyboard.
 */
class TuckMenuHost(context: Context) : FrameLayout(context) {
    enum class Corner(val gravity: Int) {
        TOP_START(Gravity.TOP or Gravity.START),
        BOTTOM_START(Gravity.BOTTOM or Gravity.START),
        BOTTOM_END(Gravity.BOTTOM or Gravity.END),
    }

    inner class TuckMenu internal constructor(
        val corner: Corner,
        val button: ImageButton,
        val panel: FrameLayout,
        internal val scroller: MaxHeightScrollView,
    ) {
        val isOpen: Boolean get() = open === this
        fun open() = this@TuckMenuHost.open(this)
        fun close() { if (isOpen) this@TuckMenuHost.close() }
    }

    private val density = resources.displayMetrics.density
    private val menus = mutableListOf<TuckMenu>()
    private var open: TuckMenu? = null
    private var imeBottom = 0
    var accentColor: Int = InstrumentControls.BRASS
        set(value) { field = value; menus.forEach { style(it) } }
    var iconColor: Int = 0xFFF2EFE7.toInt()
        set(value) { field = value; menus.forEach { style(it) } }
    /** Told whenever a menu unfolds or the last one tucks away, so Back can be claimed only while one is open. */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    private val scrim = View(context).apply {
        setBackgroundColor(0x70000000)
        alpha = 0f
        visibility = GONE
        setOnClickListener { close() }
    }

    init {
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnApplyWindowInsetsListener { _, insets ->
            imeBottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            open?.let { place(it) }
            insets
        }
    }

    /** Adds the instrument (or any full-screen content) beneath the menus. */
    fun setContent(view: View) {
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun addMenu(corner: Corner, iconRes: Int, description: String, content: View): TuckMenu {
        val margin = dp(14)
        val buttonSize = dp(50)
        val button = ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13))
            elevation = dp(4).toFloat()
        }
        val scroller = MaxHeightScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val panel = FrameLayout(context).apply {
            visibility = GONE
            elevation = dp(12).toFloat()
            clipToOutline = true
            addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val panelWidth = minOf(resources.displayMetrics.widthPixels - 2 * margin, dp(372))
        addView(panel, LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT, corner.gravity).apply {
            leftMargin = margin
            rightMargin = margin
            if (corner == Corner.TOP_START) topMargin = margin + buttonSize + dp(8)
            else bottomMargin = margin + buttonSize + dp(8)
        })
        addView(button, LayoutParams(buttonSize, buttonSize, corner.gravity).apply { setMargins(margin, margin, margin, margin) })
        val menu = TuckMenu(corner, button, panel, scroller)
        button.setOnClickListener { if (menu.isOpen) close() else open(menu) }
        menus += menu
        style(menu)
        return menu
    }

    fun open(menu: TuckMenu) {
        if (open === menu) return
        val wasOpen = open != null
        open?.let { hide(it) }
        open = menu
        style(menu)
        if (!wasOpen) onOpenChanged?.invoke(true)
        scrim.visibility = VISIBLE
        scrim.animate().alpha(1f).setDuration(ANIMATION_MS).start()
        place(menu)
        val panel = menu.panel
        panel.visibility = VISIBLE
        panel.post {
            panel.pivotX = if (menu.corner == Corner.BOTTOM_END) panel.width.toFloat() else 0f
            panel.pivotY = if (menu.corner == Corner.TOP_START) 0f else panel.height.toFloat()
            panel.scaleX = .82f
            panel.scaleY = .82f
            panel.alpha = 0f
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(ANIMATION_MS)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    fun close(): Boolean {
        val menu = open ?: return false
        open = null
        hide(menu)
        style(menu)
        onOpenChanged?.invoke(false)
        scrim.animate().alpha(0f).setDuration(ANIMATION_MS).withEndAction { if (open == null) scrim.visibility = GONE }.start()
        findFocus()?.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        return true
    }

    val isMenuOpen: Boolean get() = open != null

    private fun hide(menu: TuckMenu) {
        menu.panel.animate().scaleX(.82f).scaleY(.82f).alpha(0f).setDuration(ANIMATION_MS)
            .withEndAction { if (open !== menu) menu.panel.visibility = GONE }.start()
    }

    /** Fits the panel between its button and the far edge, lifted above the keyboard when shown. */
    private fun place(menu: TuckMenu) {
        // Leave the button rows at both top and bottom uncovered, whichever corner unfolds.
        val reserved = 2 * (dp(14) + dp(50) + dp(8)) + dp(10)
        val params = menu.panel.layoutParams as LayoutParams
        val lift = if (menu.corner == Corner.TOP_START) 0 else (imeBottom - params.bottomMargin + dp(8)).coerceAtLeast(0)
        menu.panel.translationY = -lift.toFloat()
        menu.scroller.maxHeight = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) - reserved - lift
        menu.scroller.requestLayout()
    }

    private fun style(menu: TuckMenu) {
        menu.button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (menu.isOpen) 0xE0201A12.toInt() else 0x8C000000.toInt())
            setStroke(dp(1), if (menu.isOpen) accentColor else 0x59FFFFFF)
        }
        menu.button.imageTintList = ColorStateList.valueOf(if (menu.isOpen) accentColor else iconColor)
        menu.panel.background = GradientDrawable().apply {
            setColor(0xF20C0B0D.toInt())
            setStroke(dp(1), android.graphics.Color.argb(120, android.graphics.Color.red(accentColor),
                android.graphics.Color.green(accentColor), android.graphics.Color.blue(accentColor)))
            cornerRadius = dp(22).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    /** A ScrollView that wraps its content up to [maxHeight], then scrolls. */
    internal class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeight = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight.coerceAtLeast(0), MeasureSpec.AT_MOST))
        }
    }

    private companion object {
        const val ANIMATION_MS = 190L
    }
}
