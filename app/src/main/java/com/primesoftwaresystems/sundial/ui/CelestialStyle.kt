package com.primesoftwaresystems.sundial.ui

import android.content.Context

enum class CelestialStyle(
    val displayName: String,
    val baseColor: Int,
    val haloColor: Int,
    val accentColor: Int,
    val instrumentColor: Int,
) {
    VOID_BLACK("Void Black", 0xFF010101.toInt(), 0xFF17130D.toInt(), 0xFFFFD37A.toInt(), 0xFFF2EFE7.toInt()),
    CRIMSON_NEBULA("Crimson Nebula", 0xFF27030B.toInt(), 0xFF78152B.toInt(), 0xFFFFA27B.toInt(), 0xFFFFE5DE.toInt()),
    DEEP_SPACE_BLUE("Deep Space Blue", 0xFF031027.toInt(), 0xFF155284.toInt(), 0xFF8ED9FF.toInt(), 0xFFE2F3FF.toInt()),
    COSMIC_VIOLET("Cosmic Violet", 0xFF170628.toInt(), 0xFF60298A.toInt(), 0xFFD7A4FF.toInt(), 0xFFF3E5FF.toInt()),
    SOLAR_BRONZE("Solar Bronze", 0xFF251204.toInt(), 0xFF754313.toInt(), 0xFFFFC96B.toInt(), 0xFFFFEFD0.toInt()),
}

object CelestialStylePreferences {
    private const val PREFERENCES = "celestial_appearance"
    private const val BACKGROUND_STYLE = "background_style"

    fun get(context: Context): CelestialStyle {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(BACKGROUND_STYLE, null)
        return CelestialStyle.entries.firstOrNull { it.name == stored } ?: CelestialStyle.VOID_BLACK
    }

    fun set(context: Context, style: CelestialStyle) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(BACKGROUND_STYLE, style.name).apply()
    }
}
