package com.metavirtuoso.sundial.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalTime

class BirthTimeInputTest {
    @Test fun `twelve hour entry maps midnight noon and evening`() {
        assertEquals(LocalTime.of(0, 5), BirthTimeInput.parse("12", "05", pm = false))
        assertEquals(LocalTime.of(12, 0), BirthTimeInput.parse("12", "00", pm = true))
        assertEquals(LocalTime.of(19, 42), BirthTimeInput.parse("7", "42", pm = true))
        assertEquals(LocalTime.of(9, 7), BirthTimeInput.parse("09", "7", pm = false))
    }

    @Test fun `out of range entries are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BirthTimeInput.parse("13", "00", pm = false) }
        assertThrows(IllegalArgumentException::class.java) { BirthTimeInput.parse("0", "00", pm = false) }
        assertThrows(IllegalArgumentException::class.java) { BirthTimeInput.parse("6", "60", pm = true) }
        assertThrows(IllegalArgumentException::class.java) { BirthTimeInput.parse("six", "00", pm = true) }
    }
}
