package com.primesoftwaresystems.sundial.ui

import com.primesoftwaresystems.sundial.astronomy.Astronomy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Unity's galactic view: the Sun carries the planets along an axis perpendicular to their orbits,
 * so each orbit is drawn out into a helix. As in Unity, the Sun stays put and an endless ribbon of
 * years slides beneath it; later years lie further along the direction of travel.
 *
 * Screen vectors use Android canvas axes (x right, y down).
 */
internal object GalacticGeometry {
    /** Direction of travel on screen: up and to the left. */
    private val travelRadians = Math.toRadians(-122.0)
    val travelX = cos(travelRadians).toFloat()
    val travelY = sin(travelRadians).toFloat()

    /** Perpendicular to travel, pointing to its right, so orbits run counter-clockwise from above. */
    val sideX = -travelY
    val sideY = travelX

    /** Distance the Sun travels in one year, in dial radii. */
    const val YEAR_PITCH = .62f

    /** Foreshortening of each orbit's depth, seen obliquely from the direction of travel. */
    const val ORBIT_DEPTH = .24f

    const val MIN_YEAR = 1
    const val MAX_YEAR = 9_999

    /** Local civil date as a continuous year: 2026.5 is the middle of 2026. */
    fun continuousYear(instant: Instant, zone: ZoneId): Double {
        val local = instant.atZone(zone)
        return local.year + Astronomy.civilYearFraction(local)
    }

    /** Inverse of [continuousYear], clamped to the years a civil calendar label can show. */
    fun instantAt(continuousYear: Double, zone: ZoneId): Instant {
        val clamped = continuousYear.coerceIn(MIN_YEAR.toDouble(), MAX_YEAR + .999_999)
        val year = floor(clamped).toInt()
        return Astronomy.instantAtYearFraction(year, clamped - year, zone)
    }

    /** Continuous year at which a month begins, for the ribbon's month ticks. */
    fun monthStart(year: Int, month: Int): Double {
        val date = LocalDate.of(year, month, 1)
        return year + (date.dayOfYear - 1).toDouble() / Astronomy.daysInYear(year)
    }

    /**
     * A body's offset from the Sun as (along travel, to the side), in the same units as
     * [orbitRadius]. The far side of an orbit leans toward the direction of travel.
     */
    fun orbitOffset(longitudeDegrees: Double, orbitRadius: Float): Pair<Float, Float> {
        val longitude = Math.toRadians(longitudeDegrees)
        return Pair(sin(longitude).toFloat() * orbitRadius * ORBIT_DEPTH, cos(longitude).toFloat() * orbitRadius)
    }
}
