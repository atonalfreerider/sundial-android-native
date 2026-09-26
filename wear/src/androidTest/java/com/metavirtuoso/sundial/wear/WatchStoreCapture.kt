package com.metavirtuoso.sundial.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metavirtuoso.sundial.ui.CelestialStyle
import com.metavirtuoso.sundial.ui.InstrumentLayout
import com.metavirtuoso.sundial.ui.SundialView
import com.metavirtuoso.sundial.ui.ZodiacProfile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Renders Wear OS store screenshots (1:1, round-masked) from the watch instrument with sample
 * settings. Opt-in:
 *
 *   adb shell am instrument -w -e storeAssets true \
 *     -e class com.metavirtuoso.sundial.wear.WatchStoreCapture \
 *     com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Files land in /sdcard/Android/data/com.metavirtuoso.sundial/files/store-wear/.
 */
@RunWith(AndroidJUnit4::class)
class WatchStoreCapture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val instant = Instant.parse("2026-10-14T16:20:00Z")

    @Test fun captureWatchScreenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("storeAssets") == "true")
        val out = File(context.getExternalFilesDir(null), "store-wear").apply { mkdirs() }
        instrumentation.runOnMainSync {
            listOf(
                Triple("wear-1-brass-astrology", CelestialStyle.BRASS_WATCH, true) to SundialView.ViewState.HELIOCENTRIC,
                Triple("wear-2-brass-earth-view", CelestialStyle.BRASS_WATCH, false) to SundialView.ViewState.GEOCENTRIC,
                Triple("wear-3-solar-view", CelestialStyle.CRIMSON_NEBULA, false) to SundialView.ViewState.HELIOCENTRIC,
                Triple("wear-4-earth-view", CelestialStyle.DEEP_SPACE_BLUE, false) to SundialView.ViewState.GEOCENTRIC,
                Triple("wear-5-galactic", CelestialStyle.COSMIC_VIOLET, false) to SundialView.ViewState.GALACTIC,
            ).forEach { (spec, state) ->
                val (name, style, astrology) = spec
                val view = SundialView(context, InstrumentLayout.WATCH_ROUND)
                view.setAstrologyContentForTest(
                    ZodiacProfile(enabled = astrology, birthDate = LocalDate.of(1990, 4, 18)), null)
                view.setClockVisible(true)
                view.freezeForCapture(instant, state, style)
                val frame = view.renderWallpaperBitmap(SIZE, SIZE, instant, state)
                File(out, "$name.png").outputStream().use { roundMask(frame).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** Black outside the circle, as the screenshot appears on a round watch. */
    private fun roundMask(frame: Bitmap): Bitmap {
        val masked = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(masked)
        canvas.drawBitmap(frame, 0f, 0f, null)
        val outside = Path().apply {
            fillType = Path.FillType.INVERSE_WINDING
            addCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Path.Direction.CW)
        }
        canvas.drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        })
        return masked
    }

    private companion object {
        const val SIZE = 454
    }
}
