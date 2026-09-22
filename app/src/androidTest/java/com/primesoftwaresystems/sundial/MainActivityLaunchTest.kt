package com.primesoftwaresystems.sundial

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
}
