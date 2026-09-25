package com.metavirtuoso.sundial.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoonSphereRendererTest {
    @Test fun solarNorthMoonAlwaysLightsTheHemisphereFacingTheSun() {
        val renderer = MoonSphereRenderer()
        val sunToRight = renderer.render(120, 1f, 0f)
        assertTrue(hemisphereLuma(sunToRight, right = true) > hemisphereLuma(sunToRight, right = false) * 2.2)

        val sunToLeft = renderer.render(120, -1f, 0f)
        assertTrue(hemisphereLuma(sunToLeft, right = false) > hemisphereLuma(sunToLeft, right = true) * 2.2)
        assertTrue(!sunToRight.isRecycled && !sunToLeft.isRecycled)
    }

    private fun hemisphereLuma(bitmap: Bitmap, right: Boolean): Double {
        val center = bitmap.width / 2
        var sum = 0L
        var samples = 0
        for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
            val range = if (right) center until bitmap.width * 3 / 4 else bitmap.width / 4 until center
            for (x in range) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) > 0) {
                    sum += Color.red(color) + Color.green(color) + Color.blue(color)
                    samples++
                }
            }
        }
        return sum.toDouble() / samples.coerceAtLeast(1)
    }
}
