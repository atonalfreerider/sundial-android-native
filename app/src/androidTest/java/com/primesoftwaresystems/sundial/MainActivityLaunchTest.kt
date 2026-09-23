package com.primesoftwaresystems.sundial

import android.content.Intent
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.primesoftwaresystems.sundial.ui.SundialView
import com.primesoftwaresystems.sundial.ui.CelestialStyle
import com.primesoftwaresystems.sundial.ui.CelestialStylePreferences
import com.primesoftwaresystems.sundial.wallpaper.DailyWallpaperScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test fun activityLaunchesAndDrawsWithoutFinishing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity finished during launch", activity.isFinishing)
                assertNotNull(activity.findViewById(R.id.sundial_view))
                assertNotNull(activity.findViewById(R.id.astral_drawer))
                val drawer = activity.findViewById<DrawerLayout>(R.id.drawer_layout)
                drawer.openDrawer(GravityCompat.START, false)
                assertTrue("Native settings drawer must open", drawer.isDrawerOpen(GravityCompat.START))
                drawer.closeDrawer(GravityCompat.START, false)
            }
        }
    }

    @Test fun wallpaperFrameRendersHeliocentricViewWithoutApplicationChrome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = SundialView(context).renderWallpaperBitmap(
                360,
                800,
                Instant.parse("2024-02-29T12:00:00Z"),
            )
            try {
                val corner = bitmap.getPixel(12, 12)
                assertTrue("Void background should remain dark without being flat black",
                    Color.red(corner) + Color.green(corner) + Color.blue(corner) < 80)
                val sun = bitmap.getPixel(180, (800 * .47f).toInt())
                assertTrue("Wallpaper Sun should be luminous", Color.red(sun) > 160)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun geocentricLockWallpaperUsesSharedCelestialBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalStyle = CelestialStylePreferences.get(context)
        val originalLockSetting = DailyWallpaperScheduler.usesLockScreen(context)
        CelestialStylePreferences.set(context, CelestialStyle.CRIMSON_NEBULA)
        DailyWallpaperScheduler.setUseLockScreen(context, true)
        try {
            assertTrue(DailyWallpaperScheduler.usesLockScreen(context))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val bitmap = SundialView(context).renderWallpaperBitmap(
                    360,
                    800,
                    Instant.parse("2026-09-22T19:30:00Z"),
                    SundialView.ViewState.GEOCENTRIC,
                )
                try {
                    val corner = bitmap.getPixel(12, 12)
                    assertTrue("Crimson background must carry into wallpaper", Color.red(corner) > Color.blue(corner))
                    val earth = bitmap.getPixel(180, (800 * .47f).toInt())
                    assertTrue("Earth-centered wallpaper must render a visible globe", Color.red(earth) + Color.green(earth) + Color.blue(earth) > 55)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            CelestialStylePreferences.set(context, originalStyle)
            DailyWallpaperScheduler.setUseLockScreen(context, originalLockSetting)
        }
    }
}
