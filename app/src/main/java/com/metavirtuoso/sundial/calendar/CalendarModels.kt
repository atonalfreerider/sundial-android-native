package com.metavirtuoso.sundial.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val selected: Boolean = false,
) {
    val isGoogle: Boolean get() = accountType.equals("com.google", ignoreCase = true)
}

data class RawCalendarInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val eventTimeZone: String?,
    val color: Int,
)

/** A normalized occurrence. End is exclusive, matching CalendarContract. */
data class CalendarOccurrence(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val start: ZonedDateTime,
    val endExclusive: ZonedDateTime,
    val allDayStart: LocalDate?,
    val allDayEndExclusive: LocalDate?,
    val color: Int,
) {
    val isAllDay: Boolean get() = allDayStart != null
    val isYearRingEvent: Boolean
        get() = isAllDay || java.time.Duration.between(start, endExclusive).toHours() >= 24
}

object CalendarNormalizer {
    /**
     * Android stores all-day event millis at UTC midnight. Those millis represent date labels, not instants
     * to shift into the device zone. Timed events are real instants and use full ZoneId/DST rules.
     */
    fun normalize(raw: RawCalendarInstance, displayZone: ZoneId): CalendarOccurrence {
        val beginInstant = Instant.ofEpochMilli(raw.beginMillis)
        val endInstant = Instant.ofEpochMilli(raw.endMillis.coerceAtLeast(raw.beginMillis + 1))
        return if (raw.allDay) {
            val startDate = beginInstant.atZone(ZoneId.of("UTC")).toLocalDate()
            val endDate = endInstant.atZone(ZoneId.of("UTC")).toLocalDate()
            CalendarOccurrence(
                raw.eventId, raw.calendarId, raw.title,
                startDate.atStartOfDay(displayZone), endDate.atStartOfDay(displayZone),
                startDate, endDate, raw.color,
            )
        } else {
            CalendarOccurrence(
                raw.eventId, raw.calendarId, raw.title,
                beginInstant.atZone(displayZone), endInstant.atZone(displayZone),
                null, null, raw.color,
            )
        }
    }
}
