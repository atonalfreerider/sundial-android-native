package com.primesoftwaresystems.sundial.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import com.primesoftwaresystems.sundial.calendar.DeviceCalendar
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
    private var menuOpen = false
    private var showClock = false
    private var north = true
    private var permissionDenied = false
    private var menuScrollY = 0f
    private var menuContentHeight = 0f
    private var menuLastTouchY = 0f
    private var menuDragging = false
    private var realtime = true
    private var running = true
    private var selectedInstant: Instant = Instant.now()
    private var dragMode = DragMode.NONE
    private var calendars: List<DeviceCalendar> = emptyList()
    private val selectedCalendarIds = linkedSetOf<Long>()
    private var occurrences: List<CalendarOccurrence> = emptyList()
    private var lastReportedCalendarYear = displayedYear
    private var earthPoint = Pair(0f, 0f)
    private var moonPoint = Pair(0f, 0f)
    private val menuRows = mutableListOf<MenuRow>()
    var onCalendarSelectionChanged: ((Set<Long>) -> Unit)? = null
    var onQuit: (() -> Unit)? = null

    val displayedYear: Int get() = selectedInstant.atZone(zone).year

    private enum class DragMode { NONE, YEAR, MOON }
    private data class MenuRow(val bounds: RectF, val calendarId: Long?)

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isClickable = true
    }

    fun setCalendars(value: List<DeviceCalendar>) {
        calendars = value
        // Start with Google calendars available but opt-in, matching the Unity menu behavior.
        invalidate()
    }

    fun setCalendarOccurrences(value: List<CalendarOccurrence>) {
        occurrences = value
        invalidate()
    }

    fun setCalendarPermissionDenied() {
        permissionDenied = true
        invalidate()
    }

    fun resumeClock() { running = true; invalidate() }
    fun pauseClock() { running = false }

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
        if (menuOpen) drawMenu(canvas)
        if (running) postInvalidateDelayed(if (showClock) 250L else 1_000L)
    }

    private fun geometry(): Triple<Float, Float, Float> {
        val cx = width / 2f
        val cy = height * if (height > width * 1.25f) 0.53f else 0.5f
        val radius = min(width * 0.475f, height * 0.43f)
        return Triple(cx, cy, radius)
    }

    private fun drawHeliocentric(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        drawAnnualDial(canvas, cx, cy, r)
        drawSeasonCross(canvas, cx, cy, r)
        drawOrbitPaths(canvas, cx, cy, r)
        drawCalendarYearEvents(canvas, cx, cy, r)
        drawSunBloom(canvas, cx, cy, r * 0.075f)
        text.textSize = r * 0.105f
        text.letterSpacingCompat(0.10f)
        canvas.drawText("SUN:DIAL", cx, cy - r * 0.56f, text)
        text.letterSpacingCompat(0f)
    }

    private fun drawAnnualDial(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val local = selectedInstant.atZone(zone)
        val year = local.year
        val days = Astronomy.daysInYear(year)
        white.strokeWidth = maxOf(1.2f * density, r * 0.0038f)
        white.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, white)
        for (dayIndex in 0 until days) {
            val date = LocalDate.ofYearDay(year, dayIndex + 1)
            val monthStart = date.dayOfMonth == 1
            val week = date.dayOfMonth % 7 == 0
            val length = when { monthStart -> r * 0.056f; week -> r * 0.033f; else -> r * 0.020f }
            val angle = annualAngle(dayIndex.toDouble() / days)
            drawRadialLine(canvas, cx, cy, r - length, r, angle, white)
        }
        text.textSize = r * 0.043f
        for (month in 1..12) {
            val date = LocalDate.of(year, month, 15)
            val fraction = (date.dayOfYear - 0.5) / days
            drawRotatedText(canvas, date.month.name.take(3), cx, cy, r * 0.925f, annualAngle(fraction), text, upright = true)
        }
        val current = Astronomy.civilYearFraction(local)
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); strokeWidth = r * .006f }
        drawRadialLine(canvas, cx, cy, r * .12f, r * .985f, annualAngle(current), marker)
    }

    private fun drawSeasonCross(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) }
        for (i in -10..10) {
            val q = i / 10f
            canvas.drawCircle(cx, cy + q * r * .97f, r * .003f, dotPaint)
            canvas.drawCircle(cx + q * r * .97f, cy, r * .003f, dotPaint)
        }
        dimText.textSize = r * .052f
        drawRotatedText(canvas, if (north) "SPRING" else "FALL", cx, cy, r * .78f, -45.0, dimText, true)
        drawRotatedText(canvas, if (north) "SUMMER" else "WINTER", cx, cy, r * .78f, -135.0, dimText, true)
        drawRotatedText(canvas, if (north) "FALL" else "SPRING", cx, cy, r * .78f, 135.0, dimText, true)
        drawRotatedText(canvas, if (north) "WINTER" else "SUMMER", cx, cy, r * .78f, 45.0, dimText, true)
    }

    private fun drawOrbitPaths(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val radii = mapOf(
            Astronomy.Body.MERCURY to r * .15f,
            Astronomy.Body.VENUS to r * .30f,
            Astronomy.Body.EARTH to r * .50f,
            Astronomy.Body.MARS to r * .76f,
        )
        val colors = mapOf(
            Astronomy.Body.MERCURY to Color.rgb(110, 135, 158),
            Astronomy.Body.VENUS to Color.rgb(247, 247, 220),
            Astronomy.Body.EARTH to Color.rgb(160, 215, 240),
            Astronomy.Body.MARS to Color.rgb(220, 56, 70),
        )
        for (body in Astronomy.Body.entries) {
            val orbitR = radii.getValue(body)
            val pathPaint = Paint(white).apply { color = Color.argb(if (body == Astronomy.Body.EARTH) 210 else 95, 255, 255, 255); strokeWidth = r * .0017f }
            val sweep = if (body == Astronomy.Body.EARTH) 300f else 122f
            canvas.drawArc(RectF(cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR), -90f, sweep, false, pathPaint)
            val longitude = Astronomy.heliocentricPosition(body, selectedInstant).longitudeDegrees
            val angle = if (north) 90.0 - longitude else longitude - 90.0
            val point = point(cx, cy, orbitR, angle)
            val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.getValue(body); alpha = if (body == Astronomy.Body.EARTH) 230 else 90 }
            val handPath = Path().apply {
                moveTo(cx - r * .008f, cy)
                lineTo(point.first, point.second)
                lineTo(cx + r * .008f, cy)
                close()
            }
            canvas.drawPath(handPath, hand)
            val planetRadius = when (body) {
                Astronomy.Body.MERCURY -> r * .009f
                Astronomy.Body.VENUS -> r * .017f
                Astronomy.Body.EARTH -> r * .016f
                Astronomy.Body.MARS -> r * .012f
            }
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(point.first, point.second, planetRadius * 2.7f,
                    intArrayOf(Color.WHITE, colors.getValue(body), Color.TRANSPARENT), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(point.first, point.second, planetRadius * 2.7f, glow)
            if (body == Astronomy.Body.EARTH) earthPoint = point
        }
    }

    private fun drawGeocentric(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        val hourR = r * .63f
        val moonR = r * .88f
        drawSprocket(canvas, cx, cy, hourR, 24, majorEvery = 1, inward = r * .045f)
        drawSprocket(canvas, cx, cy, moonR, 30, majorEvery = 5, inward = r * .035f)
        text.textSize = r * .042f
        for (hour in 0 until 24) drawRotatedText(canvas, hour.toString(), cx, cy, hourR * .91f, -90.0 + hour * 15.0, text, true)

        val local = selectedInstant.atZone(zone)
        text.textSize = r * .040f
        for (i in 0 until 29) {
            val date = local.toLocalDate().plusDays(i.toLong())
            drawRotatedText(canvas, date.dayOfMonth.toString(), cx, cy, moonR * .94f,
                -90.0 + i * 360.0 / Astronomy.SYNODIC_MONTH_DAYS, text, true)
        }

        // Sun stays at the top in Earth-following view, as in the original camera behavior.
        drawSunBloom(canvas, cx, cy - r * .99f, r * .105f)
        val sunHand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 255, 255, 255) }
        canvas.drawPath(Path().apply {
            moveTo(cx - r * .035f, cy); lineTo(cx, cy - r); lineTo(cx + r * .035f, cy); close()
        }, sunHand)

        drawCalendarDayEvents(canvas, cx, cy, hourR)
        val sphereRadius = r * .42f
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
        val moonGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(moonPoint.first, moonPoint.second, r * .035f,
                intArrayOf(Color.WHITE, Color.rgb(185, 185, 170), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(moonPoint.first, moonPoint.second, r * .035f, moonGlow)
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

    private fun drawSunBloom(canvas: Canvas, x: Float, y: Float, core: Float) {
        val glowRadius = core * 6.2f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, glowRadius,
                intArrayOf(Color.WHITE, Color.argb(235, 255, 241, 195), Color.argb(105, 255, 90, 60), Color.argb(34, 170, 30, 30), Color.TRANSPARENT),
                floatArrayOf(0f, .12f, .32f, .62f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(x, y, glowRadius, glow)
        val rays = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 255, 220, 200); strokeWidth = maxOf(1f, core * .045f) }
        for (i in 0 until 28) {
            val a = i * 2 * PI / 28
            val ray = core * (2.6f + (i % 5) * .55f)
            canvas.drawLine(x + cos(a).toFloat() * core, y + sin(a).toFloat() * core,
                x + cos(a).toFloat() * ray, y + sin(a).toFloat() * ray, rays)
        }
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, core, fill)
        white.color = Color.argb(125, 255, 55, 70)
        white.strokeWidth = core * .13f
        canvas.drawCircle(x, y, core * 3.1f, white)
    }

    private fun drawChrome(canvas: Canvas) {
        val margin = 18f * density
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND }
        for (i in 0..2) canvas.drawLine(margin, margin + i * 7f * density, margin + 25f * density, margin + i * 7f * density, line)
        if (showClock) {
            val local = selectedInstant.atZone(zone)
            text.textSize = 24f * density
            canvas.drawText(local.format(DateTimeFormatter.ofPattern("dd/MM/yy   HH : mm : ss")), width / 2f, 33f * density, text)
        }
        if (!realtime) {
            fill.color = Color.argb(220, 15, 15, 15)
            val rect = RectF(width * .25f, height - 58f * density, width * .75f, height - 14f * density)
            canvas.drawRoundRect(rect, 5f * density, 5f * density, fill)
            white.color = Color.WHITE; white.strokeWidth = density
            canvas.drawRoundRect(rect, 5f * density, 5f * density, white)
            text.textSize = 18f * density
            canvas.drawText("RESET CURRENT TIME", width / 2f, height - 29f * density, text)
        }
    }

    private fun drawMenu(canvas: Canvas) {
        val panelWidth = min(width * .76f, 340f * density)
        fill.color = Color.argb(242, 0, 0, 0)
        canvas.drawRect(0f, 0f, panelWidth, height.toFloat(), fill)
        white.color = Color.argb(165, 255, 255, 255); white.strokeWidth = density
        canvas.drawLine(panelWidth, 0f, panelWidth, height.toFloat(), white)
        menuRows.clear()
        canvas.save()
        canvas.clipRect(0f, 0f, panelWidth, height.toFloat())
        canvas.translate(0f, -menuScrollY)
        var top = 58f * density
        top = drawMenuButton(canvas, "SHOW CLOCK", top, checked = showClock, calendarId = ACTION_CLOCK)
        top = drawMenuButton(canvas, "GALACTIC", top, checked = state == ViewState.GALACTIC, calendarId = ACTION_GALACTIC)
        top = drawMenuButton(canvas, "FLIP N/S", top, checked = !north, calendarId = ACTION_NORTH)
        top = drawMenuButton(canvas, "QUIT", top, checked = false, calendarId = ACTION_QUIT)
        top += 12f * density
        dimText.textAlign = Paint.Align.LEFT; dimText.textSize = 16f * density
        canvas.drawText("GOOGLE CALENDAR", 20f * density, top + 18f * density, dimText)
        dimText.textAlign = Paint.Align.CENTER
        top += 30f * density
        if (permissionDenied) {
            text.textSize = 15f * density
            canvas.drawText("Calendar permission denied", panelWidth / 2f, top + 24f * density, text)
        } else if (calendars.isEmpty()) {
            text.textSize = 15f * density
            canvas.drawText("No synced calendars found", panelWidth / 2f, top + 24f * density, text)
        } else {
            calendars.forEach { calendar ->
                val account = if (calendar.isGoogle) "Google" else calendar.accountType.substringAfterLast('.')
                top = drawMenuButton(canvas, "${calendar.displayName}  ·  $account", top,
                    checked = calendar.id in selectedCalendarIds, calendarId = calendar.id, color = calendar.color)
            }
        }
        menuContentHeight = top + 16f * density
        menuScrollY = menuScrollY.coerceIn(0f, (menuContentHeight - height).coerceAtLeast(0f))
        canvas.restore()
    }

    private fun drawMenuButton(canvas: Canvas, label: String, top: Float, checked: Boolean, calendarId: Long, color: Int = Color.WHITE): Float {
        val panelWidth = min(width * .76f, 340f * density)
        val rect = RectF(12f * density, top, panelWidth - 12f * density, top + 48f * density)
        fill.color = if (checked) Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)) else Color.BLACK
        canvas.drawRoundRect(rect, 4f * density, 4f * density, fill)
        white.color = Color.argb(150, 255, 255, 255); white.strokeWidth = density
        canvas.drawRoundRect(rect, 4f * density, 4f * density, white)
        fill.color = if (checked) color else Color.DKGRAY
        canvas.drawCircle(rect.left + 18f * density, rect.centerY(), 6f * density, fill)
        text.textSize = 18f * density; text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, rect.left + 34f * density, rect.centerY() + 6f * density, text)
        text.textAlign = Paint.Align.CENTER
        menuRows += MenuRow(RectF(rect).apply { offset(0f, -menuScrollY) }, calendarId)
        return rect.bottom + 8f * density
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (cx, cy, r) = geometry()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x < 64f * density && event.y < 64f * density) {
                    menuOpen = !menuOpen; invalidate(); return true
                }
                if (menuOpen) {
                    if (event.x <= min(width * .76f, 340f * density)) {
                        menuLastTouchY = event.y
                        menuDragging = false
                        return true
                    }
                    menuOpen = false; invalidate(); return true
                }
                if (!realtime && event.y > height - 75f * density && event.x in width * .2f..width * .8f) {
                    resetNow(); return true
                }
                val radiusFromCenter = distance(event.x, event.y, cx, cy)
                if (state == ViewState.HELIOCENTRIC && distance(event.x, event.y, earthPoint.first, earthPoint.second) < r * .09f) {
                    dragMode = DragMode.YEAR; realtime = false; updateYearDrag(event.x, event.y); return true
                }
                if (state == ViewState.HELIOCENTRIC && radiusFromCenter < r * .13f) {
                    state = ViewState.GEOCENTRIC; invalidate(); return true
                }
                if (state == ViewState.GEOCENTRIC && distance(event.x, event.y, moonPoint.first, moonPoint.second) < r * .09f) {
                    dragMode = DragMode.MOON; realtime = false; updateMoonDrag(event.x, event.y); return true
                }
                if (state == ViewState.HELIOCENTRIC && radiusFromCenter > r * .78f) {
                    dragMode = DragMode.YEAR; realtime = false; updateYearDrag(event.x, event.y); return true
                }
                if (state == ViewState.GEOCENTRIC && radiusFromCenter < r * .48f) {
                    state = ViewState.HELIOCENTRIC; invalidate(); return true
                }
                if (state == ViewState.GALACTIC) { state = ViewState.HELIOCENTRIC; invalidate(); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                if (menuOpen) {
                    val delta = menuLastTouchY - event.y
                    if (kotlin.math.abs(delta) > density) menuDragging = true
                    menuScrollY = (menuScrollY + delta).coerceIn(0f, (menuContentHeight - height).coerceAtLeast(0f))
                    menuLastTouchY = event.y
                    invalidate()
                } else when (dragMode) {
                    DragMode.YEAR -> updateYearDrag(event.x, event.y)
                    DragMode.MOON -> updateMoonDrag(event.x, event.y)
                    DragMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                if (menuOpen && !menuDragging) {
                    menuRows.firstOrNull { it.bounds.contains(event.x, event.y) }?.let { activateMenuRow(it.calendarId) }
                }
                menuDragging = false
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

    private fun activateMenuRow(id: Long?) {
        when (id) {
            ACTION_CLOCK -> showClock = !showClock
            ACTION_GALACTIC -> state = if (state == ViewState.GALACTIC) ViewState.HELIOCENTRIC else ViewState.GALACTIC
            ACTION_NORTH -> north = !north
            ACTION_QUIT -> onQuit?.invoke()
            null -> Unit
            else -> {
                if (!selectedCalendarIds.add(id)) selectedCalendarIds.remove(id)
                onCalendarSelectionChanged?.invoke(selectedCalendarIds.toSet())
            }
        }
        invalidate()
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

    private fun resetNow() {
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
    private fun Paint.letterSpacingCompat(value: Float) { /* Canvas Paint has no cross-version letter-spacing; retained as a layout marker. */ }

    companion object {
        private const val ACTION_CLOCK = -1L
        private const val ACTION_GALACTIC = -2L
        private const val ACTION_NORTH = -3L
        private const val ACTION_QUIT = -4L
    }
}
