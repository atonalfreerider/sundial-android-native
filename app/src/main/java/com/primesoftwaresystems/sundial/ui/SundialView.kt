package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.primesoftwaresystems.sundial.R
import com.primesoftwaresystems.sundial.astronomy.Astronomy
import com.primesoftwaresystems.sundial.calendar.CalendarOccurrence
import com.primesoftwaresystems.sundial.calendar.CalendarIntervals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var state = ViewState.HELIOCENTRIC
    private var showClock = false
    private var north = true
    private var realtime = true
    private var running = true
    private var selectedInstant: Instant = Instant.now()
    private var dragMode = DragMode.NONE
    private val selectedCalendarIds = linkedSetOf<Long>()
    private var occurrences: List<CalendarOccurrence> = emptyList()
    private var lastReportedCalendarYear = displayedYear
    private var earthPoint = Pair(0f, 0f)
    private var moonPoint = Pair(0f, 0f)
    var onCalendarSelectionChanged: ((Set<Long>) -> Unit)? = null
    var onMenuRequested: (() -> Unit)? = null
    var onControlsChanged: (() -> Unit)? = null

    val displayedYear: Int get() = selectedInstant.atZone(zone).year
    val isClockVisible: Boolean get() = showClock
    val isGalacticVisible: Boolean get() = state == ViewState.GALACTIC
    val isSouthernHemisphere: Boolean get() = !north

    private enum class DragMode { NONE, YEAR, MOON }

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
        onControlsChanged?.invoke(); invalidate()
    }
    fun setSouthernHemisphere(value: Boolean) { north = !value; invalidate() }
    fun setSelectedCalendarIds(ids: Set<Long>) {
        selectedCalendarIds.clear(); selectedCalendarIds.addAll(ids); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (running && realtime) selectedInstant = Instant.now()
        if (displayedYear != lastReportedCalendarYear && selectedCalendarIds.isNotEmpty()) {
            lastReportedCalendarYear = displayedYear
            onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet())
        }
        canvas.drawColor(Color.BLACK)
        when (state) {
            ViewState.HELIOCENTRIC -> drawHeliocentric(canvas)
            ViewState.GEOCENTRIC -> drawGeocentric(canvas)
            ViewState.GALACTIC -> drawGalactic(canvas)
        }
        drawChrome(canvas)
        if (running) postInvalidateDelayed(if (showClock) 250L else 1_000L)
    }

    private fun geometry(): Triple<Float, Float, Float> {
        val cx = width / 2f
        val cy = height * if (height > width * 1.25f) 0.47f else 0.5f
        val radius = min(width * 0.47f, height * 0.41f)
        return Triple(cx, cy, radius)
    }

    private fun drawHeliocentric(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        drawAnnualDial(canvas, cx, cy, r)
        drawSeasonCross(canvas, cx, cy, r)
        drawOrbitPaths(canvas, cx, cy, r)
        drawCalendarYearEvents(canvas, cx, cy, r)
        drawSunBloom(canvas, cx, cy, r * 0.052f)
        text.textSize = r * 0.115f
        canvas.drawText("S U N : D I A L", cx, cy - r * 0.62f, text)
    }

    private fun drawAnnualDial(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val local = selectedInstant.atZone(zone)
        val year = local.year
        val days = Astronomy.daysInYear(year)
        white.strokeWidth = maxOf(1.1f * density, r * 0.0034f)
        white.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, white)
        white.color = Color.argb(75, 255, 255, 255)
        white.strokeWidth = maxOf(.5f * density, r * .0012f)
        canvas.drawCircle(cx, cy, r * .978f, white)
        for (dayIndex in 0 until days) {
            val date = LocalDate.ofYearDay(year, dayIndex + 1)
            val monthStart = date.dayOfMonth == 1
            val week = date.dayOfMonth % 7 == 0
            val length = when { monthStart -> r * 0.062f; week -> r * 0.036f; else -> r * 0.019f }
            val angle = annualAngle(dayIndex.toDouble() / days)
            white.color = if (monthStart) Color.WHITE else Color.argb(if (week) 205 else 145, 255, 255, 255)
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
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 255, 255, 255); strokeWidth = r * .004f }
        drawRadialLine(canvas, cx, cy, r * .77f, r * 1.015f, annualAngle(current), marker)
        val nowPoint = point(cx, cy, r * 1.015f, annualAngle(current))
        fill.color = Color.WHITE
        canvas.save()
        canvas.rotate((annualAngle(current) + 45.0).toFloat(), nowPoint.first, nowPoint.second)
        canvas.drawRect(nowPoint.first - r * .008f, nowPoint.second - r * .008f,
            nowPoint.first + r * .008f, nowPoint.second + r * .008f, fill)
        canvas.restore()
    }

    private fun drawSeasonCross(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 255, 255, 255) }
        for (i in -12..12) {
            val q = i / 12f
            val size = if (i % 3 == 0) r * .0031f else r * .0018f
            canvas.drawCircle(cx, cy + q * r * .86f, size, dotPaint)
            canvas.drawCircle(cx + q * r * .86f, cy, size, dotPaint)
        }
        dimText.textSize = r * .047f
        drawRotatedText(canvas, if (north) "SPRING" else "FALL", cx, cy, r * .79f, -45.0, dimText, true)
        drawRotatedText(canvas, if (north) "SUMMER" else "WINTER", cx, cy, r * .79f, -135.0, dimText, true)
        drawRotatedText(canvas, if (north) "FALL" else "SPRING", cx, cy, r * .79f, 135.0, dimText, true)
        drawRotatedText(canvas, if (north) "WINTER" else "SUMMER", cx, cy, r * .79f, 45.0, dimText, true)
    }

    private fun drawOrbitPaths(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val radii = mapOf(
            Astronomy.Body.MERCURY to r * .19f,
            Astronomy.Body.VENUS to r * .32f,
            Astronomy.Body.EARTH to r * .50f,
            Astronomy.Body.MARS to r * .69f,
        )
        val colors = mapOf(
            Astronomy.Body.MERCURY to Color.rgb(110, 135, 158),
            Astronomy.Body.VENUS to Color.rgb(247, 247, 220),
            Astronomy.Body.EARTH to Color.rgb(160, 215, 240),
            Astronomy.Body.MARS to Color.rgb(220, 56, 70),
        )
        for (body in Astronomy.Body.entries) {
            val orbitR = radii.getValue(body)
            val pathPaint = Paint(white).apply {
                color = Color.argb(if (body == Astronomy.Body.EARTH) 165 else 80, 255, 255, 255)
                strokeWidth = if (body == Astronomy.Body.EARTH) r * .0022f else r * .0015f
                pathEffect = when (body) {
                    Astronomy.Body.MERCURY -> DashPathEffect(floatArrayOf(r * .008f, r * .012f), 0f)
                    Astronomy.Body.VENUS -> DashPathEffect(floatArrayOf(r * .05f, r * .018f), 0f)
                    Astronomy.Body.EARTH -> null
                    Astronomy.Body.MARS -> DashPathEffect(floatArrayOf(r * .12f, r * .025f), 0f)
                }
            }
            canvas.drawCircle(cx, cy, orbitR, pathPaint)
            val longitude = Astronomy.heliocentricPosition(body, selectedInstant).longitudeDegrees
            val angle = if (north) 90.0 - longitude else longitude - 90.0
            val point = point(cx, cy, orbitR, angle)
            val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.getValue(body); alpha = if (body == Astronomy.Body.EARTH) 105 else 55 }
            val handPath = Path().apply {
                moveTo(cx - r * .008f, cy)
                lineTo(point.first, point.second)
                lineTo(cx + r * .008f, cy)
                close()
            }
            canvas.drawPath(handPath, hand)
            drawPlanetGlyph(canvas, body, point.first, point.second, r)
            if (body == Astronomy.Body.EARTH) earthPoint = point
        }
    }

    private fun drawPlanetGlyph(canvas: Canvas, body: Astronomy.Body, x: Float, y: Float, r: Float) {
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
                fill.color = Color.BLACK
                canvas.drawCircle(x + radius * .48f, y - radius * .12f, radius * .84f, fill)
                white.color = 0x99FFE0A3.toInt(); white.strokeWidth = r * .0015f
                canvas.drawCircle(x, y, radius * 1.12f, white)
            }
            Astronomy.Body.EARTH -> {
                fill.color = 0xFF56A66D.toInt()
                canvas.drawOval(RectF(x - radius * .55f, y - radius * .38f, x + radius * .1f, y + radius * .05f), fill)
                white.color = 0xCCFFFFFF.toInt(); white.strokeWidth = r * .0015f
                canvas.drawArc(RectF(x - radius, y - radius * .43f, x + radius, y + radius * .43f), 8f, 164f, false, white)
                canvas.drawCircle(x, y, radius * 1.14f, white)
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

    private fun drawGeocentric(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        val hourR = r * .61f
        val moonR = r * .87f
        drawSprocket(canvas, cx, cy, hourR, 24, majorEvery = 1, inward = r * .042f)
        drawSprocket(canvas, cx, cy, moonR, 30, majorEvery = 5, inward = r * .031f)
        text.textSize = r * .038f
        for (hour in 0 until 24) drawRotatedText(canvas, hour.toString(), cx, cy, hourR * .91f, -90.0 + hour * 15.0, text, true)

        val local = selectedInstant.atZone(zone)
        text.textSize = r * .037f
        for (i in 0 until 29) {
            val date = local.toLocalDate().plusDays(i.toLong())
            drawRotatedText(canvas, date.dayOfMonth.toString(), cx, cy, moonR * .94f,
                -90.0 + i * 360.0 / Astronomy.SYNODIC_MONTH_DAYS, text, true)
        }

        // Sun stays at the top in Earth-following view, as in the original camera behavior.
        drawSunBloom(canvas, cx, cy - r * .97f, r * .061f)
        val sunHand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 255, 255, 255) }
        canvas.drawPath(Path().apply {
            moveTo(cx - r * .035f, cy); lineTo(cx, cy - r); lineTo(cx + r * .035f, cy); close()
        }, sunHand)

        drawCalendarDayEvents(canvas, cx, cy, hourR)
        val sphereRadius = r * .365f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx + sphereRadius * .16f, cy + sphereRadius * .24f, sphereRadius * 1.25f,
                intArrayOf(Color.argb(160, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawOval(RectF(cx - sphereRadius * 1.1f, cy - sphereRadius * .84f,
            cx + sphereRadius * 1.35f, cy + sphereRadius * 1.5f), shadow)
        val earth = earthRenderer.render((sphereRadius * 2).toInt(), Astronomy.greenwichMeanSiderealDegrees(selectedInstant), north)
        canvas.drawBitmap(earth, null, RectF(cx - sphereRadius, cy - sphereRadius, cx + sphereRadius, cy + sphereRadius), fill)
        white.strokeWidth = r * .008f
        white.color = Color.argb(210, 255, 255, 255)
        canvas.drawCircle(cx, cy, sphereRadius, white)

        val phaseAngle = Astronomy.moonPhaseDegrees(selectedInstant)
        val moonAngle = if (north) -90.0 + phaseAngle else -90.0 - phaseAngle
        moonPoint = point(cx, cy, moonR, moonAngle)
        drawMoonGlyph(canvas, moonPoint.first, moonPoint.second, r * .025f, phaseAngle)
        text.textSize = r * .045f
        canvas.drawText(local.month.name.take(3), cx, cy + moonR + r * .065f, text)
    }

    private fun drawGalactic(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 255, 255, 255); strokeWidth = r * .004f }
        canvas.save()
        canvas.rotate(-28f, cx, cy)
        canvas.drawLine(cx, cy - r * 1.05f, cx, cy + r * 1.05f, line)
        text.textSize = r * .12f
        text.textAlign = Paint.Align.RIGHT
        val year = displayedYear
        for (offset in -1..1) {
            val y = cy + offset * r * .68f
            canvas.drawLine(cx - r * .05f, y, cx + r * .05f, y, line)
            canvas.drawText((year + offset).toString(), cx - r * .07f, y + r * .04f, text)
            for (m in 1..11) {
                val yy = y + m * r * .68f / 12f
                canvas.drawLine(cx - r * .015f, yy, cx + r * .015f, yy, line)
            }
        }
        text.textAlign = Paint.Align.CENTER
        canvas.restore()
        drawSunBloom(canvas, cx, cy, r * .065f)
        dimText.textSize = r * .06f
        canvas.drawText("GALACTIC", cx, cy - r * .72f, dimText)
    }

    private fun drawCalendarYearEvents(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val year = displayedYear
        occurrences.filter { it.isYearRingEvent }.forEachIndexed { index, event ->
            val segment = CalendarIntervals.inYear(event, year, zone) ?: return@forEachIndexed
            val eventR = r * (.89f - index.coerceAtMost(4) * .025f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(event.color, 145); strokeWidth = r * .021f; style = Paint.Style.STROKE }
            canvas.drawArc(RectF(cx - eventR, cy - eventR, cx + eventR, cy + eventR),
                annualAngle(segment.startFraction).toFloat(), (-360.0 * segment.sweepFraction).toFloat(), false, p)
            val labelPaint = Paint(text).apply { color = withAlpha(event.color, 220); textSize = r * .027f }
            val allowed = (segment.sweepFraction * 80).toInt().coerceAtLeast(1)
            drawRotatedText(canvas, event.title.take(allowed), cx, cy, eventR,
                annualAngle(segment.startFraction + segment.sweepFraction / 2.0), labelPaint, true)
        }
    }

    private fun drawCalendarDayEvents(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val day = selectedInstant.atZone(zone).toLocalDate()
        occurrences.filter { !it.isYearRingEvent }.forEachIndexed { index, event ->
            val segment = CalendarIntervals.inDay(event, day, zone) ?: return@forEachIndexed
            val eventR = r * (.95f - index.coerceAtMost(4) * .04f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(event.color, 160); strokeWidth = r * .035f; style = Paint.Style.STROKE }
            canvas.drawArc(RectF(cx - eventR, cy - eventR, cx + eventR, cy + eventR),
                (-90.0 + segment.startMinute / 4.0).toFloat(), ((segment.endMinuteExclusive - segment.startMinute).coerceAtLeast(1.0) / 4.0).toFloat(), false, p)
            val middleMinute = (segment.startMinute + segment.endMinuteExclusive) / 2.0
            val allowed = ((segment.endMinuteExclusive - segment.startMinute) / 15.0).toInt().coerceAtLeast(1)
            val labelPaint = Paint(text).apply { color = withAlpha(event.color, 230); textSize = r * .04f }
            drawRotatedText(canvas, event.title.take(allowed), cx, cy, eventR,
                -90.0 + middleMinute / 4.0, labelPaint, true)
        }
    }

    private fun drawMoonGlyph(canvas: Canvas, x: Float, y: Float, radius: Float, phaseDegrees: Double) {
        val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, radius * 2.7f,
                intArrayOf(0xAAFFFCE5.toInt(), 0x38D9D4B9, Color.TRANSPARENT),
                floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(x, y, radius * 2.7f, aura)
        fill.color = 0xFFFFFBE9.toInt()
        canvas.drawCircle(x, y, radius, fill)
        val illumination = (1.0 - cos(Math.toRadians(phaseDegrees))) / 2.0
        val offset = ((illumination - .5) * radius * 1.1).toFloat()
        fill.color = 0xFF111419.toInt()
        canvas.drawCircle(x + offset, y - radius * .08f, radius * .82f, fill)
        white.color = 0x99FFFFFF.toInt(); white.strokeWidth = radius * .12f
        canvas.drawCircle(x, y, radius * 1.1f, white)
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
    }

    private fun drawChrome(canvas: Canvas) {
        val center = 30f * density
        fill.color = 0x16000000
        canvas.drawCircle(center, center, 24f * density, fill)
        white.color = 0x38FFFFFF
        white.strokeWidth = .8f * density
        canvas.drawCircle(center, center, 23f * density, white)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND }
        for (i in -1..1) canvas.drawLine(center - 10f * density, center + i * 7f * density,
            center + 10f * density, center + i * 7f * density, line)
        if (showClock) {
            val local = selectedInstant.atZone(zone)
            text.textSize = 24f * density
            canvas.drawText(local.format(DateTimeFormatter.ofPattern("dd/MM/yy   HH : mm : ss")), width / 2f, 33f * density, text)
        }
        if (!realtime) {
            fill.color = Color.argb(220, 15, 15, 15)
            val rect = RectF(width * .27f, height - 60f * density, width * .73f, height - 14f * density)
            canvas.drawRoundRect(rect, 18f * density, 18f * density, fill)
            white.color = Color.WHITE; white.strokeWidth = density
            canvas.drawRoundRect(rect, 18f * density, 18f * density, white)
            text.textSize = 15f * density
            canvas.drawText("RESET CURRENT TIME", width / 2f, height - 29f * density, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (cx, cy, r) = geometry()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < 64f * density && event.y < 64f * density) {
                    onMenuRequested?.invoke(); return true
                }
                if (!realtime && event.y > height - 75f * density && event.x in width * .2f..width * .8f) {
                    resetNow(); return true
                }
                val radiusFromCenter = distance(event.x, event.y, cx, cy)
                if (state == ViewState.HELIOCENTRIC && distance(event.x, event.y, earthPoint.first, earthPoint.second) < r * .09f) {
                    dragMode = DragMode.YEAR; realtime = false; updateYearDrag(event.x, event.y); return true
                }
                if (state == ViewState.HELIOCENTRIC && radiusFromCenter < r * .13f) {
                    state = ViewState.GEOCENTRIC; onControlsChanged?.invoke(); invalidate(); return true
                }
                if (state == ViewState.GEOCENTRIC && distance(event.x, event.y, moonPoint.first, moonPoint.second) < r * .09f) {
                    dragMode = DragMode.MOON; realtime = false; updateMoonDrag(event.x, event.y); return true
                }
                if (state == ViewState.HELIOCENTRIC && radiusFromCenter > r * .78f) {
                    dragMode = DragMode.YEAR; realtime = false; updateYearDrag(event.x, event.y); return true
                }
                if (state == ViewState.GEOCENTRIC && radiusFromCenter < r * .48f) {
                    state = ViewState.HELIOCENTRIC; onControlsChanged?.invoke(); invalidate(); return true
                }
                if (state == ViewState.GALACTIC) { state = ViewState.HELIOCENTRIC; onControlsChanged?.invoke(); invalidate(); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    DragMode.YEAR -> updateYearDrag(event.x, event.y)
                    DragMode.MOON -> updateMoonDrag(event.x, event.y)
                    DragMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                dragMode = DragMode.NONE
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
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
        var fraction = (105.0 - canvasAngle) / 360.0
        fraction -= kotlin.math.floor(fraction)
        selectedInstant = Astronomy.instantAtYearFraction(displayedYear, fraction, zone)
        invalidate()
    }

    private fun updateMoonDrag(x: Float, y: Float) {
        val (cx, cy, _) = geometry()
        val angle = Astronomy.normalizeDegrees(Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())) + 90.0)
        val currentPhase = Astronomy.moonPhaseDegrees(selectedInstant)
        val difference = Astronomy.normalizeSignedDegrees(angle - currentPhase)
        selectedInstant = selectedInstant.plusSeconds((difference / 360.0 * Astronomy.SYNODIC_MONTH_DAYS * 86_400).toLong())
        invalidate()
    }

    fun resetNow() {
        selectedInstant = Instant.now(); realtime = true; onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet()); invalidate()
    }

    private fun drawSprocket(canvas: Canvas, cx: Float, cy: Float, radius: Float, count: Int, majorEvery: Int, inward: Float) {
        white.color = Color.WHITE; white.strokeWidth = maxOf(density, radius * .004f)
        canvas.drawCircle(cx, cy, radius, white)
        for (i in 0 until count) {
            val length = if (i % majorEvery == 0) inward else inward * .55f
            drawRadialLine(canvas, cx, cy, radius - length, radius, -90.0 + i * 360.0 / count, white)
        }
    }

    private fun drawRadialLine(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float, angleDegrees: Double, paint: Paint) {
        val a = Math.toRadians(angleDegrees)
        canvas.drawLine(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner,
            cx + cos(a).toFloat() * outer, cy + sin(a).toFloat() * outer, paint)
    }

    private fun drawRotatedText(canvas: Canvas, value: String, cx: Float, cy: Float, radius: Float, angleDegrees: Double, paint: Paint, upright: Boolean) {
        val p = point(cx, cy, radius, angleDegrees)
        canvas.save()
        canvas.rotate((angleDegrees + 90.0).toFloat(), p.first, p.second)
        if (upright && angleDegrees > 0 && angleDegrees < 180) canvas.rotate(180f, p.first, p.second)
        canvas.drawText(value, p.first, p.second - (paint.ascent() + paint.descent()) / 2f, paint)
        canvas.restore()
    }

    private fun point(cx: Float, cy: Float, radius: Float, angleDegrees: Double): Pair<Float, Float> {
        val a = Math.toRadians(angleDegrees)
        return Pair(cx + cos(a).toFloat() * radius, cy + sin(a).toFloat() * radius)
    }

    private fun annualAngle(fraction: Double): Double = if (north) 105.0 - fraction * 360.0 else 75.0 + fraction * 360.0
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float = kotlin.math.hypot(x1 - x2, y1 - y2)
    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
