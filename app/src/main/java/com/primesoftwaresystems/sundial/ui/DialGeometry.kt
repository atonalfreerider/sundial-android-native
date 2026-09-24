package com.primesoftwaresystems.sundial.ui

import kotlin.math.pow

/** Ratios reconstructed from the original Unity instrument's 150-unit annual dial. */
object DialGeometry {
    const val MERCURY_ORBIT = 0.1935f
    const val VENUS_ORBIT = 0.3615f
    const val EARTH_ORBIT = 0.5000f
    const val MARS_ORBIT = 0.7615f

    // The native viewport radius is the lunar dial, rather than Unity's annual dial.
    const val MOON_DIAL = 0.965f
    /** One Unity world unit on the Earth instrument, whose lunar dial is 67.5 units. */
    private const val EARTH_UNIT = MOON_DIAL / 67.5f
    const val HOUR_DIAL = 60f * EARTH_UNIT
    const val EARTH_RADIUS = 37.5f * EARTH_UNIT

    /**
     * Unity's local wheel: a thin ring hugging the globe with 24 outward hour teeth. The tooth at
     * the selected zone's local time is long, with a shorter red tooth inscribed in it.
     */
    const val LOCAL_WHEEL = 42f * EARTH_UNIT
    const val LOCAL_WHEEL_SMALL_TOOTH = 3f * EARTH_UNIT
    const val LOCAL_WHEEL_BIG_TOOTH = 15f * EARTH_UNIT
    const val LOCAL_WHEEL_RED_TOOTH = 10.5f * EARTH_UNIT
    const val LOCAL_WHEEL_RED_HALF_BASE = 1.125f * EARTH_UNIT
    /** Half-width of each tooth's base, Unity's 0.04 rad. */
    const val LOCAL_WHEEL_TOOTH_HALF_ANGLE = 2.2918
    /** The translucent band inside the wheel that spans the zones already on the new date. */
    const val DATE_STRIP_OUTER = 41f * EARTH_UNIT
    const val DATE_STRIP_INNER = 38.5f * EARTH_UNIT

    /** Matches Unity's portrait earthOrthoSize = solOrthoSize * .45 camera move. */
    const val EARTH_CAMERA_ZOOM = 1f / .45f

    /**
     * The solar view's Earth subdial is the Earth view drawn at this scale (deliberately enlarged, not
     * to scale), so the camera flight starts from exactly what the subdial shows.
     */
    const val EARTH_SUBDIAL_SCALE = .115f
    const val HELIOCENTRIC_EARTH_RADIUS = EARTH_RADIUS * EARTH_SUBDIAL_SCALE
    const val SUBDIAL_GEAR = HOUR_DIAL * EARTH_SUBDIAL_SCALE
    /** The enlarged lunar track, just outside the subdial's gear teeth. */
    const val SUBDIAL_MOON_TRACK = .135f

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

    /** [minThickness] lets a band grow past Unity's proportion so its label stays legible. */
    fun yearEventBand(annualRadius: Float, calendarIndex: Int, minThickness: Float = 0f): EventBand {
        val earthDialRadius = annualRadius * .4f
        val thickness = maxOf(earthDialRadius * .1166f, minThickness)
        val outer = annualRadius - earthDialRadius * .0166f - calendarIndex.coerceAtLeast(0) * thickness
        return EventBand(outer - thickness / 2f, thickness)
    }

    fun dayEventBand(hourRadius: Float, calendarIndex: Int, minThickness: Float = 0f): EventBand {
        val thickness = maxOf(hourRadius * .1166f, minThickness)
        val outer = hourRadius - hourRadius * .0166f - calendarIndex.coerceAtLeast(0) * thickness
        return EventBand(outer - thickness / 2f, thickness)
    }

    /** How much of the camera zoom the distant star field shows: a little parallax sells the flight. */
    const val SKY_PARALLAX = .35f

    data class EarthFlightFrame(val cameraScale: Float, val earthSystemScale: Float, val skyScale: Float)

    /**
     * Camera for the flight from the Sun-centred dial to the Earth. Scales interpolate
     * geometrically, so the zoom feels like constant forward motion and the Earth swells like an
     * approaching body, the way a fly-in from space looks (deliberately not to scale).
     */
    fun earthFlightFrame(progress: Float): EarthFlightFrame {
        val p = progress.coerceIn(0f, 1f)
        return EarthFlightFrame(
            cameraScale = EARTH_CAMERA_ZOOM.pow(p),
            earthSystemScale = (HELIOCENTRIC_EARTH_RADIUS / EARTH_RADIUS).pow(1f - p),
            skyScale = EARTH_CAMERA_ZOOM.pow(p * SKY_PARALLAX),
        )
    }
}
