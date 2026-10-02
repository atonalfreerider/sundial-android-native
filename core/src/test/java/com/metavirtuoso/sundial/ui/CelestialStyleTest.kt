package com.metavirtuoso.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class CelestialStyleTest {
    @Test fun `element palettes are hidden and astrology selects them`() {
        assertFalse(CelestialStyle.FIRE.pickable)
        assertEquals(CelestialStyle.FIRE, CelestialStyle.effective(CelestialStyle.VOID_BLACK,
            ZodiacProfile(enabled = true, birthDate = LocalDate.of(1990, 4, 18))))
        assertEquals(CelestialStyle.EARTH, CelestialStyle.effective(CelestialStyle.CRIMSON_NEBULA,
            ZodiacProfile(enabled = true, birthDate = LocalDate.of(1990, 5, 5))))
    }

    @Test fun `brass overrides an astrology element`() {
        assertEquals(CelestialStyle.BRASS_WATCH, CelestialStyle.effective(CelestialStyle.BRASS_WATCH,
            ZodiacProfile(enabled = true, birthDate = LocalDate.of(1990, 7, 5))))
    }
}
