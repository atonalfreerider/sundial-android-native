package com.primesoftwaresystems.sundial.astronomy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic astronomical state. All calculations receive an Instant; none read the system clock. */
object Astronomy {
    const val JULIAN_DATE_UNIX_EPOCH = 2_440_587.5
    const val JULIAN_DATE_J2000 = 2_451_545.0
    const val SYNODIC_MONTH_DAYS = 29.530588853

    enum class Body { MERCURY, VENUS, EARTH, MARS }

    data class Vector3(val x: Double, val y: Double, val z: Double) {
        val radius: Double get() = sqrt(x * x + y * y + z * z)
        val longitudeDegrees: Double get() = normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    private data class Elements(
        val a0: Double, val aRate: Double,
        val e0: Double, val eRate: Double,
        val i0: Double, val iRate: Double,
        val l0: Double, val lRate: Double,
        val peri0: Double, val periRate: Double,
        val node0: Double, val nodeRate: Double,
    )

    // JPL SSD Table 2a, J2000 ecliptic/equinox, valid 3000 BC through 3000 AD.
    private val elements = mapOf(
        Body.MERCURY to Elements(0.38709843, 0.0, 0.20563661, 0.00002123, 7.00559432, -0.00590158,
            252.25166724, 149472.67486623, 77.45771895, 0.15940013, 48.33961819, -0.12214182),
        Body.VENUS to Elements(0.72332102, -0.00000026, 0.00676399, -0.00005107, 3.39777545, 0.00043494,
            181.97970850, 58517.81560260, 131.76755713, 0.05679648, 76.67261496, -0.27274174),
        Body.EARTH to Elements(1.00000018, -0.00000003, 0.01673163, -0.00003661, -0.00054346, -0.01337178,
            100.46691572, 35999.37306329, 102.93005885, 0.31795260, -5.11260389, -0.24123856),
        Body.MARS to Elements(1.52371243, 0.00000097, 0.09336511, 0.00009149, 1.85181869, -0.00724757,
            -4.56813164, 19140.29934243, -23.91744784, 0.45223625, 49.71320984, -0.26852431),
    )

    fun julianDate(instant: Instant): Double =
        JULIAN_DATE_UNIX_EPOCH + instant.epochSecond / 86_400.0 + instant.nano / 86_400_000_000_000.0

    fun heliocentricPosition(body: Body, instant: Instant): Vector3 {
        val t = (julianDate(instant) - JULIAN_DATE_J2000) / 36_525.0
        val p = elements.getValue(body)
        val a = p.a0 + p.aRate * t
        val e = p.e0 + p.eRate * t
        val inclination = radians(p.i0 + p.iRate * t)
        val meanLongitude = p.l0 + p.lRate * t
        val longitudePerihelion = p.peri0 + p.periRate * t
        val node = radians(p.node0 + p.nodeRate * t)
        val omega = radians(longitudePerihelion - (p.node0 + p.nodeRate * t))
        val meanAnomaly = normalizeSignedDegrees(meanLongitude - longitudePerihelion)
        val eccentricAnomaly = solveKepler(radians(meanAnomaly), e)

        val xp = a * (cos(eccentricAnomaly) - e)
        val yp = a * sqrt(1.0 - e * e) * sin(eccentricAnomaly)
        val cosW = cos(omega)
        val sinW = sin(omega)
        val cosO = cos(node)
        val sinO = sin(node)
        val cosI = cos(inclination)
        val sinI = sin(inclination)

        return Vector3(
            (cosW * cosO - sinW * sinO * cosI) * xp + (-sinW * cosO - cosW * sinO * cosI) * yp,
            (cosW * sinO + sinW * cosO * cosI) * xp + (-sinW * sinO + cosW * cosO * cosI) * yp,
            sinW * sinI * xp + cosW * sinI * yp,
        )
    }

    /** Greenwich mean sidereal angle in degrees. Suitable for orienting the rendered Earth texture. */
    fun greenwichMeanSiderealDegrees(instant: Instant): Double {
        val d = julianDate(instant) - JULIAN_DATE_J2000
        val t = d / 36_525.0
        return normalizeDegrees(
            280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38_710_000.0
        )
    }

