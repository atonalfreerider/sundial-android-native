package com.metavirtuoso.sundial.ui

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
    private var cachedBrass = false
    private var cached: Bitmap? = null
    private val retainedFrames = ArrayDeque<Bitmap>(8)

    /**
     * [brass] engraves the Moon in polished brass instead: etched maria and a 30° graticule, lit
     * the same way.
     */
    fun render(size: Int, lightDx: Float, lightDy: Float, brass: Boolean = false): Bitmap {
        val safeSize = size.coerceIn(24, 180)
        val length = hypot(lightDx.toDouble(), lightDy.toDouble()).coerceAtLeast(0.001)
        val lx = lightDx / length
        val ly = lightDy / length
        val lightBucket = Math.toDegrees(atan2(ly, lx)).toInt()
        cached?.let {
            if (cachedSize == safeSize && cachedLightBucket == lightBucket && cachedBrass == brass) return it
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
                // The dark side keeps some earthshine, so the whole disc reads; the lit side is bright.
                val illumination = (NIGHT_LIGHT + diffuse * (1.0 - NIGHT_LIGHT)) * (.78 + sz * .22)
                val edgeAlpha = ((1.0 - ((rr - .93) / .07).coerceIn(0.0, 1.0)) * 255).toInt()
                pixels[py * safeSize + px] = if (brass) {
                    brassPixel(sx, sy, sz, maria, illumination, safeSize, edgeAlpha)
                } else {
                    val surface = (1.0 - maria * .25).coerceIn(.68, 1.0)
                    Color.argb(edgeAlpha,
                        (252 * surface * illumination).toInt().coerceIn(0, 255),
                        (248 * surface * illumination).toInt().coerceIn(0, 255),
                        (230 * surface * illumination).toInt().coerceIn(0, 255))
                }
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
        cachedBrass = brass
        return output
    }

    private fun brassPixel(x: Double, y: Double, z: Double, maria: Double, light: Double, size: Int, alpha: Int): Int {
        // The Moon's own latitude and longitude, seen face on.
        val latitude = Math.toDegrees(kotlin.math.asin(-y))
        val longitude = Math.toDegrees(atan2(x, z))
        val width = 1.6 * 57.3 / (size * .485) / z.coerceAtLeast(.25)
        fun nearLine(angle: Double) = kotlin.math.abs(angle - 30.0 * kotlin.math.round(angle / 30.0)) < width
        // Maria are hatched with fine diagonal strokes; the graticule is cut across everything.
        val hatched = maria > .25 && ((x + y) * size * .22).let { it - kotlin.math.floor(it) } < .42
        val etched = nearLine(latitude) || (kotlin.math.abs(latitude) < 80 && nearLine(longitude)) || hatched
        val sheen = max(0.0, -y * .5 + z * .6 - x * .2).pow(16) * 92
        val daylight = ((light - NIGHT_LIGHT) / (1.0 - NIGHT_LIGHT)).coerceIn(0.0, 1.0)
        val metalLight = .56 + daylight * .44
        val shade = metalLight * (.86 + z * .14)
        var red = 244 * shade + sheen
        var green = 207 * shade + sheen * .9
        var blue = 121 * shade + sheen * .6
        if (etched) {
            red = red * .24 + 69 * .76 * metalLight
            green = green * .24 + 44 * .76 * metalLight
            blue = blue * .24 + 14 * .76 * metalLight
        }
        return Color.argb(alpha, red.toInt().coerceIn(0, 255), green.toInt().coerceIn(0, 255), blue.toInt().coerceIn(0, 255))
    }

    private companion object {
        /** Share of full sunlight on the Moon's dark side (earthshine). */
        const val NIGHT_LIGHT = .26
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
