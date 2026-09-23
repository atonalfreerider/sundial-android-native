package com.primesoftwaresystems.sundial.astronomy

import java.time.Instant
import java.time.LocalDate
import kotlin.math.atan2

/** The common Western tropical zodiac and geocentric sign calculations. */
object Zodiac {
    enum class Sign(
        val displayName: String,
        val symbol: String,
        val element: Element,
        val startMonth: Int,
        val startDay: Int,
    ) {
        ARIES("Aries", "♈︎", Element.FIRE, 3, 21),
        TAURUS("Taurus", "♉︎", Element.EARTH, 4, 20),
        GEMINI("Gemini", "♊︎", Element.AIR, 5, 21),
        CANCER("Cancer", "♋︎", Element.WATER, 6, 21),
        LEO("Leo", "♌︎", Element.FIRE, 7, 23),
        VIRGO("Virgo", "♍︎", Element.EARTH, 8, 23),
        LIBRA("Libra", "♎︎", Element.AIR, 9, 23),
        SCORPIO("Scorpio", "♏︎", Element.WATER, 10, 23),
        SAGITTARIUS("Sagittarius", "♐︎", Element.FIRE, 11, 22),
        CAPRICORN("Capricorn", "♑︎", Element.EARTH, 12, 22),
        AQUARIUS("Aquarius", "♒︎", Element.AIR, 1, 20),
        PISCES("Pisces", "♓︎", Element.WATER, 2, 19),
    }

    enum class Element { FIRE, EARTH, AIR, WATER }
    enum class Season { SPRING, SUMMER, FALL, WINTER }

    data class Placement(val label: String, val symbol: String, val longitudeDegrees: Double, val sign: Sign)

    /** Common civil-date Sun sign, intentionally distinct from a sidereal constellation calendar. */
    fun signFor(date: LocalDate): Sign {
        val monthDay = date.monthValue * 100 + date.dayOfMonth
        return Sign.entries
            .sortedBy { it.startMonth * 100 + it.startDay }
            .lastOrNull { monthDay >= it.startMonth * 100 + it.startDay }
            ?: Sign.CAPRICORN
    }

    fun signForLongitude(longitudeDegrees: Double): Sign =
        Sign.entries[(Astronomy.normalizeDegrees(longitudeDegrees) / 30.0).toInt().coerceIn(0, 11)]

    fun seasonFor(date: LocalDate, northernHemisphere: Boolean): Season {
        val northern = when (signFor(date)) {
            Sign.ARIES, Sign.TAURUS, Sign.GEMINI -> Season.SPRING
            Sign.CANCER, Sign.LEO, Sign.VIRGO -> Season.SUMMER
            Sign.LIBRA, Sign.SCORPIO, Sign.SAGITTARIUS -> Season.FALL
            Sign.CAPRICORN, Sign.AQUARIUS, Sign.PISCES -> Season.WINTER
        }
        if (northernHemisphere) return northern
        return when (northern) {
            Season.SPRING -> Season.FALL
            Season.SUMMER -> Season.WINTER
            Season.FALL -> Season.SPRING
            Season.WINTER -> Season.SUMMER
        }
    }

    /**
     * Geocentric tropical longitude for the instrument hands. Planet vectors are J2000 ecliptic;
     * general precession moves them into the equinox of date before assigning a tropical sign.
     */
    fun geocentricLongitude(body: Astronomy.Body, instant: Instant): Double {
        require(body != Astronomy.Body.EARTH)
        val earth = Astronomy.heliocentricPosition(Astronomy.Body.EARTH, instant)
        val planet = Astronomy.heliocentricPosition(body, instant)
        val j2000Longitude = Math.toDegrees(atan2(planet.y - earth.y, planet.x - earth.x))
        return Astronomy.normalizeDegrees(j2000Longitude + precessionDegrees(instant))
    }

    fun sunLongitude(instant: Instant): Double {
        val earth = Astronomy.heliocentricPosition(Astronomy.Body.EARTH, instant)
        return Astronomy.normalizeDegrees(earth.longitudeDegrees + 180.0 + precessionDegrees(instant))
    }

    fun placements(instant: Instant): List<Placement> = listOf(
        Placement("SUN", "☉", sunLongitude(instant), signForLongitude(sunLongitude(instant))),
        Placement("MOON", "☾︎", Astronomy.moonLongitudeDegrees(instant), signForLongitude(Astronomy.moonLongitudeDegrees(instant))),
        Placement("MERCURY", "☿", geocentricLongitude(Astronomy.Body.MERCURY, instant), signForLongitude(geocentricLongitude(Astronomy.Body.MERCURY, instant))),
        Placement("VENUS", "♀", geocentricLongitude(Astronomy.Body.VENUS, instant), signForLongitude(geocentricLongitude(Astronomy.Body.VENUS, instant))),
    )

    private fun precessionDegrees(instant: Instant): Double {
        val centuries = (Astronomy.julianDate(instant) - Astronomy.JULIAN_DATE_J2000) / 36_525.0
        return 1.397 * centuries + 0.00031 * centuries * centuries
    }
}
