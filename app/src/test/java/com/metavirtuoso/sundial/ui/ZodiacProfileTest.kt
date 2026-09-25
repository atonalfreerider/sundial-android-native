package com.metavirtuoso.sundial.ui

import com.metavirtuoso.sundial.astronomy.Zodiac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ZodiacProfileTest {
    @Test fun `zodiac and horoscope features are opt in`() {
        assertFalse(ZodiacProfile().enabled)
        assertFalse(ZodiacProfile().isComplete)
    }

    @Test fun `birthday resolves common sun sign and complete profile`() {
        val profile = ZodiacProfile(
            enabled = true,
            birthDate = LocalDate.of(1990, 10, 23),
            birthTime = LocalTime.of(8, 45),
        )
        assertEquals(Zodiac.Sign.SCORPIO, profile.resolvedSign())
        assertTrue(profile.isComplete)
    }

    @Test fun `mode-only updates cannot erase stored birth date and time`() {
        val stored = ZodiacProfile(
            enabled = true,
            birthDate = LocalDate.of(1984, 2, 29),
            birthTime = LocalTime.of(23, 7),
        )

        val result = ZodiacPreferences.preserveNatalData(ZodiacProfile(enabled = false), stored)

        assertFalse(result.enabled)
        assertEquals(stored.birthDate, result.birthDate)
        assertEquals(stored.birthTime, result.birthTime)
    }
}
