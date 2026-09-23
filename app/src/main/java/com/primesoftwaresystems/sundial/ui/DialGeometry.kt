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

    /** Unity's sidereal year and "January 1st is 10 days past the winter solstice" dial offset. */
    const val UNITY_YEAR_DAYS = 365.256363004
    /**
     * Canvas angle of January 1st (clockwise from +x). Unity rotates the sun sprocket by
     * -10 * 360 / YEAR - 180, putting the December solstice straight below the Sun and the
     * equinoxes on the horizontal season-cross axis.
     */
    const val JANUARY_FIRST_ANGLE = 90.0 - 10.0 * 360.0 / UNITY_YEAR_DAYS

    /**
     * Annual dial angle. North is Unity's view from the north ecliptic pole (time runs
     * counter-clockwise); south is its mirror image.
     */
    fun annualAngle(fraction: Double, north: Boolean): Double {
        val northAngle = JANUARY_FIRST_ANGLE - fraction * 360.0
        return if (north) northAngle else 180.0 - northAngle
    }

    fun yearFractionFromAngle(angleDegrees: Double, north: Boolean): Double {
        val northAngle = if (north) angleDegrees else 180.0 - angleDegrees
        var fraction = (JANUARY_FIRST_ANGLE - northAngle) / 360.0
        fraction -= kotlin.math.floor(fraction)
        return fraction
    }

    /**
     * Canvas angle of an ecliptic longitude in the same frame as [annualAngle]: longitude 0 (the
     * direction of Earth at the September equinox) is on the left in the north view, and Earth's
     * true heliocentric longitude lands on its civil date on the annual dial.
     */
    fun eclipticAngle(longitudeDegrees: Double, north: Boolean): Double =
        if (north) 180.0 - longitudeDegrees else longitudeDegrees

    /**
     * Earth-centred (geocentric) view with the Sun at the top. Unity's 24-hour ring puts solar noon
     * toward the Sun and midnight away from it, with hours running counter-clockwise in the north.
     */
    fun hourAngle(hours: Double, north: Boolean): Double =
        if (north) 90.0 - hours * 15.0 else 90.0 + hours * 15.0

    /** Inverse of [hourAngle], in minutes after midnight on the range [0, 1440). */
    fun minuteFromHourAngle(angleDegrees: Double, north: Boolean): Double {
        val hours = if (north) (90.0 - angleDegrees) / 15.0 else (angleDegrees - 90.0) / 15.0
        return (((hours * 60.0) % 1_440.0) + 1_440.0) % 1_440.0
    }

    /** Moon hand angle in the geocentric view: new Moon toward the Sun, then counter-clockwise (north). */
    fun moonAngle(phaseDegrees: Double, north: Boolean): Double =
        if (north) -90.0 - phaseDegrees else -90.0 + phaseDegrees

    /** Inverse of [moonAngle] on [0, 360). */
    fun phaseFromMoonAngle(angleDegrees: Double, north: Boolean): Double {
        val phase = if (north) -90.0 - angleDegrees else angleDegrees + 90.0
        return ((phase % 360.0) + 360.0) % 360.0
    }

    /** The annual dial as seen from the Earth camera: the Earth sits Unity's .5 system radius from the Sun. */
    const val GEOCENTRIC_SUN_DISTANCE = EARTH_ORBIT * EARTH_CAMERA_ZOOM

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
