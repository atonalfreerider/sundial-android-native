package com.primesoftwaresystems.sundial.calendar

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class DaySegment(val startMinute: Double, val endMinuteExclusive: Double)
data class YearSegment(val startFraction: Double, val sweepFraction: Double)

object CalendarIntervals {
    fun inDay(event: CalendarOccurrence, day: LocalDate, zone: ZoneId): DaySegment? {
        val dayStart = day.atStartOfDay(zone)
        val dayEnd = day.plusDays(1).atStartOfDay(zone)
        val start = maxOf(event.start, dayStart)
        val end = minOf(event.endExclusive, dayEnd)
        if (!end.isAfter(start)) return null
        return DaySegment(
            Duration.between(dayStart, start).toMillis() / 60_000.0,
            Duration.between(dayStart, end).toMillis() / 60_000.0,
        )
    }

    fun inYear(event: CalendarOccurrence, year: Int, zone: ZoneId): YearSegment? {
        val yearStart = LocalDate.of(year, 1, 1).atStartOfDay(zone)
        val yearEnd = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone)
        val start = maxOf(event.start, yearStart)
        val end = minOf(event.endExclusive, yearEnd)
        if (!end.isAfter(start)) return null
        val yearMillis = Duration.between(yearStart, yearEnd).toMillis().toDouble()
        return YearSegment(
            Duration.between(yearStart, start).toMillis() / yearMillis,
            Duration.between(start, end).toMillis() / yearMillis,
        )
    }
}
