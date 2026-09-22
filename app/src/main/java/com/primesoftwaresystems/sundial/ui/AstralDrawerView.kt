package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.primesoftwaresystems.sundial.R
import com.primesoftwaresystems.sundial.calendar.DeviceCalendar

/** Native controls presented as a dark astronomical instrument side sheet. */
class AstralDrawerView(context: Context) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private val instrumentTypeface = resources.getFont(R.font.franklin_condensed)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(38), dp(20), dp(28))
    }
    private val clockSwitch = controlSwitch("CLOCK")
    private val galacticSwitch = controlSwitch("GALACTIC AXIS")
    private val hemisphereSwitch = controlSwitch("SOUTHERN HEMISPHERE")
    private val calendarContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val selectedCalendarIds = linkedSetOf<Long>()
    private var syncing = false

    var onClockChanged: ((Boolean) -> Unit)? = null
    var onGalacticChanged: ((Boolean) -> Unit)? = null
    var onHemisphereChanged: ((Boolean) -> Unit)? = null
    var onResetNow: (() -> Unit)? = null
    var onQuit: (() -> Unit)? = null
    var onCalendarSelectionChanged: ((Set<Long>) -> Unit)? = null

    init {
        isFillViewport = true
        setBackgroundColor(Color.rgb(7, 9, 11))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        content.addView(label("SUN:DIAL", 31f, Color.WHITE).apply { letterSpacing = .12f })
        content.addView(label("CELESTIAL INSTRUMENT", 12f, 0x99FFFFFF.toInt()).apply {
            letterSpacing = .18f
            setPadding(0, dp(2), 0, dp(22))
        })
        content.addView(hairline())
        content.addView(section("DISPLAY"))
        content.addView(clockSwitch)
        content.addView(galacticSwitch)
        content.addView(hemisphereSwitch)
        content.addView(action("RETURN TO NOW", "Reset the instrument to the current date and time") { onResetNow?.invoke() })
        content.addView(section("GOOGLE CALENDAR"))
        content.addView(calendarContainer)
        content.addView(section("APPLICATION"))
        content.addView(action("QUIT", "Close Sundial") { onQuit?.invoke() })

        clockSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onClockChanged?.invoke(checked) }
        galacticSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onGalacticChanged?.invoke(checked) }
        hemisphereSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onHemisphereChanged?.invoke(checked) }
        showCalendarMessage("Loading synced calendars…")
    }

    fun syncControls(view: SundialView) {
        syncing = true
        clockSwitch.isChecked = view.isClockVisible
        galacticSwitch.isChecked = view.isGalacticVisible
        hemisphereSwitch.isChecked = view.isSouthernHemisphere
        syncing = false
    }

    fun setCalendars(calendars: List<DeviceCalendar>) {
        calendarContainer.removeAllViews()
        if (calendars.isEmpty()) {
            showCalendarMessage("No synced calendars found")
            return
        }
        calendars.forEach { calendar ->
            val row = controlSwitch(calendar.displayName).apply {
                isChecked = calendar.id in selectedCalendarIds
                contentDescription = "Show events from ${calendar.displayName}"
                applySwitchTint(calendar.color)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedCalendarIds += calendar.id else selectedCalendarIds -= calendar.id
                    onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet())
                }
            }
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2), 0, dp(4))
            }
            group.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
            group.addView(label(
                if (calendar.isGoogle) "Google · ${calendar.accountName}" else calendar.accountName,
                11f,
                0x7AFFFFFF,
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), 0, dp(8), dp(5))
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(22)))
            calendarContainer.addView(group)
        }
    }

    fun setCalendarPermissionDenied() = showCalendarMessage("Calendar permission denied")

    private fun showCalendarMessage(message: String) {
        calendarContainer.removeAllViews()
        calendarContainer.addView(label(message, 14f, 0x99FFFFFF.toInt()).apply {
            setPadding(dp(2), dp(10), dp(2), dp(18))
        })
    }

    private fun controlSwitch(title: String): Switch = Switch(context).apply {
        text = title
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = instrumentTypeface
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(2), 0, dp(4), 0)
        showText = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        applySwitchTint(0xFFFFB34A.toInt())
    }

    private fun Switch.applySwitchTint(accent: Int) {
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, 0xFF8A8D90.toInt()),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(withAlpha(accent, 112), 0xFF35393D.toInt()),
        )
    }

    private fun action(title: String, description: String, click: () -> Unit): TextView = label(title, 16f, Color.WHITE).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(16), 0, dp(12), 0)
        background = rippleBackground()
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    private fun section(title: String): TextView = label(title, 12f, 0x99FFFFFF.toInt()).apply {
        letterSpacing = .16f
        setPadding(dp(2), dp(24), 0, dp(8))
    }

    private fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = instrumentTypeface
    }

    private fun hairline() = View(context).apply {
        setBackgroundColor(0x38FFFFFF)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun rippleBackground(): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(0x12000000)
            setStroke(dp(1), 0x45FFFFFF)
            cornerRadius = dp(14).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), shape, null)
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * density).toInt()
}
