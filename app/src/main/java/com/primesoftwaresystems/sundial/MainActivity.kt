package com.primesoftwaresystems.sundial

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import com.primesoftwaresystems.sundial.astronomy.Zodiac
import com.primesoftwaresystems.sundial.horoscope.HoroscopeGenerator
import com.primesoftwaresystems.sundial.ui.AstralDrawerView
import com.primesoftwaresystems.sundial.ui.SundialView
import com.primesoftwaresystems.sundial.ui.ZodiacPreferences
import com.primesoftwaresystems.sundial.ui.ZodiacProfile
import com.primesoftwaresystems.sundial.wallpaper.DailyWallpaperScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var sundialView: SundialView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerView: AstralDrawerView
    private val calendarExecutor = Executors.newSingleThreadExecutor()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val horoscopeGenerator = HoroscopeGenerator()
    private var horoscopeGenerating = false
    private lateinit var repository: CalendarRepository
    private lateinit var zodiacProfile: ZodiacProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repository = CalendarRepository(this)
        zodiacProfile = ZodiacPreferences.get(this)

        sundialView = SundialView(this).apply {
            id = R.id.sundial_view
            onMenuRequested = { drawerLayout.openDrawer(GravityCompat.START) }
            onControlsChanged = {
                if (::drawerView.isInitialized) {
                    drawerView.syncControls(
                        this,
                        DailyWallpaperScheduler.isEnabled(this@MainActivity),
                        DailyWallpaperScheduler.usesLockScreen(this@MainActivity),
                    )
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
                    if (enabled) "15-minute celestial wallpaper enabled" else "Celestial wallpaper disabled",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onLockWallpaperChanged = { useLockScreen ->
                DailyWallpaperScheduler.setUseLockScreen(this@MainActivity, useLockScreen)
                Toast.makeText(
                    this@MainActivity,
                    if (useLockScreen) "Wallpaper target: lock screen" else "Wallpaper target: home screen",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onZodiacChanged = { enabled -> updateZodiacProfile(zodiacProfile.copy(enabled = enabled)) }
            onBirthDateRequested = { showBirthDatePicker() }
            onBirthTimeRequested = { showBirthTimePicker() }
            onZodiacSignRequested = { showZodiacSignPicker() }
            onHoroscopeRequested = { generateHoroscope() }
            onBackgroundStyleChanged = { style ->
                sundialView.setBackgroundStyle(style)
                setBackgroundStyle(style)
                if (DailyWallpaperScheduler.isEnabled(this@MainActivity)) {
                    DailyWallpaperScheduler.applyNow(this@MainActivity)
                }
            }
            onResetNow = { sundialView.resetNow() }
            onQuit = { finishAndRemoveTask() }
            onCalendarSelectionChanged = { ids ->
                sundialView.setSelectedCalendarIds(ids)
                loadOccurrences(ids)
            }
            syncControls(
                sundialView,
                DailyWallpaperScheduler.isEnabled(this@MainActivity),
                DailyWallpaperScheduler.usesLockScreen(this@MainActivity),
            )
            setZodiacProfile(zodiacProfile)
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
        drawerView.syncControls(
            sundialView,
            DailyWallpaperScheduler.isEnabled(this),
            DailyWallpaperScheduler.usesLockScreen(this),
        )
        sundialView.setZodiacProfile(zodiacProfile)
        ZodiacPreferences.getCurrentHoroscope(this, zodiacProfile, LocalDate.now())?.let {
            sundialView.setHoroscope(it)
            drawerView.setHoroscopeStatus("Today's on-device horoscope is displayed on the instrument.")
        }
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

    private fun updateZodiacProfile(profile: ZodiacProfile) {
        zodiacProfile = profile
        ZodiacPreferences.set(this, profile)
        sundialView.setZodiacProfile(profile)
        drawerView.setZodiacProfile(profile)
        drawerView.setHoroscopeStatus(
            if (profile.isComplete) "Birth details stay on this device. Generate a fresh daily reading."
            else "Set birthday and birth time to enable the private on-device horoscope."
        )
        if (DailyWallpaperScheduler.isEnabled(this)) DailyWallpaperScheduler.applyNow(this)
    }

    private fun showBirthDatePicker() {
        val initial = zodiacProfile.birthDate ?: LocalDate.now().minusYears(30)
        DatePickerDialog(this, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            updateZodiacProfile(zodiacProfile.copy(birthDate = date, selectedSign = Zodiac.signFor(date)))
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
            setTitle("Birth date")
        }.show()
    }

    private fun showBirthTimePicker() {
        val initial = zodiacProfile.birthTime ?: LocalTime.NOON
        TimePickerDialog(this, { _, hour, minute ->
            updateZodiacProfile(zodiacProfile.copy(birthTime = LocalTime.of(hour, minute)))
        }, initial.hour, initial.minute, false).apply { setTitle("Birth time") }.show()
    }

    private fun showZodiacSignPicker() {
        val signs = Zodiac.Sign.entries
        val labels = arrayOf("AUTOMATIC FROM BIRTHDAY") + signs.map { "${it.symbol}  ${it.displayName.uppercase()}" }
        val checked = zodiacProfile.selectedSign?.ordinal?.plus(1) ?: 0
        AlertDialog.Builder(this)
            .setTitle("Natal sun sign")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                updateZodiacProfile(zodiacProfile.copy(selectedSign = if (which == 0) null else signs[which - 1]))
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun generateHoroscope() {
        if (horoscopeGenerating) return
        if (!zodiacProfile.isComplete) {
            Toast.makeText(this, "Set birthday and birth time first", Toast.LENGTH_SHORT).show()
            return
        }
        val requestedProfile = zodiacProfile
        horoscopeGenerating = true
        uiScope.launch {
            runCatching {
                horoscopeGenerator.generate(requestedProfile, Instant.now(), ZoneId.systemDefault()) { status ->
                    drawerView.setHoroscopeStatus(status)
                }
            }.onSuccess { horoscope ->
                val today = LocalDate.now()
                ZodiacPreferences.setHoroscope(this@MainActivity, requestedProfile, today, horoscope)
                sundialView.setHoroscope(horoscope)
                drawerView.setHoroscopeStatus("Written privately by Gemini Nano · displayed on the instrument")
                drawerLayout.closeDrawer(GravityCompat.START)
            }.onFailure { error ->
                val message = error.message ?: "On-device horoscope generation failed"
                drawerView.setHoroscopeStatus(message)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
            horoscopeGenerating = false
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
        uiScope.cancel()
        horoscopeGenerator.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val CALENDAR_PERMISSION = 2401 }
}
