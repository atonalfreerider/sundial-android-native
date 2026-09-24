package com.primesoftwaresystems.sundial.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.primesoftwaresystems.sundial.astronomy.Astronomy

/**
 * The classical astronomical symbols, engraved as strokes like the hands of an astrological watch:
 * Mercury ☿, Venus ♀ (female), Earth ⊕, Mars ♂ (male) and the lunar crescent ☽. Drawn as paths
 * rather than font glyphs so they share one line weight and never fall back to colour emoji.
 */
internal class PlanetSymbols {
    private val path = Path()
    private val oval = RectF()

    /** Draws [body]'s symbol centred on ([x], [y]), about 2 × [size] tall, with a stroke [paint]. */
    fun draw(canvas: Canvas, body: Astronomy.Body, x: Float, y: Float, size: Float, paint: Paint) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = size * .11f
        when (body) {
            Astronomy.Body.MERCURY -> {
                canvas.drawCircle(x, y - size * .08f, size * .32f, paint)
                canvas.drawLine(x, y + size * .24f, x, y + size * .9f, paint)
                canvas.drawLine(x - size * .26f, y + size * .6f, x + size * .26f, y + size * .6f, paint)
                oval.set(x - size * .3f, y - size * .98f, x + size * .3f, y - size * .38f)
                canvas.drawArc(oval, 0f, 180f, false, paint)
            }
            Astronomy.Body.VENUS -> {
                canvas.drawCircle(x, y - size * .28f, size * .4f, paint)
                canvas.drawLine(x, y + size * .12f, x, y + size * .92f, paint)
                canvas.drawLine(x - size * .3f, y + size * .55f, x + size * .3f, y + size * .55f, paint)
            }
            Astronomy.Body.EARTH -> {
                canvas.drawCircle(x, y, size * .5f, paint)
                canvas.drawLine(x - size * .5f, y, x + size * .5f, y, paint)
                canvas.drawLine(x, y - size * .5f, x, y + size * .5f, paint)
            }
            Astronomy.Body.MARS -> {
                val cx = x - size * .14f
                val cy = y + size * .14f
                canvas.drawCircle(cx, cy, size * .4f, paint)
                val tipX = x + size * .62f
                val tipY = y - size * .62f
                canvas.drawLine(cx + size * .28f, cy - size * .28f, tipX, tipY, paint)
                path.rewind()
                path.moveTo(tipX - size * .36f, tipY)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX, tipY + size * .36f)
                canvas.drawPath(path, paint)
            }
        }
    }

    /** The lunar crescent, horns turned away from the Sun at [sunAngleDegrees] (canvas angle). */
    fun drawCrescent(canvas: Canvas, x: Float, y: Float, size: Float, sunAngleDegrees: Double, paint: Paint) {
        val away = Math.toRadians(sunAngleDegrees + 180.0)
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        path.addCircle(x, y, size * .5f, Path.Direction.CW)
        path.addCircle(
            x + kotlin.math.cos(away).toFloat() * size * .24f,
            y + kotlin.math.sin(away).toFloat() * size * .24f,
            size * .42f,
            Path.Direction.CW,
        )
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }
}
