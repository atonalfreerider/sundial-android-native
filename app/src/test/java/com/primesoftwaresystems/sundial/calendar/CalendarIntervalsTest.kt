package com.primesoftwaresystems.sundial.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CalendarIntervalsTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test fun `event crossing midnight is clipped into both civil days`() {
        val event = occurrence("2024-05-02T06:30:00Z", "2024-05-02T08:30:00Z") // 23:30–01:30 PDT
        val first = CalendarIntervals.inDay(event, java.time.LocalDate.of(2024, 5, 1), zone)!!
        val second = CalendarIntervals.inDay(event, java.time.LocalDate.of(2024, 5, 2), zone)!!
        assertEquals(1410.0, first.startMinute, 0.001)
        assertEquals(1440.0, first.endMinuteExclusive, 0.001)
        assertEquals(0.0, second.startMinute, 0.001)
        assertEquals(90.0, second.endMinuteExclusive, 0.001)
    }

    @Test fun `event outside day is excluded`() {
        val event = occurrence("2024-05-02T06:30:00Z", "2024-05-02T08:30:00Z")
        assertNull(CalendarIntervals.inDay(event, java.time.LocalDate.of(2024, 5, 3), zone))
    }

    @Test fun `year clipping honors leap-year duration and exclusive end`() {
        val event = occurrence("2023-12-31T08:00:00Z", "2025-01-02T08:00:00Z")
        val segment = CalendarIntervals.inYear(event, 2024, zone)!!
        assertEquals(0.0, segment.startFraction, 1e-12)
        assertEquals(1.0, segment.sweepFraction, 1e-12)
    }

    private fun occurrence(start: String, end: String): CalendarOccurrence {
        val startZoned = Instant.parse(start).atZone(zone)
        val endZoned = Instant.parse(end).atZone(zone)
        return CalendarOccurrence(1, 2, "Test", startZoned, endZoned, null, null, 0xffcccc00.toInt())
    }
}
