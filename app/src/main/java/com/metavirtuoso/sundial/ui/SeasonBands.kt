package com.metavirtuoso.sundial.ui

import com.metavirtuoso.sundial.astronomy.Astronomy
import com.metavirtuoso.sundial.astronomy.Zodiac
import java.time.LocalDate

/**
 * Seasons of the civil year (northern names) with soft edges: within [BLEND_DAYS] of a solstice or
 * equinox the outgoing season fades into the incoming one instead of switching at a hard line.
 */
internal object SeasonBands {
    const val BLEND_DAYS = 12.0

    data class Mix(val from: Zodiac.Season, val to: Zodiac.Season, val amount: Double)

    /** Year fractions at which spring, summer, fall and winter begin. */
    fun starts(year: Int): List<Pair<Double, Zodiac.Season>> {
        val days = Astronomy.daysInYear(year).toDouble()
        fun fraction(month: Int, day: Int) = (LocalDate.of(year, month, day).dayOfYear - 1) / days
        return listOf(
            fraction(3, 21) to Zodiac.Season.SPRING,
            fraction(6, 21) to Zodiac.Season.SUMMER,
            fraction(9, 23) to Zodiac.Season.FALL,
            fraction(12, 22) to Zodiac.Season.WINTER,
        )
    }

    fun mixAt(fraction: Double, starts: List<Pair<Double, Zodiac.Season>>, daysInYear: Int): Mix {
        val halfWidth = BLEND_DAYS / daysInYear
        starts.forEachIndexed { index, (start, season) ->
            var offset = fraction - start
            offset -= kotlin.math.floor(offset + .5)
            if (kotlin.math.abs(offset) < halfWidth) {
                val previous = starts[(index + starts.size - 1) % starts.size].second
                val t = (offset + halfWidth) / (2 * halfWidth)
                return Mix(previous, season, t * t * (3 - 2 * t))
            }
        }
        val season = starts.lastOrNull { fraction >= it.first }?.second ?: starts.last().second
        return Mix(season, season, 0.0)
    }
}
