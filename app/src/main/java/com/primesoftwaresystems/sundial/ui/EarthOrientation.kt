package com.primesoftwaresystems.sundial.ui

import com.primesoftwaresystems.sundial.astronomy.Astronomy
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Coordinate frame for viewing Earth from ecliptic (solar-system) north with the Sun at screen top. */
internal object EarthOrientation {
    private const val OBLIQUITY_DEGREES = 23.43928

    data class Vector(val x: Double, val y: Double, val z: Double) {
        operator fun times(scale: Double) = Vector(x * scale, y * scale, z * scale)
        operator fun plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)
        fun dot(other: Vector): Double = x * other.x + y * other.y + z * other.z
    }

    data class Frame(
        val right: Vector,
        val sunward: Vector,
        val viewer: Vector,
        val northPole: Vector,
        val primeMeridian: Vector,
        val eastAtPrimeMeridian: Vector,
    )

    fun frame(instant: Instant, north: Boolean): Frame {
        val earthLongitude = Astronomy.heliocentricPosition(Astronomy.Body.EARTH, instant).longitudeDegrees
        val sunLongitude = Math.toRadians(Astronomy.normalizeDegrees(earthLongitude + 180.0))
        val observerSign = if (north) 1.0 else -1.0
        val sunward = Vector(cos(sunLongitude), sin(sunLongitude), 0.0)
        val right = Vector(sin(sunLongitude) * observerSign, -cos(sunLongitude) * observerSign, 0.0)
        val viewer = Vector(0.0, 0.0, observerSign)

        val obliquity = OBLIQUITY_DEGREES * PI / 180.0
        val northPole = Vector(0.0, -sin(obliquity), cos(obliquity))
        val sidereal = Math.toRadians(Astronomy.greenwichMeanSiderealDegrees(instant))
        val primeMeridian = equatorialToEcliptic(cos(sidereal), sin(sidereal), 0.0, obliquity)
        val east = equatorialToEcliptic(-sin(sidereal), cos(sidereal), 0.0, obliquity)
        return Frame(right, sunward, viewer, northPole, primeMeridian, east)
    }

    /** Projected geographic north pole. Screen y is positive upward. */
    fun projectedNorthPole(instant: Instant, north: Boolean): Pair<Double, Double> {
        val frame = frame(instant, north)
        return frame.northPole.dot(frame.right) to frame.northPole.dot(frame.sunward)
    }

    private fun equatorialToEcliptic(
        x: Double,
        y: Double,
        z: Double,
        obliquity: Double,
    ): Vector = Vector(
        x,
        y * cos(obliquity) + z * sin(obliquity),
        -y * sin(obliquity) + z * cos(obliquity),
    )
}
