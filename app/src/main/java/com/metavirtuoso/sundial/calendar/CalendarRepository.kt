package com.metavirtuoso.sundial.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.ZoneId

class CalendarRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun loadCalendars(): List<DeviceCalendar> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        val calendars = mutableListOf<DeviceCalendar>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.SYNC_EVENTS}=1",
            null,
            "${CalendarContract.Calendars.ACCOUNT_TYPE}, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars += DeviceCalendar(
                    id = cursor.getLong(0),
                    displayName = cursor.getString(1).orEmpty().ifBlank { "Calendar" },
                    accountName = cursor.getString(2).orEmpty(),
                    accountType = cursor.getString(3).orEmpty(),
                    color = cursor.getInt(4),
                )
            }
        }
        // Google calendars are the first supported integration and appear first in the native menu.
        return calendars.sortedWith(compareByDescending<DeviceCalendar> { it.isGoogle }.thenBy { it.displayName.lowercase() })
    }

    fun loadInstances(
        calendarIds: Set<Long>,
        begin: Instant,
        endExclusive: Instant,
        displayZone: ZoneId,
    ): List<CalendarOccurrence> {
        if (!hasPermission() || calendarIds.isEmpty()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.CALENDAR_COLOR,
        )
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)"
        val args = calendarIds.map(Long::toString).toTypedArray()
        val rows = mutableListOf<CalendarOccurrence>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin.toEpochMilli())
            ContentUris.appendId(it, endExclusive.toEpochMilli())
        }.build()
        resolver.query(
            uri,
            projection,
            selection,
            args,
            CalendarContract.Instances.BEGIN,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calendarId = cursor.getLong(1)
                val raw = RawCalendarInstance(
                    eventId = cursor.getLong(0),
                    calendarId = calendarId,
                    title = cursor.getString(2).orEmpty().ifBlank { "Busy" },
                    beginMillis = cursor.getLong(3),
                    endMillis = cursor.getLong(4),
                    allDay = cursor.getInt(5) != 0,
                    eventTimeZone = cursor.getString(6),
                    color = cursor.getInt(7),
                )
                rows += CalendarNormalizer.normalize(raw, displayZone)
            }
        }
        return rows.sortedBy { it.start.toInstant() }
    }
}
