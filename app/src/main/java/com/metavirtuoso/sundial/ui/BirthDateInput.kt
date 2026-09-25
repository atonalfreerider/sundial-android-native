package com.metavirtuoso.sundial.ui

import java.time.LocalDate

/** Strict direct-entry parsing for a human birthday, including Gregorian leap-year validation. */
object BirthDateInput {
    fun parse(month: String, day: String, year: String, today: LocalDate = LocalDate.now()): LocalDate {
        require(year.trim().length == 4) { "Enter a 4-digit birth year" }
        val monthNumber = month.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a numeric month")
        val dayNumber = day.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a numeric day")
        val yearNumber = year.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a numeric year")
        val date = runCatching { LocalDate.of(yearNumber, monthNumber, dayNumber) }
            .getOrElse { throw IllegalArgumentException("Enter a valid calendar date") }
        require(!date.isAfter(today)) { "Birth date cannot be in the future" }
        return date
    }
}

/** Strict direct-entry parsing for a 12-hour birth time. */
object BirthTimeInput {
    fun parse(hour: String, minute: String, pm: Boolean): java.time.LocalTime {
        val hourNumber = hour.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a numeric hour")
        val minuteNumber = minute.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter numeric minutes")
        require(hourNumber in 1..12) { "Hour must be 1 to 12" }
        require(minuteNumber in 0..59) { "Minutes must be 00 to 59" }
        return java.time.LocalTime.of(hourNumber % 12 + if (pm) 12 else 0, minuteNumber)
    }
}