    /** Approximate geocentric lunar ecliptic longitude (degrees), including the largest periodic terms. */
    fun moonLongitudeDegrees(instant: Instant): Double {
        val d = julianDate(instant) - JULIAN_DATE_J2000
        val l = normalizeDegrees(218.3164477 + 13.17639648 * d)
        val elongation = normalizeDegrees(297.8501921 + 12.19074912 * d)
        val sunAnomaly = normalizeDegrees(357.5291092 + 0.98560028 * d)
        val moonAnomaly = normalizeDegrees(134.9633964 + 13.06499295 * d)
        val argumentLatitude = normalizeDegrees(93.2720950 + 13.22935024 * d)
        fun s(degrees: Double) = sin(radians(degrees))
        return normalizeDegrees(
            l + 6.289 * s(moonAnomaly)
                + 1.274 * s(2 * elongation - moonAnomaly)
                + 0.658 * s(2 * elongation)
                + 0.214 * s(2 * moonAnomaly)
                - 0.186 * s(sunAnomaly)
                - 0.059 * s(2 * elongation - 2 * moonAnomaly)
                - 0.057 * s(2 * elongation - sunAnomaly - moonAnomaly)
                + 0.053 * s(2 * elongation + moonAnomaly)
                + 0.046 * s(2 * elongation - sunAnomaly)
                + 0.041 * s(sunAnomaly - moonAnomaly)
                - 0.035 * s(elongation)
                - 0.031 * s(sunAnomaly + moonAnomaly)
                - 0.015 * s(2 * argumentLatitude - 2 * elongation)
                + 0.011 * s(moonAnomaly - 4 * elongation)
        )
    }

    fun moonPhaseDegrees(instant: Instant): Double {
        val apparentSunLongitude = heliocentricPosition(Body.EARTH, instant).longitudeDegrees + 180.0
        return normalizeDegrees(moonLongitudeDegrees(instant) - apparentSunLongitude)
    }

    fun daysInYear(year: Int): Int = if (LocalDate.of(year, 1, 1).isLeapYear) 366 else 365

    /** Fraction of the local civil year, preserving leap day and sub-day precision. */
    fun civilYearFraction(dateTime: ZonedDateTime): Double {
        val start = dateTime.toLocalDate().withDayOfYear(1).atStartOfDay(dateTime.zone).toInstant()
        val end = dateTime.toLocalDate().withDayOfYear(1).plusYears(1).atStartOfDay(dateTime.zone).toInstant()
        val totalNanos = (end.epochSecond - start.epochSecond) * 1_000_000_000.0 + end.nano - start.nano
        val elapsedNanos = (dateTime.toInstant().epochSecond - start.epochSecond) * 1_000_000_000.0 + dateTime.nano - start.nano
        return (elapsedNanos / totalNanos).coerceIn(0.0, 1.0)
    }

    fun instantAtYearFraction(year: Int, fraction: Double, zoneId: ZoneId): Instant {
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zoneId).toInstant()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zoneId).toInstant()
        val nanos = ((end.epochSecond - start.epochSecond) * fraction.coerceIn(0.0, 0.999999999) * 1e9).toLong()
        return start.plusNanos(nanos)
    }

    private fun solveKepler(meanAnomalyRadians: Double, eccentricity: Double): Double {
        var eccentricAnomaly = meanAnomalyRadians + eccentricity * sin(meanAnomalyRadians)
        repeat(20) {
            val delta = (meanAnomalyRadians - (eccentricAnomaly - eccentricity * sin(eccentricAnomaly))) /
                (1.0 - eccentricity * cos(eccentricAnomaly))
            eccentricAnomaly += delta
            if (kotlin.math.abs(delta) < 1e-12) return eccentricAnomaly
        }
        return eccentricAnomaly
    }

    fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    fun normalizeSignedDegrees(value: Double): Double = normalizeDegrees(value + 180.0) - 180.0
    private fun radians(degrees: Double): Double = degrees * PI / 180.0
}
