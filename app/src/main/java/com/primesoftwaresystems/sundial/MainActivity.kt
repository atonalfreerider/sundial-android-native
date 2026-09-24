package com.primesoftwaresystems.sundial

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import com.primesoftwaresystems.sundial.astronomy.Zodiac
import com.primesoftwaresystems.sundial.calendar.CalendarRepository
import com.primesoftwaresystems.sundial.horoscope.HoroscopeGenerator
import com.primesoftwaresystems.sundial.ui.AstrologyPanel
import com.primesoftwaresystems.sundial.ui.CalendarPanel
import com.primesoftwaresystems.sundial.ui.CelestialStylePreferences
import com.primesoftwaresystems.sundial.ui.SettingsPanel
import com.primesoftwaresystems.sundial.ui.SundialView
import com.primesoftwaresystems.sundial.ui.TuckMenuHost
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
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var sundialView: SundialView
    private lateinit var host: TuckMenuHost
    private lateinit var settingsPanel: SettingsPanel
    private lateinit var calendarPanel: CalendarPanel
    private lateinit var astrologyPanel: AstrologyPanel
    private val calendarExecutor = Executors.newSingleThreadExecutor()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val horoscopeGenerator = HoroscopeGenerator()
    private var horoscopeGenerating = false
    private lateinit var repository: CalendarRepository
    private lateinit var zodiacProfile: ZodiacProfile
    private val automaticHoroscope = Runnable { generateHoroscope(automatic = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            // Let the tuck menus see the keyboard's insets and rise above it themselves.
            window.setDecorFitsSystemWindows(false)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }
        repository = CalendarRepository(this)
        zodiacProfile = ZodiacPreferences.get(this)

        sundialView = SundialView(this).apply {
            id = R.id.sundial_view
            onControlsChanged = {
                if (::settingsPanel.isInitialized) {
                    settingsPanel.syncControls(
                        this,
                        DailyWallpaperScheduler.isEnabled(this@MainActivity),
                        DailyWallpaperScheduler.usesLockScreen(this@MainActivity),
                    )
                }
            }
            onCalendarSelectionChanged = { ids -> loadOccurrences(ids) }
        }
        settingsPanel = SettingsPanel(this).apply {
            onClockChanged = { sundialView.setClockVisible(it) }
            onGalacticChanged = {
                sundialView.setGalacticVisible(it)
                host.close()
            }
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
                    if (useLockScreen) "Home and lock wallpapers enabled" else "Home wallpaper only",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onBackgroundStyleChanged = { style ->
                sundialView.setBackgroundStyle(style)
                setBackgroundStyle(style)
                host.iconColor = style.chromeColor
                refreshWallpapers()
            }
            onResetNow = {
                sundialView.resetNow()
                refreshWallpapers()
                host.close()
            }
            onQuit = { finishAndRemoveTask() }
            syncControls(
                sundialView,
                DailyWallpaperScheduler.isEnabled(this@MainActivity),
                DailyWallpaperScheduler.usesLockScreen(this@MainActivity),
            )
        }
        calendarPanel = CalendarPanel(this).apply {
            onCalendarSelectionChanged = { ids ->
                sundialView.setSelectedCalendarIds(ids)
                loadOccurrences(ids)
            }
        }
        astrologyPanel = AstrologyPanel(this).apply {
            onZodiacChanged = { enabled -> updateZodiacProfile(zodiacProfile.copy(enabled = enabled), horoscopeDelayMs = 0L) }
            onBirthDateChanged = { date ->
                updateZodiacProfile(zodiacProfile.copy(birthDate = date, selectedSign = Zodiac.signFor(date)))
            }
            onBirthTimeChanged = { time -> updateZodiacProfile(zodiacProfile.copy(birthTime = time)) }
            onZodiacSignRequested = { showZodiacSignPicker() }
            onHoroscopeRequested = { generateHoroscope(automatic = false) }
            setZodiacProfile(zodiacProfile)
        }
        host = TuckMenuHost(this).apply {
            id = R.id.tuck_host
            setContent(sundialView)
            addMenu(TuckMenuHost.Corner.TOP_START, R.drawable.ic_tuck_settings, "Settings", settingsPanel)
                .panel.id = R.id.settings_menu
            addMenu(TuckMenuHost.Corner.BOTTOM_START, R.drawable.ic_tuck_calendar, "Calendars", calendarPanel)
                .panel.id = R.id.calendar_menu
            addMenu(TuckMenuHost.Corner.BOTTOM_END, R.drawable.ic_tuck_astrology, "Astrology", astrologyPanel)
                .panel.id = R.id.astrology_menu
            iconColor = CelestialStylePreferences.get(this@MainActivity).chromeColor
        }
        setContentView(host)
        DailyWallpaperScheduler.configureForRequest(this)
        sundialView.setZodiacProfile(zodiacProfile)
        ZodiacPreferences.getCurrentHoroscope(this, zodiacProfile, LocalDate.now())?.let {
            sundialView.setHoroscope(it)
            astrologyPanel.setHoroscopeStatus("Today's on-device horoscope is displayed on the instrument.")
        }
        window.decorView.post { hideSystemBars() }
        ensureCalendarPermission()
    }

    @Deprecated("Back is still delivered here for this targetSdk; it first tucks away an open menu.")
    override fun onBackPressed() {
        if (!host.close()) super.onBackPressed()
    }

    internal val tuckHostForTest: TuckMenuHost get() = host

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
            calendarPanel.setPermissionDenied()
        }
    }

    private fun loadCalendars() {
        calendarExecutor.execute {
            val calendars = repository.loadCalendars()
            runOnUiThread { calendarPanel.setCalendars(calendars) }
        }
    }

    private fun loadOccurrences(ids: Set<Long>) {
        // Read the view's year on the main thread; the view keeps changing it while time is scrubbed.
        val displayedYear = sundialView.displayedYear
        calendarExecutor.execute {
            val zone = ZoneId.systemDefault()
            val begin = LocalDate.of(displayedYear, 1, 1).atStartOfDay(zone).toInstant()
            val end = LocalDate.of(displayedYear + 1, 1, 2).atStartOfDay(zone).toInstant()
            val occurrences = repository.loadInstances(ids, begin, end, zone)
            runOnUiThread { sundialView.setCalendarOccurrences(occurrences) }
        }
    }

    /**
     * Saves the profile and, once astrology is on with a valid birth date and time, writes today's
     * horoscope straight away. Typing settles for [horoscopeDelayMs] first so a half-edited date
     * does not start a reading.
     */
    private fun updateZodiacProfile(profile: ZodiacProfile, horoscopeDelayMs: Long = 900L) {
        zodiacProfile = ZodiacPreferences.set(this, profile)
        sundialView.setZodiacProfile(zodiacProfile)
        astrologyPanel.setZodiacProfile(zodiacProfile)
        astrologyPanel.setHoroscopeStatus(
            when {
                !zodiacProfile.isComplete -> "Enter birth date and time for a private on-device horoscope."
                !zodiacProfile.enabled -> "Turn on astrology mode to write today's horoscope."
                else -> "Birth details stay on this device."
            }
        )
        refreshWallpapers()
        scheduleHoroscope(horoscopeDelayMs)
    }

    private fun scheduleHoroscope(delayMs: Long) {
        handler.removeCallbacks(automaticHoroscope)
        if (!zodiacProfile.enabled || !zodiacProfile.isComplete) return
        if (ZodiacPreferences.getCurrentHoroscope(this, zodiacProfile, LocalDate.now()) != null) return
        handler.postDelayed(automaticHoroscope, delayMs)
    }

    private fun refreshWallpapers() {
        if (DailyWallpaperScheduler.isEnabled(this)) DailyWallpaperScheduler.applyNow(this)
    }

    private fun showZodiacSignPicker() {
        val signs = Zodiac.Sign.entries
        val labels = arrayOf("AUTOMATIC FROM BIRTHDAY") + signs.map { "${it.symbol}  ${it.displayName.uppercase()}" }
        val checked = zodiacProfile.selectedSign?.ordinal?.plus(1) ?: 0
        AlertDialog.Builder(this)
            .setTitle("Natal sun sign")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val sign = if (which == 0) zodiacProfile.birthDate?.let(Zodiac::signFor) else signs[which - 1]
                updateZodiacProfile(zodiacProfile.copy(selectedSign = sign), horoscopeDelayMs = 0L)
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun generateHoroscope(automatic: Boolean) {
        handler.removeCallbacks(automaticHoroscope)
        if (horoscopeGenerating) return
        if (!zodiacProfile.isComplete) {
            if (!automatic) Toast.makeText(this, "Set birth date and birth time first", Toast.LENGTH_SHORT).show()
            return
        }
        val requestedProfile = zodiacProfile
        horoscopeGenerating = true
        uiScope.launch {
            runCatching {
                horoscopeGenerator.generate(requestedProfile, Instant.now(), ZoneId.systemDefault()) { status ->
                    astrologyPanel.setHoroscopeStatus(status)
                }
            }.onSuccess { horoscope ->
                val today = LocalDate.now()
                ZodiacPreferences.setHoroscope(this@MainActivity, requestedProfile, today, horoscope)
                if (requestedProfile.signature == zodiacProfile.signature) sundialView.setHoroscope(horoscope)
                astrologyPanel.setHoroscopeStatus("Written privately by Gemini Nano · displayed on the instrument")
                refreshWallpapers()
            }.onFailure { error ->
                val message = error.message ?: "On-device horoscope generation failed"
                astrologyPanel.setHoroscopeStatus(message)
                if (!automatic) Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
            horoscopeGenerating = false
            // Birth details changed while the reading was being written: write the new one.
            if (requestedProfile.signature != zodiacProfile.signature) scheduleHoroscope(0L)
        }
    }

    override fun onResume() {
        super.onResume()
        sundialView.resumeClock()
        // A new day (or a first launch with astrology on) gets its reading without being asked.
        scheduleHoroscope(0L)
    }

    override fun onPause() {
        sundialView.pauseClock()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(automaticHoroscope)
        calendarExecutor.shutdownNow()
        uiScope.cancel()
        horoscopeGenerator.close()
        super.onDestroy()
    }

    companion object { private const val CALENDAR_PERMISSION = 2401 }
}
