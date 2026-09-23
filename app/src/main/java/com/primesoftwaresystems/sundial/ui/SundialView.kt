package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.primesoftwaresystems.sundial.R
import com.primesoftwaresystems.sundial.astronomy.Astronomy
import com.primesoftwaresystems.sundial.astronomy.Zodiac
import com.primesoftwaresystems.sundial.calendar.CalendarOccurrence
import com.primesoftwaresystems.sundial.calendar.CalendarIntervals
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class SundialView(context: Context) : View(context) {
    enum class ViewState { HELIOCENTRIC, GEOCENTRIC, GALACTIC }

    private val density = resources.displayMetrics.density
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = resources.getFont(R.font.franklin_condensed)
    }
    private val dimText = Paint(text).apply { color = Color.argb(150, 255, 255, 255) }
    private val earthTexture = BitmapFactory.decodeResource(resources, R.drawable.earth_texture)
    private val earthRenderer = EarthSphereRenderer(earthTexture)
    private val earthBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val moonRenderer = MoonSphereRenderer()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private data class AmbientStar(
        val xFraction: Float,
        val yFraction: Float,
        val radiusDp: Float,
        val alpha: Int,
        val flare: Boolean = false,
    )
    private val ambientStars = Random(0x51A7D1A1).run {
        List(76) { index ->
            AmbientStar(
                xFraction = nextFloat(),
                yFraction = nextFloat(),
                radiusDp = .28f + nextFloat() * if (index % 11 == 0) 1.12f else .63f,
                alpha = 30 + nextInt(if (index % 11 == 0) 100 else 64),
                flare = index % 17 == 0,
            )
        }
    }
    private val dustLaneStars = Random(0x0B17A5E).run {
        List(64) {
            val x = nextFloat()
            AmbientStar(
                xFraction = x,
                yFraction = (.10f + x * .78f + (nextFloat() - .5f) * .13f).coerceIn(.02f, .98f),
                radiusDp = .20f + nextFloat() * .42f,
                alpha = 12 + nextInt(34),
            )
        }
    }
    private val constellationPaths = listOf(
        listOf(.70f to .075f, .76f to .105f, .82f to .072f, .88f to .128f, .93f to .095f),
        listOf(.055f to .76f, .11f to .79f, .15f to .845f, .22f to .82f, .27f to .875f),
        listOf(.69f to .84f, .76f to .80f, .82f to .855f, .89f to .82f, .94f to .91f),
    )

    private var state = ViewState.HELIOCENTRIC
    private var showClock = false
    private var north = true
    private var realtime = true
    private var running = true
    private var wallpaperMode = false
    private var backgroundStyle = CelestialStylePreferences.get(context)
    private var zodiacProfile = ZodiacPreferences.get(context)
    private var horoscopeText = ZodiacPreferences.getCurrentHoroscope(context, zodiacProfile, LocalDate.now())
    private var selectedInstant: Instant = Instant.now()
    private var galacticAnchorYear = selectedInstant.atZone(zone).year
    private var dragMode = DragMode.NONE
    private val selectedCalendarIds = linkedSetOf<Long>()
    private var occurrences: List<CalendarOccurrence> = emptyList()
    private var lastReportedCalendarYear = displayedYear
    private var earthPoint = Pair(0f, 0f)
    private var moonPoint = Pair(0f, 0f)
    private var sunPoint = Pair(0f, 0f)
    private var selectedTimeZoneOffsetMinutes: Int? = null
    private var selectedTimeZoneIsLocal = true
    private var transitionFrom: ViewState? = null
    private var transitionStartedAt = 0L
    private var transitionEarthPoint = Pair(0f, 0f)
    private var transitionCameraRotation = 0f
    /** Rotation applied by an enclosing camera transform, so rotated labels still read upright. */
    private var textScreenRotation = 0.0
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStarted = false
    private var inspectedEvent: CalendarOccurrence? = null
    private var inspectedCandidates: List<CalendarOccurrence> = emptyList()
    private var inspectedCandidateIndex = 0
    private var eventCycleAnchorX = 0f
    private var eventCycleAnchorY = 0f
    var onCalendarSelectionChanged: ((Set<Long>) -> Unit)? = null
    var onMenuRequested: (() -> Unit)? = null
    var onControlsChanged: (() -> Unit)? = null

    val displayedYear: Int get() = selectedInstant.atZone(zone).year
    val isClockVisible: Boolean get() = showClock
    val isGalacticVisible: Boolean get() = state == ViewState.GALACTIC
    val isSouthernHemisphere: Boolean get() = !north
    internal val selectedInstantForTest: Instant get() = selectedInstant
    internal val earthPointForTest: Pair<Float, Float> get() = earthPoint
    internal val sunPointForTest: Pair<Float, Float> get() = sunPoint
    internal val inspectedEventTitleForTest: String? get() = inspectedEvent?.title
    private val instrumentColor: Int get() = backgroundStyle.instrumentColor

    private enum class DragMode { NONE, YEAR, MOON, SUN_TIME, EVENT }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isClickable = true
    }

    fun setCalendarOccurrences(value: List<CalendarOccurrence>) {
        occurrences = value
        invalidate()
    }

    fun resumeClock() { running = true; invalidate() }
    fun pauseClock() { running = false }
    fun setClockVisible(value: Boolean) { showClock = value; invalidate() }
    fun setGalacticVisible(value: Boolean) {
        if (value && state != ViewState.GALACTIC) galacticAnchorYear = displayedYear
        state = if (value) ViewState.GALACTIC else ViewState.HELIOCENTRIC
        transitionFrom = null
        onControlsChanged?.invoke(); invalidate()
    }
    fun setBackgroundStyle(value: CelestialStyle) {
        backgroundStyle = value
        CelestialStylePreferences.set(context, value)
        invalidate()
    }
    fun setZodiacProfile(value: ZodiacProfile) {
        zodiacProfile = ZodiacPreferences.set(context, value)
        horoscopeText = ZodiacPreferences.getCurrentHoroscope(context, zodiacProfile, LocalDate.now())
        invalidate()
    }
    fun setHoroscope(value: String?) { horoscopeText = value?.trim()?.takeIf { it.isNotBlank() }; invalidate() }
    internal fun setAstrologyContentForTest(profile: ZodiacProfile, horoscope: String?) {
        zodiacProfile = profile
        horoscopeText = horoscope
    }
    fun setSouthernHemisphere(value: Boolean) { north = !value; invalidate() }
    fun setSelectedCalendarIds(ids: Set<Long>) {
        selectedCalendarIds.clear(); selectedCalendarIds.addAll(ids); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        white.color = instrumentColor
        text.color = instrumentColor
        dimText.color = withAlpha(instrumentColor, 150)
        // Freeze astronomy while a camera flight is active so both views target the same Earth.
        if (running && realtime && transitionFrom == null) selectedInstant = Instant.now()
        if (displayedYear != lastReportedCalendarYear && selectedCalendarIds.isNotEmpty()) {
            lastReportedCalendarYear = displayedYear
            onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet())
        }
        drawBackground(canvas)
        val from = transitionFrom
        if (from != null) {
            val progress = ((SystemClock.uptimeMillis() - transitionStartedAt) / TRANSITION_DURATION_MS.toFloat())
                .coerceIn(0f, 1f)
            if (progress >= 1f) {
                transitionFrom = null
                drawState(canvas, state)
            } else {
                drawTransition(canvas, from, state, smoothStep(progress))
                postInvalidateOnAnimation()
            }
        } else {
            drawState(canvas, state)
        }
        if (wallpaperMode) {
            if (zodiacProfile.enabled) horoscopeText?.let { drawHoroscopeCard(canvas, it, forWallpaper = true) }
        } else {
            drawChrome(canvas)
            inspectedEvent?.let { drawEventInspectionOverlay(canvas, it) }
        }
        if (running && !wallpaperMode) postInvalidateDelayed(if (showClock) 250L else 1_000L)
    }

    private fun drawState(canvas: Canvas, requestedState: ViewState) {
        when (requestedState) {
            ViewState.HELIOCENTRIC -> drawHeliocentric(canvas)
            ViewState.GEOCENTRIC -> drawGeocentric(canvas)
            ViewState.GALACTIC -> drawGalactic(canvas)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        val (cx, cy, _) = geometry()
        val atmosphere = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy,
                kotlin.math.hypot(width.toFloat(), height.toFloat()) * .72f,
                intArrayOf(
                    backgroundStyle.haloColor,
                    blendColor(backgroundStyle.haloColor, backgroundStyle.baseColor, .58f),
                    backgroundStyle.baseColor,
                ),
                floatArrayOf(0f, .58f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), atmosphere)
        drawAmbientStars(canvas)
    }

    private fun drawAmbientStars(canvas: Canvas) {
        val constellation = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(blendColor(instrumentColor, backgroundStyle.accentColor, .35f), 30)
            style = Paint.Style.STROKE
            strokeWidth = .55f * density
            pathEffect = DashPathEffect(floatArrayOf(2.5f * density, 5f * density), 0f)
        }
        constellationPaths.forEach { points ->
            canvas.drawPath(Path().apply {
                points.forEachIndexed { index, point ->
                    val x = point.first * width
                    val y = point.second * height
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }, constellation)
        }

        val dust = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        dustLaneStars.forEach { point ->
            dust.color = withAlpha(backgroundStyle.accentColor, point.alpha)
            canvas.drawCircle(point.xFraction * width, point.yFraction * height, point.radiusDp * density, dust)
        }

        val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; strokeCap = Paint.Cap.ROUND }
        ambientStars.forEach { point ->
            val x = point.xFraction * width
            val y = point.yFraction * height
            val radius = point.radiusDp * density
            star.color = withAlpha(instrumentColor, point.alpha)
            canvas.drawCircle(x, y, radius, star)
            if (point.flare) {
                star.strokeWidth = maxOf(.45f * density, radius * .42f)
                canvas.drawLine(x - radius * 2.4f, y, x + radius * 2.4f, y, star)
                canvas.drawLine(x, y - radius * 2.4f, x, y + radius * 2.4f, star)
            }
        }

        val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        constellationPaths.flatten().forEachIndexed { index, point ->
            node.color = withAlpha(instrumentColor, if (index % 4 == 0) 112 else 72)
            canvas.drawCircle(
                point.first * width,
                point.second * height,
                (if (index % 4 == 0) 1.15f else .72f) * density,
                node,
            )
        }
    }

    private fun drawTransition(canvas: Canvas, from: ViewState, to: ViewState, progress: Float) {
        val (cx, cy, _) = geometry()
        if ((from == ViewState.HELIOCENTRIC && to == ViewState.GEOCENTRIC) ||
            (from == ViewState.GEOCENTRIC && to == ViewState.HELIOCENTRIC)) {
            val flightProgress = if (to == ViewState.GEOCENTRIC) progress else 1f - progress
            drawEarthCameraFlight(canvas, cx, cy, flightProgress)
        } else {
            drawTransformedState(canvas, from, cx, cy, 1f, 0f, 1f - progress)
            drawTransformedState(canvas, to, cx, cy, 1f, 0f, progress)
        }
    }

    private fun drawEarthCameraFlight(canvas: Canvas, cx: Float, cy: Float, progress: Float) {
        val targetX = lerp(transitionEarthPoint.first, cx, progress)
        val targetY = lerp(transitionEarthPoint.second, cy, progress)
        val frame = DialGeometry.earthFlightFrame(progress)
        val cameraRotation = transitionCameraRotation * progress

        val heliocentricAlpha = when {
            progress < .48f -> 1f
            progress > .94f -> 0f
            else -> 1f - (progress - .48f) / .46f
        }
        val geocentricAlpha = (progress / .22f).coerceIn(0f, 1f)

        val (_, _, r) = geometry()
        fun heliocentricCamera() {
            canvas.translate(targetX, targetY)
            canvas.rotate(cameraRotation)
            canvas.scale(frame.cameraScale, frame.cameraScale)
            canvas.translate(-transitionEarthPoint.first, -transitionEarthPoint.second)
        }
        // The annual sprocket stays visible from the Earth camera, so it rides the camera at full
        // opacity and lands exactly where the Earth view draws it.
        canvas.save()
        heliocentricCamera()
        withTextScreenRotation(cameraRotation.toDouble()) { drawAnnualBackdrop(canvas, cx, cy, r) }
        canvas.restore()

        if (heliocentricAlpha > .01f) {
            val checkpoint = canvas.saveLayerAlpha(
                0f, 0f, width.toFloat(), height.toFloat(), (heliocentricAlpha * 255).toInt(),
            )
            heliocentricCamera()
            withTextScreenRotation(cameraRotation.toDouble()) {
                drawSeasonShading(canvas, cx, cy, r)
                drawHeliocentricForeground(canvas, cx, cy, r, includeSun = false)
            }
            canvas.restoreToCount(checkpoint)
        }

        if (geocentricAlpha > .01f) {
            val checkpoint = canvas.saveLayerAlpha(
                0f, 0f, width.toFloat(), height.toFloat(), (geocentricAlpha * 255).toInt(),
            )
            canvas.translate(targetX, targetY)
            canvas.scale(frame.earthSystemScale, frame.earthSystemScale)
            canvas.translate(-cx, -cy)
            drawGeocentric(canvas, includeBackdrop = false, includeSun = false)
            canvas.restoreToCount(checkpoint)
        }

        // One Sun for the whole flight: it follows the camera from the dial centre to the top of
        // the Earth view instead of cross-fading between two differently scaled copies.
        val sunOffsetX = (cx - transitionEarthPoint.first) * frame.cameraScale
        val sunOffsetY = (cy - transitionEarthPoint.second) * frame.cameraScale
        val cameraRadians = Math.toRadians(cameraRotation.toDouble())
        drawSunBloom(
            canvas,
            targetX + (sunOffsetX * cos(cameraRadians) - sunOffsetY * sin(cameraRadians)).toFloat(),
            targetY + (sunOffsetX * sin(cameraRadians) + sunOffsetY * cos(cameraRadians)).toFloat(),
            r * lerp(.052f, .058f, progress),
        )
    }

    private fun drawTransformedState(
        canvas: Canvas,
        requestedState: ViewState,
        cx: Float,
        cy: Float,
        scale: Float,
        rotation: Float,
        alpha: Float,
    ) {
        if (alpha <= .01f) return
        val checkpoint = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        canvas.scale(scale, scale, cx, cy)
        canvas.rotate(rotation, cx, cy)
        drawState(canvas, requestedState)
        canvas.restoreToCount(checkpoint)
    }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

    private fun switchToState(newState: ViewState) {
        if (state == newState) return
        if ((state == ViewState.HELIOCENTRIC && newState == ViewState.GEOCENTRIC) ||
            (state == ViewState.GEOCENTRIC && newState == ViewState.HELIOCENTRIC)) {
            val (cx, cy, r) = geometry()
            val earthAngle = currentEarthAngle()
            val calculatedEarthPoint = point(cx, cy, r * DialGeometry.EARTH_ORBIT, earthAngle)
            transitionEarthPoint = if (
                state == ViewState.HELIOCENTRIC && earthPoint.first.isFinite() && earthPoint.second.isFinite() &&
                earthPoint != Pair(0f, 0f)
            ) earthPoint else calculatedEarthPoint
            transitionCameraRotation = Astronomy.normalizeSignedDegrees(90.0 - earthAngle).toFloat()
        }
        transitionFrom = state
        state = newState
        transitionStartedAt = SystemClock.uptimeMillis()
        onControlsChanged?.invoke()
        invalidate()
    }

    /** Renders an instrument view without interactive application chrome. Main thread only. */
    fun renderWallpaperBitmap(
        widthPx: Int,
        heightPx: Int,
        instant: Instant = Instant.now(),
        wallpaperState: ViewState = ViewState.HELIOCENTRIC,
    ): Bitmap {
        require(widthPx > 0 && heightPx > 0)
        state = wallpaperState
        transitionFrom = null
        selectedInstant = instant
        realtime = false
        running = false
        wallpaperMode = true
        measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY),
        )
        layout(0, 0, widthPx, heightPx)
        return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also {
            draw(Canvas(it))
        }
    }

    private fun geometry(): Triple<Float, Float, Float> {
        val cx = width / 2f
        val cy = height * if (height > width * 1.25f) 0.47f else 0.5f
        val radius = min(width * 0.47f, height * 0.41f)
        return Triple(cx, cy, radius)
    }

    private fun drawHeliocentric(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        drawSeasonShading(canvas, cx, cy, r)
        drawAnnualBackdrop(canvas, cx, cy, r)
        drawHeliocentricForeground(canvas, cx, cy, r)
    }

    /**
     * Parts of the Sun-centred instrument that Unity kept on screen after the camera flew to the
     * Earth: the annual sprocket, season cross and the Earth spike that points at today's date.
     */
    private fun drawAnnualBackdrop(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawReverseSeasonRing(canvas, cx, cy, r)
        drawAnnualDial(canvas, cx, cy, r)
        drawSeasonCross(canvas, cx, cy, r)
        // Unity's Earth line: a translucent spike from the Sun through the Earth to the annual dial.
        drawDialTriangle(canvas, cx, cy, r * .99f, currentEarthAngle(), r * .025f, withAlpha(instrumentColor, 77))
    }

    private fun drawHeliocentricForeground(canvas: Canvas, cx: Float, cy: Float, r: Float, includeSun: Boolean = true) {
        if (zodiacProfile.enabled) drawZodiacRing(canvas, cx, cy, r)
        if (zodiacProfile.enabled) drawPlanetZodiacHands(canvas, cx, cy, r)
        drawOrbitPaths(canvas, cx, cy, r)
        drawCalendarYearEvents(canvas, cx, cy, r)
        if (includeSun) drawSunBloom(canvas, cx, cy, r * 0.052f)
        text.textSize = r * 0.086f
        canvas.drawText("S U N : D I A L", cx, cy - r * 0.56f, text)
    }

    private fun currentEarthAngle(): Double = annualAngle(Astronomy.civilYearFraction(selectedInstant.atZone(zone)))

    /** Heliocentric longitude → canvas angle, sharing the annual dial's frame. */
    private fun eclipticAngle(longitudeDegrees: Double): Double = DialGeometry.eclipticAngle(longitudeDegrees, north)

    private fun drawSeasonShading(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val date = selectedInstant.atZone(zone).toLocalDate()
        val active = Zodiac.seasonFor(date, north)
        val year = date.year
        val days = Astronomy.daysInYear(year).toDouble()
        data class Span(val season: Zodiac.Season, val start: Double, val end: Double)
        fun fraction(month: Int, day: Int) = (LocalDate.of(year, month, day).dayOfYear - 1).toDouble() / days
        val spans = listOf(
            Span(Zodiac.Season.WINTER, 0.0, fraction(3, 21)),
            Span(Zodiac.Season.SPRING, fraction(3, 21), fraction(6, 21)),
            Span(Zodiac.Season.SUMMER, fraction(6, 21), fraction(9, 23)),
            Span(Zodiac.Season.FALL, fraction(9, 23), fraction(12, 22)),
            Span(Zodiac.Season.WINTER, fraction(12, 22), 1.0),
        )
        val bounds = RectF(cx - r * .965f, cy - r * .965f, cx + r * .965f, cy + r * .965f)
        spans.forEach { span ->
            val displayed = displayedSeason(span.season)
            val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = withAlpha(backgroundStyle.accentColor, if (displayed == active) 28 else 7)
            }
            canvas.drawArc(bounds, annualAngle(span.start).toFloat(),
                ((if (north) -1 else 1) * 360.0 * (span.end - span.start)).toFloat(), true, wash)
        }
    }

    private fun drawReverseSeasonRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val localDate = selectedInstant.atZone(zone).toLocalDate()
        val active = Zodiac.seasonFor(localDate, north)
        val year = localDate.year
        val days = Astronomy.daysInYear(year).toDouble()
        fun fraction(month: Int, day: Int) = (LocalDate.of(year, month, day).dayOfYear - 1).toDouble() / days
        data class SeasonArc(val season: Zodiac.Season, val start: Double, val end: Double, val labelAt: Double?)
        val arcs = listOf(
            SeasonArc(Zodiac.Season.WINTER, 0.0, fraction(3, 21), fraction(2, 1)),
            SeasonArc(Zodiac.Season.SPRING, fraction(3, 21), fraction(6, 21), fraction(5, 6)),
            SeasonArc(Zodiac.Season.SUMMER, fraction(6, 21), fraction(9, 23), fraction(8, 7)),
            SeasonArc(Zodiac.Season.FALL, fraction(9, 23), fraction(12, 22), fraction(11, 6)),
            SeasonArc(Zodiac.Season.WINTER, fraction(12, 22), 1.0, null),
        )
        val rect = RectF(cx - r * 1.025f, cy - r * 1.025f, cx + r * 1.025f, cy + r * 1.025f)
        val brass = intArrayOf(0xFFDCA247.toInt(), 0xFFB36A32.toInt(), 0xFF8D553B.toInt(), 0xFF9BB8C6.toInt())
        arcs.forEach { arc ->
            val index = arc.season.ordinal
            val displayedSeason = displayedSeason(arc.season)
            val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = r * if (displayedSeason == active) .064f else .046f
                strokeCap = Paint.Cap.BUTT
                color = withAlpha(brass[index], if (displayedSeason == active) 215 else 72)
            }
            canvas.drawArc(rect, annualAngle(arc.start).toFloat(),
                ((if (north) -1 else 1) * 360.0 * (arc.end - arc.start)).toFloat(), false, band)
            val labelAt = arc.labelAt ?: return@forEach
            val labelPaint = Paint(text).apply {
                textSize = r * .027f
                letterSpacing = .14f
                color = withAlpha(instrumentColor, if (displayedSeason == active) 245 else 105)
            }
            drawRotatedText(canvas, displayedSeason.name, cx, cy, r * 1.025f, annualAngle(labelAt), labelPaint, true)
        }
        white.color = withAlpha(backgroundStyle.accentColor, 125)
        white.strokeWidth = r * .003f
        canvas.drawCircle(cx, cy, r * 1.057f, white)
    }

    private fun drawZodiacRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val outer = r * .875f
        val inner = r * .705f
        val centerRadius = (outer + inner) / 2f
        val activeSign = Zodiac.signFor(selectedInstant.atZone(zone).toLocalDate())
        val natalSign = zodiacProfile.resolvedSign()
        val sectorRect = RectF(cx - centerRadius, cy - centerRadius, cx + centerRadius, cy + centerRadius)
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        Zodiac.Sign.entries.forEach { sign ->
            val start = zodiacAngle(sign.ordinal * 30.0)
            val sweep = if (north) -30f else 30f
            val sector = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = outer - inner
                strokeCap = Paint.Cap.BUTT
                color = withAlpha(if (sign.ordinal % 2 == 0) 0xFFD5A24F.toInt() else 0xFF9B6C3A.toInt(),
                    if (sign == activeSign) 112 else 44)
            }
            canvas.drawArc(sectorRect, start.toFloat(), sweep, false, sector)
            white.color = withAlpha(instrumentColor, if (sign == activeSign) 210 else 80)
            white.strokeWidth = if (sign == activeSign) r * .004f else r * .0014f
            drawRadialLine(canvas, cx, cy, inner, outer, start, white)

            val mid = zodiacAngle(sign.ordinal * 30.0 + 15.0)
            val glyphPoint = point(cx, cy, r * .797f, mid)
            glyphPaint.textSize = r * if (sign == activeSign || sign == natalSign) .078f else .061f
            glyphPaint.color = when {
                sign == natalSign -> 0xFFFFD889.toInt()
                sign == activeSign -> instrumentColor
                else -> withAlpha(0xFFFFE7B0.toInt(), 190)
            }
            canvas.drawText(sign.symbol, glyphPoint.first,
                glyphPoint.second - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
            val signPaint = Paint(dimText).apply {
                textSize = r * .020f
                color = withAlpha(instrumentColor, if (sign == activeSign) 225 else 105)
            }
            drawRotatedText(canvas, sign.displayName.take(3).uppercase(), cx, cy, r * .735f, mid, signPaint, true)
        }
        white.color = withAlpha(instrumentColor, 150)
        white.strokeWidth = r * .0024f
        canvas.drawCircle(cx, cy, inner, white)
        white.color = withAlpha(0xFFFFD58A.toInt(), 195)
        canvas.drawCircle(cx, cy, outer, white)
    }

    private fun drawPlanetZodiacHands(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val earth = point(cx, cy, r * DialGeometry.EARTH_ORBIT, currentEarthAngle())
        val bodies = listOf(
            Triple(Astronomy.Body.MERCURY, DialGeometry.MERCURY_ORBIT, 0xFF8EA5B8.toInt()),
            Triple(Astronomy.Body.VENUS, DialGeometry.VENUS_ORBIT, 0xFFFFCE7A.toInt()),
        )
        bodies.forEach { (body, orbitRatio, color) ->
            val heliocentricLongitude = Astronomy.heliocentricPosition(body, selectedInstant).longitudeDegrees
            val planetAngle = eclipticAngle(heliocentricLongitude)
            val planet = point(cx, cy, r * orbitRatio, planetAngle)
            val longitude = Zodiac.geocentricLongitude(body, selectedInstant)
            val sign = Zodiac.signForLongitude(longitude)
            val signPoint = point(cx, cy, r * .692f, zodiacAngle(sign.ordinal * 30.0 + 15.0))
            val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withAlpha(color, 145)
                style = Paint.Style.STROKE
                strokeWidth = r * .0022f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(earth.first, earth.second, planet.first, planet.second, hand)
            hand.pathEffect = DashPathEffect(floatArrayOf(r * .012f, r * .010f), 0f)
            canvas.drawLine(planet.first, planet.second, signPoint.first, signPoint.second, hand)
            fill.color = color
            canvas.drawCircle(signPoint.first, signPoint.second, r * .0065f, fill)
            val medallion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withAlpha(color, 225)
                textSize = r * .034f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
            canvas.drawText(sign.symbol, signPoint.first,
                signPoint.second - r * .012f - (medallion.ascent() + medallion.descent()) / 2f, medallion)
        }

        val sunLongitude = Zodiac.sunLongitude(selectedInstant)
        drawCelestialZodiacHand(canvas, earth, Pair(cx, cy), sunLongitude, r, 0xFFFFD17A.toInt(), crescent = false)
        val moonLongitude = Astronomy.moonLongitudeDegrees(selectedInstant)
        val moonAngle = zodiacAngle(moonLongitude)
        val moon = Pair(
            earth.first + cos(Math.toRadians(moonAngle)).toFloat() * r * .13f,
            earth.second + sin(Math.toRadians(moonAngle)).toFloat() * r * .13f,
        )
        drawCelestialZodiacHand(canvas, earth, moon, moonLongitude, r, 0xFFFFEAB5.toInt(), crescent = true)
    }

    private fun drawCelestialZodiacHand(
        canvas: Canvas,
        earth: Pair<Float, Float>,
        body: Pair<Float, Float>,
        longitude: Double,
        r: Float,
        color: Int,
        crescent: Boolean,
    ) {
        val sign = Zodiac.signForLongitude(longitude)
        val (cx, cy, _) = geometry()
        val signPoint = point(cx, cy, r * .692f, zodiacAngle(sign.ordinal * 30.0 + 15.0))
        val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 135)
            style = Paint.Style.STROKE
            strokeWidth = r * .0021f
        }
        canvas.drawLine(earth.first, earth.second, body.first, body.second, hand)
        hand.pathEffect = DashPathEffect(floatArrayOf(r * .010f, r * .011f), 0f)
        canvas.drawLine(body.first, body.second, signPoint.first, signPoint.second, hand)
        if (crescent) {
            val crescentPath = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addCircle(body.first, body.second, r * .018f, Path.Direction.CW)
                addCircle(body.first + r * .009f, body.second - r * .003f, r * .016f, Path.Direction.CW)
            }
            fill.color = color
            canvas.drawPath(crescentPath, fill)
        } else {
            white.color = color
            white.strokeWidth = r * .002f
            canvas.drawCircle(body.first, body.second, r * .019f, white)
            fill.color = color
            canvas.drawCircle(body.first, body.second, r * .005f, fill)
        }
    }

    private fun displayedSeason(northernSeason: Zodiac.Season): Zodiac.Season = if (north) northernSeason else when (northernSeason) {
        Zodiac.Season.SPRING -> Zodiac.Season.FALL
        Zodiac.Season.SUMMER -> Zodiac.Season.WINTER
        Zodiac.Season.FALL -> Zodiac.Season.SPRING
        Zodiac.Season.WINTER -> Zodiac.Season.SUMMER
    }

    private fun drawAnnualDial(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val local = selectedInstant.atZone(zone)
        val year = local.year
        val days = Astronomy.daysInYear(year)
        white.strokeWidth = maxOf(1.1f * density, r * 0.0034f)
        white.color = instrumentColor
        canvas.drawCircle(cx, cy, r, white)
        white.color = withAlpha(instrumentColor, 75)
        white.strokeWidth = maxOf(.5f * density, r * .0012f)
        canvas.drawCircle(cx, cy, r * .978f, white)
        for (dayIndex in 0 until days) {
            val date = LocalDate.ofYearDay(year, dayIndex + 1)
            val monthStart = date.dayOfMonth == 1
            // Unity's sun sprocket gives every Sunday a medium tick.
            val week = date.dayOfWeek == java.time.DayOfWeek.SUNDAY
            val length = when { monthStart -> r * 0.062f; week -> r * 0.036f; else -> r * 0.019f }
            val angle = annualAngle(dayIndex.toDouble() / days)
            white.color = withAlpha(instrumentColor, if (monthStart) 255 else if (week) 205 else 145)
            white.strokeWidth = if (monthStart) r * .003f else r * .0017f
            drawRadialLine(canvas, cx, cy, r - length, r * .995f, angle, white)
        }
        text.textSize = r * 0.039f
        for (month in 1..12) {
            val date = LocalDate.of(year, month, 15)
            val fraction = (date.dayOfYear - 0.5) / days
            drawRotatedText(canvas, date.month.name.take(3), cx, cy, r * 0.915f, annualAngle(fraction), text, upright = true)
        }
        val current = Astronomy.civilYearFraction(local)
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(instrumentColor, 210); strokeWidth = r * .004f }
        drawRadialLine(canvas, cx, cy, r * .77f, r * 1.015f, annualAngle(current), marker)
        val nowPoint = point(cx, cy, r * 1.015f, annualAngle(current))
        fill.color = instrumentColor
        canvas.save()
        canvas.rotate((annualAngle(current) + 45.0).toFloat(), nowPoint.first, nowPoint.second)
        canvas.drawRect(nowPoint.first - r * .008f, nowPoint.second - r * .008f,
            nowPoint.first + r * .008f, nowPoint.second + r * .008f, fill)
        canvas.restore()
    }

    private fun drawSeasonCross(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Unity: dotted solstice and equinox lines spanning the full dial, 45 dots per diameter.
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(instrumentColor, 128) }
        val spacing = 2f * r / 45f
        val size = maxOf(.9f * density, r * .0028f)
        for (i in -22..22) {
            canvas.drawCircle(cx, cy + i * spacing, size, dotPaint)
            canvas.drawCircle(cx + i * spacing, cy, size, dotPaint)
        }
    }

    private fun drawOrbitPaths(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val radii = mapOf(
            Astronomy.Body.MERCURY to r * DialGeometry.MERCURY_ORBIT,
            Astronomy.Body.VENUS to r * DialGeometry.VENUS_ORBIT,
            Astronomy.Body.EARTH to r * DialGeometry.EARTH_ORBIT,
            Astronomy.Body.MARS to r * DialGeometry.MARS_ORBIT,
        )
        // Unity's translucent planet hands; the Earth hand is the solid blue one.
        val handColors = mapOf(
            Astronomy.Body.MERCURY to Color.argb(77, 102, 128, 153),
            Astronomy.Body.VENUS to Color.argb(77, 247, 247, 217),
            Astronomy.Body.EARTH to Color.argb(235, 77, 77, 255),
            Astronomy.Body.MARS to Color.argb(77, 230, 51, 77),
        )
        for (body in listOf(Astronomy.Body.MARS, Astronomy.Body.VENUS, Astronomy.Body.MERCURY, Astronomy.Body.EARTH)) {
            val orbitR = radii.getValue(body)
            val angle = if (body == Astronomy.Body.EARTH) {
                // The Unity Earth hand is the civil calendar hand: it must agree with the annual dial.
                currentEarthAngle()
            } else {
                eclipticAngle(Astronomy.heliocentricPosition(body, selectedInstant).longitudeDegrees)
            }
            val isEarth = body == Astronomy.Body.EARTH
            drawOrbitTrail(
                canvas, cx, cy, orbitR, angle,
                sweepFraction = if (isEarth) .833f else .33f,
                headWidth = if (isEarth) maxOf(1.4f * density, r * .0045f) else maxOf(1f * density, r * .003f),
                alpha = if (isEarth) 235 else 128,
            )
            val point = point(cx, cy, orbitR, angle)
            val base = if (isEarth) r * .01665f else r * .00665f
            drawDialTriangle(canvas, cx, cy, orbitR, angle, base, handColors.getValue(body))
            drawPlanetGlyph(canvas, body, point.first, point.second, r, cx, cy)
            if (isEarth) earthPoint = point
        }
    }

    /**
     * Unity drew each orbit as a trail behind the planet (a third of the orbit, five sixths for
     * Earth) that thins to nothing at its tail, rather than a full circle.
     */
    private fun drawOrbitTrail(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        headAngle: Double,
        sweepFraction: Float,
        headWidth: Float,
        alpha: Int,
    ) {
        val segments = 72
        // Planets advance counter-clockwise in the north view, so the trail lies clockwise of them.
        val direction = if (north) 1f else -1f
        val sweep = 360f * sweepFraction / segments
        val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        for (i in 0 until segments) {
            val t = i / segments.toFloat()
            trail.strokeWidth = (headWidth * (1f - t)).coerceAtLeast(.35f * density)
            trail.color = withAlpha(instrumentColor, (alpha * (1f - .55f * t)).toInt())
            canvas.drawArc(bounds, (headAngle + direction * i * sweep).toFloat(), direction * sweep * 1.04f, false, trail)
        }
    }

    private fun drawPlanetGlyph(
        canvas: Canvas,
        body: Astronomy.Body,
        x: Float,
        y: Float,
        r: Float,
        sunX: Float,
        sunY: Float,
    ) {
        val radius = when (body) {
            Astronomy.Body.MERCURY -> r * .017f
            Astronomy.Body.VENUS -> r * .026f
            Astronomy.Body.EARTH -> r * .025f
            Astronomy.Body.MARS -> r * .021f
        }
        val color = when (body) {
            Astronomy.Body.MERCURY -> Color.rgb(150, 163, 174)
            Astronomy.Body.VENUS -> Color.rgb(255, 218, 147)
            Astronomy.Body.EARTH -> Color.rgb(78, 190, 235)
            Astronomy.Body.MARS -> Color.rgb(231, 82, 62)
        }
        val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, radius * 3.2f,
                intArrayOf(withAlpha(color, 125), withAlpha(color, 45), Color.TRANSPARENT),
                floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(x, y, radius * 3.2f, aura)
        fill.color = color
        canvas.drawCircle(x, y, radius, fill)
        when (body) {
            Astronomy.Body.MERCURY -> {
                val facet = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFDCE4E8.toInt() }
                canvas.drawPath(Path().apply {
                    moveTo(x, y - radius * .8f); lineTo(x + radius * .62f, y)
                    lineTo(x, y + radius * .5f); lineTo(x - radius * .48f, y); close()
                }, facet)
                white.color = 0xAAFFFFFF.toInt(); white.strokeWidth = r * .0013f
                canvas.drawCircle(x, y, radius * 1.35f, white)
            }
            Astronomy.Body.VENUS -> {
                // Venus is an opaque cloud pearl, deliberately distinct from the phase-rendered Moon.
                val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = 0xA6FFF0C1.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = radius * .18f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(RectF(x - radius * .78f, y - radius * .5f, x + radius * .72f, y + radius * .08f),
                    16f, 148f, false, cloud)
                cloud.color = 0x80C99457.toInt()
                cloud.strokeWidth = radius * .13f
                canvas.drawArc(RectF(x - radius * .72f, y - radius * .02f, x + radius * .8f, y + radius * .62f),
                    188f, 150f, false, cloud)
                white.color = 0x99FFE0A3.toInt(); white.strokeWidth = r * .0015f
                canvas.drawCircle(x, y, radius * 1.12f, white)
            }
            Astronomy.Body.EARTH -> {
                drawEarthSeal(canvas, x, y, radius * 1.42f, sunX, sunY, ornate = false)
            }
            Astronomy.Body.MARS -> {
                fill.color = 0xFF71291F.toInt()
                canvas.drawCircle(x - radius * .2f, y - radius * .14f, radius * .24f, fill)
                val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = 0xCCFFB089.toInt(); strokeWidth = radius * .2f; strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(x - radius * .55f, y + radius * .45f, x + radius * .58f, y - radius * .5f, slash)
                white.color = 0x99FF866F.toInt(); white.strokeWidth = r * .0015f
                canvas.drawCircle(x, y, radius * 1.18f, white)
            }
        }
    }

    private fun drawGeocentric(canvas: Canvas, includeBackdrop: Boolean = true, includeSun: Boolean = true) {
        val (cx, cy, r) = geometry()
        val moonR = r * DialGeometry.MOON_DIAL
        val hourR = r * DialGeometry.HOUR_DIAL
        val timeZoneR = r * DialGeometry.TIME_ZONE_DIAL
        val sphereRadius = r * DialGeometry.EARTH_RADIUS
        val sunY = cy - r * DialGeometry.GEOCENTRIC_SUN_DISTANCE

        if (includeBackdrop) {
            // Unity's Earth camera still saw the annual sprocket, season cross and Earth spike
            // arcing around the Earth; keep them in the same place the camera flight leaves them.
            canvas.save()
            canvas.translate(cx, sunY)
            canvas.rotate((90.0 - currentEarthAngle()).toFloat())
            canvas.scale(DialGeometry.EARTH_CAMERA_ZOOM, DialGeometry.EARTH_CAMERA_ZOOM)
            canvas.translate(-cx, -cy)
            withTextScreenRotation(90.0 - currentEarthAngle()) { drawAnnualBackdrop(canvas, cx, cy, r) }
            canvas.restore()
        }

        drawHourSprocket(canvas, cx, cy, hourR, r)
        text.textSize = r * .038f
        for (hour in 0 until 24) {
            drawRotatedText(canvas, hour.toString(), cx, cy, hourR * .87f, hourAngle(hour.toDouble()), text, true)
        }

        val moonAngle = DialGeometry.moonAngle(Astronomy.moonPhaseDegrees(selectedInstant), north)
        moonPoint = point(cx, cy, moonR, moonAngle)
        drawAnnularPointer(canvas, cx, cy, sphereRadius * 1.055f, moonR * .97f, moonAngle,
            moonR * .05f, withAlpha(instrumentColor, 122))
        drawAnnularPointer(canvas, cx, cy, sphereRadius * 1.065f, moonR * .82f, moonAngle,
            moonR * .021f, 0x9B85858A.toInt())
        drawLunarDial(canvas, cx, cy, moonR, r)

        // Sun stays at the top in Earth-following view, as in the original camera behavior.
        if (includeSun) drawSunBloom(canvas, cx, sunY, r * .058f)

        drawCalendarDayEvents(canvas, cx, cy, hourR)
        drawTimeZoneDial(canvas, cx, cy, r, sphereRadius, timeZoneR)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx + sphereRadius * .05f, cy + sphereRadius * .12f, sphereRadius * 1.18f,
                intArrayOf(Color.argb(82, 0, 0, 0), Color.argb(32, 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, .68f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawOval(RectF(cx - sphereRadius * 1.08f, cy - sphereRadius * .92f,
            cx + sphereRadius * 1.16f, cy + sphereRadius * 1.2f), shadow)
        drawEarthSeal(canvas, cx, cy, sphereRadius, cx, sunY, ornate = true,
            highlightOffsetMinutes = selectedTimeZoneOffset())

        drawMoonGlyph(canvas, moonPoint.first, moonPoint.second, r * .041f, cx, sunY)
    }

    /** Unity's Earth sprocket: 24 hour teeth with three quarter-hour teeth between them. */
    private fun drawHourSprocket(canvas: Canvas, cx: Float, cy: Float, radius: Float, r: Float) {
        white.color = instrumentColor
        white.strokeWidth = maxOf(density, r * .005f)
        canvas.drawCircle(cx, cy, radius, white)
        val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = instrumentColor; style = Paint.Style.FILL }
        for (quarter in 0 until 96) {
            val major = quarter % 4 == 0
            drawSprocketTooth(
                canvas, cx, cy, radius, radius - if (major) r * .058f else r * .025f,
                hourAngle(quarter / 4.0),
                baseHalfWidth = if (major) r * .0048f else r * .0032f,
                tipHalfWidth = if (major) r * .0016f else r * .0011f,
                paint = tooth,
            )
        }
    }

    /**
     * Unity's lunar dial: one tick at each coming local midnight, placed where the Moon will be then,
     * with the date between ticks. The 29-day window leaves a gap just behind the Moon, and the
     * first of a month gets a split tick labelled with both months.
     */
    private fun drawLunarDial(canvas: Canvas, cx: Float, cy: Float, moonR: Float, r: Float) {
        val today = selectedInstant.atZone(zone).toLocalDate()
        val midnights = (0..29).map { today.plusDays(it.toLong()).atStartOfDay(zone).toInstant() }
        val phases = midnights.map { Astronomy.moonPhaseDegrees(it) }
        val angles = phases.map { DialGeometry.moonAngle(it, north) }
        val direction = if (north) -1.0 else 1.0

        var span = 0.0
        for (i in 0 until phases.lastIndex) {
            span += Astronomy.normalizeDegrees(phases[i + 1] - phases[i])
        }
        white.color = instrumentColor
        white.strokeWidth = maxOf(density, r * .004f)
        canvas.drawArc(RectF(cx - moonR, cy - moonR, cx + moonR, cy + moonR),
            angles.first().toFloat(), (direction * span).toFloat(), false, white)

        val monthPaint = Paint(text).apply { textSize = r * .033f }
        text.textSize = r * .037f
        for (i in 0..29) {
            val date = today.plusDays(i.toLong())
            val monthStart = date.dayOfMonth == 1 && i > 0
            white.strokeWidth = if (monthStart) maxOf(density, r * .0045f) else maxOf(.8f * density, r * .0026f)
            drawRadialLine(canvas, cx, cy,
                moonR - if (monthStart) r * .05f else r * .024f,
                moonR + if (monthStart) r * .035f else 0f,
                angles[i], white)
            if (monthStart) {
                // Earlier dates lie clockwise (north); the new month continues counter-clockwise.
                drawRotatedText(canvas, date.month.name.take(3), cx, cy, moonR + r * .038f,
                    angles[i] + direction * 3.6, monthPaint, true)
                drawRotatedText(canvas, date.minusDays(1).month.name.take(3), cx, cy, moonR + r * .038f,
                    angles[i] - direction * 3.6, monthPaint, true)
            }
            if (i < 29) {
                val middle = angles[i] + direction * Astronomy.normalizeDegrees(phases[i + 1] - phases[i]) / 2.0
                drawRotatedText(canvas, date.dayOfMonth.toString(), cx, cy, moonR * .94f, middle, text, true)
            }
        }
        // Current month sits just outside the ring where today's window begins.
        drawRotatedText(canvas, today.month.name.take(3), cx, cy, moonR + r * .038f,
            angles.first() - direction * 3.6, monthPaint, true)
    }

    private fun drawSprocketTooth(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        tipRadius: Float,
        angleDegrees: Double,
        baseHalfWidth: Float,
        tipHalfWidth: Float,
        paint: Paint,
    ) {
        val base = point(cx, cy, baseRadius, angleDegrees)
        val tip = point(cx, cy, tipRadius, angleDegrees)
        val perpendicular = Math.toRadians(angleDegrees + 90.0)
        val px = cos(perpendicular).toFloat()
        val py = sin(perpendicular).toFloat()
        canvas.drawPath(Path().apply {
            moveTo(base.first - px * baseHalfWidth, base.second - py * baseHalfWidth)
            lineTo(tip.first - px * tipHalfWidth, tip.second - py * tipHalfWidth)
            lineTo(tip.first + px * tipHalfWidth, tip.second + py * tipHalfWidth)
            lineTo(base.first + px * baseHalfWidth, base.second + py * baseHalfWidth)
            close()
        }, paint)
    }

    private inline fun withTextScreenRotation(rotation: Double, block: () -> Unit) {
        val previous = textScreenRotation
        textScreenRotation = previous + rotation
        try { block() } finally { textScreenRotation = previous }
    }

    private fun hourAngle(hours: Double): Double = DialGeometry.hourAngle(hours, north)

    private fun selectedTimeZoneOffset(): Int {
        val currentLocalOffset = TimeZoneDial.localOffsetMinutes(selectedInstant, zone)
        if (selectedTimeZoneOffsetMinutes == null) selectedTimeZoneOffsetMinutes = currentLocalOffset
        return if (selectedTimeZoneIsLocal) currentLocalOffset else selectedTimeZoneOffsetMinutes!!
    }

    private fun drawTimeZoneDial(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        sphereRadius: Float,
        timeZoneR: Float,
    ) {
        val spokes = TimeZoneDial.spokes(selectedInstant, north)
        val dates = spokes.map { it.localDate }.distinct().sorted()
        val chordRadius = timeZoneR - r * .024f
        val chordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * .025f
            strokeCap = Paint.Cap.BUTT
        }
        spokes.forEach { spoke ->
            chordPaint.color = if (spoke.localDate == dates.first()) 0x533FA7C7 else 0x535ED18B
            canvas.drawArc(
                RectF(cx - chordRadius, cy - chordRadius, cx + chordRadius, cy + chordRadius),
                (spoke.angleDegrees - 7.45).toFloat(), 14.9f, false, chordPaint,
            )
        }

        // Label the two civil dates represented around the international date line.
        dates.take(2).forEachIndexed { index, date ->
            val candidates = spokes.filter { it.localDate == date }
            if (candidates.isNotEmpty()) {
                val representative = candidates[candidates.size / 2]
                val labelPaint = Paint(dimText).apply { textSize = r * .024f; color = withAlpha(instrumentColor, 200) }
                drawRotatedText(canvas, "DAY ${index + 1} · ${date.dayOfWeek.name.take(3)}", cx, cy,
                    timeZoneR - r * .062f, representative.angleDegrees, labelPaint, true)
            }
        }

        white.color = withAlpha(instrumentColor, 159)
        white.strokeWidth = maxOf(.8f * density, r * .0018f)
        canvas.drawCircle(cx, cy, timeZoneR, white)

        val selectedOffset = selectedTimeZoneOffset()
        val selectedAngle = TimeZoneDial.angleForOffsetMinutes(selectedInstant, selectedOffset, north)

        spokes.forEach { spoke ->
            val isSelected = kotlin.math.abs(Astronomy.normalizeSignedDegrees(spoke.angleDegrees - selectedAngle)) < 4.0
            white.color = withAlpha(instrumentColor, if (isSelected) 255 else 154)
            white.strokeWidth = if (isSelected) r * .0045f else r * .0022f
            val inner = if (isSelected) sphereRadius * 1.015f else timeZoneR - r * .028f
            val outer = if (isSelected) timeZoneR + r * .105f else timeZoneR + r * .027f
            drawRadialLine(canvas, cx, cy, inner, outer, spoke.angleDegrees, white)
        }

        // Exact location arrow supports half/quarter-hour zones as well as the 24 whole-hour spikes.
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(instrumentColor, 239); style = Paint.Style.FILL }
        val tip = point(cx, cy, timeZoneR + r * .115f, selectedAngle)
        val left = point(cx, cy, timeZoneR + r * .075f, selectedAngle - 2.2)
        val right = point(cx, cy, timeZoneR + r * .075f, selectedAngle + 2.2)
        canvas.drawPath(Path().apply {
            moveTo(tip.first, tip.second); lineTo(left.first, left.second); lineTo(right.first, right.second); close()
        }, arrowPaint)

        val zoneLabel = if (selectedTimeZoneIsLocal) TimeZoneDial.localName(zone, selectedInstant)
            else TimeZoneDial.commonName(selectedOffset.floorDiv(60))
        white.color = withAlpha(instrumentColor, 239)
        white.strokeWidth = r * .0048f
        drawRadialLine(canvas, cx, cy, sphereRadius * 1.015f, timeZoneR + r * .09f, selectedAngle, white)
        val zoneText = Paint(text).apply { textSize = r * .031f; color = withAlpha(instrumentColor, 242) }
        drawRotatedText(canvas, zoneLabel.uppercase(), cx, cy, timeZoneR + r * .17f, selectedAngle, zoneText, true)
    }

    private fun drawGalactic(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        val frame = galacticFrame(r)
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(instrumentColor, 175)
            strokeWidth = r * .0032f
            style = Paint.Style.STROKE
        }
        val axisStart = galacticAxisPoint(cx, cy, frame, -r * 1.13f)
        val axisEnd = galacticAxisPoint(cx, cy, frame, r * 1.13f)
        canvas.drawLine(axisStart.first, axisStart.second, axisEnd.first, axisEnd.second, axis)
        drawDirectionArrow(canvas, axisStart, frame, r, axis)
        drawGalacticYearTicks(canvas, cx, cy, r, frame)

        sunPoint = galacticSunPoint(cx, cy, r, selectedInstant)
        drawOrthonormalPlane(canvas, sunPoint.first, sunPoint.second, frame, r)

        val pathColors = mapOf(
            Astronomy.Body.MERCURY to 0xFF9BB6C7.toInt(),
            Astronomy.Body.VENUS to 0xFFFFD591.toInt(),
            Astronomy.Body.EARTH to 0xFF6ED6F3.toInt(),
            Astronomy.Body.MARS to 0xFFE7523E.toInt(),
        )
        val orbitRadii = mapOf(
            Astronomy.Body.MERCURY to r * .105f,
            Astronomy.Body.VENUS to r * .185f,
            Astronomy.Body.EARTH to r * .285f,
            Astronomy.Body.MARS to r * .434f,
        )
        val start = LocalDate.of(galacticAnchorYear - 1, 1, 1).atStartOfDay(zone).toInstant()
        val end = LocalDate.of(galacticAnchorYear + 2, 1, 1).atStartOfDay(zone).toInstant()
        val durationSeconds = Duration.between(start, end).seconds
        for (body in Astronomy.Body.entries) {
            val spiral = Path()
            for (step in 0..240) {
                val instant = start.plusSeconds(durationSeconds * step / 240L)
                val p = galacticPlanetPoint(cx, cy, r, frame, body, orbitRadii.getValue(body), instant)
                if (step == 0) spiral.moveTo(p.first, p.second) else spiral.lineTo(p.first, p.second)
            }
            axis.color = withAlpha(pathColors.getValue(body), if (body == Astronomy.Body.EARTH) 150 else 78)
            axis.strokeWidth = r * if (body == Astronomy.Body.EARTH) .006f else .003f
            axis.pathEffect = if (body == Astronomy.Body.EARTH) null else DashPathEffect(floatArrayOf(r * .012f, r * .014f), 0f)
            canvas.drawPath(spiral, axis)
        }
        axis.pathEffect = null

        drawGalacticEvents(canvas, cx, cy, r, frame, orbitRadii.getValue(Astronomy.Body.EARTH))
        Astronomy.Body.entries.forEach { body ->
            val point = galacticPlanetPoint(cx, cy, r, frame, body, orbitRadii.getValue(body), selectedInstant)
            drawPlanetGlyph(canvas, body, point.first, point.second, r * .82f, sunPoint.first, sunPoint.second)
        }
        drawSunBloom(canvas, sunPoint.first, sunPoint.second, r * .061f)

        val label = Paint(dimText).apply { textSize = r * .037f; letterSpacing = .17f }
        canvas.drawText("DIRECTION OF TRAVEL", axisStart.first, axisStart.second - r * .065f, label)
        val yearLabel = Paint(text).apply { textSize = r * .055f }
        canvas.drawText(selectedInstant.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d  yyyy")),
            cx, cy + r * 1.13f, yearLabel)
    }

    /** Unity's galactic sun line: a big tick and year label at each New Year, lighter month ticks. */
    private fun drawGalacticYearTicks(canvas: Canvas, cx: Float, cy: Float, r: Float, frame: GalacticFrame) {
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val yearLabel = Paint(text).apply {
            textSize = r * .05f
            color = withAlpha(instrumentColor, 190)
            textAlign = if (frame.sideX < 0) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        val limit = r * 1.13f
        for (year in galacticAnchorYear - 2..galacticAnchorYear + 2) {
            for (month in 0 until 12) {
                val offset = year - galacticAnchorYear + month / 12.0 - .5
                val distance = (offset * frame.yearPitch).toFloat()
                if (kotlin.math.abs(distance) > limit) continue
                val center = galacticAxisPoint(cx, cy, frame, distance)
                val half = if (month == 0) r * .045f else r * .014f
                tick.color = withAlpha(instrumentColor, if (month == 0) 200 else 120)
                tick.strokeWidth = if (month == 0) r * .0045f else r * .0025f
                canvas.drawLine(center.first - frame.sideX * half, center.second - frame.sideY * half,
                    center.first + frame.sideX * half, center.second + frame.sideY * half, tick)
                if (month == 0) {
                    val label = Pair(center.first + frame.sideX * r * .065f, center.second + frame.sideY * r * .065f)
                    canvas.drawText(year.toString(), label.first,
                        label.second - (yearLabel.ascent() + yearLabel.descent()) / 2f, yearLabel)
                }
            }
        }
    }

    private data class GalacticFrame(
        val downX: Float,
        val downY: Float,
        val sideX: Float,
        val sideY: Float,
        val yearPitch: Float,
    )

    private fun galacticFrame(r: Float): GalacticFrame {
        val angle = Math.toRadians(58.0)
        val downX = cos(angle).toFloat()
        val downY = sin(angle).toFloat()
        return GalacticFrame(downX, downY, -downY, downX, r * .62f)
    }

    private fun galacticAxisPoint(
        cx: Float,
        cy: Float,
        frame: GalacticFrame,
        distance: Float,
    ) = Pair(cx + frame.downX * distance, cy + frame.downY * distance)

    private fun galacticYearOffset(instant: Instant): Double {
        val local = instant.atZone(zone)
        return local.year - galacticAnchorYear + Astronomy.civilYearFraction(local) - .5
    }

    private fun galacticSunPoint(cx: Float, cy: Float, r: Float, instant: Instant): Pair<Float, Float> {
        val frame = galacticFrame(r)
        return galacticAxisPoint(cx, cy, frame, (galacticYearOffset(instant) * frame.yearPitch).toFloat())
    }

    private fun galacticPlanetPoint(
        cx: Float,
        cy: Float,
        r: Float,
        frame: GalacticFrame,
        body: Astronomy.Body,
        orbitRadius: Float,
        instant: Instant,
    ): Pair<Float, Float> {
        val longitude = Math.toRadians(Astronomy.heliocentricPosition(body, instant).longitudeDegrees)
        val axisDistance = (galacticYearOffset(instant) * frame.yearPitch).toFloat() +
            sin(longitude).toFloat() * orbitRadius * .24f
        val sideDistance = cos(longitude).toFloat() * orbitRadius
        return Pair(
            cx + frame.downX * axisDistance + frame.sideX * sideDistance,
            cy + frame.downY * axisDistance + frame.sideY * sideDistance,
        )
    }

    private fun drawDirectionArrow(
        canvas: Canvas,
        tip: Pair<Float, Float>,
        frame: GalacticFrame,
        r: Float,
        paint: Paint,
    ) {
        val length = r * .085f
        val halfWidth = r * .027f
        paint.style = Paint.Style.FILL
        canvas.drawPath(Path().apply {
            moveTo(tip.first, tip.second)
            lineTo(
                tip.first + frame.downX * length + frame.sideX * halfWidth,
                tip.second + frame.downY * length + frame.sideY * halfWidth,
            )
            lineTo(
                tip.first + frame.downX * length - frame.sideX * halfWidth,
                tip.second + frame.downY * length - frame.sideY * halfWidth,
            )
            close()
        }, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun drawOrthonormalPlane(
        canvas: Canvas,
        x: Float,
        y: Float,
        frame: GalacticFrame,
        r: Float,
    ) {
        val plane = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * .002f
            color = withAlpha(backgroundStyle.accentColor, 105)
        }
        val sideAngle = Math.toDegrees(atan2(frame.sideY.toDouble(), frame.sideX.toDouble())).toFloat()
        canvas.save()
        canvas.rotate(sideAngle, x, y)
        for (scale in listOf(.35f, .67f, 1f)) {
            canvas.drawOval(
                RectF(x - r * .38f * scale, y - r * .085f * scale,
                    x + r * .38f * scale, y + r * .085f * scale),
                plane,
            )
        }
        canvas.restore()
        plane.color = withAlpha(instrumentColor, 75)
        canvas.drawLine(x - frame.sideX * r * .43f, y - frame.sideY * r * .43f,
            x + frame.sideX * r * .43f, y + frame.sideY * r * .43f, plane)
        canvas.drawLine(x - frame.downX * r * .10f, y - frame.downY * r * .10f,
            x + frame.downX * r * .10f, y + frame.downY * r * .10f, plane)
    }

    private fun drawGalacticEvents(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        frame: GalacticFrame,
        earthOrbitRadius: Float,
    ) {
        occurrences.forEachIndexed { index, event ->
            val instant = event.start.toInstant()
            if (instant.atZone(zone).year !in (galacticAnchorYear - 1)..(galacticAnchorYear + 1)) return@forEachIndexed
            val point = galacticPlanetPoint(
                cx, cy, r, frame, Astronomy.Body.EARTH, earthOrbitRadius, instant,
            )
            val bead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(event.color, 230)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(point.first, point.second, r * if (event.isAllDay) .014f else .010f, bead)
            bead.style = Paint.Style.STROKE
            bead.strokeWidth = r * .002f
            bead.color = withAlpha(instrumentColor, 155)
            canvas.drawCircle(point.first, point.second, r * .020f, bead)
            if (index < 7) {
                val eventText = Paint(dimText).apply {
                    color = withAlpha(event.color, 220)
                    textSize = r * .025f
                    textAlign = if (frame.sideX < 0) Paint.Align.RIGHT else Paint.Align.LEFT
                }
                canvas.drawText(event.title.take(16), point.first + frame.sideX * r * .03f,
                    point.second + frame.sideY * r * .03f, eventText)
            }
        }
    }

    private fun drawCalendarYearEvents(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val year = displayedYear
        occurrences.filter { it.isYearRingEvent }.forEach { event ->
            val segment = CalendarIntervals.inYear(event, year, zone) ?: return@forEach
            val band = DialGeometry.yearEventBand(r, calendarIndex(event.calendarId))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(event.color, 145); strokeWidth = band.thickness; style = Paint.Style.STROKE
            }
            canvas.drawArc(RectF(cx - band.centerRadius, cy - band.centerRadius,
                cx + band.centerRadius, cy + band.centerRadius),
                annualAngle(segment.startFraction).toFloat(),
                ((if (north) -360.0 else 360.0) * segment.sweepFraction).toFloat(), false, p)
            val labelPaint = Paint(text).apply { color = withAlpha(event.color, 220); textSize = r * .027f }
            val allowed = (segment.sweepFraction * 80).toInt().coerceAtLeast(1)
            drawRotatedText(canvas, event.title.take(allowed), cx, cy, band.centerRadius,
                annualAngle(segment.startFraction + segment.sweepFraction / 2.0), labelPaint, true)
        }
    }

    private fun drawCalendarDayEvents(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val day = selectedInstant.atZone(zone).toLocalDate()
        occurrences.filter { !it.isYearRingEvent }.forEach { event ->
            val segment = CalendarIntervals.inDay(event, day, zone) ?: return@forEach
            val band = DialGeometry.dayEventBand(r, calendarIndex(event.calendarId))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(event.color, 160); strokeWidth = band.thickness; style = Paint.Style.STROKE
            }
            canvas.drawArc(RectF(cx - band.centerRadius, cy - band.centerRadius,
                cx + band.centerRadius, cy + band.centerRadius),
                hourAngle(segment.startMinute / 60.0).toFloat(),
                ((if (north) -1.0 else 1.0) *
                    (segment.endMinuteExclusive - segment.startMinute).coerceAtLeast(1.0) / 4.0).toFloat(), false, p)
            val middleMinute = (segment.startMinute + segment.endMinuteExclusive) / 2.0
            val allowed = ((segment.endMinuteExclusive - segment.startMinute) / 15.0).toInt().coerceAtLeast(1)
            val labelPaint = Paint(text).apply { color = withAlpha(event.color, 230); textSize = r * .04f }
            drawRotatedText(canvas, event.title.take(allowed), cx, cy, band.centerRadius,
                hourAngle(middleMinute / 60.0), labelPaint, true)
        }
    }

    private fun calendarIndex(calendarId: Long): Int {
        val selectedIndex = selectedCalendarIds.indexOf(calendarId)
        if (selectedIndex >= 0) return selectedIndex
        return occurrences.map { it.calendarId }.distinct().indexOf(calendarId).coerceAtLeast(0)
    }

    private fun drawEarthSeal(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        sunX: Float,
        sunY: Float,
        ornate: Boolean,
        highlightOffsetMinutes: Int? = null,
    ) {
        val bitmap = earthRenderer.render(
            (radius * 2.35f).toInt().coerceAtLeast(64), selectedInstant, north, highlightOffsetMinutes,
        )
        val sunAngle = Math.toDegrees(atan2((sunY - y).toDouble(), (sunX - x).toDouble())).toFloat()
        // EarthSphereRenderer uses Sun-up coordinates; rotate the complete globe into the actual
        // Earth-to-Sun direction without changing its geographic orientation.
        canvas.save()
        canvas.rotate(sunAngle + 90f, x, y)
        canvas.drawBitmap(bitmap, null, RectF(x - radius, y - radius, x + radius, y + radius), earthBitmapPaint)
        if (ornate) {
            val pole = EarthOrientation.projectedGeographicPole(north, Zodiac.sunLongitude(selectedInstant))
            val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x9ED9B86B.toInt()
                strokeWidth = maxOf(density, radius * .009f)
                strokeCap = Paint.Cap.ROUND
            }
            val dx = pole.first.toFloat() * radius * 1.16f
            val dy = -pole.second.toFloat() * radius * 1.16f
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, axis)
            canvas.drawCircle(x + dx, y + dy, radius * .025f, axis)
        }
        canvas.restore()

        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ornate) 0xC8E5C47A.toInt() else withAlpha(instrumentColor, 190)
            style = Paint.Style.STROKE
            strokeWidth = maxOf(density, radius * if (ornate) .018f else .045f)
        }
        canvas.drawCircle(x, y, radius * 1.035f, halo)
        if (ornate) {
            halo.strokeWidth = maxOf(.7f * density, radius * .008f)
            halo.color = 0x86FFF0B8.toInt()
            canvas.drawCircle(x, y, radius * 1.105f, halo)
            halo.style = Paint.Style.FILL
            repeat(12) { index ->
                val angle = index * 2.0 * PI / 12.0
                canvas.drawCircle(
                    x + cos(angle).toFloat() * radius * 1.105f,
                    y + sin(angle).toFloat() * radius * 1.105f,
                    radius * .018f,
                    halo,
                )
            }
        }
    }

    private fun drawMoonGlyph(canvas: Canvas, x: Float, y: Float, radius: Float, sunX: Float, sunY: Float) {
        val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, radius * 2.7f,
                intArrayOf(0xAAFFFCE5.toInt(), 0x38D9D4B9, Color.TRANSPARENT),
                floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(x, y, radius * 2.7f, aura)
        val moon = moonRenderer.render((radius * 2f).toInt(), sunX - x, sunY - y)
        canvas.drawBitmap(moon, null, RectF(x - radius, y - radius, x + radius, y + radius), fill)
        white.color = withAlpha(instrumentColor, 184); white.strokeWidth = maxOf(1f, radius * .075f)
        canvas.drawCircle(x, y, radius * 1.04f, white)
        drawByzantineMoonSeal(canvas, x, y, radius)
    }

    private fun drawByzantineMoonSeal(canvas: Canvas, x: Float, y: Float, radius: Float) {
        val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB8E4C171.toInt()
            style = Paint.Style.STROKE
            strokeWidth = maxOf(.7f * density, radius * .055f)
        }
        canvas.drawArc(RectF(x - radius * 1.22f, y - radius * 1.22f,
            x + radius * 1.22f, y + radius * 1.22f), 198f, 284f, false, gold)
        gold.style = Paint.Style.FILL
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0 + 22.5)
            canvas.drawCircle(x + cos(angle).toFloat() * radius * 1.21f,
                y + sin(angle).toFloat() * radius * 1.21f, radius * .055f, gold)
        }
        // A tiny cross pattée crowns the lunar seal, echoing the reference's engraved metalwork.
        val crownY = y - radius * 1.48f
        gold.strokeWidth = maxOf(.8f * density, radius * .10f)
        gold.strokeCap = Paint.Cap.SQUARE
        canvas.drawLine(x - radius * .18f, crownY, x + radius * .18f, crownY, gold)
        canvas.drawLine(x, crownY - radius * .18f, x, crownY + radius * .18f, gold)
    }

    private fun drawSunBloom(canvas: Canvas, x: Float, y: Float, core: Float) {
        val hazeRadius = core * 7.8f
        val haze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, hazeRadius,
                intArrayOf(0xF5FFFDF0.toInt(), 0xD8FFD37A.toInt(), 0x52F28B32, 0x16C85022, Color.TRANSPARENT),
                floatArrayOf(0f, .10f, .27f, .56f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(x, y, hazeRadius, haze)

        for (i in 0 until 36) {
            val a = i * 2 * PI / 36.0
            val length = core * when {
                i % 9 == 0 -> 6.2f
                i % 3 == 0 -> 4.5f
                else -> 3.25f
            }
            val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (i % 3 == 0) 0x48FFD88D else 0x28FFF4CF
                strokeWidth = if (i % 9 == 0) maxOf(1f, core * .075f) else maxOf(.6f, core * .035f)
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(
                x + cos(a).toFloat() * core * 1.08f, y + sin(a).toFloat() * core * 1.08f,
                x + cos(a).toFloat() * length, y + sin(a).toFloat() * length, ray,
            )
        }

        white.style = Paint.Style.STROKE
        white.strokeWidth = maxOf(density * .7f, core * .06f)
        white.color = 0x42FFD798
        canvas.drawCircle(x, y, core * 2.7f, white)
        white.color = 0x24FFB64C
        canvas.drawCircle(x, y, core * 4.25f, white)
        canvas.drawArc(RectF(x - core * 3.35f, y - core * 3.35f, x + core * 3.35f, y + core * 3.35f), -62f, 118f, false, white)

        val ghostAxis = Math.toRadians(132.0)
        listOf(3.25f to .42f, 5.1f to .24f).forEach { (distance, scale) ->
            val gx = x + cos(ghostAxis).toFloat() * core * distance
            val gy = y + sin(ghostAxis).toFloat() * core * distance
            white.color = if (scale > .3f) 0x38A9E4FF else 0x28FFB86B
            white.strokeWidth = maxOf(.6f, core * .045f)
            canvas.drawCircle(gx, gy, core * scale, white)
        }

        fill.shader = RadialGradient(x - core * .22f, y - core * .25f, core * 1.45f,
            intArrayOf(Color.WHITE, 0xFFFFF7D2.toInt(), 0xFFFFC65A.toInt()),
            floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, core, fill)
        fill.shader = null
        drawByzantineSunSeal(canvas, x, y, core)
    }

    private fun drawByzantineSunSeal(canvas: Canvas, x: Float, y: Float, core: Float) {
        val engraving = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xA68D5517.toInt()
            style = Paint.Style.FILL
        }
        repeat(4) { quarter ->
            canvas.save()
            canvas.rotate(quarter * 90f, x, y)
            canvas.drawPath(Path().apply {
                moveTo(x - core * .10f, y - core * .06f)
                lineTo(x - core * .25f, y - core * .52f)
                lineTo(x + core * .25f, y - core * .52f)
                lineTo(x + core * .10f, y - core * .06f)
                close()
            }, engraving)
            canvas.restore()
        }
        engraving.color = 0xC8FFF2C2.toInt()
        repeat(12) { index ->
            val angle = index * 2.0 * PI / 12.0
            canvas.drawCircle(x + cos(angle).toFloat() * core * .80f,
                y + sin(angle).toFloat() * core * .80f, core * .045f, engraving)
        }
        engraving.style = Paint.Style.STROKE
        engraving.strokeWidth = maxOf(.6f * density, core * .045f)
        engraving.color = 0x9B8B571B.toInt()
        canvas.drawCircle(x, y, core * .68f, engraving)
    }

    private fun drawChrome(canvas: Canvas) {
        val center = 30f * density
        fill.color = 0x16000000
        canvas.drawCircle(center, center, 24f * density, fill)
        white.color = withAlpha(instrumentColor, 56)
        white.strokeWidth = .8f * density
        canvas.drawCircle(center, center, 23f * density, white)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = instrumentColor; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND }
        for (i in -1..1) canvas.drawLine(center - 10f * density, center + i * 7f * density,
            center + 10f * density, center + i * 7f * density, line)
        if (zodiacProfile.enabled) drawNatalSignMedallion(canvas)
        if (showClock) {
            val local = selectedInstant.atZone(zone)
            text.textSize = 24f * density
            canvas.drawText(local.format(DateTimeFormatter.ofPattern("dd/MM/yy   HH : mm : ss")), width / 2f, 33f * density, text)
        }
        if (!realtime) {
            fill.color = Color.argb(220, 15, 15, 15)
            val rect = RectF(width * .27f, height - 60f * density, width * .73f, height - 14f * density)
            canvas.drawRoundRect(rect, 18f * density, 18f * density, fill)
            white.color = instrumentColor; white.strokeWidth = density
            canvas.drawRoundRect(rect, 18f * density, 18f * density, white)
            text.textSize = 15f * density
            canvas.drawText("RESET CURRENT TIME", width / 2f, height - 29f * density, text)
        }
        if (zodiacProfile.enabled) horoscopeText?.let { drawHoroscopeCard(canvas, it, forWallpaper = false) }
    }

    private fun drawNatalSignMedallion(canvas: Canvas) {
        val sign = zodiacProfile.resolvedSign()
        val x = width - 31f * density
        val y = 31f * density
        val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, 29f * density,
                intArrayOf(withAlpha(backgroundStyle.accentColor, 95), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(x, y, 29f * density, aura)
        fill.color = 0xA8121010.toInt()
        canvas.drawCircle(x, y, 22f * density, fill)
        white.color = withAlpha(0xFFFFD889.toInt(), 205)
        white.strokeWidth = density
        canvas.drawCircle(x, y, 22f * density, white)
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFDE9C.toInt()
            textSize = 27f * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        canvas.drawText(sign.symbol, x, y - (glyph.ascent() + glyph.descent()) / 2f, glyph)
    }

    private fun drawHoroscopeCard(canvas: Canvas, value: String, forWallpaper: Boolean) {
        val (_, cy, r) = geometry()
        val cardWidth = minOf(width - 32f * density, 430f * density)
        val left = (width - cardWidth) / 2f
        val right = left + cardWidth
        // The Earth view keeps the Sun above the lunar dial and the annual dial arcing below it.
        val geocentric = state == ViewState.GEOCENTRIC
        val dialTop = cy - r * if (geocentric) 1.14f else 1.08f
        val dialBottom = cy + r * if (geocentric) 1.16f else 1.08f
        val topBounds = RectF(left, 66f * density, right, dialTop - 10f * density)
        val bottomLimit = height - (if (forWallpaper || realtime) 16f else 76f) * density
        val bottomBounds = RectF(left, dialBottom + 10f * density, right, bottomLimit)
        val minimumPanelHeight = 92f * density
        val sign = zodiacProfile.resolvedSign()

        if (topBounds.height() >= minimumPanelHeight && bottomBounds.height() >= minimumPanelHeight) {
            val (first, second) = splitHoroscope(value)
            drawHoroscopePanel(
                canvas,
                topBounds,
                "${sign.symbol}  ${sign.displayName.uppercase()} · TODAY'S ORACLE",
                first,
            )
            drawHoroscopePanel(
                canvas,
                bottomBounds,
                if (forWallpaper) "CONTINUED · CELESTIAL WALLPAPER" else "CONTINUED · PRIVATE ON-DEVICE",
                second,
            )
            return
        }

        val fallbackHeight = minOf(height * .30f, 250f * density)
        val fallbackTop = (height - fallbackHeight - 16f * density).coerceAtLeast(dialBottom + 8f * density)
        drawHoroscopePanel(
            canvas,
            RectF(left, fallbackTop, right, height - 16f * density),
            "${sign.symbol}  ${sign.displayName.uppercase()} · TODAY'S ORACLE",
            value,
        )
    }

    private fun drawHoroscopePanel(canvas: Canvas, bounds: RectF, title: String, value: String) {
        if (value.isBlank() || bounds.height() < 64f * density) return
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(instrumentColor, 225)
            textSize = 34f * density
            typeface = resources.getFont(R.font.franklin_condensed)
        }
        val bodyTop = bounds.top + 38f * density
        val availableBodyHeight = (bounds.bottom - bodyTop - 9f * density).coerceAtLeast(1f)
        val lineHeight = bodyPaint.fontSpacing + 2f * density
        val maxLines = (availableBodyHeight / lineHeight).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, bodyPaint, (bounds.width() - 38f * density).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(2f * density, 1f)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(bounds.centerX(), bounds.top, bounds.width() * .78f,
                intArrayOf(withAlpha(backgroundStyle.haloColor, 224), withAlpha(backgroundStyle.baseColor, 234)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(bounds, 18f * density, 18f * density, card)
        white.color = withAlpha(backgroundStyle.accentColor, 165)
        white.strokeWidth = density
        canvas.drawRoundRect(bounds, 18f * density, 18f * density, white)

        val titlePaint = Paint(text).apply {
            color = backgroundStyle.accentColor
            textSize = 17f * density
            letterSpacing = .09f
        }
        canvas.drawText(title, bounds.centerX(), bounds.top + 23f * density, titlePaint)
        canvas.save()
        canvas.translate(bounds.left + 19f * density, bodyTop)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun splitHoroscope(value: String): Pair<String, String> {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 48) return normalized to "Reflect on this guidance as the day unfolds."
        val midpoint = normalized.length / 2
        val sentenceBreaks = normalized.indices.filter { normalized[it] in charArrayOf('.', '!', '?') }
        val sentenceBreak = sentenceBreaks.minByOrNull { kotlin.math.abs(it - midpoint) }
            ?.takeIf { kotlin.math.abs(it - midpoint) < normalized.length * .24f }
            ?.plus(1)
        val wordBreak = normalized.lastIndexOf(' ', midpoint).takeIf { it > 0 } ?: midpoint
        val splitAt = sentenceBreak ?: wordBreak
        return normalized.substring(0, splitAt).trim() to normalized.substring(splitAt).trim()
    }

    private fun drawEventInspectionOverlay(canvas: Canvas, event: CalendarOccurrence) {
        val cardWidth = minOf(width - 36f * density, 420f * density)
        val cardHeight = 196f * density
        val bounds = RectF(
            (width - cardWidth) / 2f,
            (height - cardHeight) / 2f,
            (width + cardWidth) / 2f,
            (height + cardHeight) / 2f,
        )
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                bounds.centerX(), bounds.centerY(), bounds.width() * .72f,
                intArrayOf(withAlpha(backgroundStyle.haloColor, 248), withAlpha(backgroundStyle.baseColor, 248)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(bounds, 22f * density, 22f * density, panel)
        white.color = withAlpha(event.color, 245)
        white.strokeWidth = 2f * density
        canvas.drawRoundRect(bounds, 22f * density, 22f * density, white)

        val countLabel = if (inspectedCandidates.size > 1) {
            "EVENT ${inspectedCandidateIndex + 1} OF ${inspectedCandidates.size} · DRAG TO CYCLE"
        } else {
            "CALENDAR EVENT · HOLD TO INSPECT"
        }
        val eyebrow = Paint(text).apply {
            color = withAlpha(event.color, 255)
            textSize = 13f * density
            letterSpacing = .10f
        }
        canvas.drawText(countLabel, bounds.centerX(), bounds.top + 29f * density, eyebrow)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = instrumentColor
            textSize = 28f * density
            typeface = resources.getFont(R.font.franklin_condensed)
        }
        val titleLayout = StaticLayout.Builder.obtain(
            event.title, 0, event.title.length, titlePaint, (bounds.width() - 40f * density).toInt(),
        ).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(2)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(bounds.left + 20f * density, bounds.top + 54f * density)
        titleLayout.draw(canvas)
        canvas.restore()

        val timing = Paint(text).apply {
            color = withAlpha(instrumentColor, 220)
            textSize = 17f * density
        }
        canvas.drawText(eventTimingLabel(event), bounds.centerX(), bounds.bottom - 29f * density, timing)
    }

    private fun eventTimingLabel(event: CalendarOccurrence): String {
        if (event.isAllDay) {
            val start = requireNotNull(event.allDayStart)
            val endExclusive = requireNotNull(event.allDayEndExclusive)
            return if (endExclusive == start.plusDays(1)) {
                "ALL DAY · ${start.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}"
            } else {
                "ALL DAY · ${start.format(DateTimeFormatter.ofPattern("MMM d"))} – " +
                    endExclusive.minusDays(1).format(DateTimeFormatter.ofPattern("MMM d"))
            }
        }
        val localStart = event.start.withZoneSameInstant(zone)
        val localEnd = event.endExclusive.withZoneSameInstant(zone)
        val sameDay = localStart.toLocalDate() == localEnd.toLocalDate()
        return if (sameDay) {
            localStart.format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")) +
                " – " + localEnd.format(DateTimeFormatter.ofPattern("h:mm a"))
        } else {
            localStart.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")) +
                " – " + localEnd.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
        }
    }

    private fun calendarEventsAt(x: Float, y: Float): List<CalendarOccurrence> {
        val (cx, cy, r) = geometry()
        val radius = distance(x, y, cx, cy)
        val angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        val touchPadding = 13f * density
        return when (state) {
            ViewState.HELIOCENTRIC -> {
                val fraction = DialGeometry.yearFractionFromAngle(angle, north)
                occurrences.asSequence()
                    .filter { it.isYearRingEvent }
                    .mapNotNull { event ->
                        val segment = CalendarIntervals.inYear(event, displayedYear, zone) ?: return@mapNotNull null
                        val band = DialGeometry.yearEventBand(r, calendarIndex(event.calendarId))
                        if (kotlin.math.abs(radius - band.centerRadius) > band.thickness / 2f + touchPadding) {
                            return@mapNotNull null
                        }
                        val angularPadding = Math.toDegrees(
                            atan2(touchPadding.toDouble(), band.centerRadius.toDouble()),
                        ) / 360.0
                        if (!CalendarHitTesting.containsYearFraction(
                                fraction, segment.startFraction, segment.sweepFraction, angularPadding,
                            )) return@mapNotNull null
                        event to kotlin.math.abs(radius - band.centerRadius)
                    }
                    .sortedBy { it.second }
                    .map { it.first }
                    .toList()
            }
            ViewState.GEOCENTRIC -> {
                val minute = DialGeometry.minuteFromHourAngle(angle, north)
                val hourR = r * DialGeometry.HOUR_DIAL
                occurrences.asSequence()
                    .filter { !it.isYearRingEvent }
                    .mapNotNull { event ->
                        val segment = CalendarIntervals.inDay(
                            event, selectedInstant.atZone(zone).toLocalDate(), zone,
                        ) ?: return@mapNotNull null
                        val band = DialGeometry.dayEventBand(hourR, calendarIndex(event.calendarId))
                        if (kotlin.math.abs(radius - band.centerRadius) > band.thickness / 2f + touchPadding) {
                            return@mapNotNull null
                        }
                        val minutePadding = Math.toDegrees(
                            atan2(touchPadding.toDouble(), band.centerRadius.toDouble()),
                        ) * 4.0
                        if (!CalendarHitTesting.containsMinute(
                                minute, segment.startMinute, segment.endMinuteExclusive, minutePadding,
                            )) return@mapNotNull null
                        event to kotlin.math.abs(radius - band.centerRadius)
                    }
                    .sortedBy { it.second }
                    .map { it.first }
                    .toList()
            }
            ViewState.GALACTIC -> emptyList()
        }
    }

    private fun beginEventInspection(x: Float, y: Float): Boolean {
        val hits = calendarEventsAt(x, y)
        if (hits.isEmpty()) return false
        dragMode = DragMode.EVENT
        inspectedCandidates = hits
        inspectedCandidateIndex = 0
        inspectedEvent = hits.first()
        eventCycleAnchorX = x
        eventCycleAnchorY = y
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
        return true
    }

    private fun updateEventInspection(x: Float, y: Float) {
        val hits = calendarEventsAt(x, y)
        if (hits.isEmpty()) return
        if (hits != inspectedCandidates) {
            inspectedCandidates = hits
            inspectedCandidateIndex = 0
            inspectedEvent = hits.first()
            eventCycleAnchorX = x
            eventCycleAnchorY = y
        } else if (hits.size > 1 && distance(x, y, eventCycleAnchorX, eventCycleAnchorY) >= 18f * density) {
            inspectedCandidateIndex = (inspectedCandidateIndex + 1) % hits.size
            inspectedEvent = hits[inspectedCandidateIndex]
            eventCycleAnchorX = x
            eventCycleAnchorY = y
        }
        invalidate()
    }

    private fun finishInteraction() {
        if (dragMode == DragMode.EVENT) {
            inspectedEvent = null
            inspectedCandidates = emptyList()
            inspectedCandidateIndex = 0
        }
        dragMode = DragMode.NONE
        dragStarted = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (cx, cy, r) = geometry()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (transitionFrom != null) return true
                if (event.x < 64f * density && event.y < 64f * density) {
                    onMenuRequested?.invoke(); return true
                }
                if (!realtime && event.y > height - 75f * density && event.x in width * .2f..width * .8f) {
                    resetNow(); return true
                }
                if (beginEventInspection(event.x, event.y)) return true
                val radiusFromCenter = distance(event.x, event.y, cx, cy)
                if (state == ViewState.HELIOCENTRIC && distance(event.x, event.y, earthPoint.first, earthPoint.second) < r * .09f) {
                    dragMode = DragMode.YEAR
                    dragStartX = event.x
                    dragStartY = event.y
                    dragStarted = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (state == ViewState.HELIOCENTRIC && radiusFromCenter < r * .13f) {
                    switchToState(ViewState.GEOCENTRIC); return true
                }
                if (state == ViewState.GEOCENTRIC && distance(event.x, event.y, moonPoint.first, moonPoint.second) < r * .09f) {
                    dragMode = DragMode.MOON
                    dragStartX = event.x
                    dragStartY = event.y
                    dragStarted = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (state == ViewState.GALACTIC &&
                    distance(event.x, event.y, sunPoint.first, sunPoint.second) < r * .14f) {
                    dragMode = DragMode.SUN_TIME
                    dragStartX = event.x
                    dragStartY = event.y
                    dragStarted = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (state == ViewState.GEOCENTRIC && radiusFromCenter in r * .53f..r * .75f) {
                    val touchAngle = Math.toDegrees(atan2((event.y - cy).toDouble(), (event.x - cx).toDouble()))
                    val selected = TimeZoneDial.nearestSpoke(TimeZoneDial.spokes(selectedInstant, north), touchAngle)
                    selectedTimeZoneOffsetMinutes = selected.offsetHours * 60
                    selectedTimeZoneIsLocal = false
                    invalidate()
                    performClick()
                    return true
                }
                if (state == ViewState.GEOCENTRIC && radiusFromCenter < r * .48f) {
                    switchToState(ViewState.HELIOCENTRIC); return true
                }
                if (state == ViewState.GALACTIC) { switchToState(ViewState.HELIOCENTRIC); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    DragMode.YEAR -> {
                        if (dragStarted || distance(event.x, event.y, dragStartX, dragStartY) >= 8f * density) {
                            dragStarted = true
                            realtime = false
                            updateYearDrag(event.x, event.y)
                        }
                    }
                    DragMode.MOON -> {
                        if (dragStarted || distance(event.x, event.y, dragStartX, dragStartY) >= 8f * density) {
                            dragStarted = true
                            realtime = false
                            updateMoonDrag(event.x, event.y)
                        }
                    }
                    DragMode.SUN_TIME -> {
                        if (dragStarted || distance(event.x, event.y, dragStartX, dragStartY) >= 8f * density) {
                            dragStarted = true
                            realtime = false
                            updateGalacticSunDrag(event.x, event.y)
                        }
                    }
                    DragMode.EVENT -> updateEventInspection(event.x, event.y)
                    DragMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                finishInteraction()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> finishInteraction()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateYearDrag(x: Float, y: Float) {
        val (cx, cy, _) = geometry()
        val canvasAngle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        val fraction = DialGeometry.yearFractionFromAngle(canvasAngle, north)
        // Like Unity's angle search from the last date, keep scrubbing continuous across New Year
        // instead of wrapping back to the start of the displayed year.
        val current = selectedInstant
        selectedInstant = (displayedYear - 1..displayedYear + 1)
            .map { Astronomy.instantAtYearFraction(it, fraction, zone) }
            .minBy { kotlin.math.abs(Duration.between(current, it).seconds) }
        invalidate()
    }

    private fun updateMoonDrag(x: Float, y: Float) {
        val (cx, cy, _) = geometry()
        val touchAngle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        val targetPhase = DialGeometry.phaseFromMoonAngle(touchAngle, north)
        val currentPhase = Astronomy.moonPhaseDegrees(selectedInstant)
        val difference = Astronomy.normalizeSignedDegrees(targetPhase - currentPhase)
        selectedInstant = selectedInstant.plusSeconds((difference / 360.0 * Astronomy.SYNODIC_MONTH_DAYS * 86_400).toLong())
        invalidate()
    }

    private fun updateGalacticSunDrag(x: Float, y: Float) {
        val (cx, cy, r) = geometry()
        val frame = galacticFrame(r)
        val axisDistance = (x - cx) * frame.downX + (y - cy) * frame.downY
        val continuousYear = galacticAnchorYear + .5 + axisDistance / frame.yearPitch
        val clamped = continuousYear.coerceIn(galacticAnchorYear - .999, galacticAnchorYear + 1.999)
        val year = floor(clamped).toInt()
        val fraction = clamped - year
        selectedInstant = Astronomy.instantAtYearFraction(year, fraction, zone)
        invalidate()
    }

    fun resetNow() {
        selectedInstant = Instant.now()
        galacticAnchorYear = displayedYear
        transitionFrom = null
        transitionStartedAt = 0L
        dragMode = DragMode.NONE
        dragStarted = false
        inspectedEvent = null
        inspectedCandidates = emptyList()
        val (cx, cy, r) = geometry()
        val earthAngle = annualAngle(Astronomy.civilYearFraction(selectedInstant.atZone(zone)))
        earthPoint = point(cx, cy, r * DialGeometry.EARTH_ORBIT, earthAngle)
        transitionEarthPoint = earthPoint
        selectedTimeZoneOffsetMinutes = TimeZoneDial.localOffsetMinutes(selectedInstant, zone)
        selectedTimeZoneIsLocal = true
        realtime = true
        onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet())
        invalidate()
    }

    private fun drawRadialLine(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float, angleDegrees: Double, paint: Paint) {
        val a = Math.toRadians(angleDegrees)
        canvas.drawLine(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner,
            cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer, paint)
    }

    private fun drawDialTriangle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        height: Float,
        angleDegrees: Double,
        halfBase: Float,
        color: Int,
    ) {
        val tip = point(cx, cy, height, angleDegrees)
        val perpendicular = Math.toRadians(angleDegrees + 90.0)
        val dx = cos(perpendicular).toFloat() * halfBase
        val dy = sin(perpendicular).toFloat() * halfBase
        val triangle = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawPath(Path().apply {
            moveTo(cx - dx, cy - dy)
            lineTo(tip.first, tip.second)
            lineTo(cx + dx, cy + dy)
            close()
        }, triangle)
    }

    /** A dial hand whose base begins outside a central body rather than showing through it. */
    private fun drawAnnularPointer(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        innerRadius: Float,
        outerRadius: Float,
        angleDegrees: Double,
        halfBase: Float,
        color: Int,
    ) {
        if (outerRadius <= innerRadius) return
        val base = point(cx, cy, innerRadius, angleDegrees)
        val tip = point(cx, cy, outerRadius, angleDegrees)
        val perpendicular = Math.toRadians(angleDegrees + 90.0)
        val dx = cos(perpendicular).toFloat() * halfBase
        val dy = sin(perpendicular).toFloat() * halfBase
        val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawPath(Path().apply {
            moveTo(base.first - dx, base.second - dy)
            lineTo(tip.first, tip.second)
            lineTo(base.first + dx, base.second + dy)
            close()
        }, pointer)
    }

    private fun drawRotatedText(canvas: Canvas, value: String, cx: Float, cy: Float, radius: Float, angleDegrees: Double, paint: Paint, upright: Boolean) {
        val p = point(cx, cy, radius, angleDegrees)
        canvas.save()
        canvas.rotate((angleDegrees + 90.0).toFloat(), p.first, p.second)
        // Flip by where the label ends up on screen, including any camera rotation around it.
        val screenAngle = Astronomy.normalizeDegrees(angleDegrees + textScreenRotation)
        if (upright && screenAngle > 0 && screenAngle < 180) canvas.rotate(180f, p.first, p.second)
        canvas.drawText(value, p.first, p.second - (paint.ascent() + paint.descent()) / 2f, paint)
        canvas.restore()
    }

    private fun point(cx: Float, cy: Float, radius: Float, angleDegrees: Double): Pair<Float, Float> {
        val a = Math.toRadians(angleDegrees)
        return Pair(cx + cos(a).toFloat() * radius, cy + sin(a).toFloat() * radius)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

    private fun blendColor(start: Int, end: Int, progress: Float): Int = Color.rgb(
        lerp(Color.red(start).toFloat(), Color.red(end).toFloat(), progress).toInt(),
        lerp(Color.green(start).toFloat(), Color.green(end).toFloat(), progress).toInt(),
        lerp(Color.blue(start).toFloat(), Color.blue(end).toFloat(), progress).toInt(),
    )

    private fun zodiacAngle(longitudeDegrees: Double): Double = eclipticAngle(longitudeDegrees)

    private fun annualAngle(fraction: Double): Double = DialGeometry.annualAngle(fraction, north)
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float = kotlin.math.hypot(x1 - x2, y1 - y2)
    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val TRANSITION_DURATION_MS = 1_000L
    }
}
