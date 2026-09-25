package com.metavirtuoso.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.asin
import kotlin.math.sin

class EarthOrientationTest {
    private val tilt = sin(Math.toRadians(23.43928))

    @Test fun `north pole leans toward the Sun in June and away in December`() {
        val june = EarthOrientation.projectedGeographicPole(north = true, sunLongitudeDegrees = 90.0)
        assertEquals(0.0, june.first, 1e-12)
        assertEquals(tilt, june.second, 1e-12)

        val december = EarthOrientation.projectedGeographicPole(north = true, sunLongitudeDegrees = 270.0)
        assertEquals(0.0, december.first, 1e-12)
        assertEquals(-tilt, december.second, 1e-12)
    }

    @Test fun `pole leans sideways at the equinoxes and mirrors in the south view`() {
        val march = EarthOrientation.projectedGeographicPole(north = true, sunLongitudeDegrees = 0.0)
        assertEquals(-tilt, march.first, 1e-12)
        assertEquals(0.0, march.second, 1e-12)
        val september = EarthOrientation.projectedGeographicPole(north = true, sunLongitudeDegrees = 180.0)
        assertEquals(tilt, september.first, 1e-12)

        val southJune = EarthOrientation.projectedGeographicPole(north = false, sunLongitudeDegrees = 90.0)
        assertEquals(-tilt, southJune.second, 1e-12)
    }

    @Test fun `sun facing limb is the subsolar point`() {
        // June solstice: the Sun's declination equals the obliquity.
        val june = EarthOrientation.screenToEquatorial(0.0, 1.0, 0.0, 90.0, north = true)
        assertEquals(23.43928, Math.toDegrees(asin(june.third)), 1e-9)
        // March equinox: the Sun sits on the equator at right ascension 0.
        val march = EarthOrientation.screenToEquatorial(0.0, 1.0, 0.0, 0.0, north = false)
        assertEquals(1.0, march.first, 1e-12)
        assertEquals(0.0, march.third, 1e-12)
        // The view axis is the ecliptic pole in both hemispheres.
        val north = EarthOrientation.screenToEquatorial(0.0, 0.0, 1.0, 45.0, north = true)
        assertEquals(90.0 - 23.43928, Math.toDegrees(asin(north.third)), 1e-9)
        val south = EarthOrientation.screenToEquatorial(0.0, 0.0, 1.0, 45.0, north = false)
        assertEquals(-(90.0 - 23.43928), Math.toDegrees(asin(south.third)), 1e-9)
    }
}
