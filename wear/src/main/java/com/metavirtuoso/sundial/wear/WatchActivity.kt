package com.metavirtuoso.sundial.wear

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import com.metavirtuoso.sundial.ui.CelestialStylePreferences
import com.metavirtuoso.sundial.ui.InstrumentLayout
import com.metavirtuoso.sundial.ui.SundialView
import com.metavirtuoso.sundial.ui.ZodiacPreferences

/**
 * Sundial on the wrist: the full instrument fitted to a round or rectangular face. The crown or
 * bezel moves through time, a long press opens settings, and the always-on display shows a dim
 * instrument that updates every minute.
 */
class WatchActivity : ComponentActivity() {
    private lateinit var sundial: SundialView

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) =
            sundial.setAmbient(true, ambientDetails.burnInProtectionRequired)
        override fun onUpdateAmbient() = sundial.invalidate()
        override fun onExitAmbient() = sundial.setAmbient(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = if (resources.configuration.isScreenRound) InstrumentLayout.WATCH_ROUND else InstrumentLayout.WATCH_RECT
        sundial = SundialView(this, layout).apply {
            isFocusableInTouchMode = true
            onLongPress = { startActivity(Intent(this@WatchActivity, WatchSettingsActivity::class.java)) }
            setOnGenericMotionListener { _, event -> onRotary(event) }
        }
        setContentView(sundial)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        sundial.resumeClock()
        sundial.requestFocus()
    }

    override fun onPause() {
        sundial.pauseClock()
        super.onPause()
    }

    /** Clockwise turns move forward in time. */
    private fun onRotary(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false
        sundial.scrubBy(-event.getAxisValue(MotionEvent.AXIS_SCROLL))
        return true
    }

    private fun applySettings() {
        sundial.setBackgroundStyle(CelestialStylePreferences.get(this))
        sundial.setZodiacProfile(ZodiacPreferences.get(this))
        sundial.setClockVisible(WatchPreferences.showClock(this))
        sundial.setSouthernHemisphere(WatchPreferences.southern(this))
        when (WatchPreferences.consumeRequest(this)) {
            WatchPreferences.Request.GALACTIC -> sundial.setGalacticVisible(!sundial.isGalacticVisible)
            WatchPreferences.Request.NOW -> sundial.resetNow()
            WatchPreferences.Request.NONE -> Unit
        }
    }
}
