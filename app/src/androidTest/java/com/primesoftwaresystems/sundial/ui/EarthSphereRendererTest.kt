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
    }
}
