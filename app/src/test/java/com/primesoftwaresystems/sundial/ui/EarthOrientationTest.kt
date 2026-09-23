package com.primesoftwaresystems.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.hypot

class EarthOrientationTest {
    @Test fun `geographic pole keeps true obliquity while rotating in sun-up frame`() {
        val march = EarthOrientation.projectedNorthPole(Instant.parse("2026-03-20T12:00:00Z"), north = true)
        val june = EarthOrientation.projectedNorthPole(Instant.parse("2026-06-21T12:00:00Z"), north = true)

        assertEquals(Math.sin(Math.toRadians(23.43928)), hypot(march.first, march.second), .008)
        assertEquals(Math.sin(Math.toRadians(23.43928)), hypot(june.first, june.second), .008)
        assertTrue("Fixed screen tilt is incorrect in a Sun-up reference frame",
            hypot(march.first - june.first, march.second - june.second) > .45)
    }

    @Test fun `south view reverses screen handedness without changing sunward axis`() {
        val instant = Instant.parse("2026-09-22T19:30:00Z")
        val north = EarthOrientation.frame(instant, north = true)
        val south = EarthOrientation.frame(instant, north = false)

        assertEquals(-north.right.x, south.right.x, 1e-12)
        assertEquals(-north.right.y, south.right.y, 1e-12)
        assertEquals(north.sunward.x, south.sunward.x, 1e-12)
        assertEquals(north.sunward.y, south.sunward.y, 1e-12)
    }
}
