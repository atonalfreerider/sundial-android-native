package com.metavirtuoso.sundial.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.metavirtuoso.sundial.R

/** The shared look of Sundial's native controls: instrument type, brass switches, hairlines. */
internal class InstrumentControls(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    val typeface = context.resources.getFont(R.font.franklin_condensed)

    fun title(value: String, subtitle: String): List<View> = listOf(
        label(value, 26f, Color.WHITE).apply { letterSpacing = .12f },
        label(subtitle, 11f, 0x99FFFFFF.toInt()).apply {
            letterSpacing = .18f
            setPadding(0, dp(2), 0, dp(14))
        },
        hairline(),
    )

    fun section(title: String): TextView = label(title, 12f, 0x99FFFFFF.toInt()).apply {
        letterSpacing = .16f
        setPadding(dp(2), dp(18), 0, dp(6))
    }

    fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = this@InstrumentControls.typeface
    }

    fun switch(title: String): Switch = Switch(context).apply {
        text = title
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = this@InstrumentControls.typeface
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(50)
        setPadding(dp(2), 0, dp(4), 0)
        showText = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        tint(BRASS)
    }

    fun Switch.tint(accent: Int) {
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, 0xFF8A8D90.toInt()),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.argb(112, Color.red(accent), Color.green(accent), Color.blue(accent)), 0xFF35393D.toInt()),
        )
    }

    fun action(title: String, description: String, click: () -> Unit): TextView = label(title, 16f, Color.WHITE).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(50)
        setPadding(dp(16), 0, dp(12), 0)
        background = outlined()
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    fun hairline() = View(context).apply {
        setBackgroundColor(0x38FFFFFF)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    fun outlined(): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(0x12000000)
            setStroke(dp(1), 0x45FFFFFF)
            cornerRadius = dp(14).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), shape, null)
    }

    fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        const val BRASS = 0xFFFFB34A.toInt()
    }
}
