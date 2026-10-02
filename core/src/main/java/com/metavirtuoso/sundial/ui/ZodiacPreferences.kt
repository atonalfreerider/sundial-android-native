package com.metavirtuoso.sundial.ui

import android.content.Context
import com.metavirtuoso.sundial.astronomy.Zodiac
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ZodiacProfile(
    val enabled: Boolean = false,
    val birthDate: LocalDate? = null,
    val birthTime: LocalTime? = null,
    val selectedSign: Zodiac.Sign? = null,
    /** IANA id of the zone the birth time was read in; null means this device's zone. */
    val birthZoneId: String? = null,
) {
    fun resolvedSign(today: LocalDate = LocalDate.now()): Zodiac.Sign =
        selectedSign ?: natalSign() ?: Zodiac.signFor(today)

    /** The zone the birth time is in: the saved one if it is a valid zone, else the device's. */
    val birthZone: ZoneId
        get() = birthZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    /** The moment of birth, once date and time are known. */
    val birthInstant: Instant?
        get() = if (birthDate != null && birthTime != null) birthDate.atTime(birthTime).atZone(birthZone).toInstant() else null

    /**
     * The Sun's sign at birth: from the Sun's true longitude at the moment of birth when the time
     * and zone are known (exact on cusp days), else from the date's usual sign boundaries.
     */
    fun natalSign(): Zodiac.Sign? =
        birthInstant?.let { Zodiac.signForLongitude(Zodiac.sunLongitude(it)) } ?: birthDate?.let(Zodiac::signFor)

    val isComplete: Boolean get() = birthDate != null && birthTime != null
    val signature: String get() = listOf(enabled, birthDate, birthTime, selectedSign, birthZoneId).joinToString("|")
}

object ZodiacPreferences {
    private const val PREFS = "zodiac_profile"
    private const val ENABLED = "enabled"
    private const val OPT_IN_VERSION = "opt_in_version"
    private const val BIRTH_DATE = "birth_date"
    private const val BIRTH_TIME = "birth_time"
    private const val BIRTH_ZONE = "birth_zone"
    private const val SIGN = "sign"
    private const val HOROSCOPE = "horoscope"
    private const val HOROSCOPE_SIGNATURE = "horoscope_signature"
    private const val HOROSCOPE_DATE = "horoscope_date"
    private const val REPORTED_DATE = "reported_date"

    fun get(context: Context): ZodiacProfile {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = values.getInt(OPT_IN_VERSION, 0) >= 1 && values.getBoolean(ENABLED, false)
        if (values.getInt(OPT_IN_VERSION, 0) < 1) {
            values.edit().putInt(OPT_IN_VERSION, 1).putBoolean(ENABLED, false).apply()
        }
        return ZodiacProfile(
            enabled = enabled,
            birthDate = values.getString(BIRTH_DATE, null)?.let(LocalDate::parse),
            birthTime = values.getString(BIRTH_TIME, null)?.let(LocalTime::parse),
            selectedSign = values.getString(SIGN, null)?.let { runCatching { Zodiac.Sign.valueOf(it) }.getOrNull() },
            birthZoneId = values.getString(BIRTH_ZONE, null),
        )
    }

    /**
     * Saves profile controls without allowing a partial UI update to erase natal data. There is
     * deliberately no implicit "clear" operation: birthday and time remain until the user replaces
     * them through their respective editors.
     */
    fun set(context: Context, profile: ZodiacProfile): ZodiacProfile {
        val existing = get(context)
        val preserved = preserveNatalData(profile, existing)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(OPT_IN_VERSION, 1)
            .putBoolean(ENABLED, preserved.enabled)
            .putString(BIRTH_DATE, preserved.birthDate?.toString())
            .putString(BIRTH_TIME, preserved.birthTime?.toString())
            .putString(SIGN, preserved.selectedSign?.name)
            .putString(BIRTH_ZONE, preserved.birthZoneId)
            .apply()
        return preserved
    }

    internal fun preserveNatalData(requested: ZodiacProfile, stored: ZodiacProfile): ZodiacProfile =
        requested.copy(
            birthDate = requested.birthDate ?: stored.birthDate,
            birthTime = requested.birthTime ?: stored.birthTime,
            birthZoneId = requested.birthZoneId ?: stored.birthZoneId,
        )

    fun setHoroscope(context: Context, profile: ZodiacProfile, date: LocalDate, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(HOROSCOPE, text.trim())
            .putString(HOROSCOPE_SIGNATURE, profile.signature)
            .putString(HOROSCOPE_DATE, date.toString())
            .apply()
    }

    /** Hides a reported reading and holds off writing another automatically until tomorrow. */
    fun hideReportedHoroscope(context: Context, date: LocalDate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(HOROSCOPE)
            .putString(REPORTED_DATE, date.toString())
            .apply()
    }

    fun wasReadingReported(context: Context, date: LocalDate): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(REPORTED_DATE, null) == date.toString()

    fun getCurrentHoroscope(context: Context, profile: ZodiacProfile, date: LocalDate): String? {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (values.getString(HOROSCOPE_SIGNATURE, null) != profile.signature) return null
        if (values.getString(HOROSCOPE_DATE, null) != date.toString()) return null
        return values.getString(HOROSCOPE, null)?.takeIf { it.isNotBlank() }
    }
}
