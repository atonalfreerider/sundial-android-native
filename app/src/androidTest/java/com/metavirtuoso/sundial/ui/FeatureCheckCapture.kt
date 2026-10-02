package com.metavirtuoso.sundial.ui

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metavirtuoso.sundial.calendar.CalendarOccurrence
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Renders the instrument in configurations that are awkward to reach by hand, for a visual check:
 * the brass globe and Moon, the alarm tooth, holiday icons and each element's palette. Nothing is
 * saved to the app's settings. Opt-in:
 *
 *   adb shell am instrument -w -e featureCheck true \
 *     -e class com.metavirtuoso.sundial.ui.FeatureCheckCapture \
 *     com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Files land in /sdcard/Android/data/com.metavirtuoso.sundial/files/feature-check/.
 */
@RunWith(AndroidJUnit4::class)
class FeatureCheckCapture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val zone = ZoneId.systemDefault()
    private val instant = LocalDate.of(2026, 10, 2).atTime(12, 20).atZone(zone).toInstant()
    private val out by lazy { File(context.getExternalFilesDir(null), "feature-check").apply { mkdirs() } }

    private fun holidays(): List<CalendarOccurrence> = listOf(
        "New Year's Day" to LocalDate.of(2026, 1, 1),
        "Independence Day" to LocalDate.of(2026, 7, 4),
        "Independence Day (observed)" to LocalDate.of(2026, 7, 3),
        "Halloween" to LocalDate.of(2026, 10, 31),
        "Thanksgiving Day" to LocalDate.of(2026, 11, 26),
        "Christmas Eve" to LocalDate.of(2026, 12, 24),
        "Christmas Day" to LocalDate.of(2026, 12, 25),
    ).mapIndexed { i, (title, date) ->
        CalendarOccurrence(100L + i, HOLIDAYS, title, date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone),
            date, date.plusDays(1), 0xFF0B8043.toInt())
    }

    @Test fun capture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("featureCheck") == "true")
        instrumentation.runOnMainSync {
            render("brass-earth", SundialView.ViewState.GEOCENTRIC, CelestialStyle.BRASS_WATCH, null, alarm = true)
            render("brass-solar", SundialView.ViewState.HELIOCENTRIC, CelestialStyle.BRASS_WATCH, null, alarm = true)
            render("crimson-earth-alarm", SundialView.ViewState.GEOCENTRIC, CelestialStyle.CRIMSON_NEBULA, null, alarm = true)
            listOf(
                "fire" to LocalDate.of(1990, 4, 18),
                "earth" to LocalDate.of(1990, 5, 5),
                "air" to LocalDate.of(1990, 6, 5),
                "water" to LocalDate.of(1990, 7, 5),
            ).forEach { (element, birthday) ->
                render("astrology-$element", SundialView.ViewState.HELIOCENTRIC, CelestialStyle.CRIMSON_NEBULA,
                    ZodiacProfile(enabled = true, birthDate = birthday, birthTime = LocalTime.of(6, 45)))
            }
            render("astrology-brass", SundialView.ViewState.HELIOCENTRIC, CelestialStyle.BRASS_WATCH,
                ZodiacProfile(enabled = true, birthDate = LocalDate.of(1990, 4, 18), birthTime = LocalTime.of(6, 45)))
        }
    }

    private fun render(name: String, state: SundialView.ViewState, style: CelestialStyle, profile: ZodiacProfile?, alarm: Boolean = false) {
        val view = SundialView(context)
        view.setSelectedCalendarIds(setOf(HOLIDAYS))
        view.setHolidayCalendarIds(setOf(HOLIDAYS))
        view.setCalendarOccurrences(holidays())
        view.setAstrologyContentForTest(profile ?: ZodiacProfile(enabled = false), null)
        if (alarm) view.setNextAlarm(instant.plusSeconds(5 * 3600L + 40 * 60L))
        view.freezeForCapture(instant, state, style)
        val bitmap = view.renderWallpaperBitmap(1080, 2160, instant, state)
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object { const val HOLIDAYS = 9L }
}
