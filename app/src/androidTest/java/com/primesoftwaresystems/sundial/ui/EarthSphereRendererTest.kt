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

@RunWith(AndroidJUnit4::class)
class EarthSphereRendererTest {
    @Test fun consecutiveRotationsKeepBothHardwareFramesAliveAndVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texture = BitmapFactory.decodeResource(context.resources, R.drawable.earth_texture)
        val renderer = EarthSphereRenderer(texture)
        val first = renderer.render(256, 30.0, north = true)
        val second = renderer.render(256, 120.0, north = true)
        val southern = renderer.render(256, 120.0, north = false)

        assertNotSame(second, first)
        assertNotSame(southern, second)
        assertFalse(first.isRecycled)
        assertFalse(second.isRecycled)
        assertFalse(southern.isRecycled)
        val center = second.getPixel(second.width / 2, second.height / 2)
        assertTrue("Rotated Earth center must remain visible", Color.red(center) + Color.green(center) + Color.blue(center) > 85)
        val southernCenter = southern.getPixel(southern.width / 2, southern.height / 2)
        assertTrue("South-pole Earth center must remain visible",
            Color.red(southernCenter) + Color.green(southernCenter) + Color.blue(southernCenter) > 85)

        repeat(14) { index ->
            val frame = renderer.render(256, index * 23.0, north = true)
            val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
            assertFalse("Earth frame $index must not disappear", frame.isRecycled)
            assertTrue(Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 85)
        }
    }

    @Test fun solarNorthLightingKeepsANoticeableNonSunFacingShadow() {
        val neutralTexture = android.graphics.Bitmap.createBitmap(8, 4, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(170, 170, 170))
        }
        val earth = EarthSphereRenderer(neutralTexture).render(240, 0.0, north = true)
        val upper = averageLuma(earth, 45, 95)
        val lower = averageLuma(earth, 145, 195)

        assertTrue("Sun-facing north must be visibly brighter", upper > lower * 1.16)
        assertTrue("Shadow must retain readable surface detail", lower > 95.0)
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
