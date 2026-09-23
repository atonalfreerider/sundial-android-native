package com.primesoftwaresystems.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EarthOrientationTest {
    @Test fun `geographic pole stays stable and off axis in top-down frame`() {
        val pole = EarthOrientation.projectedGeographicPole(north = true)

        assertEquals(0.0, pole.first, 1e-12)
        assertEquals(Math.sin(Math.toRadians(23.43928)), pole.second, 1e-12)
        assertTrue(abs(pole.second) > .39 && abs(pole.second) < .41)
    }

    @Test fun `south view mirrors the projected geographic pole`() {
        val north = EarthOrientation.projectedGeographicPole(north = true)
        val south = EarthOrientation.projectedGeographicPole(north = false)

        assertEquals(north.first, south.first, 1e-12)
        assertEquals(-north.second, south.second, 1e-12)
    }
}
