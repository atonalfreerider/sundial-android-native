package com.metavirtuoso.sundial.wear

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import com.metavirtuoso.sundial.ui.CelestialStyle
import com.metavirtuoso.sundial.ui.CelestialStylePreferences
import com.metavirtuoso.sundial.ui.InstrumentControls
import com.metavirtuoso.sundial.ui.ZodiacPreferences

/** Watch settings, reached by a long press on the dial: a scrolling list padded for round faces. */
class WatchSettingsActivity : ComponentActivity() {
    private lateinit var controls: InstrumentControls
    private val styles by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controls = InstrumentControls(this)
        val metrics = resources.displayMetrics
        val round = resources.configuration.isScreenRound
        val side = (metrics.widthPixels * if (round) .12f else .05f).toInt()
        val vertical = (metrics.heightPixels * if (round) .16f else .06f).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, vertical, side, vertical)
            addView(controls.label("SUN:DIAL", 20f, 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                letterSpacing = .12f
            })
            addView(controls.label("LONG-PRESS THE DIAL FOR SETTINGS · TURN THE CROWN TO MOVE TIME", 9f, 0x99FFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, controls.dp(2), 0, controls.dp(8))
            })
            addView(controls.switch("CLOCK").apply {
                isChecked = WatchPreferences.showClock(context)
                setOnCheckedChangeListener { _, checked -> WatchPreferences.setShowClock(context, checked) }
            })
            addView(controls.switch("ASTROLOGY").apply {
                isChecked = ZodiacPreferences.get(context).enabled
                setOnCheckedChangeListener { _, checked ->
                    ZodiacPreferences.set(context, ZodiacPreferences.get(context).copy(enabled = checked))
                }
            })
            addView(controls.switch("SOUTHERN").apply {
                isChecked = WatchPreferences.southern(context)
                setOnCheckedChangeListener { _, checked -> WatchPreferences.setSouthern(context, checked) }
            })
            addView(controls.action("GALACTIC VIEW", "Show or leave the galactic view") {
                WatchPreferences.request(context, WatchPreferences.Request.GALACTIC)
                finish()
            })
            addView(controls.action("RETURN TO NOW", "Reset the instrument to the current time") {
                WatchPreferences.request(context, WatchPreferences.Request.NOW)
                finish()
            })
            addView(controls.section("AESTHETIC"))
            addView(styles)
        }
        showStyles(CelestialStylePreferences.get(this))
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusableInTouchMode = true
            addView(content)
        }
        setContentView(scroller)
        // The crown scrolls the list once the scroller has focus.
        scroller.requestFocus()
    }

    private fun showStyles(selected: CelestialStyle) {
        styles.removeAllViews()
        CelestialStyle.entries.forEach { style ->
            styles.addView(controls.action("${if (style == selected) "◆" else "◇"}  ${style.displayName.uppercase()}",
                "Use ${style.displayName}") {
                CelestialStylePreferences.set(this, style)
                showStyles(style)
            })
        }
    }
}
