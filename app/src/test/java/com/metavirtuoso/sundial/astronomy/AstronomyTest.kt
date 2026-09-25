package com.metavirtuoso.sundial.astronomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class AstronomyTest {
    @Test fun `Unix epoch converts to canonical Julian date`() {
        assertEquals(2_440_587.5, Astronomy.julianDate(Instant.EPOCH), 1e-9)
    }

    @Test fun `J2000 instant and sidereal angle are correct`() {
        val j2000 = Instant.parse("2000-01-01T12:00:00Z")
        assertEquals(Astronomy.JULIAN_DATE_J2000, Astronomy.julianDate(j2000), 1e-9)
        assertEquals(280.46061837, Astronomy.greenwichMeanSiderealDegrees(j2000), 1e-7)
    }

    @Test fun `Gregorian century leap rules are honored`() {
        assertEquals(365, Astronomy.daysInYear(1900))
        assertEquals(366, Astronomy.daysInYear(2000))
        assertEquals(365, Astronomy.daysInYear(2100))
        assertEquals(366, Astronomy.daysInYear(2024))
    }

    @Test fun `civil year fraction places leap day after 59 complete days`() {
        val utc = ZoneId.of("UTC")
        val leapDay = ZonedDateTime.of(2024, 2, 29, 0, 0, 0, 0, utc)
        assertEquals(59.0 / 366.0, Astronomy.civilYearFraction(leapDay), 1e-12)
    }

    @Test fun `year fraction round trip uses requested year and zone`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val target = ZonedDateTime.of(2024, 7, 2, 12, 0, 0, 0, zone)
        val fraction = Astronomy.civilYearFraction(target)
        val rebuilt = Astronomy.instantAtYearFraction(2024, fraction, zone).atZone(zone)
        assertEquals(target.toLocalDate(), rebuilt.toLocalDate())
        assertTrue(abs(target.hour - rebuilt.hour) <= 1)
    }

    @Test fun `JPL approximate planet vectors track Horizons at J2000`() {
        val time = Instant.parse("2000-01-01T12:00:00Z")
        // Reference vectors are NASA/JPL Horizons heliocentric ecliptic AU values.
        val expected = mapOf(
            Astronomy.Body.MERCURY to doubleArrayOf(-0.130094, -0.447288, -0.024598),
            Astronomy.Body.VENUS to doubleArrayOf(-0.718302, -0.032654, 0.041014),
            Astronomy.Body.EARTH to doubleArrayOf(-0.177159, 0.967219, -0.000001),
            Astronomy.Body.MARS to doubleArrayOf(1.390716, -0.013416, -0.034468),
        )
        expected.forEach { (body, reference) ->
            val actual = Astronomy.heliocentricPosition(body, time)
            assertEquals("$body x", reference[0], actual.x, 0.012)
            assertEquals("$body y", reference[1], actual.y, 0.012)
            assertEquals("$body z", reference[2], actual.z, 0.012)
        }
    }

    @Test fun `lunar phase is near zero at a published new moon`() {
        val phase = Astronomy.moonPhaseDegrees(Instant.parse("2000-01-06T18:14:00Z"))
        val distanceFromNew = minOf(phase, 360.0 - phase)
        assertTrue("phase=$phase", distanceFromNew < 2.0)
    }

    @Test fun `lunar phase compares Moon and Sun in the same equinox frame`() {
        // New Moon of the 8 April 2024 total solar eclipse, 18:21 UTC.
        val phase = Astronomy.moonPhaseDegrees(Instant.parse("2024-04-08T18:21:00Z"))
        assertTrue("phase=$phase", minOf(phase, 360.0 - phase) < .5)
    }

    @Test fun `lunar phase is near full at a published full moon`() {
        val phase = Astronomy.moonPhaseDegrees(Instant.parse("2024-03-25T07:00:00Z"))
        assertTrue("phase=$phase", abs(phase - 180.0) < 3.0)
    }

    @Test fun `annual mapping never depends on current system year`() {
        val old = ZonedDateTime.parse("1984-12-31T12:00:00Z")
        val modern = ZonedDateTime.parse("2024-12-31T12:00:00Z")
        assertTrue(Astronomy.civilYearFraction(old) > 0.998)
        assertTrue(Astronomy.civilYearFraction(modern) > 0.998)
        assertEquals(LocalDate.of(1984, 12, 31), Astronomy.instantAtYearFraction(1984, Astronomy.civilYearFraction(old), ZoneId.of("UTC")).atZone(ZoneId.of("UTC")).toLocalDate())
    }
}
