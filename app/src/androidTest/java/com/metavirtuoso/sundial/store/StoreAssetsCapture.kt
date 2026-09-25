package com.metavirtuoso.sundial.store

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metavirtuoso.sundial.R
import com.metavirtuoso.sundial.calendar.CalendarOccurrence
import com.metavirtuoso.sundial.calendar.DeviceCalendar
import com.metavirtuoso.sundial.ui.AstrologyPanel
import com.metavirtuoso.sundial.ui.CalendarPanel
import com.metavirtuoso.sundial.ui.CelestialStyle
import com.metavirtuoso.sundial.ui.SettingsPanel
import com.metavirtuoso.sundial.ui.SundialView
import com.metavirtuoso.sundial.ui.TuckMenuHost
import com.metavirtuoso.sundial.ui.ZodiacProfile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Renders the Play Store icon, feature graphic and phone screenshots from the app's own drawing
 * code, using sample data only (no real calendars or birth details). Opt-in:
 *
 *   adb shell am instrument -w -e storeAssets true \
 *     -e class com.metavirtuoso.sundial.store.StoreAssetsCapture \
 *     com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Files land in /sdcard/Android/data/com.metavirtuoso.sundial/files/store/.
 */
@RunWith(AndroidJUnit4::class)
class StoreAssetsCapture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val zone = ZoneId.of("America/Los_Angeles")
    private val instant = Instant.parse("2026-10-14T16:20:00Z")
    private val out by lazy { File(context.getExternalFilesDir(null), "store").apply { mkdirs() } }

    private val sampleProfile = ZodiacProfile(
        enabled = true,
        birthDate = LocalDate.of(1990, 4, 18),
        birthTime = LocalTime.of(6, 45),
    )
    private val sampleReading = "The brass gears of the heavens turn in your favour today, Aries. With the Sun " +
        "in Libra across from your own sign, partnerships ask for patience and a generous ear. The Moon " +
        "brightens your curiosity; follow a question you have been saving. Venus lends grace to a " +
        "difficult conversation, and by evening a small kindness returns to you twice over."

    private fun events(): List<CalendarOccurrence> {
        val today = instant.atZone(zone).toLocalDate()
        fun allDay(id: Long, calendar: Long, title: String, start: LocalDate, days: Long, color: Int) = CalendarOccurrence(
            id, calendar, title, start.atStartOfDay(zone), start.plusDays(days).atStartOfDay(zone), start, start.plusDays(days), color)
        fun timed(id: Long, calendar: Long, title: String, hour: Int, minutes: Long, color: Int): CalendarOccurrence {
            val start = today.atTime(hour, 0).atZone(zone)
            return CalendarOccurrence(id, calendar, title, start, start.plusMinutes(minutes), null, null, color)
        }
        return listOf(
            allDay(1, 1, "Harvest festival", LocalDate.of(2026, 9, 26), 3, 0xFFE8A33D.toInt()),
            allDay(2, 1, "Autumn road trip", LocalDate.of(2026, 10, 30), 9, 0xFF4CAF50.toInt()),
            allDay(3, 2, "Winter break", LocalDate.of(2026, 12, 19), 14, 0xFF42A5F5.toInt()),
            allDay(4, 2, "Spring term", LocalDate.of(2027, 1, 20), 30, 0xFFB388FF.toInt()),
            timed(5, 1, "Morning run", 7, 45, 0xFF4CAF50.toInt()),
            timed(6, 2, "Design review", 10, 90, 0xFFE040FB.toInt()),
            timed(7, 1, "Lunch with Sam", 12, 60, 0xFFE8A33D.toInt()),
            timed(8, 2, "Piano lesson", 17, 60, 0xFF42A5F5.toInt()),
        )
    }

    @Test fun captureStoreAssets() {
        assumeTrue("Pass -e storeAssets true to render store assets",
            InstrumentationRegistry.getArguments().getString("storeAssets") == "true")
        instrumentation.runOnMainSync {
            icon()
            featureGraphic()
            screen("1-solar-view", SundialView.ViewState.HELIOCENTRIC, CelestialStyle.CRIMSON_NEBULA, astrology = false)
            screen("2-earth-view", SundialView.ViewState.GEOCENTRIC, CelestialStyle.CRIMSON_NEBULA, astrology = false)
            screen("3-brass-astrology", SundialView.ViewState.HELIOCENTRIC, CelestialStyle.BRASS_WATCH, astrology = true)
            screen("4-brass-earth-view", SundialView.ViewState.GEOCENTRIC, CelestialStyle.BRASS_WATCH, astrology = false)
            screen("5-galactic", SundialView.ViewState.GALACTIC, CelestialStyle.DEEP_SPACE_BLUE, astrology = false)
            screen("6-settings", SundialView.ViewState.HELIOCENTRIC, CelestialStyle.BRASS_WATCH, astrology = false, openMenu = 0)
            screen("7-astrology-menu", SundialView.ViewState.HELIOCENTRIC, CelestialStyle.VOID_BLACK, astrology = true, openMenu = 2)
            screen("8-calendars", SundialView.ViewState.GEOCENTRIC, CelestialStyle.COSMIC_VIOLET, astrology = false, openMenu = 1)
        }
    }

    /** Full-bleed 512 × 512 icon: Play applies its own rounded mask. */
    private fun icon() {
        val drawable = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Show the launcher's visible 72 of 108 units, with a little extra margin.
        val layer = (size * 108f / 78f).toInt()
        val offset = (size - layer) / 2
        listOf(drawable.background, drawable.foreground).forEach {
            it.setBounds(offset, offset, offset + layer, offset + layer)
            it.draw(canvas)
        }
        save(bitmap, "icon-512")
    }

    /** 1024 × 500: the centre band of the brass watch face. */
    private fun featureGraphic() {
        listOf(CelestialStyle.BRASS_WATCH to "feature-graphic-brass", CelestialStyle.CRIMSON_NEBULA to "feature-graphic-crimson")
            .forEach { (style, name) ->
                val view = SundialView(context)
                view.setAstrologyContentForTest(ZodiacProfile(enabled = false), null)
                view.freezeForCapture(instant, SundialView.ViewState.HELIOCENTRIC, style)
                val square = view.renderWallpaperBitmap(1024, 1024, instant, SundialView.ViewState.HELIOCENTRIC)
                save(Bitmap.createBitmap(square, 0, 512 - 250 - 20, 1024, 500), name)
            }
    }

    private fun screen(
        name: String,
        state: SundialView.ViewState,
        style: CelestialStyle,
        astrology: Boolean,
        openMenu: Int? = null,
    ) {
        val width = 1080
        val height = 1920
        val view = SundialView(context)
        view.setSelectedCalendarIds(setOf(1L, 2L))
        view.setCalendarOccurrences(events())
        view.setAstrologyContentForTest(if (astrology) sampleProfile else ZodiacProfile(enabled = false),
            if (astrology) sampleReading else null)
        view.freezeForCapture(instant, state, style)

        val settings = SettingsPanel(context).apply { setBackgroundStyle(style) }
        val calendars = CalendarPanel(context).apply {
            setCalendars(listOf(
                DeviceCalendar(1, "Personal", "you@example.com", "com.google", 0xFF4CAF50.toInt()),
                DeviceCalendar(2, "Work", "you@example.com", "com.google", 0xFFE040FB.toInt()),
                DeviceCalendar(3, "Holidays", "you@example.com", "com.google", 0xFF42A5F5.toInt()),
            ))
        }
        val astrologyPanel = AstrologyPanel(context).apply {
            setZodiacProfile(sampleProfile)
            setHasReading(true)
            setHoroscopeStatus("Written privately by Gemini Nano · displayed on the instrument")
        }
        val host = TuckMenuHost(context).apply {
            setContent(view)
            iconColor = style.chromeColor
        }
        val menus = listOf(
            host.addMenu(TuckMenuHost.Corner.TOP_START, R.drawable.ic_tuck_settings, "Settings", settings),
            host.addMenu(TuckMenuHost.Corner.BOTTOM_START, R.drawable.ic_tuck_calendar, "Calendars", calendars),
            host.addMenu(TuckMenuHost.Corner.BOTTOM_END, R.drawable.ic_tuck_astrology, "Astrology", astrologyPanel),
        )
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        if (openMenu != null) {
            // Captured before the unfold animation starts, so the panel is at its resting size.
            menus[openMenu].open()
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
            menus[openMenu].panel.animate().cancel()
            menus[openMenu].panel.apply { scaleX = 1f; scaleY = 1f; alpha = 1f }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
