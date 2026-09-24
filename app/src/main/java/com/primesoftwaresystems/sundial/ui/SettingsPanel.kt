package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.widget.LinearLayout

/** The upper-left tuck menu: display, aesthetic, wallpaper and application settings. */
class SettingsPanel(context: Context) : LinearLayout(context) {
    private val controls = InstrumentControls(context)
    private val clockSwitch = controls.switch("CLOCK")
    private val galacticSwitch = controls.switch("GALACTIC AXIS")
    private val hemisphereSwitch = controls.switch("SOUTHERN HEMISPHERE")
    private val wallpaperSwitch = controls.switch("15-MIN CELESTIAL WALLPAPER")
    private val lockWallpaperSwitch = controls.switch("APPLY TO LOCK SCREEN")
    private val styleContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private var syncing = false

    var onClockChanged: ((Boolean) -> Unit)? = null
    var onGalacticChanged: ((Boolean) -> Unit)? = null
    var onHemisphereChanged: ((Boolean) -> Unit)? = null
    var onDailyWallpaperChanged: ((Boolean) -> Unit)? = null
    var onLockWallpaperChanged: ((Boolean) -> Unit)? = null
    var onBackgroundStyleChanged: ((CelestialStyle) -> Unit)? = null
    var onResetNow: (() -> Unit)? = null
    var onQuit: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(controls.dp(22), controls.dp(20), controls.dp(18), controls.dp(18))
        controls.title("SUN:DIAL", "CELESTIAL INSTRUMENT").forEach(::addView)
        addView(controls.section("DISPLAY"))
        addView(clockSwitch)
        addView(galacticSwitch)
        addView(hemisphereSwitch)
        addView(controls.action("RETURN TO NOW", "Reset the instrument to the current date and time") { onResetNow?.invoke() })
        addView(controls.section("AESTHETIC"))
        addView(styleContainer)
        setBackgroundStyle(CelestialStylePreferences.get(context))
        addView(controls.section("WALLPAPER"))
        addView(wallpaperSwitch)
        addView(lockWallpaperSwitch)
        addView(controls.section("APPLICATION"))
        addView(controls.action("QUIT", "Close Sundial") { onQuit?.invoke() })

        clockSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onClockChanged?.invoke(checked) }
        galacticSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onGalacticChanged?.invoke(checked) }
        hemisphereSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onHemisphereChanged?.invoke(checked) }
        wallpaperSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onDailyWallpaperChanged?.invoke(checked) }
        lockWallpaperSwitch.setOnCheckedChangeListener { _, checked -> if (!syncing) onLockWallpaperChanged?.invoke(checked) }
    }

    fun syncControls(view: SundialView, dailyWallpaperEnabled: Boolean, lockWallpaperEnabled: Boolean) {
        syncing = true
        clockSwitch.isChecked = view.isClockVisible
        galacticSwitch.isChecked = view.isGalacticVisible
        hemisphereSwitch.isChecked = view.isSouthernHemisphere
        wallpaperSwitch.isChecked = dailyWallpaperEnabled
        lockWallpaperSwitch.isChecked = lockWallpaperEnabled
        syncing = false
    }

    fun setBackgroundStyle(selected: CelestialStyle) {
        styleContainer.removeAllViews()
        CelestialStyle.entries.forEach { style ->
            val marker = if (style == selected) "◆" else "◇"
            styleContainer.addView(controls.action(
                "$marker  ${style.displayName.uppercase()}",
                "Use ${style.displayName} in Sundial and wallpaper",
            ) { onBackgroundStyleChanged?.invoke(style) })
        }
    }
}
