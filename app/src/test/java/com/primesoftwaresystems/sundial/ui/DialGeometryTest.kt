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

    @Test fun `event bands follow Unity calendar channels rather than event order`() {
        val year0 = DialGeometry.yearEventBand(1000f, 0)
        val year1 = DialGeometry.yearEventBand(1000f, 1)
        assertEquals(46.64f, year0.thickness, .01f)
        assertEquals(year0.thickness, year0.centerRadius - year1.centerRadius, .01f)

        val day0 = DialGeometry.dayEventBand(600f, 0)
        assertEquals(69.96f, day0.thickness, .01f)
        assertTrue(day0.centerRadius < 600f)
    }
}
