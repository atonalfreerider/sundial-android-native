package com.metavirtuoso.sundial.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidayIconsTest {
    @Test fun `holiday calendars are recognised by name`() {
        assertTrue(HolidayIcons.isHolidayCalendar("Holidays in United States"))
        assertTrue(HolidayIcons.isHolidayCalendar("x", "en.usa#holiday@group.v.calendar.google.com"))
        assertFalse(HolidayIcons.isHolidayCalendar("Work"))
    }

    @Test fun `specific titles win over the general ones`() {
        assertEquals("🥂", HolidayIcons.iconFor("New Year's Eve"))
        assertEquals("🎉", HolidayIcons.iconFor("New Year's Day"))
        assertEquals("🏮", HolidayIcons.iconFor("Lunar New Year"))
        assertEquals("🌟", HolidayIcons.iconFor("Christmas Eve"))
        assertEquals("🎄", HolidayIcons.iconFor("Christmas Day"))
        assertEquals("🛍️", HolidayIcons.iconFor("Day after Thanksgiving"))
        assertEquals("🦃", HolidayIcons.iconFor("Thanksgiving Day"))
        assertEquals("🎆", HolidayIcons.iconFor("Independence Day"))
        assertEquals("✊", HolidayIcons.iconFor("Martin Luther King Jr. Day"))
    }

    @Test fun `unknown holidays get the star`() {
        assertEquals(HolidayIcons.FALLBACK, HolidayIcons.iconFor("Some Local Festival"))
    }
}
