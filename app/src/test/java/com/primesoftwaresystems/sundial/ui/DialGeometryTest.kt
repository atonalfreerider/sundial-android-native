package com.primesoftwaresystems.sundial.ui

import com.primesoftwaresystems.sundial.astronomy.Astronomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class DialGeometryTest {
    @Test fun `September civil date maps to September sector rather than January`() {
        val date = ZonedDateTime.parse("2026-09-22T12:00:00-07:00[America/Los_Angeles]")
        val fraction = Astronomy.civilYearFraction(date)
        assertTrue(fraction in .72..0.74)
        val angle = DialGeometry.annualAngle(fraction, north = true)
        assertEquals(fraction, DialGeometry.yearFractionFromAngle(angle, north = true), 1e-12)
    }

    @Test fun `annual dial round trips in both hemispheres`() {
        listOf(true, false).forEach { north ->
            listOf(0.0, .1, .5, .75, .999).forEach { fraction ->
                assertEquals(fraction, DialGeometry.yearFractionFromAngle(
                    DialGeometry.annualAngle(fraction, north), north), 1e-12)
            }
        }
    }

    @Test fun `solstices and equinoxes sit on Unity's season cross`() {
        fun angleOf(date: String): Double {
            val zoned = ZonedDateTime.parse(date)
            return Astronomy.normalizeDegrees(DialGeometry.annualAngle(Astronomy.civilYearFraction(zoned), north = true))
        }
        // Canvas angles: 90 is straight below the Sun, 270 above, 180 left, 0 right.
        assertEquals(90.0, angleOf("2026-12-21T12:00:00-08:00[America/Los_Angeles]"), 1.5)
        assertEquals(270.0, angleOf("2026-06-21T12:00:00-07:00[America/Los_Angeles]"), 1.5)
        assertEquals(180.0, angleOf("2026-09-22T12:00:00-07:00[America/Los_Angeles]"), 1.5)
        assertEquals(0.0, Astronomy.normalizeSignedDegrees(angleOf("2026-03-20T12:00:00-07:00[America/Los_Angeles]")), 3.0)
    }

    @Test fun `planet longitudes share the annual dial frame`() {
        listOf("2026-01-01T00:00:00Z", "2026-04-15T00:00:00Z", "2026-09-22T12:00:00Z").forEach { date ->
            val instant = java.time.Instant.parse(date)
            val civil = DialGeometry.annualAngle(
                Astronomy.civilYearFraction(instant.atZone(java.time.ZoneOffset.UTC)), north = true)
            val physical = DialGeometry.eclipticAngle(
                Astronomy.heliocentricPosition(Astronomy.Body.EARTH, instant).longitudeDegrees, north = true)
            assertEquals(0.0, Astronomy.normalizeSignedDegrees(civil - physical), 2.5)
        }
    }

    @Test fun `earth view keeps noon toward the Sun and the Moon moving counter-clockwise`() {
        assertEquals(-90.0, DialGeometry.hourAngle(12.0, north = true), 1e-9)
        assertEquals(90.0, DialGeometry.hourAngle(0.0, north = true), 1e-9)
        assertEquals(0.0, DialGeometry.hourAngle(6.0, north = true), 1e-9)
        assertEquals(180.0, DialGeometry.hourAngle(6.0, north = false), 1e-9)
        listOf(true, false).forEach { north ->
            listOf(0.0, 90.0, 721.5, 1_439.0).forEach { minute ->
                assertEquals(minute, DialGeometry.minuteFromHourAngle(
                    DialGeometry.hourAngle(minute / 60.0, north), north), 1e-9)
            }
            listOf(0.0, 45.0, 180.0, 359.0).forEach { phase ->
                assertEquals(phase, DialGeometry.phaseFromMoonAngle(DialGeometry.moonAngle(phase, north), north), 1e-9)
            }
        }
        // First quarter Moon is 90° east of the Sun: left of the Earth in the north view.
        assertEquals(-180.0, DialGeometry.moonAngle(90.0, north = true), 1e-9)
    }

    @Test fun `event bands follow Unity calendar channels rather than event order`() {
        val year0 = DialGeometry.yearEventBand(1000f, 0)
        val year1 = DialGeometry.yearEventBand(1000f, 1)
        assertEquals(46.64f, year0.thickness, .01f)
        assertEquals(year0.thickness, year0.centerRadius - year1.centerRadius, .01f)

        val day0 = DialGeometry.dayEventBand(600f, 0)
        assertEquals(69.96f, day0.thickness, .01f)
        assertTrue(day0.centerRadius < 600f)
    }

    @Test fun `earth flight follows Unity camera zoom while earth system expands independently`() {
        val start = DialGeometry.earthFlightFrame(0f)
        val middle = DialGeometry.earthFlightFrame(.5f)
        val end = DialGeometry.earthFlightFrame(1f)

        assertEquals(1f, start.cameraScale, .0001f)
        assertEquals(DialGeometry.EARTH_CAMERA_ZOOM, end.cameraScale, .0001f)
        assertEquals(DialGeometry.HELIOCENTRIC_EARTH_RADIUS,
            start.earthSystemScale * DialGeometry.EARTH_RADIUS, .0001f)
        assertEquals(1f, end.earthSystemScale, .0001f)
        assertTrue(middle.cameraScale in start.cameraScale..end.cameraScale)
        assertTrue(middle.earthSystemScale in start.earthSystemScale..end.earthSystemScale)
    }
}
