package com.primesoftwaresystems.sundial.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders the Moon as seen from solar north. Sunlight therefore travels in the screen plane:
 * the visible disk is divided by a central terminator whose bright side always faces the Sun.
 */
class MoonSphereRenderer {
    private var cachedSize = 0
    private var cachedLightBucket = Int.MIN_VALUE
    private var cached: Bitmap? = null
    private val retainedFrames = ArrayDeque<Bitmap>(8)

    fun render(size: Int, lightDx: Float, lightDy: Float): Bitmap {
        val safeSize = size.coerceIn(24, 180)
        val length = hypot(lightDx.toDouble(), lightDy.toDouble()).coerceAtLeast(0.001)
        val lx = lightDx / length
        val ly = lightDy / length
        val lightBucket = Math.toDegrees(atan2(ly, lx)).toInt()
        cached?.let {
            if (cachedSize == safeSize && cachedLightBucket == lightBucket) return it
        }

        val bucketAngle = Math.toRadians(lightBucket.toDouble())
        val bucketLx = cos(bucketAngle)
        val bucketLy = sin(bucketAngle)
        val output = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        val center = (safeSize - 1) / 2.0
        val radius = safeSize * .485

        for (py in 0 until safeSize) {
            val sy = (py - center) / radius
            for (px in 0 until safeSize) {
                val sx = (px - center) / radius
                val rr = sx * sx + sy * sy
                if (rr > 1.0) continue
                val sz = sqrt(1.0 - rr)
                val diffuse = max(0.0, sx * bucketLx + sy * bucketLy)
                val maria = lunarMaria(sx, sy)
                val surface = (1.0 - maria * .25).coerceIn(.68, 1.0)
                val illumination = (.085 + diffuse * .915) * (.72 + sz * .28)
                val edgeAlpha = ((1.0 - ((rr - .93) / .07).coerceIn(0.0, 1.0)) * 255).toInt()
                val red = (238 * surface * illumination).toInt().coerceIn(0, 255)
                val green = (234 * surface * illumination).toInt().coerceIn(0, 255)
                val blue = (215 * surface * illumination).toInt().coerceIn(0, 255)
                pixels[py * safeSize + px] = Color.argb(edgeAlpha, red, green, blue)
            }
        }
        output.setPixels(pixels, 0, safeSize, 0, 0, safeSize, safeSize)
        cached?.let {
            retainedFrames.addLast(it)
            while (retainedFrames.size > 8) retainedFrames.removeFirst()
        }
        cached = output
        cachedSize = safeSize
        cachedLightBucket = lightBucket
        return output
    }

    private fun lunarMaria(x: Double, y: Double): Double {
        fun crater(cx: Double, cy: Double, radius: Double): Double {
            val distanceSquared = (x - cx).pow(2) + (y - cy).pow(2)
            return (1.0 - distanceSquared / (radius * radius)).coerceIn(0.0, 1.0)
        }
        return maxOf(
            crater(-.28, -.18, .22),
            crater(.24, .22, .16),
            crater(.04, -.41, .11),
            crater(.39, -.18, .09),
        )
    }
}
