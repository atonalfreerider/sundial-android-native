package com.primesoftwaresystems.sundial.ui

import com.primesoftwaresystems.sundial.astronomy.Zodiac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonBandsTest {
    private val starts = SeasonBands.starts(2026)
    private fun fraction(dayOfYear: Int) = (dayOfYear - 1) / 365.0

    @Test fun `mid-season dates are a single season`() {
        assertEquals(SeasonBands.Mix(Zodiac.Season.WINTER, Zodiac.Season.WINTER, 0.0), SeasonBands.mixAt(fraction(20), starts, 365))
        assertEquals(Zodiac.Season.SUMMER, SeasonBands.mixAt(fraction(200), starts, 365).to)
        assertEquals(0.0, SeasonBands.mixAt(fraction(200), starts, 365).amount, 0.0)
    }

    @Test fun `seasons fade into each other across each solstice and equinox`() {
        val equinox = java.time.LocalDate.of(2026, 3, 21).dayOfYear
        val before = SeasonBands.mixAt(fraction(equinox - 6), starts, 365)
        val at = SeasonBands.mixAt(fraction(equinox), starts, 365)
        val after = SeasonBands.mixAt(fraction(equinox + 6), starts, 365)
        listOf(before, at, after).forEach {
            assertEquals(Zodiac.Season.WINTER, it.from)
            assertEquals(Zodiac.Season.SPRING, it.to)
        }
        assertTrue(before.amount < at.amount && at.amount < after.amount)
        assertEquals(.5, at.amount, .03)
    }

    @Test fun `winter blends into itself across New Year without a seam`() {
        assertEquals(Zodiac.Season.WINTER, SeasonBands.mixAt(0.0, starts, 365).to)
        assertEquals(Zodiac.Season.WINTER, SeasonBands.mixAt(.9999, starts, 365).to)
    }
}
