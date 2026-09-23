package com.primesoftwaresystems.sundial.ui

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
