package com.metavirtuoso.sundial.ui

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
            // Zones further east are later in the day, so they sit counter-clockwise (north view).
            val separation = ((a.angleDegrees - b.angleDegrees) + 360.0) % 360.0
            assertEquals(15.0, separation, 1e-9)
        }
    }

    @Test fun `zone at local noon faces the Sun and midnight faces away`() {
        val instant = Instant.parse("2026-09-22T19:00:00Z")
        val spokes = TimeZoneDial.spokes(instant)
        fun normalized(value: Double) = ((value % 360.0) + 360.0) % 360.0
        assertEquals(270.0, normalized(spokes.single { it.offsetHours == -7 }.angleDegrees), 1e-9)
        assertEquals(90.0, normalized(spokes.single { it.offsetHours == 5 }.angleDegrees), 1e-9)
        // 06:00 is on the right and 18:00 on the left, matching the Unity hour ring.
        assertEquals(0.0, normalized(spokes.single { it.offsetHours == 11 }.angleDegrees), 1e-9)
        assertEquals(180.0, normalized(spokes.single { it.offsetHours == -1 }.angleDegrees), 1e-9)
        val south = TimeZoneDial.spokes(instant, north = false)
        assertEquals(180.0, normalized(south.single { it.offsetHours == 11 }.angleDegrees), 1e-9)
    }

    @Test fun `Los Angeles location arrow follows daylight saving and common name`() {
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals(-420, TimeZoneDial.localOffsetMinutes(Instant.parse("2026-09-22T19:30:00Z"), zone))
        assertEquals(-480, TimeZoneDial.localOffsetMinutes(Instant.parse("2026-01-22T20:30:00Z"), zone))
        assertEquals("Pacific Time", TimeZoneDial.localName(zone, Instant.parse("2026-09-22T19:30:00Z")))
    }

    @Test fun `date line sits at the local time of UTC+12`() {
        assertEquals(7.5, TimeZoneDial.datelineHours(Instant.parse("2026-09-22T19:30:00Z")), 1e-9)
        assertEquals(0.0, TimeZoneDial.datelineHours(Instant.parse("2026-09-22T12:00:00Z")), 1e-9)
    }

    @Test fun `touch selection resolves to closest common timezone spoke`() {
        val spokes = TimeZoneDial.spokes(Instant.parse("2026-09-22T19:30:00Z"))
        val eastern = spokes.single { it.offsetHours == -5 }
        assertEquals(eastern, TimeZoneDial.nearestSpoke(spokes, eastern.angleDegrees + 3.0))
        assertTrue(eastern.label.contains("Eastern"))
    }
}
