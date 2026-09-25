package com.metavirtuoso.sundial.ui

/** Pure circular hit-testing shared by the annual and daily calendar rings. */
object CalendarHitTesting {
    fun containsYearFraction(
        touchFraction: Double,
        startFraction: Double,
        sweepFraction: Double,
        paddingFraction: Double,
    ): Boolean = containsCircular(
        touchFraction,
        startFraction,
        sweepFraction,
        paddingFraction,
        period = 1.0,
    )

    fun containsMinute(
        touchMinute: Double,
        startMinute: Double,
        endMinuteExclusive: Double,
        paddingMinutes: Double,
    ): Boolean = containsCircular(
        touchMinute,
        startMinute,
        (endMinuteExclusive - startMinute).coerceAtLeast(0.0),
        paddingMinutes,
        period = 1_440.0,
    )

    private fun containsCircular(
        touch: Double,
        start: Double,
        sweep: Double,
        padding: Double,
        period: Double,
    ): Boolean {
        val safePadding = padding.coerceAtLeast(0.0)
        val span = (sweep + safePadding * 2.0).coerceAtMost(period)
        val paddedStart = normalize(start - safePadding, period)
        val forward = normalize(touch - paddedStart, period)
        return forward <= span
    }

    private fun normalize(value: Double, period: Double): Double {
        var normalized = value % period
        if (normalized < 0.0) normalized += period
        return normalized
    }
}
