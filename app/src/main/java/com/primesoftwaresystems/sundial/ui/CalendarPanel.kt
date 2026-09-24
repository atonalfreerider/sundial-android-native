package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.text.TextUtils
import android.widget.LinearLayout
import com.primesoftwaresystems.sundial.calendar.DeviceCalendar

/** The lower-left tuck menu: which synced calendars to lay onto the dials. */
class CalendarPanel(context: Context) : LinearLayout(context) {
    private val controls = InstrumentControls(context)
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val selectedCalendarIds = linkedSetOf<Long>()

    var onCalendarSelectionChanged: ((Set<Long>) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(controls.dp(22), controls.dp(20), controls.dp(18), controls.dp(14))
        controls.title("CALENDARS", "SYNCED · READ ONLY").forEach(::addView)
        addView(list)
        showMessage("Loading synced calendars…")
    }

    fun setCalendars(calendars: List<DeviceCalendar>) {
        list.removeAllViews()
        if (calendars.isEmpty()) {
            showMessage("No synced calendars found")
            return
        }
        calendars.forEach { calendar ->
            val row = controls.switch(calendar.displayName).apply {
                isChecked = calendar.id in selectedCalendarIds
                contentDescription = "Show events from ${calendar.displayName}"
                with(controls) { tint(calendar.color) }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedCalendarIds += calendar.id else selectedCalendarIds -= calendar.id
                    onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet())
                }
            }
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, controls.dp(46)).apply { topMargin = controls.dp(6) })
            list.addView(controls.label(
                if (calendar.isGoogle) "Google · ${calendar.accountName}" else calendar.accountName,
                11f,
                0x7AFFFFFF,
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(controls.dp(2), 0, controls.dp(8), controls.dp(5))
            })
        }
    }

    fun setPermissionDenied() = showMessage("Calendar permission denied")

    private fun showMessage(message: String) {
        list.removeAllViews()
        list.addView(controls.label(message, 14f, 0x99FFFFFF.toInt()).apply {
            setPadding(controls.dp(2), controls.dp(12), controls.dp(2), controls.dp(14))
        })
    }
}
