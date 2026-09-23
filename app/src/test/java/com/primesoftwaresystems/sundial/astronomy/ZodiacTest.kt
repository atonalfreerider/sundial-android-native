package com.primesoftwaresystems.sundial.astronomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ZodiacTest {
    @Test fun `common tropical zodiac boundaries are current and leap safe`() {
        assertEquals(Zodiac.Sign.CAPRICORN, Zodiac.signFor(LocalDate.of(2024, 1, 19)))
        assertEquals(Zodiac.Sign.AQUARIUS, Zodiac.signFor(LocalDate.of(2024, 1, 20)))
        assertEquals(Zodiac.Sign.AQUARIUS, Zodiac.signFor(LocalDate.of(2024, 2, 18)))
        assertEquals(Zodiac.Sign.PISCES, Zodiac.signFor(LocalDate.of(2024, 2, 19)))
        assertEquals(Zodiac.Sign.PISCES, Zodiac.signFor(LocalDate.of(2024, 2, 29)))
        assertEquals(Zodiac.Sign.ARIES, Zodiac.signFor(LocalDate.of(2024, 3, 21)))
        assertEquals(Zodiac.Sign.VIRGO, Zodiac.signFor(LocalDate.of(2026, 9, 22)))
        assertEquals(Zodiac.Sign.LIBRA, Zodiac.signFor(LocalDate.of(2026, 9, 23)))
        assertEquals(Zodiac.Sign.CAPRICORN, Zodiac.signFor(LocalDate.of(2026, 12, 31)))
        Zodiac.Sign.entries.forEach { sign ->
            val boundary = LocalDate.of(2026, sign.startMonth, sign.startDay)
            assertEquals("${sign.displayName} start", sign, Zodiac.signFor(boundary))
            val previous = Zodiac.Sign.entries[(sign.ordinal + 11) % 12]
            assertEquals("day before ${sign.displayName}", previous, Zodiac.signFor(boundary.minusDays(1)))
        }
    }

    @Test fun `longitude boundaries map to canonical signs`() {
        assertEquals(Zodiac.Sign.ARIES, Zodiac.signForLongitude(0.0))
        assertEquals(Zodiac.Sign.ARIES, Zodiac.signForLongitude(29.999))
        assertEquals(Zodiac.Sign.TAURUS, Zodiac.signForLongitude(30.0))
        assertEquals(Zodiac.Sign.PISCES, Zodiac.signForLongitude(359.999))
        assertEquals(Zodiac.Sign.PISCES, Zodiac.signForLongitude(-0.001))
    }

    @Test fun `geocentric placements are finite and exclude Earth`() {
        val placements = Zodiac.placements(Instant.parse("2026-09-22T12:00:00Z"))
        assertEquals(listOf("SUN", "MOON", "MERCURY", "VENUS"), placements.map { it.label })
        assertTrue(placements.all { it.longitudeDegrees in 0.0..<360.0 })
    }

    @Test fun `seasons reverse between hemispheres`() {
        val date = LocalDate.of(2026, 7, 15)
        assertEquals(Zodiac.Season.SUMMER, Zodiac.seasonFor(date, true))
        assertEquals(Zodiac.Season.WINTER, Zodiac.seasonFor(date, false))
    }
}
