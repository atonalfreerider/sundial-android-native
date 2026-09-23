package com.primesoftwaresystems.sundial.ui

import kotlin.math.sin

/** Stable top-down globe frame: solar north/south camera with a visibly off-axis geographic pole. */
internal object EarthOrientation {
    const val OBLIQUITY_DEGREES = 23.43928

    /** Projected geographic pole. Screen y is positive upward and the Sun remains at screen top. */
    fun projectedGeographicPole(north: Boolean): Pair<Double, Double> =
        0.0 to sin(Math.toRadians(OBLIQUITY_DEGREES)) * if (north) 1.0 else -1.0
}
