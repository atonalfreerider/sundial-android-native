package com.primesoftwaresystems.sundial.ui

/** Ratios reconstructed from the original Unity instrument's 150-unit annual dial. */
object DialGeometry {
    const val MERCURY_ORBIT = 0.1935f
    const val VENUS_ORBIT = 0.3615f
    const val EARTH_ORBIT = 0.5000f
    const val MARS_ORBIT = 0.7615f

    // The native viewport radius is the lunar dial, rather than Unity's annual dial.
    const val MOON_DIAL = 0.965f
    const val HOUR_DIAL = MOON_DIAL * (60f / 67.5f)
    const val TIME_ZONE_DIAL = MOON_DIAL * (42f / 67.5f)
    const val EARTH_RADIUS = MOON_DIAL * (37.5f / 67.5f)

    /** Matches Unity's portrait earthOrthoSize = solOrthoSize * .45 camera move. */
    const val EARTH_CAMERA_ZOOM = 1f / .45f
    const val HELIOCENTRIC_EARTH_RADIUS = .025f

    data class EventBand(val centerRadius: Float, val thickness: Float)

    fun annualAngle(fraction: Double, north: Boolean): Double =
        if (north) 105.0 - fraction * 360.0 else 75.0 + fraction * 360.0

    fun yearFractionFromAngle(angleDegrees: Double, north: Boolean): Double {
        var fraction = if (north) (105.0 - angleDegrees) / 360.0 else (angleDegrees - 75.0) / 360.0
        fraction -= kotlin.math.floor(fraction)
        return fraction
    }

    fun yearEventBand(annualRadius: Float, calendarIndex: Int): EventBand {
        val earthDialRadius = annualRadius * .4f
        val thickness = earthDialRadius * .1166f
        val outer = annualRadius - earthDialRadius * .0166f - calendarIndex.coerceAtLeast(0) * thickness
        return EventBand(outer - thickness / 2f, thickness)
    }

    fun dayEventBand(hourRadius: Float, calendarIndex: Int): EventBand {
        val thickness = hourRadius * .1166f
        val outer = hourRadius - hourRadius * .0166f - calendarIndex.coerceAtLeast(0) * thickness
        return EventBand(outer - thickness / 2f, thickness)
    }

    data class EarthFlightFrame(val cameraScale: Float, val earthSystemScale: Float)

    fun earthFlightFrame(progress: Float): EarthFlightFrame {
        val p = progress.coerceIn(0f, 1f)
        return EarthFlightFrame(
            cameraScale = 1f + (EARTH_CAMERA_ZOOM - 1f) * p,
            earthSystemScale = HELIOCENTRIC_EARTH_RADIUS / EARTH_RADIUS +
                (1f - HELIOCENTRIC_EARTH_RADIUS / EARTH_RADIUS) * p,
        )
    }
}
