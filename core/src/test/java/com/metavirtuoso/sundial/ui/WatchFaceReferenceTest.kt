package com.metavirtuoso.sundial.ui

import com.metavirtuoso.sundial.astronomy.Astronomy
import com.metavirtuoso.sundial.astronomy.Zodiac
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Writes the instrument's own angles for a spread of instants to build/watchface-reference.json.
 * The watch face computes the same angles with Watch Face Format expressions; its generator
 * (watchface/tools/generate.py --check) evaluates them and compares against this file.
 */
class WatchFaceReferenceTest {
    @Test fun `write watch face reference angles`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        // Every 37 days and 7 hours for 12 years: all seasons, weekdays, hours and lunar phases.
        val instants = (0 until 120).map { start.plusSeconds(it * (37L * 86_400 + 7 * 3_600 + 13 * 60)) }
        val rows = instants.joinToString(",\n") { instant ->
            val utc = instant.atZone(ZoneOffset.UTC)
            val date = utc.toLocalDate()
            val firstSunday = LocalDate.of(date.year, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            val values = linkedMapOf(
                "epochMillis" to instant.toEpochMilli().toDouble(),
                "year" to date.year.toDouble(),
                "month" to date.monthValue.toDouble(),
                "day" to date.dayOfMonth.toDouble(),
                "dayOfYear" to date.dayOfYear.toDouble(),
                // java.time counts Monday as 1; Watch Face Format counts Sunday as 1.
                "dayOfWeek" to (date.dayOfWeek.value % 7 + 1).toDouble(),
                "hour" to utc.hour.toDouble(),
                "minute" to utc.minute.toDouble(),
                "earthAngle" to DialGeometry.annualAngle(Astronomy.civilYearFraction(utc), true),
                "mercuryAngle" to planetAngle(Astronomy.Body.MERCURY, instant),
                "venusAngle" to planetAngle(Astronomy.Body.VENUS, instant),
                "marsAngle" to planetAngle(Astronomy.Body.MARS, instant),
                "moonPhase" to Astronomy.moonPhaseDegrees(instant),
                "sidereal" to Astronomy.greenwichMeanSiderealDegrees(instant),
                "firstSunday" to (firstSunday.dayOfYear - 1).toDouble(),
                "season" to Zodiac.seasonFor(date, true).ordinal.toDouble(),
                "dateSign" to Zodiac.signFor(date).ordinal.toDouble(),
                "sunSign" to Zodiac.signForLongitude(Zodiac.sunLongitude(instant)).ordinal.toDouble(),
                "moonSign" to Zodiac.signForLongitude(Astronomy.moonLongitudeDegrees(instant)).ordinal.toDouble(),
                "mercurySign" to geocentricSign(Astronomy.Body.MERCURY, instant),
                "venusSign" to geocentricSign(Astronomy.Body.VENUS, instant),
                "marsSign" to geocentricSign(Astronomy.Body.MARS, instant),
                "mercuryLongitude" to Zodiac.geocentricLongitude(Astronomy.Body.MERCURY, instant),
                "venusLongitude" to Zodiac.geocentricLongitude(Astronomy.Body.VENUS, instant),
                "marsLongitude" to Zodiac.geocentricLongitude(Astronomy.Body.MARS, instant),
                "sunLongitude" to Zodiac.sunLongitude(instant),
                "moonLongitude" to Astronomy.moonLongitudeDegrees(instant),
            )
            values.entries.joinToString(", ", "  {", "}") { (key, value) -> "\"$key\": $value" }
        }
        File("build").mkdirs()
        File("build/watchface-reference.json").writeText("[\n$rows\n]\n")
    }

    private fun planetAngle(body: Astronomy.Body, instant: Instant): Double =
        DialGeometry.eclipticAngle(Astronomy.heliocentricPosition(body, instant).longitudeDegrees, true)

    private fun geocentricSign(body: Astronomy.Body, instant: Instant): Double =
        Zodiac.signForLongitude(Zodiac.geocentricLongitude(body, instant)).ordinal.toDouble()
}
