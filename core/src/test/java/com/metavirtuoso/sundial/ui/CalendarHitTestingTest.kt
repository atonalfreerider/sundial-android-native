package com.metavirtuoso.sundial.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarHitTestingTest {
    @Test fun `annual event hit target includes small touch padding without selecting distant dates`() {
        assertTrue(CalendarHitTesting.containsYearFraction(.250, .248, .003, .002))
        assertTrue(CalendarHitTesting.containsYearFraction(.247, .248, .003, .002))
        assertFalse(CalendarHitTesting.containsYearFraction(.300, .248, .003, .002))
    }

    @Test fun `annual hit testing handles new year wraparound padding`() {
        assertTrue(CalendarHitTesting.containsYearFraction(.999, 0.0, .002, .003))
        assertTrue(CalendarHitTesting.containsYearFraction(.003, 0.0, .002, .003))
    }

    @Test fun `daily hit testing accepts the event chord and rejects other hours`() {
        assertTrue(CalendarHitTesting.containsMinute(9.0 * 60.0 + 15.0, 540.0, 570.0, 8.0))
        assertTrue(CalendarHitTesting.containsMinute(535.0, 540.0, 570.0, 8.0))
        assertFalse(CalendarHitTesting.containsMinute(600.0, 540.0, 570.0, 8.0))
    }
}
