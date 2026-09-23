package com.primesoftwaresystems.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class BirthDateInputTest {
    private val today = LocalDate.of(2026, 9, 22)

    @Test fun `direct four digit birth year accepts Gregorian leap day`() {
        assertEquals(LocalDate.of(2000, 2, 29), BirthDateInput.parse("02", "29", "2000", today))
    }

    @Test fun `century year that is not divisible by 400 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BirthDateInput.parse("2", "29", "1900", today)
        }
    }

    @Test fun `short and future birth years are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BirthDateInput.parse("9", "22", "86", today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BirthDateInput.parse("9", "23", "2026", today)
        }
    }
}
