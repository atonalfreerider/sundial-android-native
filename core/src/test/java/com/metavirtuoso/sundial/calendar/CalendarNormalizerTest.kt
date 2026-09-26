package com.metavirtuoso.sundial.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CalendarNormalizerTest {
    @Test fun `all-day event stays on its UTC date west of Greenwich`() {
        val raw = allDay("2024-03-10T00:00:00Z", "2024-03-11T00:00:00Z")
        val event = CalendarNormalizer.normalize(raw, ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalDate.of(2024, 3, 10), event.allDayStart)
        assertEquals(LocalDate.of(2024, 3, 11), event.allDayEndExclusive)
        assertEquals(LocalDate.of(2024, 3, 10), event.start.toLocalDate())
    }

    @Test fun `all-day event stays on its UTC date east of Greenwich`() {
        val raw = allDay("2024-03-10T00:00:00Z", "2024-03-11T00:00:00Z")
        val event = CalendarNormalizer.normalize(raw, ZoneId.of("Asia/Kathmandu"))
        assertEquals(LocalDate.of(2024, 3, 10), event.start.toLocalDate())
    }

    @Test fun `timed event uses DST transition rules rather than a fixed offset`() {
        val raw = timed("2024-03-10T09:30:00Z", "2024-03-10T10:30:00Z")
        val event = CalendarNormalizer.normalize(raw, ZoneId.of("America/Los_Angeles"))
        assertEquals(1, event.start.hour)
        assertEquals(3, event.endExclusive.hour)
        assertEquals(60, Duration.between(event.start, event.endExclusive).toMinutes())
    }

    @Test fun `non-hour timezone offsets are retained`() {
        val event = CalendarNormalizer.normalize(
            timed("2024-04-01T00:00:00Z", "2024-04-01T01:00:00Z"),
            ZoneId.of("Asia/Kathmandu"),
        )
        assertEquals(5, event.start.hour)
        assertEquals(45, event.start.minute)
    }

    @Test fun `leap-day all-day span uses exclusive end`() {
        val event = CalendarNormalizer.normalize(
            allDay("2024-02-28T00:00:00Z", "2024-03-01T00:00:00Z"),
            ZoneId.of("UTC"),
        )
        assertEquals(2, Duration.between(event.start, event.endExclusive).toDays())
        assertTrue(event.isYearRingEvent)
    }

    @Test fun `short timed event remains a day-ring event`() {
        val event = CalendarNormalizer.normalize(
            timed("2040-01-01T20:00:00Z", "2040-01-01T20:30:00Z"),
            ZoneId.of("UTC"),
        )
        assertFalse(event.isYearRingEvent)
        assertEquals(Instant.parse("2040-01-01T20:00:00Z"), event.start.toInstant())
    }

    private fun allDay(start: String, end: String) = raw(start, end, true)
    private fun timed(start: String, end: String) = raw(start, end, false)
    private fun raw(start: String, end: String, allDay: Boolean) = RawCalendarInstance(
        eventId = 1L,
        calendarId = 2L,
        title = "Test",
        beginMillis = Instant.parse(start).toEpochMilli(),
        endMillis = Instant.parse(end).toEpochMilli(),
        allDay = allDay,
        eventTimeZone = if (allDay) "UTC" else "America/Los_Angeles",
        color = 0xffcccc00.toInt(),
    )
}
