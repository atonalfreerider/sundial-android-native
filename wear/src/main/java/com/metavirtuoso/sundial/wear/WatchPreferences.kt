package com.metavirtuoso.sundial.wear

import android.content.Context

/**
 * Watch-only settings. The aesthetic and astrology mode use the shared core preferences; the
 * galactic view and "return to now" are one-shot requests the dial picks up when it resumes.
 */
object WatchPreferences {
    private const val PREFERENCES = "watch_settings"
    private const val CLOCK = "clock"
    private const val SOUTHERN = "southern_hemisphere"
    private const val REQUEST = "request"

    enum class Request { NONE, GALACTIC, NOW }

    fun showClock(context: Context): Boolean = prefs(context).getBoolean(CLOCK, true)
    fun setShowClock(context: Context, value: Boolean) = prefs(context).edit().putBoolean(CLOCK, value).apply()

    fun southern(context: Context): Boolean = prefs(context).getBoolean(SOUTHERN, false)
    fun setSouthern(context: Context, value: Boolean) = prefs(context).edit().putBoolean(SOUTHERN, value).apply()

    fun request(context: Context, value: Request) = prefs(context).edit().putString(REQUEST, value.name).apply()

    fun consumeRequest(context: Context): Request {
        val value = prefs(context).getString(REQUEST, null)?.let { runCatching { Request.valueOf(it) }.getOrNull() } ?: Request.NONE
        if (value != Request.NONE) prefs(context).edit().remove(REQUEST).apply()
        return value
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
