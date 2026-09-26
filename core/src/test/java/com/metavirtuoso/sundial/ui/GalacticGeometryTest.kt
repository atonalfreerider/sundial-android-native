package com.metavirtuoso.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GalacticGeometryTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test fun `continuous year round trips across centuries`() {
        listOf("1901-07-04T12:00:00Z", "2026-09-23T16:00:00Z", "2400-02-29T00:00:00Z").forEach { date ->
            val instant = Instant.parse(date)
            val year = GalacticGeometry.continuousYear(instant, zone)
            assertEquals(instant.epochSecond.toDouble(),
                GalacticGeometry.instantAt(year, zone).epochSecond.toDouble(), 1.0)
        }
        assertEquals(2026.0, GalacticGeometry.monthStart(2026, 1), 1e-12)
        assertEquals(2027.0, GalacticGeometry.continuousYear(Instant.parse("2027-01-01T08:00:00Z"), zone), 1e-9)
    }

    @Test fun `later years lie ahead of the Sun in the direction of travel`() {
        // Travel points up the screen (canvas y grows downward).
        assertTrue(GalacticGeometry.travelY < 0f)
        assertEquals(1f, GalacticGeometry.travelX * GalacticGeometry.travelX +
            GalacticGeometry.travelY * GalacticGeometry.travelY, 1e-6f)
        assertEquals(0f, GalacticGeometry.travelX * GalacticGeometry.sideX +
            GalacticGeometry.travelY * GalacticGeometry.sideY, 1e-6f)
    }

    @Test fun `orbits run counter clockwise on screen as seen from the direction of travel`() {
        fun screen(longitude: Double): Pair<Float, Float> {
            val (along, side) = GalacticGeometry.orbitOffset(longitude, 1f)
            return Pair(
                GalacticGeometry.travelX * along + GalacticGeometry.sideX * side,
                GalacticGeometry.travelY * along + GalacticGeometry.sideY * side,
            )
        }
        val a = screen(0.0)
        val b = screen(90.0)
        // Counter-clockwise on a y-down canvas has a negative cross product.
        assertTrue(a.first * b.second - a.second * b.first < 0f)
    }

    @Test fun `ribbon is clamped only to civil calendar years`() {
        assertEquals(GalacticGeometry.MIN_YEAR, GalacticGeometry.instantAt(-50.0, zone).atZone(zone).year)
        assertEquals(GalacticGeometry.MAX_YEAR, GalacticGeometry.instantAt(20_000.0, zone).atZone(zone).year)
    }
}
