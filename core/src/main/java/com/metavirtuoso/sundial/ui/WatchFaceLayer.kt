package com.metavirtuoso.sundial.ui

import com.metavirtuoso.sundial.astronomy.Astronomy
import com.metavirtuoso.sundial.astronomy.Zodiac

/**
 * One pre-rendered image of the Watch Face Format face, drawn by [SundialView.drawWatchFaceLayer]
 * with the instrument's own code. The face moves them with expressions, so each moving part is
 * drawn in a reference pose: planets and the Earth at the top of the dial (12 o'clock), the Moon
 * straight above the Earth with the Sun below it, and upright parts around their own centre.
 */
sealed interface WatchFaceLayer {
    /** The sky and, for Brass Watch, the polished face and bezel. */
    data object Sky : WatchFaceLayer

    /** Season wash, band and names with [active] (if any) lit. [band] false keeps only names and rim. */
    data class Seasons(val active: Zodiac.Season?, val band: Boolean = true) : WatchFaceLayer

    /** The annual dial's rings, day and month ticks, month names and the season cross. */
    data object Dial : WatchFaceLayer

    /** The Sunday ticks of a year that begins on a Sunday; the face turns them to the current year. */
    data object Sundays : WatchFaceLayer

    /** Today's marker line and diamond, and the Earth's translucent spike to the rim. */
    data object EarthSpike : WatchFaceLayer

    /** Zodiac sectors, dividers and rims, with no sign lit and no glyphs. */
    data object ZodiacRing : WatchFaceLayer

    /** What [sign]'s sector gains when the Sun is in it, laid over [ZodiacRing]. */
    data class ZodiacHighlight(val sign: Zodiac.Sign) : WatchFaceLayer

    data class ZodiacGlyph(val sign: Zodiac.Sign, val active: Boolean) : WatchFaceLayer

    /** A zodiac hand's pearl and sign glyph where it meets the ring, in white for the face to tint. */
    data class SignMarker(val sign: Zodiac.Sign) : WatchFaceLayer

    /** [body]'s trail and hand from the Sun. */
    data class Orbit(val body: Astronomy.Body) : WatchFaceLayer

    /** A planet (not the Earth): its small world, or its symbol in astrology mode. */
    data class Planet(val body: Astronomy.Body) : WatchFaceLayer

    /** The Earth's 24-hour gear and lunar track, noon toward the Sun. */
    data object Subdial : WatchFaceLayer

    /** The subdial's hand to the Moon. */
    data object MoonHand : WatchFaceLayer

    /** The Moon (a crescent in astrology mode), lit from the Sun below it. */
    data object Moon : WatchFaceLayer

    /** Astronomy: the globe's night side and rim. Astrology: the enamel disc under the ⊕. */
    data object EarthCentre : WatchFaceLayer

    /** Astrology: the upright ⊕. */
    data object EarthSymbol : WatchFaceLayer

    /** The globe's unlit surface turned to [siderealDegrees], upright on the dial. */
    data class Globe(val siderealDegrees: Double) : WatchFaceLayer

    /** A red tooth on the Earth's gear at local midnight; the face turns it to the local time. */
    data object LocalHour : WatchFaceLayer

    data object Sun : WatchFaceLayer
}

/**
 * Astrology hands from the Earth to each body and on to its sign, shared by the instrument and
 * the watch face, which draws them as lines.
 */
enum class ZodiacHand(
    private val color: Int,
    val alpha: Int,
    val strokeRatio: Float,
    val dashRatio: Float,
    val gapRatio: Float,
) {
    SUN(0xFFFFD17A.toInt(), 135, .0021f, .010f, .011f),
    MOON(0xFFFFEAB5.toInt(), 135, .0021f, .010f, .011f),
    MERCURY(0xFF8EA5B8.toInt(), 145, .0022f, .012f, .010f),
    VENUS(0xFFFFCE7A.toInt(), 145, .0022f, .012f, .010f),
    MARS(0xFFE8735C.toInt(), 145, .0022f, .012f, .010f);

    /** Brass Watch engraves every hand in its ink. */
    fun colorIn(style: CelestialStyle): Int = if (style.brassFace) style.instrumentColor else color
}
