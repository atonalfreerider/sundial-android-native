package com.primesoftwaresystems.sundial

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.primesoftwaresystems.sundial.calendar.CalendarRepository
import com.primesoftwaresystems.sundial.ui.AstralDrawerView
import com.primesoftwaresystems.sundial.ui.SundialView
import com.primesoftwaresystems.sundial.wallpaper.DailyWallpaperScheduler
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var sundialView: SundialView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerView: AstralDrawerView
    private val calendarExecutor = Executors.newSingleThreadExecutor()
    private lateinit var repository: CalendarRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repository = CalendarRepository(this)

        sundialView = SundialView(this).apply {
            id = R.id.sundial_view
            onMenuRequested = { drawerLayout.openDrawer(GravityCompat.START) }
            onControlsChanged = {
                if (::drawerView.isInitialized) {
                    drawerView.syncControls(this, DailyWallpaperScheduler.isEnabled(this@MainActivity))
                }
            }
            onCalendarSelectionChanged = { ids -> loadOccurrences(ids) }
        }
        drawerView = AstralDrawerView(this).apply {
            id = R.id.astral_drawer
            onClockChanged = { sundialView.setClockVisible(it) }
            onGalacticChanged = { sundialView.setGalacticVisible(it) }
            onHemisphereChanged = { sundialView.setSouthernHemisphere(it) }
            onDailyWallpaperChanged = { enabled ->
                DailyWallpaperScheduler.setEnabled(this@MainActivity, enabled)
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "Daily heliocentric wallpaper enabled" else "Daily wallpaper disabled",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onResetNow = { sundialView.resetNow() }
            onQuit = { finishAndRemoveTask() }
            onCalendarSelectionChanged = { ids ->
                sundialView.setSelectedCalendarIds(ids)
                loadOccurrences(ids)
            }
            syncControls(sundialView, DailyWallpaperScheduler.isEnabled(this@MainActivity))
        }
        drawerLayout = DrawerLayout(this).apply {
            id = R.id.drawer_layout
            setScrimColor(0xB8000000.toInt())
            addView(sundialView, DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(drawerView, DrawerLayout.LayoutParams(
                minOf((resources.displayMetrics.widthPixels * .86f).toInt(), dp(376)),
                DrawerLayout.LayoutParams.MATCH_PARENT,
                Gravity.START,
            ))
        }
        setContentView(drawerLayout)
        DailyWallpaperScheduler.configureForRequest(this)
        drawerView.syncControls(sundialView, DailyWallpaperScheduler.isEnabled(this))
        window.decorView.post { hideSystemBars() }
        ensureCalendarPermission()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 5894
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun ensureCalendarPermission() {
        if (repository.hasPermission()) loadCalendars()
        else requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALENDAR_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadCalendars()
        } else if (requestCode == CALENDAR_PERMISSION) {
            drawerView.setCalendarPermissionDenied()
        }
    }

    private fun loadCalendars() {
        calendarExecutor.execute {
            val calendars = repository.loadCalendars()
            runOnUiThread { drawerView.setCalendars(calendars) }
        }
    }

    private fun loadOccurrences(ids: Set<Long>) {
        calendarExecutor.execute {
            val zone = ZoneId.systemDefault()
            val displayedYear = sundialView.displayedYear
            val begin = LocalDate.of(displayedYear, 1, 1).atStartOfDay(zone).toInstant()
            val end = LocalDate.of(displayedYear + 1, 1, 2).atStartOfDay(zone).toInstant()
            val occurrences = repository.loadInstances(ids, begin, end, zone)
            runOnUiThread { sundialView.setCalendarOccurrences(occurrences) }
        }
    }

    override fun onResume() {
        super.onResume()
        sundialView.resumeClock()
    }

    override fun onPause() {
        sundialView.pauseClock()
        super.onPause()
    }

    override fun onDestroy() {
        calendarExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val CALENDAR_PERMISSION = 2401 }
}
