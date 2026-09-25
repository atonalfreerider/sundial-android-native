package com.metavirtuoso.sundial.calendar

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs against the real CalendarContract provider on a deployment device.
 * Before running, grant READ_CALENDAR to the target package with adb as documented in README.md.
 */
@RunWith(AndroidJUnit4::class)
class GoogleCalendarIntegrationTest {
    @Test fun syncedCalendarProviderRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            val command = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.READ_CALENDAR}",
            )
            FileInputStream(command.fileDescriptor).bufferedReader().use { it.readText() }
            command.close()
        }
        assertEquals(
            "The deployment test must be able to grant READ_CALENDAR",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR),
        )
        val repository = CalendarRepository(context)
        val calendars = repository.loadCalendars()
        assertEquals(calendars.map { it.id }.distinct().size, calendars.size)
        calendars.forEach {
            assertTrue("Calendar display names must not be blank", it.displayName.isNotBlank())
            if (it.accountType.equals("com.google", ignoreCase = true)) assertTrue(it.isGoogle)
        }

        val selected = calendars.filter { it.isGoogle }.ifEmpty { calendars.take(1) }.map { it.id }.toSet()
        if (selected.isEmpty()) return // A fresh device may legitimately have no synced calendars.

        val zone = ZoneId.systemDefault()
        val year = LocalDate.now(zone).year
        val begin = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant()
        val occurrences = repository.loadInstances(selected, begin, end, zone)
        occurrences.forEach { event ->
            assertTrue(event.calendarId in selected)
            assertTrue("Calendar end must be exclusive and after start", event.endExclusive.isAfter(event.start))
            if (event.isAllDay) {
                assertNotNull(event.allDayStart)
                assertTrue(event.allDayEndExclusive!!.isAfter(event.allDayStart))
            }
        }
    }
}
