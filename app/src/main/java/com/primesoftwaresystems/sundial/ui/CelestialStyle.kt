package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.graphics.Color

enum class CelestialStyle(
    val displayName: String,
    val baseColor: Int,
    val haloColor: Int,
    val accentColor: Int,
) {
    VOID_BLACK("Void Black", Color.BLACK, 0xFF080808.toInt(), 0xFFFFD37A.toInt()),
    CRIMSON_NEBULA("Crimson Nebula", 0xFF160003.toInt(), 0xFF5A0715.toInt(), 0xFFFFA27B.toInt()),
    DEEP_SPACE_BLUE("Deep Space Blue", 0xFF01050E.toInt(), 0xFF092A4A.toInt(), 0xFF8ED9FF.toInt()),
    COSMIC_VIOLET("Cosmic Violet", 0xFF08010F.toInt(), 0xFF321055.toInt(), 0xFFD7A4FF.toInt()),
    SOLAR_BRONZE("Solar Bronze", 0xFF0D0701.toInt(), 0xFF3D2108.toInt(), 0xFFFFC96B.toInt()),
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
