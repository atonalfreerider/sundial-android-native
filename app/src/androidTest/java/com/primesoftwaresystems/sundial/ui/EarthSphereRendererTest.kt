package com.primesoftwaresystems.sundial.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.primesoftwaresystems.sundial.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class EarthSphereRendererTest {
    @Test fun consecutiveRotationsKeepBothHardwareFramesAliveAndVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texture = BitmapFactory.decodeResource(context.resources, R.drawable.earth_texture)
        val renderer = EarthSphereRenderer(texture, 256)
        val firstInstant = Instant.parse("2026-03-20T00:00:00Z")
        val secondInstant = firstInstant.plusSeconds(6 * 3_600L)
        val first = renderer.render(firstInstant, north = true)
        val second = renderer.render(secondInstant, north = true)
        val southern = renderer.render(secondInstant, north = false)

        assertNotSame(second, first)
        assertNotSame(southern, second)
        assertFalse(first.isRecycled)
        assertFalse(second.isRecycled)
        assertFalse(southern.isRecycled)
        val center = second.getPixel(second.width / 2, second.height / 2)
        assertTrue("Rotated Earth center must retain nonzero night-side detail",
            Color.alpha(center) > 200 && Color.red(center) + Color.green(center) + Color.blue(center) > 34)
        val southernCenter = southern.getPixel(southern.width / 2, southern.height / 2)
        assertTrue("South-pole Earth center must retain nonzero night-side detail",
            Color.alpha(southernCenter) > 200 &&
                Color.red(southernCenter) + Color.green(southernCenter) + Color.blue(southernCenter) > 34)

        repeat(14) { index ->
            val frame = renderer.render(firstInstant.plusSeconds(index * 7_200L), north = true)
            val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
            assertFalse("Earth frame $index must not disappear", frame.isRecycled)
            assertTrue(Color.alpha(pixel) > 200 &&
                Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 34)
        }
    }

    @Test fun solarNorthLightingKeepsANoticeableNonSunFacingShadow() {
        val neutralTexture = android.graphics.Bitmap.createBitmap(8, 4, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(170, 170, 170))
        }
        val earth = EarthSphereRenderer(neutralTexture, 240).render(
            Instant.parse("2026-09-22T19:30:00Z"), north = true,
        )
        val upper = averageLuma(earth, 45, 95)
        val lower = averageLuma(earth, 145, 195)

        assertTrue("Solar-north view must have a strong half-globe terminator", upper > lower * 1.55)
        assertTrue("Shadow must retain readable surface detail", lower > 24.0)
    }

    private fun averageLuma(bitmap: android.graphics.Bitmap, fromY: Int, untilY: Int): Double {
        var total = 0L
        var count = 0
        for (y in fromY until untilY) {
            for (x in 65 until 175) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 200) {
                    total += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                    count++
                }
            }
        }
        return total.toDouble() / count.coerceAtLeast(1) / 3.0
    }
}
