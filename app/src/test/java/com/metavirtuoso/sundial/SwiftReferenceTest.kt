package com.metavirtuoso.sundial

import com.metavirtuoso.sundial.astronomy.Astronomy
import com.metavirtuoso.sundial.astronomy.Zodiac
import com.metavirtuoso.sundial.calendar.CalendarIntervals
import com.metavirtuoso.sundial.calendar.CalendarNormalizer
import com.metavirtuoso.sundial.calendar.CalendarOccurrence
import com.metavirtuoso.sundial.calendar.DeviceCalendar
import com.metavirtuoso.sundial.calendar.RawCalendarInstance
import com.metavirtuoso.sundial.horoscope.ReadingReporter
import com.metavirtuoso.sundial.ui.BirthDateInput
import com.metavirtuoso.sundial.ui.BirthTimeInput
import com.metavirtuoso.sundial.ui.CalendarHitTesting
import com.metavirtuoso.sundial.ui.DialGeometry
import com.metavirtuoso.sundial.ui.TimeZoneDial
import com.metavirtuoso.sundial.ui.ZodiacPreferences
import com.metavirtuoso.sundial.ui.ZodiacProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Writes build/swift-reference.json: the outputs of the pure functions that the Swift port
 * (sundial-apple, SundialCore) re-implements, over many inputs, so its tests can compare value for
 * value. The record shapes are documented in
 * sundial-apple/Tests/SundialCoreTests/Resources/README.md; change both together.
 *
 * GalacticGeometry, EarthOrientation and SeasonBands are internal to :core, so they are reached by
 * reflection. The horoscope prompt, the report form body and SundialView's star fields live inside
 * functions that need a model, the network or a View; they are copied here, and the copies are
 * checked against the sources so they cannot drift silently.
 */
class SwiftReferenceTest {
    private val out = LinkedHashMap<String, Any?>()

    private fun put(key: String, value: Any?) {
        require(key !in out) { "duplicate key $key" }
        out[key] = value
    }

    @Test fun `write Swift reference data`() {
        meta()
        astronomy()
        zodiac()
        dialGeometry()
        seasonBands()
        timeZoneDial()
        galacticGeometry()
        earthOrientation()
        calendarHitTesting()
        calendar()
        zodiacProfile()
        birthInput()
        horoscope()
        readingReporter()
        random()
        stars()

        val text = Json.render(out)
        File("build").mkdirs()
        File("build/swift-reference.json").writeText(text)
        assertTrue("reference data is ${text.length} bytes", text.length < 8_000_000)
    }

    // ------------------------------------------------------------------------------------------
    // Inputs

    private companion object {
        val ZONES: List<ZoneId> = listOf(
            "UTC", "America/Los_Angeles", "Asia/Kolkata", "Australia/Lord_Howe", "Pacific/Chatham",
            "America/St_Johns", "Europe/London", "America/Santiago",
        ).map(ZoneId::of)

        val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS")

        fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()
        fun ms(instant: Instant): Long = instant.toEpochMilli()
        fun rec(vararg fields: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*fields)

        fun instantOut(instant: Instant) =
            rec("epochSecond" to instant.epochSecond, "nano" to instant.nano, "epochMillis" to instant.toEpochMilli())

        fun zonedOut(dateTime: ZonedDateTime) = rec(
            "epochMillis" to dateTime.toInstant().toEpochMilli(),
            "offsetSeconds" to dateTime.offset.totalSeconds,
            "local" to LOCAL.format(dateTime),
            "zone" to dateTime.zone.id,
        )

        fun timeOut(time: LocalTime?) = time?.let { rec("hour" to it.hour, "minute" to it.minute, "second" to it.second) }

        /** EARTH_ORBIT -> earthOrbit; names that are already camel case stay as they are. */
        fun swiftName(name: String): String =
            if (name.any { it.isLowerCase() }) name
            else name.lowercase().split('_').mapIndexed { i, part ->
                if (i == 0) part else part.replaceFirstChar(Char::uppercase)
            }.joinToString("")

        fun constant(name: String, type: String, value: Any) =
            rec("name" to name, "swiftName" to swiftName(name), "type" to type, "value" to value)

        fun grid(from: Int, to: Int, step: Double): List<Double> = (from..to).map { it * step }

        /** About 200 instants from 1950 to 2100, including J2000, leap days and year ends. */
        val INSTANTS: List<Instant> by lazy {
            val fixed = listOf(
                "1950-01-01T00:00:00Z", "1952-02-29T12:34:56.789Z", "1969-12-31T23:59:59.999Z",
                "1970-01-01T00:00:00Z", "1987-10-19T14:30:00Z", "1999-12-31T23:59:59.999Z",
                "2000-01-01T00:00:00Z", "2000-01-01T12:00:00Z", "2000-02-29T00:00:00Z",
                "2000-02-29T23:59:59.999Z", "2000-03-01T00:00:00Z", "2001-09-09T01:46:40Z",
                "2023-12-31T23:59:59.999Z", "2024-01-01T00:00:00Z", "2024-01-11T11:57:00Z",
                "2024-01-25T17:54:00Z", "2024-02-28T23:59:59.999Z", "2024-02-29T06:00:00Z",
                "2024-03-01T00:00:00Z", "2024-03-10T10:00:00Z", "2024-03-20T03:06:00Z",
                "2024-03-31T01:00:00Z", "2024-06-20T20:51:00Z", "2024-09-22T12:44:00Z",
                "2024-10-27T01:00:00Z", "2024-11-03T09:00:00Z", "2024-12-21T09:20:00Z",
                "2024-12-31T23:59:59.999Z", "2025-01-01T00:00:00Z", "2025-03-20T09:01:00Z",
                "2025-12-31T12:00:00Z", "2026-09-26T12:00:00Z", "2038-01-19T03:14:07Z",
                "2096-02-29T18:00:00Z", "2100-02-28T12:00:00Z", "2100-03-01T00:00:00Z",
                "2100-12-31T23:59:59.999Z",
            ).map(::at)
            val start = at("1950-01-01T00:00:00Z")
            val step = 322L * 86_400_000 + 7 * 3_600_000 + 13 * 60_000 + 17_123
            val spread = (1..170).map { start.plusMillis(it * step) }
            (fixed + spread).distinct().sorted()
        }

        /** Instants around every offset transition of 2024 and 2025 in [zone], and around its year starts. */
        fun zoneInstants(zone: ZoneId): List<Instant> {
            val rules = zone.rules
            val result = mutableListOf<Instant>()
            var transition = rules.nextTransition(at("2023-12-25T00:00:00Z"))
            val stop = at("2026-01-07T00:00:00Z")
            while (transition != null && transition.instant < stop) {
                for (delta in longArrayOf(-3_600_000, -1_800_000, -1, 0, 1, 1_800_000, 3_599_999, 3_600_000, 7_200_000)) {
                    result += transition.instant.plusMillis(delta)
                }
                val day = transition.instant.atZone(zone).toLocalDate()
                result += day.atStartOfDay(zone).toInstant()
                result += day.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
                transition = rules.nextTransition(transition.instant)
            }
            for (year in listOf(1950, 2000, 2024, 2025, 2026, 2100)) {
                val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant()
                result += start.minusMillis(1)
                result += start
                result += start.plusMillis(1)
            }
            return result
        }

        /** (zone, instant) for every zone: the common instants plus that zone's transitions. */
        val ZONE_CASES: List<Pair<ZoneId, Instant>> by lazy {
            ZONES.flatMap { zone -> (INSTANTS + zoneInstants(zone)).distinct().sorted().map { zone to it } }
        }

        val SPOKE_INSTANTS: List<Instant> by lazy {
            (INSTANTS.filterIndexed { i, _ -> i % 10 == 0 } + listOf(
                "2024-06-01T00:00:00Z", "2024-06-01T00:07:30Z", "2024-06-01T11:59:59.999Z",
                "2024-06-01T12:00:00Z", "2024-06-01T12:30:30.500Z", "2024-12-31T11:59:59Z",
                "2024-12-31T12:00:00Z", "2025-03-09T17:00:00Z",
            ).map(::at)).distinct().sorted()
        }
    }

