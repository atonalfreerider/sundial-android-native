package com.metavirtuoso.sundial

import android.Manifest
import android.annotation.SuppressLint
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
import com.metavirtuoso.sundial.astronomy.Zodiac
import com.metavirtuoso.sundial.calendar.CalendarRepository
import com.metavirtuoso.sundial.horoscope.HoroscopeGenerator
import com.metavirtuoso.sundial.horoscope.ReadingReporter
import com.metavirtuoso.sundial.ui.AstrologyPanel
import com.metavirtuoso.sundial.ui.CalendarPanel
import com.metavirtuoso.sundial.ui.CelestialStylePreferences
import com.metavirtuoso.sundial.ui.SettingsPanel
import com.metavirtuoso.sundial.ui.SundialView
import com.metavirtuoso.sundial.ui.TuckMenuHost
import com.metavirtuoso.sundial.ui.ZodiacPreferences
import com.metavirtuoso.sundial.ui.ZodiacProfile
import com.metavirtuoso.sundial.wallpaper.DailyWallpaperScheduler
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
    private var calendarsLoaded = false
    private var calendarAccessBlocked = false
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
            onHoroscopeTapped = { showReportDialog() }
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
            onAccessRequested = { requestCalendarAccess() }
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
            onReportRequested = { showReportDialog() }
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
            onOpenChanged = ::claimBack
        }
        setContentView(host)
        DailyWallpaperScheduler.configureForRequest(this)
        sundialView.setZodiacProfile(zodiacProfile)
        ZodiacPreferences.getCurrentHoroscope(this, zodiacProfile, LocalDate.now())?.let {
            sundialView.setHoroscope(it)
            astrologyPanel.setHasReading(true)
            astrologyPanel.setHoroscopeStatus("Today's on-device horoscope is displayed on the instrument.")
        }
        window.decorView.post { hideSystemBars() }
        refreshCalendarAccess()
    }

    /** Android 13+: Back is claimed only while a tuck menu is open, keeping predictive back-to-home otherwise. */
    private val tuckBackCallback: Any? = if (Build.VERSION.SDK_INT >= 33) {
        android.window.OnBackInvokedCallback { host.close() }
    } else null

    private fun claimBack(menuOpen: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = tuckBackCallback as android.window.OnBackInvokedCallback
        if (menuOpen) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    // Android 13+ Back goes through tuckBackCallback; this platform Activity has no other hook below 13.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Only reached below Android 13; newer versions use OnBackInvokedCallback.")
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

    /**
     * Calendar access is asked for in context, from the calendar menu, never at launch. If the
     * person has granted it (here or in system settings), the synced calendars are listed.
     */
    private fun refreshCalendarAccess() {
        if (repository.hasPermission()) {
            if (!calendarsLoaded) loadCalendars()
        } else {
            calendarsLoaded = false
            calendarPanel.setAccessNeeded(openSettings = false)
        }
    }

    private fun requestCalendarAccess() {
        if (calendarAccessBlocked) {
            // Android no longer shows the dialog after a permanent refusal; settings is the only way.
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null),
            ))
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CALENDAR_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            calendarAccessBlocked = false
            loadCalendars()
        } else {
            calendarAccessBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
            calendarPanel.setAccessNeeded(openSettings = calendarAccessBlocked)
        }
    }

    private fun loadCalendars() {
        calendarsLoaded = true
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
        // A reading the person reported is not silently replaced; they can ask for a new one.
        if (ZodiacPreferences.wasReadingReported(this, LocalDate.now())) return
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
                if (requestedProfile.signature == zodiacProfile.signature) {
                    sundialView.setHoroscope(horoscope)
                    astrologyPanel.setHasReading(true)
                }
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

    /**
     * Google Play's AI-generated content policy: readings can be reported without leaving the app.
     * The reading disappears at once and is sent to the developer with the chosen reason.
     */
    private fun showReportDialog() {
        val today = LocalDate.now()
        val reading = ZodiacPreferences.getCurrentHoroscope(this, zodiacProfile, today) ?: run {
            Toast.makeText(this, "There is no reading to report today", Toast.LENGTH_SHORT).show()
            return
        }
        val reasons = ReadingReporter.Reason.entries
        var chosen = ReadingReporter.Reason.OFFENSIVE
        AlertDialog.Builder(this)
            .setTitle("Report this reading")
            .setSingleChoiceItems(reasons.map { it.label }.toTypedArray(), 0) { _, which -> chosen = reasons[which] }
            .setPositiveButton("SEND REPORT") { _, _ ->
                ZodiacPreferences.hideReportedHoroscope(this, today)
                sundialView.setHoroscope(null)
                astrologyPanel.setHasReading(false)
                astrologyPanel.setHoroscopeStatus("Reading hidden. Write a new reading whenever you like.")
                refreshWallpapers()
                uiScope.launch {
                    val sent = ReadingReporter.send(ReadingReporter.Report(chosen, reading, today))
                    Toast.makeText(
                        this@MainActivity,
                        if (sent) "Thank you — the reading was reported" else "Reading hidden; the report could not be sent",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        sundialView.resumeClock()
        // Access may have been granted or revoked in system settings while we were away.
        if (::calendarPanel.isInitialized) refreshCalendarAccess()
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
