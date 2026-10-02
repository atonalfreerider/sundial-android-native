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
            birthDate = LocalDate.of(1990, 11, 5),
            birthTime = LocalTime.of(8, 45),
        )
        assertEquals(Zodiac.Sign.SCORPIO, profile.resolvedSign())
        assertTrue(profile.isComplete)
    }

    @Test fun `birth time and zone settle a cusp day`() {
        // The Sun entered Scorpio late on 23 October 1990 (UTC): the morning was still Libra.
        fun born(time: LocalTime, zone: String) = ZodiacProfile(
            birthDate = LocalDate.of(1990, 10, 23), birthTime = time, birthZoneId = zone,
        ).resolvedSign()
        assertEquals(Zodiac.Sign.LIBRA, born(LocalTime.of(8, 45), "America/Los_Angeles"))
        assertEquals(Zodiac.Sign.SCORPIO, born(LocalTime.of(23, 30), "America/Los_Angeles"))
        // Without a time, the date's usual boundary decides.
        assertEquals(Zodiac.Sign.SCORPIO, ZodiacProfile(birthDate = LocalDate.of(1990, 10, 23)).resolvedSign())
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
