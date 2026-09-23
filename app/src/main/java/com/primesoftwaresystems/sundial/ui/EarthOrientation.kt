package com.primesoftwaresystems.sundial.ui

import kotlin.math.cos
import kotlin.math.sin

/**
 * Earth seen from the ecliptic pole with the Sun at the top of the screen, as in Unity's Earth
 * camera. The rotation axis is fixed in space (tilted toward ecliptic longitude 90°), so relative to
 * the Sun it swings around with the seasons: toward the Sun at the June solstice, away in December.
 */
internal object EarthOrientation {
    const val OBLIQUITY_DEGREES = 23.43928

    /**
     * Screen position (x right, y up, in globe radii) of the visible geographic pole: the north pole
     * from the north ecliptic view, the south pole from the mirrored southern view.
     */
    fun projectedGeographicPole(north: Boolean, sunLongitudeDegrees: Double): Pair<Double, Double> {
        val tilt = sin(Math.toRadians(OBLIQUITY_DEGREES))
        val lambda = Math.toRadians(sunLongitudeDegrees)
        val x = -tilt * cos(lambda)
        val y = tilt * sin(lambda)
        return x to if (north) y else -y
    }

    /**
     * Maps a unit vector in screen space (x right, y up toward the Sun, z toward the viewer) to the
     * equatorial frame of date. Returns (x, y, z) with z toward the north celestial pole.
     */
    fun screenToEquatorial(
        sx: Double,
        sy: Double,
        sz: Double,
        sunLongitudeDegrees: Double,
        north: Boolean,
    ): Triple<Double, Double, Double> {
        val lambda = Math.toRadians(sunLongitudeDegrees)
        val sinL = sin(lambda)
        val cosL = cos(lambda)
        // Screen right is 90° clockwise of the Sun (north view); the southern view is its mirror
        // image seen from below the ecliptic.
        val right = if (north) sx else -sx
        val toward = if (north) sz else -sz
        val eclX = right * sinL + sy * cosL
        val eclY = -right * cosL + sy * sinL
        val eclZ = toward
        val obliquity = Math.toRadians(OBLIQUITY_DEGREES)
        return Triple(
            eclX,
            eclY * cos(obliquity) - eclZ * sin(obliquity),
            eclY * sin(obliquity) + eclZ * cos(obliquity),
        )
    }
}
