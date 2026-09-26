package com.metavirtuoso.sundial.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
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
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.metavirtuoso.sundial.R
import com.metavirtuoso.sundial.astronomy.Astronomy
import com.metavirtuoso.sundial.astronomy.Zodiac
import com.metavirtuoso.sundial.calendar.CalendarOccurrence
import com.metavirtuoso.sundial.calendar.CalendarIntervals
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SundialView(context: Context) : View(context) {
    enum class ViewState { HELIOCENTRIC, GEOCENTRIC, GALACTIC }

    // Named apart from TextPaint.density (always 1), which shadows it inside TextPaint.apply blocks.
    private val density = resources.displayMetrics.density
    private val screenDensity get() = density
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = resources.getFont(R.font.franklin_condensed)
    }
    private val dimText = Paint(text).apply { color = Color.argb(150, 255, 255, 255) }
    private val labelFont = resources.getFont(R.font.franklin_condensed)
    /** Curved event titles; drawTextOnPath lays text from the path start, so it is left aligned. */
    private val arcLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = labelFont }
    private val arcPath = Path()
    private val arcBounds = RectF()
    private val polygon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val polygonPath = Path()
    private val sunRay = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val constellationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val constellationPath = Path()
    private val atmosphere = Paint()
    private var atmosphereKey: Triple<Int, Int, CelestialStyle>? = null
    private val galacticMatrix = Matrix()
    private val earthTexture = BitmapFactory.decodeResource(resources, R.drawable.earth_texture)
    private val earthRenderer = EarthSphereRenderer(earthTexture)
    private val earthGlobeBounds = RectF()
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
    /**
     * Distant stars on a unit disc around the dial centre rather than on the screen rectangle, so
     * the sky still fills the phone when the Earth camera rolls and zooms through it.
     */
    private val ambientStars = Random(0x51A7D1A1).run {
        List(170) { index ->
            val radius = sqrt(nextFloat())
            val angle = nextFloat() * 2f * PI.toFloat()
            AmbientStar(
                xFraction = radius * cos(angle),
                yFraction = radius * sin(angle),
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
    private var dragStartYear = 0.0
    private var galacticTrails: GalacticTrails? = null
    private var inspectedEvent: CalendarOccurrence? = null
    private var inspectedCandidates: List<CalendarOccurrence> = emptyList()
    private var inspectedCandidateIndex = 0
    private var eventCycleAnchorX = 0f
    private var eventCycleAnchorY = 0f
    var onCalendarSelectionChanged: ((Set<Long>) -> Unit)? = null
    var onControlsChanged: (() -> Unit)? = null
    /** Tapping a horoscope card opens the report sheet for the AI-written reading. */
    var onHoroscopeTapped: (() -> Unit)? = null
    private val horoscopeCards = mutableListOf<RectF>()

    val displayedYear: Int get() = selectedInstant.atZone(zone).year
    val isClockVisible: Boolean get() = showClock
    val isGalacticVisible: Boolean get() = state == ViewState.GALACTIC
    val isSouthernHemisphere: Boolean get() = !north
    internal val selectedInstantForTest: Instant get() = selectedInstant
    internal val earthPointForTest: Pair<Float, Float> get() = earthPoint
    internal val sunPointForTest: Pair<Float, Float> get() = sunPoint
    internal val inspectedEventTitleForTest: String? get() = inspectedEvent?.title
    /** True while drawing on the dial face: Brass Watch engraves it in dark ink, everything else is light. */
    private var onFace = true
    private val instrumentColor: Int
        get() = if (onFace) backgroundStyle.instrumentColor else backgroundStyle.chromeColor
    private val brass: Boolean get() = backgroundStyle.brassFace
    private val symbols = PlanetSymbols()

    private enum class DragMode { NONE, YEAR, MOON, GALAXY, EVENT }

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
        state = if (value) ViewState.GALACTIC else ViewState.HELIOCENTRIC
        transitionFrom = null
        onControlsChanged?.invoke(); invalidate()
    }
    fun setBackgroundStyle(value: CelestialStyle) {
        backgroundStyle = value
        CelestialStylePreferences.set(context, value)
        invalidate()
    }
    /** Freezes the instrument in one moment, style and view for screenshots; nothing is saved. */
    internal fun freezeForCapture(instant: Instant, captureState: ViewState, style: CelestialStyle) {
        pauseClock()
        backgroundStyle = style
        state = captureState
        transitionFrom = null
        selectedInstant = instant
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
        useInk(face = false)
        // Freeze astronomy while a camera flight is active so both views target the same Earth.
        if (running && realtime && transitionFrom == null) selectedInstant = Instant.now()
        if (displayedYear != lastReportedCalendarYear && selectedCalendarIds.isNotEmpty()) {
            lastReportedCalendarYear = displayedYear
            onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet())
        }
        if (transitionFrom != null &&
            SystemClock.uptimeMillis() - transitionStartedAt >= TRANSITION_DURATION_MS) transitionFrom = null
        val from = transitionFrom
        val progress = if (from == null) 1f else smoothStep(
            ((SystemClock.uptimeMillis() - transitionStartedAt) / TRANSITION_DURATION_MS.toFloat()).coerceIn(0f, 1f),
        )
        drawBackground(canvas, from, progress)
        if (from != null) {
            useInk(face = true)
            drawTransition(canvas, from, state, progress)
            postInvalidateOnAnimation()
        } else {
            drawState(canvas, state)
        }
        useInk(face = false)
        if (wallpaperMode) {
            if (zodiacProfile.enabled) horoscopeText?.let { drawHoroscopeCard(canvas, it, forWallpaper = true) }
        } else {
            drawChrome(canvas)
            inspectedEvent?.let { drawEventInspectionOverlay(canvas, it) }
        }
        if (!wallpaperMode) updateSpokenDescription()
        if (running && !wallpaperMode) postInvalidateDelayed(if (showClock) 250L else 1_000L)
    }

    /**
     * The instrument is drawn, not built from views, so TalkBack reads this summary instead. It
     * changes at most once a minute, so it does not flood accessibility services.
     */
    private fun updateSpokenDescription() {
        val local = selectedInstant.atZone(zone)
        val moment = local.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a"))
        val description = when (state) {
            ViewState.HELIOCENTRIC -> "Sundial solar view, $moment. The year dial circles the Sun; " +
                "tap the Sun to fly to the Earth view, or drag the Earth to move through the year."
            ViewState.GEOCENTRIC -> "Sundial Earth view, $moment. The day and lunar dials circle the Earth; " +
                "tap the Earth to return to the solar view, or drag the Moon to move through the month."
            ViewState.GALACTIC -> "Sundial galactic view, $moment. The Sun carries the planets through space; " +
                "drag to move through the years, or tap to return."
        }
        if (contentDescription != description) contentDescription = description
    }

    private fun useInk(face: Boolean) {
        onFace = face
        white.color = instrumentColor
        text.color = instrumentColor
        dimText.color = withAlpha(instrumentColor, 150)
    }

    private fun drawState(canvas: Canvas, requestedState: ViewState) {
        // The galactic view has no dial face under it.
        useInk(face = requestedState != ViewState.GALACTIC)
        when (requestedState) {
            ViewState.HELIOCENTRIC -> drawHeliocentric(canvas)
            ViewState.GEOCENTRIC -> drawGeocentric(canvas)
            ViewState.GALACTIC -> drawGalactic(canvas)
        }
    }

    private fun drawBackground(canvas: Canvas, from: ViewState?, progress: Float) {
        val (cx, cy, _) = geometry()
        val key = Triple(width, height, backgroundStyle)
        if (atmosphereKey != key) {
            atmosphereKey = key
            atmosphere.shader = RadialGradient(
                cx,
                cy,
                hypot(width.toFloat(), height.toFloat()) * .72f,
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
        val (rotation, scale) = skyCamera(from, progress)
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.scale(scale, scale, cx, cy)
        drawAmbientStars(canvas, cx, cy)
        canvas.restore()
    }

    /**
     * Camera roll and zoom seen in the distant sky. The stars are fixed in the solar system, so
     * the Earth camera's roll turns them too, while a fraction of its zoom gives the flight parallax.
     */
    private fun skyCamera(from: ViewState?, progress: Float): Pair<Float, Float> {
        val earthZoom = DialGeometry.EARTH_CAMERA_ZOOM.pow(DialGeometry.SKY_PARALLAX)
        return when {
            from != null && isEarthFlight(from, state) -> {
                val flight = if (state == ViewState.GEOCENTRIC) progress else 1f - progress
                Pair(transitionCameraRotation * flight, DialGeometry.earthFlightFrame(flight).skyScale)
            }
            from == null && state == ViewState.GEOCENTRIC -> Pair(earthCameraRotation(), earthZoom)
            else -> Pair(0f, 1f)
        }
    }

    private fun drawAmbientStars(canvas: Canvas, cx: Float, cy: Float) {
        constellationPaint.color = withAlpha(blendColor(instrumentColor, backgroundStyle.accentColor, .35f), 30)
        constellationPaint.strokeWidth = .55f * density
        if (constellationPaint.pathEffect == null) {
            constellationPaint.pathEffect = DashPathEffect(floatArrayOf(2.5f * density, 5f * density), 0f)
        }
        constellationPaths.forEach { points ->
            constellationPath.rewind()
            points.forEachIndexed { index, point ->
                val x = point.first * width
                val y = point.second * height
                if (index == 0) constellationPath.moveTo(x, y) else constellationPath.lineTo(x, y)
            }
            canvas.drawPath(constellationPath, constellationPaint)
        }

        skyPaint.style = Paint.Style.FILL
        dustLaneStars.forEach { point ->
            skyPaint.color = withAlpha(backgroundStyle.accentColor, point.alpha)
            canvas.drawCircle(point.xFraction * width, point.yFraction * height, point.radiusDp * density, skyPaint)
        }

        // Reach every screen corner from the dial centre, whatever the camera's roll.
        val reach = hypot(maxOf(cx, width - cx), maxOf(cy, height - cy))
        ambientStars.forEach { point ->
            val x = cx + point.xFraction * reach
            val y = cy + point.yFraction * reach
            val radius = point.radiusDp * density
            skyPaint.color = withAlpha(instrumentColor, point.alpha)
            canvas.drawCircle(x, y, radius, skyPaint)
            if (point.flare) {
                skyPaint.strokeWidth = maxOf(.45f * density, radius * .42f)
                canvas.drawLine(x - radius * 2.4f, y, x + radius * 2.4f, y, skyPaint)
                canvas.drawLine(x, y - radius * 2.4f, x, y + radius * 2.4f, skyPaint)
            }
        }

        constellationPaths.flatten().forEachIndexed { index, point ->
            skyPaint.color = withAlpha(instrumentColor, if (index % 4 == 0) 112 else 72)
            canvas.drawCircle(
                point.first * width,
                point.second * height,
                (if (index % 4 == 0) 1.15f else .72f) * density,
                skyPaint,
            )
        }
    }

    private fun isEarthFlight(from: ViewState, to: ViewState): Boolean =
        (from == ViewState.HELIOCENTRIC && to == ViewState.GEOCENTRIC) ||
            (from == ViewState.GEOCENTRIC && to == ViewState.HELIOCENTRIC)

    /** Roll of the Earth camera: it keeps the Sun straight above the Earth. */
    private fun earthCameraRotation(): Float = Astronomy.normalizeSignedDegrees(90.0 - currentEarthAngle()).toFloat()

    private fun drawTransition(canvas: Canvas, from: ViewState, to: ViewState, progress: Float) {
        val (cx, cy, _) = geometry()
        if (isEarthFlight(from, to)) {
            val flightProgress = if (to == ViewState.GEOCENTRIC) progress else 1f - progress
            drawEarthCameraFlight(canvas, cx, cy, flightProgress)
        } else {
            drawTransformedState(canvas, from, cx, cy, 1f, 0f, 1f - progress)
            drawTransformedState(canvas, to, cx, cy, 1f, 0f, progress)
        }
    }

    /**
     * One rigid camera move from the Sun-centred dial to the Earth, as in Unity: the camera rolls,
     * zooms and follows the Earth while the Earth system swells from the planet glyph into the
     * Earth instrument. Everything, the Earth included, shares the camera's roll, so nothing turns
     * against the dial on the way in.
     */
    private fun drawEarthCameraFlight(canvas: Canvas, cx: Float, cy: Float, progress: Float) {
        val targetX = lerp(transitionEarthPoint.first, cx, progress)
        val targetY = lerp(transitionEarthPoint.second, cy, progress)
        val frame = DialGeometry.earthFlightFrame(progress)
        val cameraRotation = transitionCameraRotation * progress
        // The Earth instrument is drawn Sun-up; until the camera has finished rolling it is still
        // turned by the roll that remains, exactly like the dial around it.
        val earthSystemRotation = cameraRotation - transitionCameraRotation

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
        drawDialFace(canvas, cx, cy, r)
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
            canvas.rotate(earthSystemRotation)
            canvas.scale(frame.earthSystemScale, frame.earthSystemScale)
            canvas.translate(-cx, -cy)
            withTextScreenRotation(earthSystemRotation.toDouble()) {
                drawGeocentric(canvas, includeBackdrop = false, includeSun = false)
            }
            canvas.restoreToCount(checkpoint)
        }

        // One Sun for the whole flight: it follows the camera from the dial centre to the top of
        // the Earth view instead of cross-fading between two differently scaled copies.
        val sunOffsetX = (cx - transitionEarthPoint.first) * frame.cameraScale
        val sunOffsetY = (cy - transitionEarthPoint.second) * frame.cameraScale
        val cameraRadians = Math.toRadians(cameraRotation.toDouble())
        drawSun(
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
        if (isEarthFlight(state, newState)) {
            val (cx, cy, r) = geometry()
            transitionEarthPoint = point(cx, cy, r * DialGeometry.EARTH_ORBIT, currentEarthAngle())
            transitionCameraRotation = earthCameraRotation()
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
        drawDialFace(canvas, cx, cy, r)
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
        if (includeSun) drawSun(canvas, cx, cy, r * 0.052f)
        text.textSize = r * 0.086f
        canvas.drawText("S U N : D I A L", cx, cy - r * 0.56f, text)
    }

    private fun currentEarthAngle(): Double = annualAngle(Astronomy.civilYearFraction(selectedInstant.atZone(zone)))

    /** Heliocentric longitude → canvas angle, sharing the annual dial's frame. */
    private fun eclipticAngle(longitudeDegrees: Double): Double = DialGeometry.eclipticAngle(longitudeDegrees, north)

    private data class SeasonShaderKey(
        val year: Int, val active: Zodiac.Season, val north: Boolean,
        val cx: Float, val cy: Float, val style: CelestialStyle,
    )
    private var seasonShaderKey: SeasonShaderKey? = null
    private var seasonWash: Shader? = null
    private var seasonBand: Shader? = null
    private val seasonWashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seasonBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /**
     * Sweep gradients for the season wash and the brass season band. Around each solstice and
     * equinox the outgoing season's colour fades into the next rather than stopping at a line.
     */
    private fun updateSeasonShaders(cx: Float, cy: Float) {
        val date = selectedInstant.atZone(zone).toLocalDate()
        val active = Zodiac.seasonFor(date, north)
        val key = SeasonShaderKey(date.year, active, north, cx, cy, backgroundStyle)
        if (key == seasonShaderKey) return
        seasonShaderKey = key
        val starts = SeasonBands.starts(date.year)
        val days = Astronomy.daysInYear(date.year)
        val metals = intArrayOf(0xFFDCA247.toInt(), 0xFFB36A32.toInt(), 0xFF8D553B.toInt(), 0xFF9BB8C6.toInt())
        val bandScale = if (brass) .55f else 1f
        fun wash(season: Zodiac.Season) =
            withAlpha(backgroundStyle.accentColor, if (displayedSeason(season) == active) 28 else 7)
        fun band(season: Zodiac.Season) = withAlpha(metals[season.ordinal],
            ((if (displayedSeason(season) == active) 215 else 72) * bandScale).toInt())
        val samples = 120
        val positions = FloatArray(samples + 1) { it / samples.toFloat() }
        val washColors = IntArray(samples + 1)
        val bandColors = IntArray(samples + 1)
        for (i in 0..samples) {
            // Sweep gradients start at 3 o'clock and run clockwise, like canvas angles.
            val mix = SeasonBands.mixAt(DialGeometry.yearFractionFromAngle(360.0 * i / samples, north), starts, days)
            washColors[i] = blendArgb(wash(mix.from), wash(mix.to), mix.amount.toFloat())
            bandColors[i] = blendArgb(band(mix.from), band(mix.to), mix.amount.toFloat())
        }
        seasonWash = android.graphics.SweepGradient(cx, cy, washColors, positions)
        seasonBand = android.graphics.SweepGradient(cx, cy, bandColors, positions)
    }

    private fun drawSeasonShading(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        updateSeasonShaders(cx, cy)
        seasonWashPaint.shader = seasonWash
        canvas.drawCircle(cx, cy, r * .965f, seasonWashPaint)
    }

    private fun drawReverseSeasonRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        updateSeasonShaders(cx, cy)
        seasonBandPaint.shader = seasonBand
        seasonBandPaint.strokeWidth = r * .058f
        canvas.drawCircle(cx, cy, r * 1.025f, seasonBandPaint)

        val localDate = selectedInstant.atZone(zone).toLocalDate()
        val active = Zodiac.seasonFor(localDate, north)
        val year = localDate.year
        val days = Astronomy.daysInYear(year).toDouble()
        fun fraction(month: Int, day: Int) = (LocalDate.of(year, month, day).dayOfYear - 1).toDouble() / days
        val labels = listOf(
            Zodiac.Season.WINTER to fraction(2, 1),
            Zodiac.Season.SPRING to fraction(5, 6),
            Zodiac.Season.SUMMER to fraction(8, 7),
            Zodiac.Season.FALL to fraction(11, 6),
        )
        val labelPaint = Paint(text).apply {
            textSize = r * .027f
            letterSpacing = .14f
        }
        labels.forEach { (season, labelAt) ->
            val displayed = displayedSeason(season)
            labelPaint.color = withAlpha(instrumentColor, if (displayed == active) 245 else 105)
            drawRotatedText(canvas, displayed.name, cx, cy, r * 1.025f, annualAngle(labelAt), labelPaint, true)
        }
        white.color = withAlpha(if (brass) instrumentColor else backgroundStyle.accentColor, 125)
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
                color = if (brass) withAlpha(instrumentColor, if (sign == activeSign) 46 else if (sign.ordinal % 2 == 0) 14 else 0)
                    else withAlpha(if (sign.ordinal % 2 == 0) 0xFFD5A24F.toInt() else 0xFF9B6C3A.toInt(),
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
                sign == natalSign -> if (brass) BRASS_ENAMEL_RED else 0xFFFFD889.toInt()
                sign == activeSign -> instrumentColor
                else -> withAlpha(if (brass) instrumentColor else 0xFFFFE7B0.toInt(), 190)
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
        white.color = withAlpha(if (brass) instrumentColor else 0xFFFFD58A.toInt(), 195)
        canvas.drawCircle(cx, cy, outer, white)
    }

    private fun drawPlanetZodiacHands(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val earth = point(cx, cy, r * DialGeometry.EARTH_ORBIT, currentEarthAngle())
        val bodies = listOf(
            Triple(Astronomy.Body.MERCURY, DialGeometry.MERCURY_ORBIT, 0xFF8EA5B8.toInt()),
            Triple(Astronomy.Body.VENUS, DialGeometry.VENUS_ORBIT, 0xFFFFCE7A.toInt()),
            Triple(Astronomy.Body.MARS, DialGeometry.MARS_ORBIT, 0xFFE8735C.toInt()),
        )
        bodies.forEach { (body, orbitRatio, planetColor) ->
            val color = if (brass) instrumentColor else planetColor
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
        drawCelestialZodiacHand(canvas, earth, Pair(cx, cy), sunLongitude, r,
            if (brass) instrumentColor else 0xFFFFD17A.toInt(), marker = true)
        drawCelestialZodiacHand(canvas, earth, subdialMoonPoint(earth.first, earth.second, r, cx, cy),
            Astronomy.moonLongitudeDegrees(selectedInstant), r,
            if (brass) instrumentColor else 0xFFFFEAB5.toInt(), marker = false)
    }

    private fun drawCelestialZodiacHand(
        canvas: Canvas,
        earth: Pair<Float, Float>,
        body: Pair<Float, Float>,
        longitude: Double,
        r: Float,
        color: Int,
        marker: Boolean,
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
        if (marker) {
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

    private data class FaceKey(val cx: Float, val cy: Float, val r: Float)
    private var faceKey: FaceKey? = null
    private val faceShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bezelShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Brass Watch aesthetic: the annual dial engraved into a polished brass face inside a turned
     * bezel. It is drawn in the Sun's frame, so the Earth camera flies across the same face.
     */
    private fun drawDialFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (!brass) return
        val key = FaceKey(cx, cy, r)
        if (key != faceKey) {
            faceKey = key
            faceShadow.shader = RadialGradient(cx + r * .03f, cy + r * .06f, r * 1.2f,
                intArrayOf(0xB0000000.toInt(), 0x70000000, Color.TRANSPARENT), floatArrayOf(0f, .88f, 1f),
                Shader.TileMode.CLAMP)
            facePaint.shader = RadialGradient(cx - r * .38f, cy - r * .46f, r * 1.95f,
                intArrayOf(0xFFFFF6D6.toInt(), 0xFFF2D98F.toInt(), 0xFFDDB764.toInt(), 0xFFBE9240.toInt(), 0xFF9A6F28.toInt()),
                floatArrayOf(0f, .2f, .46f, .76f, 1f), Shader.TileMode.CLAMP)
            bezelPaint.shader = android.graphics.SweepGradient(cx, cy,
                intArrayOf(0xFFF7E3A2.toInt(), 0xFFA5762B.toInt(), 0xFFFFF2C6.toInt(), 0xFF8C6220.toInt(),
                    0xFFF1D68B.toInt(), 0xFFAE7F32.toInt(), 0xFFF7E3A2.toInt()), null)
            bezelShade.shader = RadialGradient(cx, cy, r * 1.07f,
                intArrayOf(Color.TRANSPARENT, 0x50FFFFFF, Color.TRANSPARENT, 0x70000000),
                floatArrayOf(.93f, .965f, .985f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r * 1.2f, faceShadow)
        bezelPaint.strokeWidth = r * .07f
        canvas.drawCircle(cx, cy, r * 1.032f, bezelPaint)
        bezelShade.strokeWidth = r * .07f
        canvas.drawCircle(cx, cy, r * 1.032f, bezelShade)
        canvas.drawCircle(cx, cy, r, facePaint)
        // Faint turned rings in the metal, and a milled edge where face meets bezel.
        white.color = withAlpha(instrumentColor, 10)
        white.strokeWidth = maxOf(.5f, r * .0015f)
        var ring = r * .1f
        while (ring < r * .99f) {
            canvas.drawCircle(cx, cy, ring, white)
            ring += r * .045f
        }
        white.color = withAlpha(instrumentColor, 95)
        white.strokeWidth = maxOf(.5f, r * .0022f)
        for (i in 0 until 240) drawRadialLine(canvas, cx, cy, r * 1.002f, r * 1.012f, i * 1.5, white)
        white.color = withAlpha(instrumentColor, 150)
        white.strokeWidth = maxOf(.6f, r * .003f)
        canvas.drawCircle(cx, cy, r * 1.066f, white)
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
            Astronomy.Body.EARTH to if (brass) instrumentColor else Color.WHITE,
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
            if (isEarth) {
                drawEarthSubdial(canvas, point.first, point.second, r, cx, cy)
                earthPoint = point
            } else {
                drawPlanetMarker(canvas, body, point.first, point.second, r, cx, cy)
            }
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

    /**
     * Astronomy mode shows each planet as a small world; astrology mode engraves the classical
     * symbols instead (☿ ♀ ⊕ ♂), like the hands of an astrological watch.
     */
    private fun drawPlanetMarker(
        canvas: Canvas,
        body: Astronomy.Body,
        x: Float,
        y: Float,
        r: Float,
        sunX: Float,
        sunY: Float,
    ) {
        if (!zodiacProfile.enabled) {
            drawPlanetGlyph(canvas, body, x, y, r, sunX, sunY)
            return
        }
        val tint = when {
            brass -> instrumentColor
            body == Astronomy.Body.MERCURY -> 0xFFD3DEE6.toInt()
            body == Astronomy.Body.VENUS -> 0xFFFFE2A6.toInt()
            body == Astronomy.Body.MARS -> 0xFFFF9A7E.toInt()
            else -> 0xFFB5E6FF.toInt()
        }
        // A pearl where the hand ends, as on the watch, and the symbol riding just beyond it.
        fill.color = if (brass) 0xFFF4F1E8.toInt() else withAlpha(tint, 235)
        canvas.drawCircle(x, y, r * .011f, fill)
        symbolPaint.color = tint
        symbolPaint.setShadowLayer(if (brass) 0f else r * .01f, 0f, 0f, if (brass) 0 else 0xB0000000.toInt())
        symbols.draw(canvas, body, x, y - r * .056f, r * .036f, symbolPaint)
    }

    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Where the Moon sits on the Earth's subdial: the Earth view's lunar hand, turned into this view. */
    private fun subdialMoonPoint(x: Float, y: Float, r: Float, sunX: Float, sunY: Float): Pair<Float, Float> {
        val turn = Math.toDegrees(atan2((sunY - y).toDouble(), (sunX - x).toDouble())) + 90.0
        val angle = DialGeometry.moonAngle(Astronomy.moonPhaseDegrees(selectedInstant), north) + turn
        return point(x, y, r * DialGeometry.SUBDIAL_MOON_TRACK, angle)
    }

    /**
     * The Earth's own small dial in the solar view, like the silver gear on an astrological watch:
     * a 24-hour sprocket ring with noon toward the Sun and an enlarged Moon on its lunar track.
     * It is the Earth view in miniature, so the camera flight simply grows it into that view.
     */
    private fun drawEarthSubdial(canvas: Canvas, x: Float, y: Float, r: Float, sunX: Float, sunY: Float) {
        val turn = Math.toDegrees(atan2((sunY - y).toDouble(), (sunX - x).toDouble())) + 90.0
        val gearR = r * DialGeometry.SUBDIAL_GEAR
        val trackR = r * DialGeometry.SUBDIAL_MOON_TRACK
        val metal = when {
            brass -> 0xFFF1F0EA.toInt()
            zodiacProfile.enabled -> 0xFFE3E6EA.toInt()
            else -> instrumentColor
        }
        if (brass) {
            // Polished steel gear set into the brass: a dark seat, then the bright ring.
            white.color = withAlpha(instrumentColor, 120)
            white.strokeWidth = r * .02f
            canvas.drawCircle(x, y, gearR + r * .004f, white)
        }
        white.color = withAlpha(instrumentColor, if (brass) 120 else 110)
        white.strokeWidth = r * .0026f
        canvas.drawCircle(x, y, trackR, white)
        white.color = metal
        white.strokeWidth = r * .006f
        canvas.drawCircle(x, y, gearR, white)
        polygon.color = metal
        for (hour in 0 until 24) {
            drawSprocketTooth(canvas, x, y, gearR, gearR + r * if (hour % 6 == 0) .017f else .011f,
                hourAngle(hour.toDouble()) + turn, r * .0048f, r * .0008f, polygon)
        }

        val moon = subdialMoonPoint(x, y, r, sunX, sunY)
        val moonAngle = Math.toDegrees(atan2((moon.second - y).toDouble(), (moon.first - x).toDouble()))
        drawAnnularPointer(canvas, x, y, gearR + r * .012f, trackR, moonAngle, r * .006f, withAlpha(metal, 200))
        if (zodiacProfile.enabled) {
            fill.color = if (brass) instrumentColor else 0xFFFFEAB5.toInt()
            symbols.drawCrescent(canvas, moon.first, moon.second, r * .05f,
                Math.toDegrees(atan2((sunY - moon.second).toDouble(), (sunX - moon.first).toDouble())), fill)
        } else {
            drawMoonGlyph(canvas, moon.first, moon.second, r * .027f, sunX, sunY)
        }

        if (zodiacProfile.enabled) {
            // ⊕ engraved in the middle of the gear, as on the watch.
            fill.color = if (brass) 0xFFE9E7DF.toInt() else withAlpha(0xFF0D1B2A.toInt(), 200)
            canvas.drawCircle(x, y, gearR * .82f, fill)
            symbolPaint.color = if (brass) instrumentColor else metal
            symbolPaint.clearShadowLayer()
            symbols.draw(canvas, Astronomy.Body.EARTH, x, y, gearR * 1.25f, symbolPaint)
        } else {
            drawEarthSeal(canvas, x, y, r * DialGeometry.HELIOCENTRIC_EARTH_RADIUS, sunX, sunY, ornate = false)
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
        if (brass) {
            // Set into metal rather than glowing in space: a small cast shadow instead of an aura.
            fill.color = 0x55000000
            canvas.drawCircle(x + radius * .18f, y + radius * .24f, radius * 1.08f, fill)
        } else {
            val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(x, y, radius * 3.2f,
                    intArrayOf(withAlpha(color, 125), withAlpha(color, 45), Color.TRANSPARENT),
                    floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(x, y, radius * 3.2f, aura)
        }
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
        val sphereRadius = r * DialGeometry.EARTH_RADIUS
        val sunY = cy - r * DialGeometry.GEOCENTRIC_SUN_DISTANCE

        if (includeBackdrop) {
            // Unity's Earth camera still saw the annual sprocket, season cross and Earth spike
            // arcing around the Earth; keep them in the same place the camera flight leaves them.
            val roll = earthCameraRotation()
            canvas.save()
            canvas.translate(cx, sunY)
            canvas.rotate(roll)
            canvas.scale(DialGeometry.EARTH_CAMERA_ZOOM, DialGeometry.EARTH_CAMERA_ZOOM)
            canvas.translate(-cx, -cy)
            drawDialFace(canvas, cx, cy, r)
            withTextScreenRotation(roll.toDouble()) { drawAnnualBackdrop(canvas, cx, cy, r) }
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
        if (includeSun) drawSun(canvas, cx, sunY, r * .058f)

        drawCalendarDayEvents(canvas, cx, cy, hourR)
        drawLocalWheel(canvas, cx, cy, r)
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
        polygon.color = instrumentColor
        for (quarter in 0 until 96) {
            val major = quarter % 4 == 0
            drawSprocketTooth(
                canvas, cx, cy, radius, radius - if (major) r * .058f else r * .025f,
                hourAngle(quarter / 4.0),
                baseHalfWidth = if (major) r * .0048f else r * .0032f,
                tipHalfWidth = if (major) r * .0016f else r * .0011f,
                paint = polygon,
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
        polygonPath.rewind()
        polygonPath.moveTo(base.first - px * baseHalfWidth, base.second - py * baseHalfWidth)
        polygonPath.lineTo(tip.first - px * tipHalfWidth, tip.second - py * tipHalfWidth)
        polygonPath.lineTo(tip.first + px * tipHalfWidth, tip.second + py * tipHalfWidth)
        polygonPath.lineTo(base.first + px * baseHalfWidth, base.second + py * baseHalfWidth)
        polygonPath.close()
        canvas.drawPath(polygonPath, paint)
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

    /**
     * Unity's local wheel around the globe: a thin ring with an outward tooth for every hour. The
     * long tooth points at the selected zone's local time and carries a smaller red tooth inside
     * it; the other teeth fall on the whole-hour zones either side. A translucent strip inside the
     * ring spans the zones that have already reached the new date, from local midnight round to the
     * international date line, with the two weekdays labelled at each boundary.
     */
    private fun drawLocalWheel(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val wheelR = r * DialGeometry.LOCAL_WHEEL
        val direction = if (north) -1f else 1f

        val datelineHours = TimeZoneDial.datelineHours(selectedInstant)
        val stripR = r * (DialGeometry.DATE_STRIP_OUTER + DialGeometry.DATE_STRIP_INNER) / 2f
        white.color = withAlpha(instrumentColor, 77)
        white.strokeWidth = r * (DialGeometry.DATE_STRIP_OUTER - DialGeometry.DATE_STRIP_INNER)
        arcBounds.set(cx - stripR, cy - stripR, cx + stripR, cy + stripR)
        canvas.drawArc(arcBounds, hourAngle(0.0).toFloat(), direction * datelineHours.toFloat() * 15f, false, white)

        val newDate = selectedInstant.atOffset(ZoneOffset.ofHours(12)).toLocalDate()
        val newDay = newDate.dayOfWeek.name.take(3)
        val oldDay = newDate.minusDays(1).dayOfWeek.name.take(3)
        val dayPaint = Paint(text).apply {
            textSize = maxOf(r * .03f, 9f * density)
            color = withAlpha(instrumentColor, 220)
        }
        // Unity sets each weekday 4.2° either side of the boundary it names.
        val split = 4.2 / 15.0
        listOf(0.0 to true, datelineHours to false).forEach { (boundary, newDayAfter) ->
            drawRotatedText(canvas, if (newDayAfter) newDay else oldDay, cx, cy, stripR,
                hourAngle(boundary + split), dayPaint, true)
            drawRotatedText(canvas, if (newDayAfter) oldDay else newDay, cx, cy, stripR,
                hourAngle(boundary - split), dayPaint, true)
        }

        white.color = instrumentColor
        white.strokeWidth = maxOf(.8f * density, r * .006f)
        canvas.drawCircle(cx, cy, wheelR, white)

        val selectedAngle = TimeZoneDial.angleForOffsetMinutes(selectedInstant, selectedTimeZoneOffset(), north)
        val baseHalfWidth = wheelR * Math.toRadians(DialGeometry.LOCAL_WHEEL_TOOTH_HALF_ANGLE).toFloat()
        polygon.color = instrumentColor
        for (hour in 0 until 24) {
            val tooth = if (hour == 0) DialGeometry.LOCAL_WHEEL_BIG_TOOTH else DialGeometry.LOCAL_WHEEL_SMALL_TOOTH
            drawSprocketTooth(canvas, cx, cy, wheelR, wheelR + r * tooth, selectedAngle + hour * 15.0,
                baseHalfWidth, wheelR * .001f, polygon)
        }
        polygon.color = LOCAL_TOOTH_RED
        drawSprocketTooth(canvas, cx, cy, wheelR, wheelR + r * DialGeometry.LOCAL_WHEEL_RED_TOOTH, selectedAngle,
            r * DialGeometry.LOCAL_WHEEL_RED_HALF_BASE, 0f, polygon)
    }

    /** Name and local time of the zone the local wheel is set to, shown above the Earth view. */
    private fun drawSelectedZoneCaption(canvas: Canvas) {
        val offset = selectedTimeZoneOffset()
        val name = if (selectedTimeZoneIsLocal) TimeZoneDial.localName(zone, selectedInstant)
            else TimeZoneDial.commonName(offset.floorDiv(60))
        val local = selectedInstant.atOffset(ZoneOffset.ofTotalSeconds(offset * 60))
        val caption = Paint(text).apply {
            textSize = 15f * density
            letterSpacing = .08f
            color = withAlpha(instrumentColor, 215)
        }
        val label = "${name.uppercase()}  ·  ${local.format(DateTimeFormatter.ofPattern("EEE HH:mm")).uppercase()}"
        if (width > height) {
            // In landscape the Sun sits at the top centre of the Earth view; use the free corner
            // beside the settings button instead.
            caption.textAlign = Paint.Align.LEFT
            canvas.drawText(label, 80f * density, 38f * density, caption)
        } else {
            canvas.drawText(label, width / 2f, (if (showClock) 62f else 36f) * density, caption)
        }
    }

    private fun drawGalactic(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        val g = GalacticGeometry
        // The ribbon runs off every edge of the screen; there is no end to scroll against.
        val reach = hypot(maxOf(cx, width - cx), maxOf(cy, height - cy))
        val nowYear = g.continuousYear(selectedInstant, zone)
        sunPoint = Pair(cx, cy)

        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(instrumentColor, 175)
            strokeWidth = r * .0032f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(cx - g.travelX * reach, cy - g.travelY * reach,
            cx + g.travelX * reach, cy + g.travelY * reach, axis)
        drawGalacticYearTicks(canvas, cx, cy, r, nowYear, reach)
        drawOrthonormalPlane(canvas, cx, cy, r)
        drawGalacticTrails(canvas, cx, cy, r, nowYear, reach)
        drawGalacticEvents(canvas, cx, cy, r, nowYear, reach)
        Astronomy.Body.entries.forEach { body ->
            val (along, side) = g.orbitOffset(
                Astronomy.heliocentricPosition(body, selectedInstant).longitudeDegrees, r * galacticOrbit(body))
            drawPlanetMarker(canvas, body, cx + g.travelX * along + g.sideX * side,
                cy + g.travelY * along + g.sideY * side, r * .82f, cx, cy)
        }
        drawSun(canvas, cx, cy, r * .061f)

        // Unity's Sun travels toward the north ecliptic pole: later years lie ahead of it.
        val arrowTip = minOf(r * 1.13f, reach - r * .12f)
        axis.color = withAlpha(instrumentColor, 210)
        drawDirectionArrow(canvas, cx + g.travelX * arrowTip, cy + g.travelY * arrowTip, r, axis)
        val label = Paint(dimText).apply {
            textSize = maxOf(r * .037f, 12f * density)
            letterSpacing = .17f
            textAlign = Paint.Align.LEFT
        }
        // Written down the axis from behind the arrowhead, on its left so the axis stays clear.
        canvas.save()
        canvas.translate(cx + g.travelX * arrowTip, cy + g.travelY * arrowTip)
        canvas.rotate(Math.toDegrees(atan2(-g.travelY.toDouble(), -g.travelX.toDouble())).toFloat())
        canvas.drawText("DIRECTION OF TRAVEL", r * .13f, r * .03f - label.ascent(), label)
        canvas.restore()
        val yearLabel = Paint(text).apply { textSize = r * .055f }
        canvas.drawText(selectedInstant.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d  yyyy")),
            cx, cy + r * 1.13f, yearLabel)
    }

    /** Unity's galactic sun line: a big tick and year label at each New Year, lighter month ticks. */
    private fun drawGalacticYearTicks(canvas: Canvas, cx: Float, cy: Float, r: Float, nowYear: Double, reach: Float) {
        val g = GalacticGeometry
        val pitch = r * g.YEAR_PITCH
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val yearLabel = Paint(text).apply {
            textSize = r * .05f
            color = withAlpha(instrumentColor, 190)
            textAlign = if (g.sideX < 0) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        val span = ceil(reach / pitch).toInt()
        val first = (floor(nowYear).toInt() - span).coerceAtLeast(g.MIN_YEAR)
        val last = (floor(nowYear).toInt() + span).coerceAtMost(g.MAX_YEAR)
        for (year in first..last) {
            for (month in 1..12) {
                val distance = ((g.monthStart(year, month) - nowYear) * pitch).toFloat()
                if (kotlin.math.abs(distance) > reach) continue
                val x = cx + g.travelX * distance
                val y = cy + g.travelY * distance
                val newYear = month == 1
                val half = if (newYear) r * .045f else r * .014f
                tick.color = withAlpha(instrumentColor, if (newYear) 200 else 120)
                tick.strokeWidth = if (newYear) r * .0045f else r * .0025f
                canvas.drawLine(x - g.sideX * half, y - g.sideY * half, x + g.sideX * half, y + g.sideY * half, tick)
                if (newYear) {
                    canvas.drawText(year.toString(), x + g.sideX * r * .065f,
                        y + g.sideY * r * .065f - (yearLabel.ascent() + yearLabel.descent()) / 2f, yearLabel)
                }
            }
        }
    }

    /** Orbit radius of each planet in the galactic helix, in dial radii. */
    private fun galacticOrbit(body: Astronomy.Body): Float = when (body) {
        Astronomy.Body.MERCURY -> .105f
        Astronomy.Body.VENUS -> .185f
        Astronomy.Body.EARTH -> .285f
        Astronomy.Body.MARS -> .434f
    }

    /**
     * Planet helices in ribbon coordinates (x along travel from [baseYear], y to the side). They
     * only depend on the years in view, so they are built once and slid under the Sun each frame.
     */
    private class GalacticTrails(val baseYear: Int, val endYear: Int, val pitch: Float, val paths: Map<Astronomy.Body, Path>)

    private fun galacticTrails(r: Float, nowYear: Double, reach: Float): GalacticTrails {
        val g = GalacticGeometry
        val pitch = r * g.YEAR_PITCH
        val span = ceil(reach / pitch).toInt() + 1
        val base = (floor(nowYear).toInt() - span).coerceAtLeast(g.MIN_YEAR)
        val end = (floor(nowYear).toInt() + span + 1).coerceAtMost(g.MAX_YEAR + 1)
        galacticTrails?.let { if (it.baseYear == base && it.endYear == end && it.pitch == pitch) return it }

        val paths = Astronomy.Body.entries.associateWith { Path() }
        // Dash the outer helices once here, rather than with a PathEffect on every frame.
        val builders = Astronomy.Body.entries.associateWith { body ->
            if (body == Astronomy.Body.EARTH) DashedPathBuilder(paths.getValue(body), Float.MAX_VALUE, 0f)
            else DashedPathBuilder(paths.getValue(body), r * .012f, r * .014f)
        }
        for (year in base until end) {
            val yearStart = java.time.LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant()
            val yearNanos = Duration.between(yearStart,
                java.time.LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant()).toNanos()
            for (step in 0 until GALACTIC_STEPS_PER_YEAR) {
                val fraction = step.toDouble() / GALACTIC_STEPS_PER_YEAR
                val instant = yearStart.plusNanos((yearNanos * fraction).toLong())
                val along = ((year - base) + fraction).toFloat() * pitch
                Astronomy.Body.entries.forEach { body ->
                    val (depth, side) = g.orbitOffset(
                        Astronomy.heliocentricPosition(body, instant).longitudeDegrees, r * galacticOrbit(body))
                    builders.getValue(body).to(along + depth, side)
                }
            }
        }
        return GalacticTrails(base, end, pitch, paths).also { galacticTrails = it }
    }

    /** Appends a polyline to [path] as dashes of [dash] length separated by [gap]. */
    private class DashedPathBuilder(private val path: Path, private val dash: Float, private val gap: Float) {
        private var started = false
        private var lastX = 0f
        private var lastY = 0f
        private var phase = 0f

        fun to(x: Float, y: Float) {
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                var fromX = lastX
                var fromY = lastY
                var remaining = hypot(x - fromX, y - fromY)
                if (remaining > 0f) {
                    val ux = (x - fromX) / remaining
                    val uy = (y - fromY) / remaining
                    while (remaining > 0f) {
                        val inDash = phase < dash
                        val step = minOf(if (inDash) dash - phase else dash + gap - phase, remaining)
                        fromX += ux * step
                        fromY += uy * step
                        if (inDash) path.lineTo(fromX, fromY) else path.moveTo(fromX, fromY)
                        phase += step
                        if (phase >= dash + gap) phase -= dash + gap
                        remaining -= step
                    }
                }
            }
            lastX = x
            lastY = y
        }
    }

    private fun drawGalacticTrails(canvas: Canvas, cx: Float, cy: Float, r: Float, nowYear: Double, reach: Float) {
        val g = GalacticGeometry
        val trails = galacticTrails(r, nowYear, reach)
        val offset = ((nowYear - trails.baseYear) * trails.pitch).toFloat()
        // Ribbon (along, side) → screen, with the current moment under the Sun.
        galacticMatrix.setValues(floatArrayOf(
            g.travelX, g.sideX, cx - g.travelX * offset,
            g.travelY, g.sideY, cy - g.travelY * offset,
            0f, 0f, 1f,
        ))
        val pathColors = mapOf(
            Astronomy.Body.MERCURY to 0xFF9BB6C7.toInt(),
            Astronomy.Body.VENUS to 0xFFFFD591.toInt(),
            Astronomy.Body.EARTH to 0xFF6ED6F3.toInt(),
            Astronomy.Body.MARS to 0xFFE7523E.toInt(),
        )
        val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        canvas.save()
        canvas.concat(galacticMatrix)
        Astronomy.Body.entries.forEach { body ->
            val earth = body == Astronomy.Body.EARTH
            trail.color = withAlpha(pathColors.getValue(body), if (earth) 150 else 90)
            trail.strokeWidth = r * if (earth) .006f else .003f
            canvas.drawPath(trails.paths.getValue(body), trail)
        }
        canvas.restore()
    }

    private fun drawDirectionArrow(canvas: Canvas, tipX: Float, tipY: Float, r: Float, paint: Paint) {
        val g = GalacticGeometry
        val length = r * .085f
        val halfWidth = r * .027f
        paint.style = Paint.Style.FILL
        polygonPath.rewind()
        polygonPath.moveTo(tipX, tipY)
        polygonPath.lineTo(tipX - g.travelX * length + g.sideX * halfWidth, tipY - g.travelY * length + g.sideY * halfWidth)
        polygonPath.lineTo(tipX - g.travelX * length - g.sideX * halfWidth, tipY - g.travelY * length - g.sideY * halfWidth)
        polygonPath.close()
        canvas.drawPath(polygonPath, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun drawOrthonormalPlane(canvas: Canvas, x: Float, y: Float, r: Float) {
        val g = GalacticGeometry
        val plane = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * .002f
            color = withAlpha(backgroundStyle.accentColor, 105)
        }
        val sideAngle = Math.toDegrees(atan2(g.sideY.toDouble(), g.sideX.toDouble())).toFloat()
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
        canvas.drawLine(x - g.sideX * r * .43f, y - g.sideY * r * .43f,
            x + g.sideX * r * .43f, y + g.sideY * r * .43f, plane)
    }

    private fun drawGalacticEvents(canvas: Canvas, cx: Float, cy: Float, r: Float, nowYear: Double, reach: Float) {
        val g = GalacticGeometry
        val pitch = r * g.YEAR_PITCH
        val bead = Paint(Paint.ANTI_ALIAS_FLAG)
        val eventText = Paint(text).apply {
            textSize = maxOf(r * .03f, 12f * density)
            textAlign = Paint.Align.LEFT
            color = withAlpha(instrumentColor, 235)
            setShadowLayer(2.5f * density, 0f, 0f, 0xD0000000.toInt())
        }
        // Events bunch up near the Sun (a year is only a hand's width of ribbon): label each one
        // only where it has room, nearest first.
        val placed = mutableListOf<RectF>()
        occurrences.sortedBy { kotlin.math.abs(g.continuousYear(it.start.toInstant(), zone) - nowYear) }.forEach { event ->
            val instant = event.start.toInstant()
            val distance = ((g.continuousYear(instant, zone) - nowYear) * pitch).toFloat()
            if (kotlin.math.abs(distance) > reach) return@forEach
            val (depth, side) = g.orbitOffset(
                Astronomy.heliocentricPosition(Astronomy.Body.EARTH, instant).longitudeDegrees,
                r * galacticOrbit(Astronomy.Body.EARTH))
            val x = cx + g.travelX * (distance + depth) + g.sideX * side
            val y = cy + g.travelY * (distance + depth) + g.sideY * side
            bead.style = Paint.Style.FILL
            bead.color = withAlpha(event.color, 230)
            canvas.drawCircle(x, y, r * if (event.isAllDay) .014f else .010f, bead)
            bead.style = Paint.Style.STROKE
            bead.strokeWidth = r * .002f
            bead.color = withAlpha(instrumentColor, 155)
            canvas.drawCircle(x, y, r * .020f, bead)
            val title = TextUtils.ellipsize(event.title, TextPaint(eventText), r * .6f, TextUtils.TruncateAt.END).toString()
            val left = x + r * .035f
            val baseline = y - (eventText.ascent() + eventText.descent()) / 2f
            val box = RectF(left, baseline + eventText.ascent(), left + eventText.measureText(title), baseline + eventText.descent())
            if (placed.none { RectF.intersects(it, box) }) {
                placed += box
                canvas.drawText(title, left, baseline, eventText)
            }
        }
    }

    /** Event titles on the dials never drop below a readable size, whatever Unity's proportions. */
    private fun yearEventLabelSize(r: Float): Float = maxOf(r * .036f, 12.5f * density)
    private fun dayEventLabelSize(hourR: Float): Float = maxOf(hourR * .048f, 13f * density)

    private fun yearBand(r: Float, calendarId: Long): DialGeometry.EventBand =
        DialGeometry.yearEventBand(r, calendarIndex(calendarId), minThickness = yearEventLabelSize(r) * 1.3f)

    private fun dayBand(hourR: Float, calendarId: Long): DialGeometry.EventBand =
        DialGeometry.dayEventBand(hourR, calendarIndex(calendarId), minThickness = dayEventLabelSize(hourR) * 1.3f)

    internal fun yearEventBandForTest(calendarId: Long): DialGeometry.EventBand = yearBand(geometry().third, calendarId)

    private fun drawCalendarYearEvents(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val year = displayedYear
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        arcLabel.textSize = yearEventLabelSize(r)
        occurrences.filter { it.isYearRingEvent }.forEach { event ->
            val segment = CalendarIntervals.inYear(event, year, zone) ?: return@forEach
            val eventBand = yearBand(r, event.calendarId)
            band.color = withAlpha(event.color, 145)
            band.strokeWidth = eventBand.thickness
            arcBounds.set(cx - eventBand.centerRadius, cy - eventBand.centerRadius,
                cx + eventBand.centerRadius, cy + eventBand.centerRadius)
            canvas.drawArc(arcBounds, annualAngle(segment.startFraction).toFloat(),
                ((if (north) -360.0 else 360.0) * segment.sweepFraction).toFloat(), false, band)
            drawArcLabel(canvas, event.title, cx, cy, eventBand.centerRadius,
                annualAngle(segment.startFraction + segment.sweepFraction / 2.0),
                maxOf(segment.sweepFraction * 360.0, MIN_YEAR_LABEL_DEGREES))
        }
    }

    private fun drawCalendarDayEvents(canvas: Canvas, cx: Float, cy: Float, hourR: Float) {
        val day = selectedInstant.atZone(zone).toLocalDate()
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        arcLabel.textSize = dayEventLabelSize(hourR)
        occurrences.filter { !it.isYearRingEvent }.forEach { event ->
            val segment = CalendarIntervals.inDay(event, day, zone) ?: return@forEach
            val eventBand = dayBand(hourR, event.calendarId)
            val minutes = (segment.endMinuteExclusive - segment.startMinute).coerceAtLeast(1.0)
            band.color = withAlpha(event.color, 160)
            band.strokeWidth = eventBand.thickness
            arcBounds.set(cx - eventBand.centerRadius, cy - eventBand.centerRadius,
                cx + eventBand.centerRadius, cy + eventBand.centerRadius)
            canvas.drawArc(arcBounds, hourAngle(segment.startMinute / 60.0).toFloat(),
                ((if (north) -1.0 else 1.0) * minutes / 4.0).toFloat(), false, band)
            drawArcLabel(canvas, event.title, cx, cy, eventBand.centerRadius,
                hourAngle((segment.startMinute + segment.endMinuteExclusive) / 120.0),
                maxOf(minutes / 4.0, MIN_DAY_LABEL_DEGREES))
        }
    }

    /**
     * Draws [value] curved along a circle of [radius], centred on [midAngle] and ellipsized to fit
     * [maxSweepDegrees] of arc. The path runs clockwise on the upper half of the screen and
     * anticlockwise on the lower half, so the title always reads left to right.
     */
    private fun drawArcLabel(
        canvas: Canvas,
        value: String,
        cx: Float,
        cy: Float,
        radius: Float,
        midAngle: Double,
        maxSweepDegrees: Double,
    ) {
        val available = (radius * Math.toRadians(maxSweepDegrees)).toFloat() * .92f
        if (available < arcLabel.textSize * 1.2f) return
        val label = TextUtils.ellipsize(value, arcLabel, available, TextUtils.TruncateAt.END).toString()
        if (label.isBlank()) return
        // Light text with a soft dark halo reads on any calendar colour, on sky or brass.
        arcLabel.color = if (brass) Color.WHITE else instrumentColor
        arcLabel.setShadowLayer(2.5f * density, 0f, 0f, 0xD0000000.toInt())
        val sweep = Math.toDegrees((arcLabel.measureText(label) / radius).toDouble()).toFloat()
        val screenAngle = Astronomy.normalizeDegrees(midAngle + textScreenRotation)
        val lowerHalf = screenAngle > 0 && screenAngle < 180
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        arcPath.rewind()
        if (lowerHalf) arcPath.addArc(arcBounds, midAngle.toFloat() + sweep / 2f, -sweep)
        else arcPath.addArc(arcBounds, midAngle.toFloat() - sweep / 2f, sweep)
        canvas.drawTextOnPath(label, arcPath, 0f, -(arcLabel.ascent() + arcLabel.descent()) / 2f, arcLabel)
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
        val bitmap = earthRenderer.render(selectedInstant, north, highlightOffsetMinutes)
        val sunAngle = Math.toDegrees(atan2((sunY - y).toDouble(), (sunX - x).toDouble())).toFloat()
        // EarthSphereRenderer uses Sun-up coordinates; rotate the complete globe into the actual
        // Earth-to-Sun direction without changing its geographic orientation.
        canvas.save()
        canvas.rotate(sunAngle + 90f, x, y)
        earthGlobeBounds.set(x - radius, y - radius, x + radius, y + radius)
        canvas.drawBitmap(bitmap, null, earthGlobeBounds, earthBitmapPaint)
        if (ornate) {
            // A small gold ring on the visible geographic pole: it swings round the globe's centre
            // with the seasons as the fixed axis tilts toward and away from the Sun.
            val pole = EarthOrientation.projectedGeographicPole(north, Zodiac.sunLongitude(selectedInstant))
            val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xC8E5C47A.toInt()
                style = Paint.Style.STROKE
                strokeWidth = maxOf(density, radius * .008f)
            }
            val px = x + pole.first.toFloat() * radius
            val py = y - pole.second.toFloat() * radius
            canvas.drawCircle(px, py, radius * .035f, marker)
            marker.style = Paint.Style.FILL
            canvas.drawCircle(px, py, radius * .011f, marker)
        }
        canvas.restore()

        // The Earth view's globe is framed by the local wheel's date strip, so it keeps only a fine
        // rim; the small planet glyph gets a bolder ring to read against the dial.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ornate) 0x9EE5C47A.toInt() else withAlpha(instrumentColor, 190)
            style = Paint.Style.STROKE
            strokeWidth = maxOf(density, radius * if (ornate) .008f else .045f)
        }
        canvas.drawCircle(x, y, radius * if (ornate) 1.004f else 1.035f, halo)
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
        // The Byzantine seal belongs to the astrology instrument; astronomy keeps a plain Moon.
        if (zodiacProfile.enabled) drawByzantineMoonSeal(canvas, x, y, radius)
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

    private fun drawSun(canvas: Canvas, x: Float, y: Float, core: Float) {
        if (brass && onFace) drawBrassSun(canvas, x, y, core) else drawSunBloom(canvas, x, y, core)
        if (zodiacProfile.enabled) drawByzantineSunSeal(canvas, x, y, core)
    }

    /** The watch's centre pivot: a polished pearl with the Sun's rays engraved around it. */
    private fun drawBrassSun(canvas: Canvas, x: Float, y: Float, core: Float) {
        sunRay.color = withAlpha(instrumentColor, 150)
        for (i in 0 until 32) {
            val a = i * 2 * PI / 32.0
            val length = core * if (i % 2 == 0) 3.1f else 2.2f
            sunRay.strokeWidth = maxOf(.6f, core * if (i % 2 == 0) .07f else .04f)
            canvas.drawLine(x + cos(a).toFloat() * core * 1.45f, y + sin(a).toFloat() * core * 1.45f,
                x + cos(a).toFloat() * length, y + sin(a).toFloat() * length, sunRay)
        }
        white.color = withAlpha(instrumentColor, 120)
        white.strokeWidth = maxOf(.6f, core * .05f)
        canvas.drawCircle(x, y, core * 3.35f, white)
        fill.color = 0x66000000
        canvas.drawCircle(x + core * .12f, y + core * .18f, core * 1.12f, fill)
        fill.shader = RadialGradient(x - core * .35f, y - core * .4f, core * 1.5f,
            intArrayOf(Color.WHITE, 0xFFF4F2EC.toInt(), 0xFFC9C6BE.toInt(), 0xFF8E8A80.toInt()),
            floatArrayOf(0f, .35f, .78f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, core * 1.08f, fill)
        fill.shader = null
        white.color = withAlpha(instrumentColor, 190)
        white.strokeWidth = maxOf(.6f, core * .06f)
        canvas.drawCircle(x, y, core * 1.1f, white)
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
            sunRay.color = if (i % 3 == 0) 0x48FFD88D else 0x28FFF4CF
            sunRay.strokeWidth = if (i % 9 == 0) maxOf(1f, core * .075f) else maxOf(.6f, core * .035f)
            canvas.drawLine(
                x + cos(a).toFloat() * core * 1.08f, y + sin(a).toFloat() * core * 1.08f,
                x + cos(a).toFloat() * length, y + sin(a).toFloat() * length, sunRay,
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
        horoscopeCards.clear()
        // In the Earth view the brass face fills the top of the screen, so text there is engraved.
        useInk(face = brass && state == ViewState.GEOCENTRIC && transitionFrom == null)
        if (showClock) {
            val local = selectedInstant.atZone(zone)
            text.textSize = 24f * density
            canvas.drawText(local.format(DateTimeFormatter.ofPattern("dd/MM/yy   HH : mm : ss")), width / 2f, 33f * density, text)
        }
        if (!realtime) {
            fill.color = Color.argb(220, 15, 15, 15)
            val rect = RectF(width * .27f, height - 60f * density, width * .73f, height - 14f * density)
            canvas.drawRoundRect(rect, 18f * density, 18f * density, fill)
            white.color = backgroundStyle.chromeColor; white.strokeWidth = density
            canvas.drawRoundRect(rect, 18f * density, 18f * density, white)
            val label = Paint(text).apply { textSize = 15f * density; color = backgroundStyle.chromeColor }
            canvas.drawText("RESET CURRENT TIME", width / 2f, height - 29f * density, label)
        }
        if (state == ViewState.GEOCENTRIC && transitionFrom == null) drawSelectedZoneCaption(canvas)
        useInk(face = false)
        if (zodiacProfile.enabled) horoscopeText?.let { drawHoroscopeCard(canvas, it, forWallpaper = false) }
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
        // In the app, keep clear of the tuck-menu buttons in three corners.
        val topBounds = RectF(left, (if (forWallpaper) 66f else 80f) * density, right, dialTop - 10f * density)
        val bottomLimit = height - (if (forWallpaper) 16f else 84f) * density
        val bottomBounds = RectF(left, dialBottom + 10f * density, right, bottomLimit)
        val minimumPanelHeight = 92f * density
        val sign = zodiacProfile.resolvedSign()

        // Landscape tablets and foldables (Android 16 ignores the portrait lock there): the dial
        // fills the height, so the reading sits in the free space either side of it instead.
        val (dialCx, _, _) = geometry()
        val sideWidth = minOf(dialCx - r * 1.1f - 28f * density, 360f * density)
        if (sideWidth >= 150f * density && topBounds.height() < minimumPanelHeight) {
            val (first, second) = splitHoroscope(value)
            val top = (if (forWallpaper) 24f else 80f) * density
            val bottom = height - (if (forWallpaper) 24f else 84f) * density
            val cardHeight = minOf(bottom - top, 380f * density)
            val cardTop = top + (bottom - top - cardHeight) / 2f
            drawHoroscopePanel(canvas, RectF(16f * density, cardTop, 16f * density + sideWidth, cardTop + cardHeight),
                "${sign.symbol}  ${sign.displayName.uppercase()} · TODAY'S ORACLE", first)
            drawHoroscopePanel(canvas, RectF(width - 16f * density - sideWidth, cardTop, width - 16f * density, cardTop + cardHeight),
                if (forWallpaper) "CONTINUED · CELESTIAL WALLPAPER" else "WRITTEN BY ON-DEVICE AI · TAP TO REPORT", second)
            return
        }

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
                if (forWallpaper) "CONTINUED · CELESTIAL WALLPAPER" else "WRITTEN BY ON-DEVICE AI · TAP TO REPORT",
                second,
            )
            return
        }

        val fallbackHeight = minOf(height * .30f, 250f * density)
        var fallbackTop = (bottomLimit - fallbackHeight).coerceAtLeast(dialBottom + 8f * density)
        // No free band below the dial either: lay the card over the dial's lower edge rather than
        // lose the reading.
        if (bottomLimit - fallbackTop < 120f * density) fallbackTop = bottomLimit - minOf(fallbackHeight, 170f * density)
        drawHoroscopePanel(
            canvas,
            RectF(left, fallbackTop, right, bottomLimit),
            "${sign.symbol}  ${sign.displayName.uppercase()} · TODAY'S ORACLE",
            value,
        )
    }

    private fun drawHoroscopePanel(canvas: Canvas, bounds: RectF, title: String, value: String) {
        if (value.isBlank() || bounds.height() < 64f * density) return
        horoscopeCards += RectF(bounds)
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(instrumentColor, 225)
            textSize = 13f * screenDensity
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

        val titlePaint = TextPaint(text).apply {
            color = backgroundStyle.accentColor
            textSize = 17f * screenDensity
            letterSpacing = .09f
        }
        // Narrow cards (beside the dial on landscape tablets) shrink the title, then shorten it.
        val titleWidth = bounds.width() - 24f * density
        while (titlePaint.measureText(title) > titleWidth && titlePaint.textSize > 11f * density) {
            titlePaint.textSize -= density
        }
        val fittedTitle = TextUtils.ellipsize(title, titlePaint, titleWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(fittedTitle, bounds.centerX(), bounds.top + 23f * density, titlePaint)
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
        val cardWidth = minOf(width - 32f * density, 460f * density)
        val padding = 22f * density
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = instrumentColor
            textSize = 30f * screenDensity
            typeface = labelFont
        }
        val titleLayout = StaticLayout.Builder.obtain(
            event.title, 0, event.title.length, titlePaint, (cardWidth - 2 * padding).toInt(),
        ).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val timingPaint = TextPaint(text).apply {
            color = withAlpha(instrumentColor, 225)
            textSize = 19f * screenDensity
        }
        val timing = TextUtils.ellipsize(eventTimingLabel(event), timingPaint, cardWidth - 2 * padding,
            TextUtils.TruncateAt.END).toString()
        val eyebrowHeight = 44f * density
        val cardHeight = eyebrowHeight + titleLayout.height + 18f * density + timingPaint.fontSpacing + padding
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
            "CALENDAR EVENT"
        }
        val eyebrow = Paint(text).apply {
            color = withAlpha(event.color, 255)
            textSize = 15f * density
            letterSpacing = .10f
        }
        canvas.drawText(countLabel, bounds.centerX(), bounds.top + 30f * density, eyebrow)

        canvas.save()
        canvas.translate(bounds.left + padding, bounds.top + eyebrowHeight)
        titleLayout.draw(canvas)
        canvas.restore()

        canvas.drawText(timing, bounds.centerX(), bounds.bottom - padding - timingPaint.descent(), timingPaint)
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
                        val band = yearBand(r, event.calendarId)
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
                        val band = dayBand(hourR, event.calendarId)
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
                if (!realtime && event.y > height - 75f * density && event.x in width * .2f..width * .8f) {
                    resetNow(); return true
                }
                if (horoscopeCards.any { it.contains(event.x, event.y) }) {
                    onHoroscopeTapped?.invoke(); return true
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
                if (state == ViewState.GALACTIC) {
                    // Drag anywhere to slide the ribbon of years; a tap returns to the Sun view.
                    dragMode = DragMode.GALAXY
                    dragStartX = event.x
                    dragStartY = event.y
                    dragStartYear = GalacticGeometry.continuousYear(selectedInstant, zone)
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
                    DragMode.GALAXY -> {
                        if (dragStarted || distance(event.x, event.y, dragStartX, dragStartY) >= 8f * density) {
                            dragStarted = true
                            realtime = false
                            updateGalacticDrag(event.x, event.y)
                        }
                    }
                    DragMode.EVENT -> updateEventInspection(event.x, event.y)
                    DragMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                val galacticTap = dragMode == DragMode.GALAXY && !dragStarted
                finishInteraction()
                if (galacticTap) switchToState(ViewState.HELIOCENTRIC)
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

    /** The ribbon follows the finger: pulling it back against the direction of travel moves time on. */
    private fun updateGalacticDrag(x: Float, y: Float) {
        val (_, _, r) = geometry()
        val g = GalacticGeometry
        val along = (x - dragStartX) * g.travelX + (y - dragStartY) * g.travelY
        selectedInstant = g.instantAt(dragStartYear - along / (r * g.YEAR_PITCH), zone)
        invalidate()
    }

    fun resetNow() {
        selectedInstant = Instant.now()
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
        polygon.color = color
        polygonPath.rewind()
        polygonPath.moveTo(cx - dx, cy - dy)
        polygonPath.lineTo(tip.first, tip.second)
        polygonPath.lineTo(cx + dx, cy + dy)
        polygonPath.close()
        canvas.drawPath(polygonPath, polygon)
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
        polygon.color = color
        polygonPath.rewind()
        polygonPath.moveTo(base.first - dx, base.second - dy)
        polygonPath.lineTo(tip.first, tip.second)
        polygonPath.lineTo(base.first + dx, base.second + dy)
        polygonPath.close()
        canvas.drawPath(polygonPath, polygon)
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

    private fun blendArgb(start: Int, end: Int, progress: Float): Int = Color.argb(
        lerp(Color.alpha(start).toFloat(), Color.alpha(end).toFloat(), progress).toInt(),
        lerp(Color.red(start).toFloat(), Color.red(end).toFloat(), progress).toInt(),
        lerp(Color.green(start).toFloat(), Color.green(end).toFloat(), progress).toInt(),
        lerp(Color.blue(start).toFloat(), Color.blue(end).toFloat(), progress).toInt(),
    )

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
        private const val LOCAL_TOOTH_RED = 0xFFE3262E.toInt()
        private const val BRASS_ENAMEL_RED = 0xFF7A1E12.toInt()
        private const val GALACTIC_STEPS_PER_YEAR = 180
        /** Short events still get this much arc (about four weeks / three hours) for their title. */
        private const val MIN_YEAR_LABEL_DEGREES = 28.0
        private const val MIN_DAY_LABEL_DEGREES = 45.0
    }
}