    /** Calls into an internal :core object by reflection (internal is public in bytecode). */
    private class Internal(className: String) {
        val type: Class<*> = Class.forName("com.metavirtuoso.sundial.ui.$className")
        val instance: Any = type.getField("INSTANCE").get(null)

        fun field(name: String): Any = type.getField(name).get(null)

        fun call(name: String, vararg args: Any?): Any? {
            val candidates = type.methods.filter { it.name == name && it.parameterCount == args.size }
            check(candidates.size == 1) { "$name has ${candidates.size} candidates" }
            return candidates[0].invoke(instance, *args)
        }

        companion object {
            fun property(target: Any, name: String): Any? =
                target.javaClass.getMethod("get" + name.replaceFirstChar(Char::uppercase)).invoke(target)
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun meta() {
        put("_meta", rec(
            "generator" to "sundial-android-native/app/src/test/java/com/metavirtuoso/sundial/SwiftReferenceTest.kt",
            "javaVersion" to System.getProperty("java.version"),
            "kotlinVersion" to KotlinVersion.CURRENT.toString(),
            // ZoneRulesProvider is missing from android.jar, which unit tests compile against.
            "tzdbVersion" to (Class.forName("java.time.zone.ZoneRulesProvider")
                .getMethod("getVersions", String::class.java).invoke(null, "Europe/London") as java.util.NavigableMap<*, *>)
                .lastKey(),
            "appVersion" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "zones" to ZONES.map { it.id },
        ))
    }

    private fun astronomy() {
        put("Astronomy.constants", listOf(
            constant("JULIAN_DATE_UNIX_EPOCH", "Double", Astronomy.JULIAN_DATE_UNIX_EPOCH),
            constant("JULIAN_DATE_J2000", "Double", Astronomy.JULIAN_DATE_J2000),
            constant("SYNODIC_MONTH_DAYS", "Double", Astronomy.SYNODIC_MONTH_DAYS),
        ))
        put("Astronomy.Body", Astronomy.Body.entries.map { rec("name" to it.name, "ordinal" to it.ordinal) })
        put("Astronomy.julianDate", INSTANTS.map { rec("instant" to ms(it), "output" to Astronomy.julianDate(it)) })
        put("Astronomy.heliocentricPosition", Astronomy.Body.entries.flatMap { body ->
            INSTANTS.map { instant ->
                val v = Astronomy.heliocentricPosition(body, instant)
                rec("body" to body.name, "instant" to ms(instant), "output" to rec(
                    "x" to v.x, "y" to v.y, "z" to v.z, "radius" to v.radius, "longitudeDegrees" to v.longitudeDegrees,
                ))
            }
        })
        put("Astronomy.greenwichMeanSiderealDegrees",
            INSTANTS.map { rec("instant" to ms(it), "output" to Astronomy.greenwichMeanSiderealDegrees(it)) })
        put("Astronomy.moonLongitudeDegrees",
            INSTANTS.map { rec("instant" to ms(it), "output" to Astronomy.moonLongitudeDegrees(it)) })
        put("Astronomy.moonPhaseDegrees",
            INSTANTS.map { rec("instant" to ms(it), "output" to Astronomy.moonPhaseDegrees(it)) })
        put("Astronomy.sunLongitudeOfDate",
            INSTANTS.map { rec("instant" to ms(it), "output" to Astronomy.sunLongitudeOfDate(it)) })
        put("Astronomy.precessionDegrees",
            INSTANTS.map { rec("instant" to ms(it), "output" to Astronomy.precessionDegrees(it)) })
        put("Astronomy.daysInYear", listOf(
            1, 4, 100, 400, 1582, 1600, 1700, 1800, 1900, 1950, 1996, 1999, 2000, 2001, 2004, 2023,
            2024, 2025, 2026, 2096, 2100, 2200, 2400, 9999,
        ).map { rec("year" to it, "output" to Astronomy.daysInYear(it)) })

        put("Astronomy.civilYearFraction", ZONE_CASES.map { (zone, instant) ->
            val local = instant.atZone(zone)
            rec(
                "instant" to ms(instant), "zone" to zone.id, "offsetSeconds" to local.offset.totalSeconds,
                "local" to LOCAL.format(local), "output" to Astronomy.civilYearFraction(local),
            )
        })

        val fractions = listOf(
            -0.5, -1e-12, 0.0, 1e-12, 1e-9, 1.0 / 366, 1.0 / 365, 0.1, 0.19, 0.2, 0.25, 1.0 / 3, 0.5, 0.6,
            0.75, 0.83, 0.9, 0.99, 0.999999, 0.999999999, 0.9999999995, 1.0, 1.5,
        )
        val years = listOf(1950, 1999, 2000, 2023, 2024, 2025, 2026, 2099, 2100)
        val cases = mutableListOf<Triple<Int, Double, ZoneId>>()
        for (zone in ZONES) {
            for (year in years) for (fraction in fractions) cases += Triple(year, fraction, zone)
            // Fractions that land on this zone's 2024 and 2025 offset transitions.
            var transition = zone.rules.nextTransition(at("2024-01-01T00:00:00Z"))
            while (transition != null && transition.instant < at("2026-01-01T00:00:00Z")) {
                val local = transition.instant.atZone(zone)
                val fraction = Astronomy.civilYearFraction(local)
                for (f in listOf(fraction - 1e-7, fraction, fraction + 1e-7)) cases += Triple(local.year, f, zone)
                transition = zone.rules.nextTransition(transition.instant)
            }
        }
        put("Astronomy.instantAtYearFraction", cases.map { (year, fraction, zone) ->
            rec("year" to year, "fraction" to fraction, "zone" to zone.id,
                "output" to instantOut(Astronomy.instantAtYearFraction(year, fraction, zone)))
        })

        val degrees = (listOf(
            0.0, -0.0, 1e-300, -1e-300, 1e-15, -1e-15, 1e-12, -1e-12, 0.5, -0.5, 90.0, -90.0,
            179.99999999999997, 180.0, -180.0, 180.00000000000003, -179.99999999999997, 270.0, 359.0,
            359.9999999999, 359.99999999999994, 360.0, -360.0, 360.00000000000006, 540.0, -540.0,
            719.999, 720.0, -720.0, 1080.5, -1080.5, 12345.678, -12345.678, 1e10, -1e10, 1e15, -1e15,
            1e17, -1e17, 1e300, -1e300, PI, -PI, 2 * PI,
        ) + grid(-48, 48, 22.5) + grid(-40, 40, 13.37)).distinctBy { it.toRawBits() }
        put("Astronomy.normalizeDegrees", degrees.map { rec("input" to it, "output" to Astronomy.normalizeDegrees(it)) })
        put("Astronomy.normalizeSignedDegrees",
            degrees.map { rec("input" to it, "output" to Astronomy.normalizeSignedDegrees(it)) })
    }

    private fun zodiac() {
        put("Zodiac.Sign", Zodiac.Sign.entries.map {
            rec(
                "name" to it.name, "ordinal" to it.ordinal, "displayName" to it.displayName, "symbol" to it.symbol,
                "element" to it.element.name, "startMonth" to it.startMonth, "startDay" to it.startDay,
            )
        })
        put("Zodiac.Element", Zodiac.Element.entries.map { rec("name" to it.name, "ordinal" to it.ordinal) })
        put("Zodiac.Season", Zodiac.Season.entries.map { rec("name" to it.name, "ordinal" to it.ordinal) })

        fun daysOf(year: Int) = (0 until Astronomy.daysInYear(year)).map { LocalDate.of(year, 1, 1).plusDays(it.toLong()) }
        put("Zodiac.signFor", (daysOf(2024) + daysOf(2025)).map {
            rec("date" to it.toString(), "output" to Zodiac.signFor(it).name)
        })

        val longitudes = (grid(-96, 144, 7.5) + listOf(
            29.999999999999996, 30.000000000000004, 359.99999999999994, 360.00000000000006, -1e-13,
            -1e-15, -0.0, 1e-300, 329.99999999999994, -29.999999999999996, -30.000000000000004, 15.5, 123.456,
            -123.456, 1e9,
        )).distinctBy { it.toRawBits() }
        put("Zodiac.signForLongitude", longitudes.map {
            rec("longitude" to it, "output" to Zodiac.signForLongitude(it).name)
        })
        put("Zodiac.seasonFor", daysOf(2025).flatMap { date ->
            listOf(true, false).map { north ->
                rec("date" to date.toString(), "northernHemisphere" to north, "output" to Zodiac.seasonFor(date, north).name)
            }
        })
        put("Zodiac.geocentricLongitude", listOf(Astronomy.Body.MERCURY, Astronomy.Body.VENUS, Astronomy.Body.MARS)
            .flatMap { body ->
                INSTANTS.map { rec("body" to body.name, "instant" to ms(it), "output" to Zodiac.geocentricLongitude(body, it)) }
            })
        put("Zodiac.sunLongitude", INSTANTS.map { rec("instant" to ms(it), "output" to Zodiac.sunLongitude(it)) })
        put("Zodiac.placements", listOf(
            "1970-01-01T00:00:00Z", "2000-01-01T12:00:00Z", "2024-02-29T06:00:00Z", "2024-12-21T09:20:00Z",
            "2025-03-20T09:01:00Z", "2026-09-26T12:00:00Z", "2100-12-31T23:59:59.999Z",
        ).map(::at).map { instant ->
            rec("instant" to ms(instant), "output" to Zodiac.placements(instant).map {
                rec("label" to it.label, "symbol" to it.symbol, "longitudeDegrees" to it.longitudeDegrees, "sign" to it.sign.name)
            })
        })
    }

    private fun dialGeometry() {
        put("DialGeometry.constants", listOf(
            constant("MERCURY_ORBIT", "Float", DialGeometry.MERCURY_ORBIT),
            constant("VENUS_ORBIT", "Float", DialGeometry.VENUS_ORBIT),
            constant("EARTH_ORBIT", "Float", DialGeometry.EARTH_ORBIT),
            constant("MARS_ORBIT", "Float", DialGeometry.MARS_ORBIT),
            constant("ZODIAC_OUTER", "Float", DialGeometry.ZODIAC_OUTER),
            constant("ZODIAC_INNER", "Float", DialGeometry.ZODIAC_INNER),
            constant("ZODIAC_HAND_END", "Float", DialGeometry.ZODIAC_HAND_END),
            constant("MOON_DIAL", "Float", DialGeometry.MOON_DIAL),
            // Private in Kotlin (MOON_DIAL / 67.5f); recomputed here.
            constant("EARTH_UNIT", "Float", DialGeometry.MOON_DIAL / 67.5f),
            constant("HOUR_DIAL", "Float", DialGeometry.HOUR_DIAL),
            constant("EARTH_RADIUS", "Float", DialGeometry.EARTH_RADIUS),
            constant("LOCAL_WHEEL", "Float", DialGeometry.LOCAL_WHEEL),
            constant("LOCAL_WHEEL_SMALL_TOOTH", "Float", DialGeometry.LOCAL_WHEEL_SMALL_TOOTH),
            constant("LOCAL_WHEEL_BIG_TOOTH", "Float", DialGeometry.LOCAL_WHEEL_BIG_TOOTH),
            constant("LOCAL_WHEEL_RED_TOOTH", "Float", DialGeometry.LOCAL_WHEEL_RED_TOOTH),
            constant("LOCAL_WHEEL_RED_HALF_BASE", "Float", DialGeometry.LOCAL_WHEEL_RED_HALF_BASE),
            constant("LOCAL_WHEEL_TOOTH_HALF_ANGLE", "Double", DialGeometry.LOCAL_WHEEL_TOOTH_HALF_ANGLE),
            constant("DATE_STRIP_OUTER", "Float", DialGeometry.DATE_STRIP_OUTER),
            constant("DATE_STRIP_INNER", "Float", DialGeometry.DATE_STRIP_INNER),
            constant("EARTH_CAMERA_ZOOM", "Float", DialGeometry.EARTH_CAMERA_ZOOM),
            constant("EARTH_SUBDIAL_SCALE", "Float", DialGeometry.EARTH_SUBDIAL_SCALE),
            constant("HELIOCENTRIC_EARTH_RADIUS", "Float", DialGeometry.HELIOCENTRIC_EARTH_RADIUS),
            constant("SUBDIAL_GEAR", "Float", DialGeometry.SUBDIAL_GEAR),
            constant("SUBDIAL_MOON_TRACK", "Float", DialGeometry.SUBDIAL_MOON_TRACK),
            constant("UNITY_YEAR_DAYS", "Double", DialGeometry.UNITY_YEAR_DAYS),
            constant("JANUARY_FIRST_ANGLE", "Double", DialGeometry.JANUARY_FIRST_ANGLE),
            constant("GEOCENTRIC_SUN_DISTANCE", "Float", DialGeometry.GEOCENTRIC_SUN_DISTANCE),
            constant("SKY_PARALLAX", "Float", DialGeometry.SKY_PARALLAX),
        ))
        val both = listOf(true, false)
        val jan = DialGeometry.JANUARY_FIRST_ANGLE
        val angles = (grid(-96, 96, 7.5) + listOf(
            jan, jan + 360.0, jan - 360.0, jan - 1e-9, jan + 1e-9, 180.0 - jan, 0.1, 33.3, -45.678, 1e-13, -1e-13,
        )).distinctBy { it.toRawBits() }

        put("DialGeometry.annualAngle", (grid(-16, 80, 1.0 / 64) + listOf(1e-9, 0.1, 0.3, 0.7, 0.9999999))
            .distinct().flatMap { f ->
                both.map { rec("fraction" to f, "north" to it, "output" to DialGeometry.annualAngle(f, it)) }
            })
        put("DialGeometry.yearFractionFromAngle", angles.flatMap { a ->
            both.map { rec("angle" to a, "north" to it, "output" to DialGeometry.yearFractionFromAngle(a, it)) }
        })
        put("DialGeometry.eclipticAngle", (grid(-24, 48, 15.0) + listOf(0.1, 123.456)).flatMap { l ->
            both.map { rec("longitude" to l, "north" to it, "output" to DialGeometry.eclipticAngle(l, it)) }
        })
        put("DialGeometry.hourAngle", (grid(-8, 104, 0.25) + listOf(1.0 / 60, 23.999999, 12.5001)).distinct().flatMap { h ->
            both.map { rec("hours" to h, "north" to it, "output" to DialGeometry.hourAngle(h, it)) }
        })
        put("DialGeometry.minuteFromHourAngle", angles.flatMap { a ->
            both.map { rec("angle" to a, "north" to it, "output" to DialGeometry.minuteFromHourAngle(a, it)) }
        })
        put("DialGeometry.moonAngle", (grid(-24, 48, 15.0) + listOf(0.5, 359.999, 1e-13)).flatMap { p ->
            both.map { rec("phase" to p, "north" to it, "output" to DialGeometry.moonAngle(p, it)) }
        })
        put("DialGeometry.phaseFromMoonAngle", angles.flatMap { a ->
            both.map { rec("angle" to a, "north" to it, "output" to DialGeometry.phaseFromMoonAngle(a, it)) }
        })

        val radii = listOf(0f, 1f, 100f, 250.5f, 480f, 512.75f, 1234.567f)
        val indices = listOf(-5, -1, 0, 1, 2, 3, 7, 12)
        val thicknesses = listOf(0f, 4f, 11.66f, 24.5f, 200f)
        fun bands(radiusName: String, band: (Float, Int, Float) -> DialGeometry.EventBand) =
            radii.flatMap { r ->
                indices.flatMap { i ->
                    thicknesses.map { t ->
                        val b = band(r, i, t)
                        rec(radiusName to r, "calendarIndex" to i, "minThickness" to t,
                            "output" to rec("centerRadius" to b.centerRadius, "thickness" to b.thickness))
                    }
                }
            }
        put("DialGeometry.yearEventBand", bands("annualRadius") { r, i, t -> DialGeometry.yearEventBand(r, i, minThickness = t) })
        put("DialGeometry.dayEventBand", bands("hourRadius") { r, i, t -> DialGeometry.dayEventBand(r, i, minThickness = t) })

        put("DialGeometry.earthFlightFrame", ((-10..110).map { it / 100f } + listOf(-1f, 2f, 0.5f, 1e-6f)).distinct().map { p ->
            val frame = DialGeometry.earthFlightFrame(p)
            rec("progress" to p, "output" to rec(
                "cameraScale" to frame.cameraScale, "earthSystemScale" to frame.earthSystemScale, "skyScale" to frame.skyScale,
            ))
        })
    }

    private fun seasonBands() {
        val bands = Internal("SeasonBands")
        put("SeasonBands.constants", listOf(constant("BLEND_DAYS", "Double", bands.field("BLEND_DAYS"))))

        fun starts(year: Int): List<*> = bands.call("starts", year) as List<*>
        fun startsOut(list: List<*>) = list.map {
            val pair = it as Pair<*, *>
            rec("fraction" to pair.first, "season" to (pair.second as Zodiac.Season).name)
        }
        put("SeasonBands.starts", (2023..2030).map { year -> rec("year" to year, "output" to startsOut(starts(year))) })

        put("SeasonBands.mixAt", listOf(2024, 2025).flatMap { year ->
            val list = starts(year)
            val days = Astronomy.daysInYear(year)
            val half = 12.0 / days
            val edges = list.flatMap {
                val start = (it as Pair<*, *>).first as Double
                listOf(start - half, start - half + 1e-12, start, start + half - 1e-12, start + half)
            }
            val fractions = ((0 until days * 6).map { it / (days * 6.0) } + edges +
                listOf(-0.02, -1e-9, 1.0, 1.0 + 1e-9, 1.02)).distinctBy { it.toRawBits() }
            fractions.map { fraction ->
                val mix = bands.call("mixAt", fraction, list, days)!!
                rec("year" to year, "fraction" to fraction, "daysInYear" to days, "output" to rec(
                    "from" to (Internal.property(mix, "from") as Zodiac.Season).name,
                    "to" to (Internal.property(mix, "to") as Zodiac.Season).name,
                    "amount" to Internal.property(mix, "amount"),
                ))
            }
        })
    }

    private fun timeZoneDial() {
        val both = listOf(true, false)
        fun spokeOut(spoke: TimeZoneDial.Spoke) = rec(
            "offsetHours" to spoke.offsetHours, "angleDegrees" to spoke.angleDegrees,
            "localDate" to spoke.localDate.toString(), "label" to spoke.label,
        )
        put("TimeZoneDial.spokes", SPOKE_INSTANTS.flatMap { instant ->
            both.map { rec("instant" to ms(instant), "north" to it, "output" to TimeZoneDial.spokes(instant, it).map(::spokeOut)) }
        })
        put("TimeZoneDial.datelineHours", (INSTANTS + SPOKE_INSTANTS).distinct().sorted().map {
            rec("instant" to ms(it), "output" to TimeZoneDial.datelineHours(it))
        })
        put("TimeZoneDial.localOffsetMinutes", ZONE_CASES.map { (zone, instant) ->
            rec("instant" to ms(instant), "zone" to zone.id, "output" to TimeZoneDial.localOffsetMinutes(instant, zone))
        })
        val offsets = listOf(-1080, -720, -660, -570, -480, -210, -150, 0, 330, 345, 525, 630, 765, 825, 840, 1080)
        put("TimeZoneDial.angleForOffsetMinutes", SPOKE_INSTANTS.flatMap { instant ->
            offsets.flatMap { offset ->
                both.map {
                    rec("instant" to ms(instant), "offsetMinutes" to offset, "north" to it,
                        "output" to TimeZoneDial.angleForOffsetMinutes(instant, offset, it))
                }
            }
        })
        val nameZones = (ZONES.map { it.id } + listOf(
            "America/Vancouver", "America/Denver", "America/Phoenix", "America/Edmonton", "America/Chicago",
            "America/Winnipeg", "America/New_York", "America/Toronto", "America/Anchorage", "Pacific/Honolulu",
            "Europe/Paris", "Europe/Berlin", "Europe/Rome", "Asia/Tokyo", "Australia/Sydney", "Australia/Melbourne",
            "Pacific/Kiritimati", "Pacific/Pago_Pago", "Etc/GMT+12", "Pacific/Marquesas", "Asia/Kathmandu",
            "Asia/Tehran", "America/Argentina/Buenos_Aires", "Atlantic/Azores", "Atlantic/South_Georgia",
            "Europe/Moscow", "Asia/Dubai", "Asia/Karachi", "Asia/Dhaka", "Asia/Bangkok", "Asia/Singapore",
            "Asia/Seoul", "Pacific/Guadalcanal", "Pacific/Auckland", "Pacific/Tongatapu", "Etc/GMT-14",
            "America/Indiana/Indianapolis", "Europe/Dublin", "America/Caracas", "Asia/Yangon", "Australia/Adelaide",
            "Australia/Eucla", "Etc/UTC", "Europe/Lisbon",
        )).distinct()
        put("TimeZoneDial.localName", nameZones.flatMap { id ->
            listOf(at("2024-01-15T12:00:00Z"), at("2024-07-15T12:00:00Z")).map { instant ->
                val zone = ZoneId.of(id)
                rec("zone" to id, "instant" to ms(instant),
                    "offsetMinutes" to TimeZoneDial.localOffsetMinutes(instant, zone),
                    "output" to TimeZoneDial.localName(zone, instant))
            }
        })
        put("TimeZoneDial.commonName", (-14..16).map { rec("offsetHours" to it, "output" to TimeZoneDial.commonName(it)) })

        val nearestInstants = listOf(
            "2024-06-01T00:00:00Z", "2024-06-01T00:07:30Z", "2024-06-01T12:34:56.789Z",
            "2025-03-09T17:00:00Z", "2024-12-31T23:59:59.999Z", "2026-09-26T12:00:00Z",
        ).map(::at)
        put("TimeZoneDial.nearestSpoke", nearestInstants.flatMap { instant ->
            both.flatMap { north ->
                val spokes = TimeZoneDial.spokes(instant, north)
                grid(-96, 192, 3.75).map { angle ->
                    rec("instant" to ms(instant), "north" to north, "angle" to angle,
                        "output" to TimeZoneDial.nearestSpoke(spokes, angle).offsetHours)
                }
            }
        })
    }

    private fun galacticGeometry() {
        val galactic = Internal("GalacticGeometry")
        put("GalacticGeometry.constants", listOf(
            constant("travelX", "Float", galactic.call("getTravelX")!!),
            constant("travelY", "Float", galactic.call("getTravelY")!!),
            constant("sideX", "Float", galactic.call("getSideX")!!),
            constant("sideY", "Float", galactic.call("getSideY")!!),
            constant("YEAR_PITCH", "Float", galactic.field("YEAR_PITCH")),
            constant("ORBIT_DEPTH", "Float", galactic.field("ORBIT_DEPTH")),
            constant("MIN_YEAR", "Int", galactic.field("MIN_YEAR")),
            constant("MAX_YEAR", "Int", galactic.field("MAX_YEAR")),
        ))
        fun continuousYear(instant: Instant, zone: ZoneId) = galactic.call("continuousYear", instant, zone) as Double
        put("GalacticGeometry.continuousYear", ZONE_CASES.map { (zone, instant) ->
            rec("instant" to ms(instant), "zone" to zone.id, "output" to continuousYear(instant, zone))
        })
        val years = listOf(
            -5.0, 0.0, 0.5, 1.0, 1.25, 1000.5, 1582.8, 1950.0, 1969.999, 1970.0, 2000.0, 2000.0833, 2024.0,
            2024.16, 2024.5, 2024.999999, 2024.9999999999, 2025.0, 2026.73, 2100.99, 9999.0, 9999.5,
            9999.999999, 10000.0, 12000.0,
        )
        put("GalacticGeometry.instantAt", ZONES.flatMap { zone ->
            years.map { year ->
                val instant = galactic.call("instantAt", year, zone) as Instant
                rec("continuousYear" to year, "zone" to zone.id, "output" to instantOut(instant),
                    "roundTrip" to continuousYear(instant, zone))
            }
        })
        put("GalacticGeometry.monthStart", listOf(1, 1900, 1999, 2000, 2023, 2024, 2025, 2100, 9999).flatMap { year ->
            (1..12).map { month -> rec("year" to year, "month" to month, "output" to galactic.call("monthStart", year, month)) }
        })
        put("GalacticGeometry.orbitOffset", (grid(-24, 48, 15.0) + listOf(0.5, 89.999, 123.456)).flatMap { longitude ->
            listOf(0f, 0.1935f, 0.5f, 0.7615f, 1f, 123.25f).map { radius ->
                val pair = galactic.call("orbitOffset", longitude, radius) as Pair<*, *>
                rec("longitude" to longitude, "orbitRadius" to radius,
                    "output" to rec("along" to pair.first, "side" to pair.second))
            }
        })
    }

    private fun earthOrientation() {
        val orientation = Internal("EarthOrientation")
        put("EarthOrientation.constants", listOf(
            constant("OBLIQUITY_DEGREES", "Double", orientation.field("OBLIQUITY_DEGREES")),
        ))
        put("EarthOrientation.projectedGeographicPole", listOf(true, false).flatMap { north ->
            (grid(-72, 144, 5.0) + listOf(0.5, 123.456)).map { longitude ->
                val pair = orientation.call("projectedGeographicPole", north, longitude) as Pair<*, *>
                rec("north" to north, "sunLongitude" to longitude, "output" to rec("x" to pair.first, "y" to pair.second))
            }
        })
        val third = 1.0 / sqrt(3.0)
        val directions = listOf(
            Triple(1.0, 0.0, 0.0), Triple(-1.0, 0.0, 0.0), Triple(0.0, 1.0, 0.0), Triple(0.0, -1.0, 0.0),
            Triple(0.0, 0.0, 1.0), Triple(0.0, 0.0, -1.0), Triple(0.6, 0.8, 0.0), Triple(0.0, 0.6, 0.8),
            Triple(0.48, 0.6, 0.64), Triple(-0.36, 0.48, 0.8), Triple(third, -third, third),
        )
        put("EarthOrientation.screenToEquatorial", directions.flatMap { (sx, sy, sz) ->
            (grid(-3, 15, 30.0) + listOf(0.5, 123.456)).flatMap { longitude ->
                listOf(true, false).map { north ->
                    val v = orientation.call("screenToEquatorial", sx, sy, sz, longitude, north) as Triple<*, *, *>
                    rec("sx" to sx, "sy" to sy, "sz" to sz, "sunLongitude" to longitude, "north" to north,
                        "output" to rec("x" to v.first, "y" to v.second, "z" to v.third))
                }
            }
        })
    }

    private fun calendarHitTesting() {
        val touches = (-8..24).map { it / 16.0 } + listOf(0.3, 0.97, 1e-12, -1e-12)
        val starts = listOf(-0.125, 0.0, 0.25, 0.875, 0.96875, 1.25)
        val sweeps = listOf(0.0, 0.03125, 0.125, 0.5, 1.0, 1.5)
        val paddings = listOf(-0.015625, 0.0, 0.0078125, 0.03125, 0.625)
        put("CalendarHitTesting.containsYearFraction", touches.flatMap { touch ->
            starts.flatMap { start ->
                sweeps.flatMap { sweep ->
                    paddings.map { padding ->
                        rec("touch" to touch, "start" to start, "sweep" to sweep, "padding" to padding,
                            "output" to CalendarHitTesting.containsYearFraction(touch, start, sweep, padding))
                    }
                }
            }
        })
        val minutes = (-2..26).map { it * 60.0 } + listOf(1439.999, 0.001, -0.001, 719.5)
        val startMinutes = listOf(-30.0, 0.0, 600.0, 1380.0, 1439.5, 1500.0)
        val durations = listOf(-10.0, 0.0, 30.0, 720.0, 1440.0, 2000.0)
        val paddingMinutes = listOf(-5.0, 0.0, 7.5, 15.0)
        put("CalendarHitTesting.containsMinute", minutes.flatMap { touch ->
            startMinutes.flatMap { start ->
                durations.flatMap { duration ->
                    paddingMinutes.map { padding ->
                        val end = start + duration
                        rec("touch" to touch, "start" to start, "endExclusive" to end, "padding" to padding,
                            "output" to CalendarHitTesting.containsMinute(touch, start, end, padding))
                    }
                }
            }
        })
    }

    private fun calendar() {
        put("DeviceCalendar.isGoogle", listOf("com.google", "COM.GOOGLE", "Com.Google", "com.google.work", "LOCAL", "", " com.google")
            .map { type ->
                rec("accountType" to type, "output" to DeviceCalendar(1, "Name", "someone@example.com", type, 0).isGoogle)
            })

        val blue = 0xFF3366CC.toInt()
        val red = 0xFFE67C73.toInt()
        fun timed(id: Long, title: String, begin: String, end: String, zone: String?, color: Int = blue) =
            RawCalendarInstance(id, 100 + id % 3, title, ms(at(begin)), ms(at(end)), false, zone, color)
        fun utcMidnight(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        fun allDay(id: Long, title: String, begin: String, end: String, color: Int = red) =
            RawCalendarInstance(id, 200, title, utcMidnight(begin), utcMidnight(end), true, "UTC", color)

        val raws = listOf(
            timed(1, "Standup", "2024-03-12T09:00-07:00", "2024-03-12T09:30-07:00", "America/Los_Angeles"),
            timed(2, "Late show", "2024-06-14T23:00-07:00", "2024-06-15T01:30-07:00", "America/Los_Angeles"),
            timed(3, "Spring forward", "2024-03-10T01:30-08:00", "2024-03-10T03:30-07:00", "America/Los_Angeles"),
            timed(4, "Fall back", "2024-11-03T01:15-07:00", "2024-11-03T01:45-08:00", "America/Los_Angeles"),
            timed(5, "Exactly a day", "2024-05-01T12:00Z", "2024-05-02T12:00Z", null),
            timed(6, "Just under a day", "2024-05-01T12:00Z", "2024-05-02T11:59:59.999Z", null),
            timed(7, "Conference", "2024-09-16T08:00+05:30", "2024-09-19T17:00+05:30", "Asia/Kolkata"),
            timed(8, "Zero length", "2024-07-04T16:00Z", "2024-07-04T16:00Z", null),
            timed(9, "Reversed", "2024-07-04T16:00Z", "2024-07-04T15:00Z", null),
            timed(10, "New Year party", "2024-12-31T22:00Z", "2025-01-01T02:00Z", "Europe/London"),
            timed(11, "The whole of 2025", "2025-01-01T00:00Z", "2026-01-01T00:00Z", "UTC"),
            timed(12, "Clocks go forward", "2024-03-31T00:30Z", "2024-03-31T02:30Z", "Europe/London"),
            timed(13, "Clocks go back", "2024-10-27T00:30Z", "2024-10-27T02:30Z", "Europe/London"),
            timed(14, "Lord Howe half hour", "2024-10-05T15:00Z", "2024-10-05T16:30Z", "Australia/Lord_Howe"),
            timed(15, "Chatham spring", "2024-09-28T13:30Z", "2024-09-28T15:30Z", "Pacific/Chatham"),
            timed(16, "St John's spring", "2024-03-10T04:00Z", "2024-03-10T07:00Z", "America/St_Johns"),
            timed(17, "Santiago midnight", "2024-09-08T03:30Z", "2024-09-08T05:30Z", "America/Santiago"),
            timed(18, "Before the epoch", "1969-12-31T23:00Z", "1970-01-01T01:00Z", null),
            timed(19, "Leap night", "2024-02-28T20:00-08:00", "2024-03-01T04:00-08:00", "America/Los_Angeles"),
            timed(20, "Kolkata new year", "2024-12-31T18:00Z", "2024-12-31T19:00Z", "Asia/Kolkata"),
            timed(21, "Title \"quoted\" ☉ é\ttab", "2025-08-01T10:00Z", "2025-08-01T10:45Z", "Europe/London", 0),
            allDay(31, "LA spring forward day", "2024-03-10", "2024-03-11"),
            allDay(32, "LA fall back day", "2024-11-03", "2024-11-04"),
            allDay(33, "Leap day", "2024-02-29", "2024-03-01"),
            allDay(34, "Across new year", "2024-12-30", "2025-01-02"),
            allDay(35, "All of 2024", "2024-01-01", "2025-01-01"),
            allDay(36, "Zero length all-day", "2024-06-01", "2024-06-01"),
            RawCalendarInstance(37, 200, "Off-midnight all-day", ms(at("2024-05-05T07:00Z")), ms(at("2024-05-06T07:00Z")),
                true, null, 0x7F00FF00),
            allDay(38, "Before the epoch all-day", "1969-12-31", "1970-01-01"),
            allDay(39, "Santiago gap day", "2024-09-08", "2024-09-09"),
            allDay(40, "Santiago long day", "2024-04-06", "2024-04-08"),
            allDay(41, "London short day", "2024-03-31", "2024-04-01"),
            allDay(42, "Lord Howe spring day", "2024-10-06", "2024-10-07"),
            allDay(43, "Three weeks", "2025-06-01", "2025-06-22"),
            allDay(44, "New Year's Day 2100", "2100-01-01", "2100-01-02"),
            RawCalendarInstance(45, 200, "Late UTC evening all-day", ms(at("2024-08-15T23:30Z")), ms(at("2024-08-16T00:30Z")),
                true, "UTC", red),
        )

        val occurrences = mutableListOf<CalendarOccurrence>()
        val normalized = raws.flatMap { raw ->
            ZONES.map { zone ->
                val event = CalendarNormalizer.normalize(raw, zone)
                occurrences += event
                rec(
                    "index" to occurrences.size - 1,
                    "raw" to rec(
                        "eventId" to raw.eventId, "calendarId" to raw.calendarId, "title" to raw.title,
                        "beginMillis" to raw.beginMillis, "endMillis" to raw.endMillis, "allDay" to raw.allDay,
                        "eventTimeZone" to raw.eventTimeZone, "color" to raw.color,
                    ),
                    "displayZone" to zone.id,
                    "output" to rec(
                        "eventId" to event.eventId, "calendarId" to event.calendarId, "title" to event.title,
                        "start" to zonedOut(event.start), "endExclusive" to zonedOut(event.endExclusive),
                        "allDayStart" to event.allDayStart?.toString(),
                        "allDayEndExclusive" to event.allDayEndExclusive?.toString(),
                        "color" to event.color, "isAllDay" to event.isAllDay, "isYearRingEvent" to event.isYearRingEvent,
                    ),
                )
            }
        }
        put("CalendarNormalizer.normalize", normalized)

        val dayRecords = mutableListOf<Map<String, Any?>>()
        val yearRecords = mutableListOf<Map<String, Any?>>()
        occurrences.forEachIndexed { index, event ->
            for (zone in listOf(event.start.zone, ZoneId.of("UTC")).distinct()) {
                val first = event.start.withZoneSameInstant(zone).toLocalDate().minusDays(1)
                val last = event.endExclusive.withZoneSameInstant(zone).toLocalDate().plusDays(1)
                val all = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
                val days = if (all.size <= 8) all else all.take(4) + all.takeLast(4)
                for (day in days) {
                    val segment = CalendarIntervals.inDay(event, day, zone)
                    dayRecords += rec("eventIndex" to index, "day" to day.toString(), "zone" to zone.id,
                        "output" to segment?.let { rec("startMinute" to it.startMinute, "endMinuteExclusive" to it.endMinuteExclusive) })
                }
                val firstYear = event.start.withZoneSameInstant(zone).year - 1
                val lastYear = event.endExclusive.withZoneSameInstant(zone).year + 1
                for (year in firstYear..lastYear) {
                    val segment = CalendarIntervals.inYear(event, year, zone)
                    yearRecords += rec("eventIndex" to index, "year" to year, "zone" to zone.id,
                        "output" to segment?.let { rec("startFraction" to it.startFraction, "sweepFraction" to it.sweepFraction) })
                }
            }
        }
        put("CalendarIntervals.inDay", dayRecords)
        put("CalendarIntervals.inYear", yearRecords)
    }

    private fun zodiacProfile() {
        val profiles = listOf(
            ZodiacProfile(),
            ZodiacProfile(enabled = true),
            ZodiacProfile(true, LocalDate.of(1976, 7, 4), LocalTime.of(7, 5), null),
            ZodiacProfile(true, LocalDate.of(1976, 7, 4), LocalTime.of(7, 5), Zodiac.Sign.PISCES),
            ZodiacProfile(false, LocalDate.of(2000, 2, 29), null, null),
            ZodiacProfile(true, null, LocalTime.of(23, 59, 30), null),
            ZodiacProfile(true, null, null, Zodiac.Sign.SAGITTARIUS),
            ZodiacProfile(true, LocalDate.of(1990, 3, 21), LocalTime.of(0, 0), null),
            ZodiacProfile(true, LocalDate.of(1990, 3, 20), LocalTime.of(12, 0), null),
            ZodiacProfile(false, LocalDate.of(1999, 12, 31), LocalTime.of(12, 0, 1), null),
            ZodiacProfile(true, LocalDate.of(1985, 1, 19), LocalTime.of(18, 45), Zodiac.Sign.AQUARIUS),
            ZodiacProfile(true, LocalDate.of(1990, 10, 23), LocalTime.of(8, 45), null, "America/Los_Angeles"),
        )
        val todays = listOf("2026-09-26", "2024-12-22", "2025-01-19", "2025-01-20", "2024-02-29").map(LocalDate::parse)
        put("ZodiacProfile", profiles.flatMap { profile ->
            todays.map { today ->
                rec(
                    "enabled" to profile.enabled, "birthDate" to profile.birthDate?.toString(),
                    "birthTime" to timeOut(profile.birthTime), "selectedSign" to profile.selectedSign?.name,
                    "birthZoneId" to profile.birthZoneId,
                    "today" to today.toString(),
                    "output" to rec(
                        "resolvedSign" to profile.resolvedSign(today).name,
                        "isComplete" to profile.isComplete,
                        "signature" to profile.signature,
                    ),
                )
            }
        })

        // Internal to :core, so its JVM name is mangled (preserveNatalData$core).
        val preserve = ZodiacPreferences::class.java.methods.single { it.name.startsWith("preserveNatalData") }
        fun profileOut(p: ZodiacProfile) = rec(
            "enabled" to p.enabled, "birthDate" to p.birthDate?.toString(),
            "birthTime" to timeOut(p.birthTime), "selectedSign" to p.selectedSign?.name,
            "birthZoneId" to p.birthZoneId,
        )
        val samples = profiles.take(7)
        put("ZodiacPreferences.preserveNatalData", samples.flatMap { requested ->
            samples.map { stored ->
                rec("requested" to profileOut(requested), "stored" to profileOut(stored),
                    "output" to profileOut(preserve.invoke(ZodiacPreferences, requested, stored) as ZodiacProfile))
            }
        })
    }

    private fun birthInput() {
        val today = "2026-09-26"
        val dates = listOf(
            listOf("2", "29", "2024", today), listOf("2", "29", "2023", today), listOf("2", "29", "1900", today),
            listOf("2", "29", "2000", today), listOf("2", "30", "2024", today), listOf("13", "1", "2000", today),
            listOf("0", "1", "2000", today), listOf("12", "31", "1999", today), listOf("1", "32", "2000", today),
            listOf("4", "31", "2000", today), listOf("6", "30", "2000", today), listOf("02", "09", "1985", today),
            listOf(" 7 ", "  4", "1976 ", today), listOf("\t7\n", "4 ", " 1976", today),
            listOf("+7", "+4", "+976", today), listOf("-1", "5", "2000", today), listOf("7", "4", "-001", today),
            listOf("7", "4", "0000", today), listOf("7", "4", "76", today), listOf("7", "4", "19760", today),
            listOf("7", "4", "", today), listOf("", "4", "1976", today), listOf("7", "", "1976", today),
            listOf("7", "4", "19a6", today), listOf("Jul", "4", "1976", today), listOf("7.0", "4", "1976", today),
            listOf("7", "4th", "1976", today), listOf("7/4", "", "1976", today), listOf("7", "4", "1976", "1976-07-04"),
            listOf("7", "4", "1976", "1976-07-03"), listOf("9", "27", "2026", today), listOf("9", "26", "2026", today),
            listOf("1", "1", "9999", today), listOf("１２", "３", "２０００", today),
            listOf("٣", "١", "١٩٩٠", today), listOf("99999999999", "1", "2000", today),
            listOf("  ", "1", "2000", today), listOf("0x7", "1", "2000", today), listOf("1", "1", " 1976 ", today),
            listOf("1", "1", "1 976", today), listOf("1", "1", "1e3", today), listOf("2", "29", "2024", "2024-02-28"),
            listOf("2", "29", "2024", "2024-02-29"), listOf("-0", "1", "2000", today), listOf("12", "-0", "2000", today),
            listOf("1", "1", "\u001F1976", today),
        )
        put("BirthDateInput.parse", dates.map { (month, day, year, todayText) ->
            val result = runCatching { BirthDateInput.parse(month, day, year, LocalDate.parse(todayText)) }
            rec(
                "month" to month, "day" to day, "year" to year, "today" to todayText,
                "output" to result.getOrNull()?.let { rec("year" to it.year, "month" to it.monthValue, "day" to it.dayOfMonth) },
                "error" to result.exceptionOrNull()?.also { check(it is IllegalArgumentException) }?.message,
            )
        })

        val times = listOf(
            Triple("12", "00", false), Triple("12", "00", true), Triple("1", "05", false), Triple("1", "05", true),
            Triple("11", "59", true), Triple("11", "59", false), Triple("0", "30", false), Triple("13", "00", true),
            Triple("12", "60", false), Triple("12", "-1", false), Triple("", "00", false), Triple("7", "", true),
            Triple(" 7 ", "\t5\n", true), Triple("07", "5", false), Triple("+7", "+05", true), Triple("7:30", "", false),
            Triple("7", "30pm", true), Triple("١٢", "٠٠", true), Triple("99999999999", "1", false),
            Triple("-0", "0", false), Triple("0", "x", false), Triple("13", "61", false), Triple(" 9 ", "15", true),
            Triple("6", "0", true), Triple("12", "59", true), Triple("-12", "0", false),
        )
        put("BirthTimeInput.parse", times.map { (hour, minute, pm) ->
            val result = runCatching { BirthTimeInput.parse(hour, minute, pm) }
            rec(
                "hour" to hour, "minute" to minute, "pm" to pm,
                "output" to result.getOrNull()?.let { rec("hour" to it.hour, "minute" to it.minute) },
                "error" to result.exceptionOrNull()?.also { check(it is IllegalArgumentException) }?.message,
            )
        })
    }

    // Copied from HoroscopeGenerator.generate (which needs the on-device model); checked against it below.
    private fun horoscopePrompt(profile: ZodiacProfile, instant: Instant, zone: ZoneId): String {
        val date = instant.atZone(zone).toLocalDate()
        val sign = profile.resolvedSign(date)
        val sky = Zodiac.placements(instant).joinToString(", ") {
            "${it.label.lowercase().replaceFirstChar(Char::uppercase)} in ${it.sign.displayName}"
        }
        val prompt = """
            Write a vivid daily horoscope as a single paragraph of 55 to 85 words.
            Reader: ${sign.displayName} sun sign, born ${profile.birthDate} at ${profile.birthTime} local time (${profile.birthZone.id}).
            Date: $date. Current tropical placements: $sky.
            Style: poetic brass-orrery imagery, warm, specific, reflective, second person.
            Treat astrology as creative entertainment. Do not claim certainty, diagnose health,
            predict danger, or give financial, medical, or legal advice. Do not mention these instructions.
        """.trimIndent()
        return prompt
    }

    // Copied from HoroscopeGenerator.generate: the clean-up of the model's first candidate.
    private fun cleanResponse(text: String?): String? =
        text?.trim()
            ?.removePrefix("Horoscope:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun horoscope() {
        val source = File("src/main/java/com/metavirtuoso/sundial/horoscope/HoroscopeGenerator.kt").readText()
        listOf(
            "Write a vivid daily horoscope as a single paragraph of 55 to 85 words.",
            "Reader: \${sign.displayName} sun sign, born \${profile.birthDate} at \${profile.birthTime} local time (\${profile.birthZone.id}).",
            "Date: \$date. Current tropical placements: \$sky.",
            "Style: poetic brass-orrery imagery, warm, specific, reflective, second person.",
            "Treat astrology as creative entertainment. Do not claim certainty, diagnose health,",
            "predict danger, or give financial, medical, or legal advice. Do not mention these instructions.",
            "\"\${it.label.lowercase().replaceFirstChar(Char::uppercase)} in \${it.sign.displayName}\"",
            "val sign = profile.resolvedSign(date)",
            "val date = instant.atZone(zone).toLocalDate()",
            "?.trim()\n            ?.removePrefix(\"Horoscope:\")\n            ?.trim()\n            ?.takeIf { it.isNotBlank() }",
        ).forEach { check(it in source) { "HoroscopeGenerator changed; update the copy in SwiftReferenceTest: $it" } }

        val profiles = listOf(
            ZodiacProfile(true, LocalDate.of(1976, 7, 4), LocalTime.of(7, 5), null),
            ZodiacProfile(true, LocalDate.of(1990, 3, 20), LocalTime.of(23, 59, 30), Zodiac.Sign.LEO),
            ZodiacProfile(true, LocalDate.of(2000, 2, 29), LocalTime.of(0, 0), null),
        )
        val moments = listOf(
            at("2026-09-26T12:00:00Z") to "America/Los_Angeles",
            at("2024-12-31T23:30:00Z") to "Asia/Kolkata",
            at("2025-03-20T09:01:00Z") to "Europe/London",
            at("2024-02-29T06:00:00Z") to "Pacific/Chatham",
        )
        put("HoroscopeGenerator.prompt", profiles.flatMap { profile ->
            moments.map { (instant, zoneId) ->
                val zone = ZoneId.of(zoneId)
                val date = instant.atZone(zone).toLocalDate()
                rec(
                    "enabled" to profile.enabled, "birthDate" to profile.birthDate?.toString(),
                    "birthTime" to timeOut(profile.birthTime), "selectedSign" to profile.selectedSign?.name,
                    // Pin the generator machine's fallback so the Swift reference is independent
                    // of the CI runner's own time zone.
                    "birthZoneId" to profile.birthZone.id,
                    "instant" to ms(instant), "zone" to zoneId, "date" to date.toString(),
                    "sign" to profile.resolvedSign(date).name, "output" to horoscopePrompt(profile, instant, zone),
                )
            }
        })
        put("HoroscopeGenerator.cleanResponse", listOf(
            "The brass gears turn toward you today.",
            "  Horoscope: The brass gears turn toward you today.  ",
            "Horoscope:The gears.",
            "horoscope: lower case is kept",
            "Horoscope:",
            "Horoscope:   \n  ",
            "",
            "   ",
            "\n\tHoroscope: Non-breaking spaces ",
            "Horoscope: Horoscope: twice",
            "A line with Horoscope: in the middle",
            " Horoscope: em space ",
            "\u001FHoroscope: unit separator\u001F",
            "​Horoscope: zero-width space",
            "Multi\nline\r\nreading\n",
            null,
        ).map { rec("input" to it, "output" to cleanResponse(it)) })
    }

    // Copied from ReadingReporter.send (which posts it); checked against it below.
    private fun formBody(report: ReadingReporter.Report, version: String, fields: Map<String, String>): String =
        mapOf(
            "reason" to report.reason.label,
            "reading" to report.reading,
            "date" to report.date.toString(),
            "version" to version,
        ).entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(fields[key] ?: key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private fun readingReporter() {
        val source = File("src/main/java/com/metavirtuoso/sundial/horoscope/ReadingReporter.kt").readText()
        listOf(
            "\"reason\" to report.reason.label,",
            "\"reading\" to report.reading,",
            "\"date\" to report.date.toString(),",
            "\"version\" to \"\${BuildConfig.VERSION_NAME} (\${BuildConfig.VERSION_CODE})\",",
            ").entries.joinToString(\"&\") { (key, value) ->",
            "\"\${URLEncoder.encode(fields[key] ?: key, \"UTF-8\")}=\${URLEncoder.encode(value, \"UTF-8\")}\"",
        ).forEach { check(it in source) { "ReadingReporter changed; update the copy in SwiftReferenceTest: $it" } }

        put("ReadingReporter.Reason", ReadingReporter.Reason.entries.map {
            rec("name" to it.name, "ordinal" to it.ordinal, "label" to it.label)
        })
        val specs = listOf(
            "",
            " reason=entry.11, reading = entry.22 ,date=,version=entry.44,junk",
            "reason=entry.1,reason=entry.2",
            "a=b=c",
            "=x,x=,,,",
            "reason = entry.1 , date = entry.3 ",
            "reading=entry.9%20x",
            " =entry.5",
            "\treason\t=\tentry.1\n",
            "reason=entry.11,reading=entry.22,date=entry.33,version=entry.44",
            "reading=entry 9&x,date=d=e",
        )
        put("ReadingReporter.fieldNames", specs.map { rec("spec" to it, "output" to ReadingReporter.fieldNames(it)) })

        val readings = listOf(
            "Plain reading.",
            "Stars & stripes = 100% + more",
            "Line one\nLine two\r\nLine three",
            "Émile's café — naïve ☉ ♈︎ 🌙",
            "a~b*c-d_e.f g",
            "",
            "!'()[]{}<>#?/:;@\\|^`\"",
            "中文",
        )
        val versions = listOf("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "1.0 (1)", "")
        val dates = listOf("2026-09-26", "2024-02-29", "1999-12-31").map(LocalDate::parse)
        val bodySpecs = listOf(specs[0], specs[9], specs[1], specs[10])
        var n = 0
        put("ReadingReporter.formBody", ReadingReporter.Reason.entries.flatMap { reason ->
            readings.map { reading ->
                val version = versions[n % versions.size]
                val date = dates[n % dates.size]
                val spec = bodySpecs[n % bodySpecs.size]
                n++
                val fields = ReadingReporter.fieldNames(spec)
                rec(
                    "reason" to reason.name, "reading" to reading, "date" to date.toString(), "version" to version,
                    "spec" to spec, "output" to formBody(ReadingReporter.Report(reason, reading, date), version, fields),
                )
            }
        })
    }

    private fun random() {
        val seeds = listOf(0x51A7D1A1, 0x0B17A5E, 0, 1, -1, 42, Int.MIN_VALUE, Int.MAX_VALUE)
        put("Random.nextFloat", seeds.map { seed ->
            val random = Random(seed)
            rec("seed" to seed, "output" to List(300) { random.nextFloat() })
        })
        put("Random.nextInt", seeds.map { seed ->
            val random = Random(seed)
            rec("seed" to seed, "output" to List(300) { random.nextInt() })
        })
        put("Random.nextIntUntil", seeds.flatMap { seed ->
            listOf(1, 2, 34, 64, 100, 1000, Int.MAX_VALUE).map { until ->
                val random = Random(seed)
                rec("seed" to seed, "until" to until, "output" to List(300) { random.nextInt(until) })
            }
        })
    }

    private data class Star(
        val index: Int, val radius: Float?, val angle: Float?,
        val xFraction: Float, val yFraction: Float, val radiusDp: Float, val alpha: Int, val flare: Boolean = false,
    )

    private fun stars() {
        val source = File("../core/src/main/java/com/metavirtuoso/sundial/ui/SundialView.kt").readText()
        listOf(
            "private val ambientStars = Random(0x51A7D1A1).run {",
            "List(170) { index ->",
            "val radius = sqrt(nextFloat())",
            "val angle = nextFloat() * 2f * PI.toFloat()",
            "xFraction = radius * cos(angle),",
            "yFraction = radius * sin(angle),",
            "radiusDp = .28f + nextFloat() * if (index % 11 == 0) 1.12f else .63f,",
            "alpha = 30 + nextInt(if (index % 11 == 0) 100 else 64),",
            "flare = index % 17 == 0,",
            "private val dustLaneStars = Random(0x0B17A5E).run {",
            "List(64) {",
            "val x = nextFloat()",
            "xFraction = x,",
            "yFraction = (.10f + x * .78f + (nextFloat() - .5f) * .13f).coerceIn(.02f, .98f),",
            "radiusDp = .20f + nextFloat() * .42f,",
            "alpha = 12 + nextInt(34),",
        ).forEach { check(it in source) { "SundialView's stars changed; update the copy in SwiftReferenceTest: $it" } }

        // Copied from SundialView.ambientStars and dustLaneStars.
        val ambientStars = Random(0x51A7D1A1).run {
            List(170) { index ->
                val radius = sqrt(nextFloat())
                val angle = nextFloat() * 2f * PI.toFloat()
                Star(
                    index = index,
                    radius = radius,
                    angle = angle,
                    xFraction = radius * cos(angle),
                    yFraction = radius * sin(angle),
                    radiusDp = .28f + nextFloat() * if (index % 11 == 0) 1.12f else .63f,
                    alpha = 30 + nextInt(if (index % 11 == 0) 100 else 64),
                    flare = index % 17 == 0,
                )
            }
        }
        val dustLaneStars = Random(0x0B17A5E).run {
            List(64) { index ->
                val x = nextFloat()
                Star(
                    index = index,
                    radius = null,
                    angle = null,
                    xFraction = x,
                    yFraction = (.10f + x * .78f + (nextFloat() - .5f) * .13f).coerceIn(.02f, .98f),
                    radiusDp = .20f + nextFloat() * .42f,
                    alpha = 12 + nextInt(34),
                )
            }
        }
        fun starOut(star: Star) = rec(
            "index" to star.index, "radius" to star.radius, "angle" to star.angle,
            "xFraction" to star.xFraction, "yFraction" to star.yFraction, "radiusDp" to star.radiusDp,
            "alpha" to star.alpha, "flare" to star.flare,
        )
        put("SundialView.ambientStars", ambientStars.map(::starOut))
        put("SundialView.dustLaneStars", dustLaneStars.map(::starOut))
    }

    // ------------------------------------------------------------------------------------------

    /**
     * A small JSON writer (org.json is stubbed in JVM unit tests). Doubles use Double.toString, the
     * shortest decimal that round-trips; Floats are widened to Double first, so a Float field holds
     * the exact binary value of the Float. Non-ASCII characters are written as \u escapes.
     */
    private object Json {
        fun render(root: Map<String, Any?>): String = buildString {
            append("{\n")
            root.entries.forEachIndexed { i, (key, value) ->
                string(key)
                append(": ")
                if (value is List<*>) {
                    append("[\n")
                    value.forEachIndexed { j, item ->
                        append("  ")
                        emit(item)
                        if (j < value.size - 1) append(',')
                        append('\n')
                    }
                    append(']')
                } else {
                    emit(value)
                }
                if (i < root.size - 1) append(',')
                append('\n')
            }
            append("}\n")
        }

        private fun StringBuilder.emit(value: Any?) {
            when (value) {
                null -> append("null")
                is Boolean, is Int, is Long -> append(value.toString())
                is Double -> {
                    check(value.isFinite()) { "non-finite $value" }
                    append(value.toString())
                }
                is Float -> {
                    check(value.isFinite()) { "non-finite $value" }
                    append(value.toDouble().toString())
                }
                is String -> string(value)
                is Map<*, *> -> {
                    append('{')
                    value.entries.forEachIndexed { i, (k, v) ->
                        if (i > 0) append(", ")
                        string(k as String)
                        append(": ")
                        emit(v)
                    }
                    append('}')
                }
                is List<*> -> {
                    append('[')
                    value.forEachIndexed { i, v ->
                        if (i > 0) append(", ")
                        emit(v)
                    }
                    append(']')
                }
                else -> error("cannot write ${value::class}")
            }
        }

        private fun StringBuilder.string(text: String) {
            append('"')
            for (c in text) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' || c.code >= 0x7F -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
