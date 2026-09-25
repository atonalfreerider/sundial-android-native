package com.metavirtuoso.sundial.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Civil-time positions and human labels for the 24 timezone spokes. */
object TimeZoneDial {
    data class Spoke(
        val offsetHours: Int,
        val angleDegrees: Double,
        val localDate: LocalDate,
        val label: String,
    )

    /**
     * Spokes sit on Unity's solar 24-hour ring: each zone points at its local time, so the zone
     * at noon faces the Sun (screen top) and midnight faces away.
     */
    fun spokes(instant: Instant, north: Boolean = true): List<Spoke> = (-12..11).map { offset ->
        val local = instant.atOffset(ZoneOffset.ofHours(offset))
        Spoke(
            offsetHours = offset,
            angleDegrees = DialGeometry.hourAngle(
                (local.hour * 60.0 + local.minute + local.second / 60.0) / 60.0, north,
            ),
            localDate = local.toLocalDate(),
            label = commonName(offset),
        )
    }

    /**
     * Hour-ring position of the international date line: the local time of day just west of it
     * (UTC+12). Zones from local midnight round to this point already have the new date.
     */
    fun datelineHours(instant: Instant): Double {
        val local = instant.atOffset(ZoneOffset.ofHours(12))
        return local.hour + local.minute / 60.0 + local.second / 3_600.0
    }

    fun localOffsetMinutes(instant: Instant, zoneId: ZoneId): Int =
        zoneId.rules.getOffset(instant).totalSeconds / 60

    fun angleForOffsetMinutes(instant: Instant, offsetMinutes: Int, north: Boolean = true): Double {
        val local = instant.atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
        val localMinutes = local.hour * 60.0 + local.minute + local.second / 60.0 + local.nano / 60_000_000_000.0
        return DialGeometry.hourAngle(localMinutes / 60.0, north)
    }

    fun localName(zoneId: ZoneId, instant: Instant): String {
        val id = zoneId.id
        return when {
            id.contains("Los_Angeles") || id.contains("Vancouver") -> "Pacific Time"
            id.contains("Denver") || id.contains("Phoenix") || id.contains("Edmonton") -> "Mountain Time"
            id.contains("Chicago") || id.contains("Winnipeg") -> "Central Time"
            id.contains("New_York") || id.contains("Toronto") -> "Eastern Time"
            id.contains("Anchorage") -> "Alaska Time"
            id.contains("Honolulu") -> "Hawaii Time"
            id.contains("London") -> "UK Time"
            id.contains("Paris") || id.contains("Berlin") || id.contains("Rome") -> "Central European Time"
            id.contains("Tokyo") -> "Japan Time"
            id.contains("Sydney") || id.contains("Melbourne") -> "Eastern Australia Time"
            else -> commonName(localOffsetMinutes(instant, zoneId).floorDiv(60))
        }
    }

    fun commonName(offsetHours: Int): String = when (offsetHours) {
        -12 -> "Date Line West"
        -11 -> "Samoa Time"
        -10 -> "Hawaii Time"
        -9 -> "Alaska Time"
        -8 -> "Pacific Time"
        -7 -> "Mountain Time"
        -6 -> "Central Time"
        -5 -> "Eastern Time"
        -4 -> "Atlantic Time"
        -3 -> "Argentina Time"
        -2 -> "South Georgia Time"
        -1 -> "Azores Time"
        0 -> "UK / Greenwich Time"
        1 -> "Central European Time"
        2 -> "Eastern European Time"
        3 -> "Moscow Time"
        4 -> "Gulf Time"
        5 -> "Pakistan Time"
        6 -> "Bangladesh Time"
        7 -> "Indochina Time"
        8 -> "China / Singapore Time"
        9 -> "Japan / Korea Time"
        10 -> "Eastern Australia Time"
        11 -> "Solomon Islands Time"
        else -> "UTC${if (offsetHours >= 0) "+" else ""}$offsetHours"
    }

    fun nearestSpoke(spokes: List<Spoke>, angleDegrees: Double): Spoke =
        spokes.minBy { kotlin.math.abs(normalizeSigned(it.angleDegrees - angleDegrees)) }

    private fun normalizeSigned(value: Double): Double =
        ((value + 540.0) % 360.0) - 180.0
}
