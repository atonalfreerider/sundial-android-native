package com.primesoftwaresystems.sundial

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.primesoftwaresystems.sundial.calendar.CalendarRepository
import com.primesoftwaresystems.sundial.ui.SundialView
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var sundialView: SundialView
    private val calendarExecutor = Executors.newSingleThreadExecutor()
    private lateinit var repository: CalendarRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 5894
        }

        repository = CalendarRepository(this)
        sundialView = SundialView(this).apply {
            onCalendarSelectionChanged = { ids -> loadOccurrences(ids) }
            onQuit = { finishAndRemoveTask() }
        }
        setContentView(sundialView)
        ensureCalendarPermission()
    }

    private fun ensureCalendarPermission() {
        if (repository.hasPermission()) {
            loadCalendars()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALENDAR_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadCalendars()
        } else if (requestCode == CALENDAR_PERMISSION) {
            sundialView.setCalendarPermissionDenied()
        }
    }

    private fun loadCalendars() {
        calendarExecutor.execute {
            val calendars = repository.loadCalendars()
            runOnUiThread { sundialView.setCalendars(calendars) }
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

    companion object {
        private const val CALENDAR_PERMISSION = 2401
    }
}
