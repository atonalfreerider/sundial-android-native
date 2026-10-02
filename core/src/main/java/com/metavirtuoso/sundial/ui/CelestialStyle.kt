package com.metavirtuoso.sundial.ui

import android.content.Context
import com.metavirtuoso.sundial.astronomy.Zodiac

/**
 * Aesthetics shared by the app and its wallpapers. Sky styles draw a luminous instrument on a
 * night sky; [brassFace] engraves it into a polished brass watch face instead, with dark
 * [instrumentColor] ink on the metal and a light [chromeColor] for anything drawn off the face.
 */
enum class CelestialStyle(
    val displayName: String,
    val baseColor: Int,
    val haloColor: Int,
    val accentColor: Int,
    val instrumentColor: Int,
    val chromeColor: Int = instrumentColor,
    val brassFace: Boolean = false,
    /** Offered in the style menus; the element palettes are chosen by astrology mode instead. */
    val pickable: Boolean = true,
) {
    VOID_BLACK("Void Black", 0xFF010101.toInt(), 0xFF17130D.toInt(), 0xFFFFD37A.toInt(), 0xFFF2EFE7.toInt()),
    CRIMSON_NEBULA("Crimson Nebula", 0xFF27030B.toInt(), 0xFF78152B.toInt(), 0xFFFFA27B.toInt(), 0xFFFFE5DE.toInt()),
    DEEP_SPACE_BLUE("Deep Space Blue", 0xFF031027.toInt(), 0xFF155284.toInt(), 0xFF8ED9FF.toInt(), 0xFFE2F3FF.toInt()),
    COSMIC_VIOLET("Cosmic Violet", 0xFF170628.toInt(), 0xFF60298A.toInt(), 0xFFD7A4FF.toInt(), 0xFFF3E5FF.toInt()),
    SOLAR_BRONZE("Solar Bronze", 0xFF251204.toInt(), 0xFF754313.toInt(), 0xFFFFC96B.toInt(), 0xFFFFEFD0.toInt()),
    BRASS_WATCH(
        "Brass Watch",
        baseColor = 0xFF0B0A09.toInt(),
        haloColor = 0xFF2C2822.toInt(),
        accentColor = 0xFF8A6526.toInt(),
        instrumentColor = 0xFF3A2710.toInt(),
        chromeColor = 0xFFEBD393.toInt(),
        brassFace = true,
    ),

    // Astrology mode dresses the sky in the reader's element (see [effective]).
    FIRE("Fire", 0xFF2A0703.toInt(), 0xFF8C2A0B.toInt(), 0xFFFFB347.toInt(), 0xFFFFEBD6.toInt(), pickable = false),
    EARTH("Earth", 0xFF0D1507.toInt(), 0xFF3F5B1F.toInt(), 0xFFD9C47C.toInt(), 0xFFF1EEDA.toInt(), pickable = false),
    AIR("Air", 0xFF0B1424.toInt(), 0xFF587DA6.toInt(), 0xFFE4F1FF.toInt(), 0xFFF6FAFF.toInt(), pickable = false),
    WATER("Water", 0xFF021A1F.toInt(), 0xFF0E6A73.toInt(), 0xFF7FE6DE.toInt(), 0xFFDDFAF7.toInt(), pickable = false),
    ;

    companion object {
        fun forElement(element: Zodiac.Element): CelestialStyle = when (element) {
            Zodiac.Element.FIRE -> FIRE
            Zodiac.Element.EARTH -> EARTH
            Zodiac.Element.AIR -> AIR
            Zodiac.Element.WATER -> WATER
        }

        /**
         * The palette actually drawn: Brass Watch always wins; otherwise astrology mode uses the
         * reader's element, and astronomy the style chosen in settings.
         */
        fun effective(selected: CelestialStyle, profile: ZodiacProfile): CelestialStyle = when {
            selected.brassFace -> selected
            profile.enabled -> forElement(profile.resolvedSign().element)
            else -> selected
        }
    }
}

object CelestialStylePreferences {
    private const val PREFERENCES = "celestial_appearance"
    private const val BACKGROUND_STYLE = "background_style"

    fun get(context: Context): CelestialStyle {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(BACKGROUND_STYLE, null)
        return CelestialStyle.entries.firstOrNull { it.name == stored && it.pickable } ?: CelestialStyle.VOID_BLACK
    }

    fun set(context: Context, style: CelestialStyle) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(BACKGROUND_STYLE, style.name).apply()
    }
}
