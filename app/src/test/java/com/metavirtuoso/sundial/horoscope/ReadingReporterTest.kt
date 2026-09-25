package com.metavirtuoso.sundial.horoscope

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingReporterTest {
    @Test fun `form field names map report fields onto a Google Form`() {
        assertEquals(
            mapOf("reason" to "entry.11", "reading" to "entry.22", "version" to "entry.44"),
            ReadingReporter.fieldNames(" reason=entry.11, reading = entry.22 ,date=,version=entry.44,junk"),
        )
        assertEquals(emptyMap<String, String>(), ReadingReporter.fieldNames(""))
    }
}
