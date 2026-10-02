package com.metavirtuoso.sundial.calendar

/**
 * Icons for the days a holiday calendar (Google's "Holidays in United States" and the like)
 * marks. Holidays crowd the year ring, so each is shown as a small icon at its date instead of a
 * title along a band. Titles are matched loosely, as the calendars word them differently.
 */
object HolidayIcons {
    /** Whether a calendar is a holiday calendar, by the name it is synced with. */
    fun isHolidayCalendar(displayName: String, ownerAccount: String? = null): Boolean =
        displayName.contains("holiday", ignoreCase = true) ||
            ownerAccount?.contains("#holiday@", ignoreCase = true) == true

    /** The icon for [title], or [FALLBACK] for a holiday this table does not know. */
    fun iconFor(title: String): String {
        val name = title.lowercase()
        return RULES.firstOrNull { (keys, _) -> keys.any { it in name } }?.second ?: FALLBACK
    }

    const val FALLBACK = "★"

    // Most specific first: "New Year's Eve" before "New Year", "Christmas Eve" before "Christmas",
    // "Day after Thanksgiving" before "Thanksgiving".
    private val RULES: List<Pair<List<String>, String>> = listOf(
        listOf("lunar new year", "chinese new year") to "🏮",
        listOf("new year's eve", "new years eve") to "🥂",
        listOf("new year") to "🎉",
        listOf("martin luther king", "mlk") to "✊",
        listOf("groundhog") to "🦫",
        listOf("lincoln") to "🎩",
        listOf("valentine") to "❤️",
        listOf("president", "washington's birthday") to "🎩",
        listOf("daylight saving") to "⏰",
        listOf("patrick") to "☘️",
        listOf("tax day") to "🧾",
        listOf("good friday") to "✝️",
        listOf("easter") to "🐣",
        listOf("earth day") to "🌍",
        listOf("cinco de mayo") to "🌮",
        listOf("mother") to "💐",
        listOf("armed forces", "memorial", "veteran") to "🎖️",
        listOf("flag day") to "🇺🇸",
        listOf("juneteenth") to "🕊️",
        listOf("father") to "👔",
        listOf("independence") to "🎆",
        listOf("labor day", "labour day") to "🛠️",
        listOf("indigenous") to "🪶",
        listOf("columbus") to "🧭",
        listOf("halloween") to "🎃",
        listOf("election") to "🗳️",
        listOf("black friday", "day after thanksgiving", "cyber monday") to "🛍️",
        listOf("thanksgiving") to "🦃",
        listOf("rosh hashanah") to "🍎",
        listOf("yom kippur") to "🕯️",
        listOf("hanukkah", "chanukah") to "🕎",
        listOf("diwali") to "🪔",
        listOf("ramadan", "eid") to "🌙",
        listOf("christmas eve") to "🌟",
        listOf("christmas") to "🎄",
        listOf("kwanzaa") to "🕯️",
        listOf("first day of", "solstice", "equinox") to "☀️",
    )
}
