package com.primesoftwaresystems.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeZoneDialTest {
    @Test fun `timezone wheel has 24 equally spaced spokes and two civil days`() {
        val spokes = TimeZoneDial.spokes(Instant.parse("2026-09-22T19:30:00Z"))
        assertEquals(24, spokes.size)
        assertEquals(2, spokes.map { it.localDate }.distinct().size)
        spokes.zipWithNext().forEach { (a, b) ->
            val separation = ((b.angleDegrees - a.angleDegrees) + 360.0) % 360.0
            assertEquals(15.0, separation, 1e-9)
        }
    }

    @Test fun `Los Angeles location arrow follows daylight saving and common name`() {
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals(-420, TimeZoneDial.localOffsetMinutes(Instant.parse("2026-09-22T19:30:00Z"), zone))
        assertEquals(-480, TimeZoneDial.localOffsetMinutes(Instant.parse("2026-01-22T20:30:00Z"), zone))
        assertEquals("Pacific Time", TimeZoneDial.localName(zone, Instant.parse("2026-09-22T19:30:00Z")))
    }

    @Test fun `touch selection resolves to closest common timezone spoke`() {
        val spokes = TimeZoneDial.spokes(Instant.parse("2026-09-22T19:30:00Z"))
        val eastern = spokes.single { it.offsetHours == -5 }
        assertEquals(eastern, TimeZoneDial.nearestSpoke(spokes, eastern.angleDegrees + 3.0))
        assertTrue(eastern.label.contains("Eastern"))
    }
}
