package com.primesoftwaresystems.sundial.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small native software 3D renderer. Rays intersect a sphere, normals are lit by the central sun,
 * and the equirectangular map is sampled after inverse axial/diurnal rotation.
 */
class EarthSphereRenderer(private val source: Bitmap) {
    private var cachedSize = 0
    private var cachedRotationBucket = Int.MIN_VALUE
    private var cachedNorth = true
    private var cached: Bitmap? = null

    fun render(size: Int, rotationDegrees: Double, north: Boolean): Bitmap {
        val safeSize = size.coerceIn(48, 420)
        val bucket = (rotationDegrees * 2.0).toInt() // half-degree cache resolution
        cached?.let {
            if (cachedSize == safeSize && cachedRotationBucket == bucket && cachedNorth == north) return it
        }

        val output = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        val texturePixels = IntArray(source.width * source.height)
        source.getPixels(texturePixels, 0, source.width, 0, 0, source.width, source.height)
        val rotation = Math.toRadians(bucket / 2.0)
        val tilt = Math.toRadians(if (north) 23.43928 else -23.43928)
        val light = doubleArrayOf(-0.38, -0.55, 0.74)
        val center = (safeSize - 1) / 2.0
        val radius = safeSize * 0.485

        for (py in 0 until safeSize) {
            val sy = -(py - center) / radius
            for (px in 0 until safeSize) {
                val sx = (px - center) / radius
                val rr = sx * sx + sy * sy
                if (rr > 1.0) continue
                val sz = sqrt(1.0 - rr)

                // Inverse axial tilt, then inverse spin, maps the visible normal to texture coordinates.
                val ty = sy * cos(tilt) + sz * sin(tilt)
                val tz = -sy * sin(tilt) + sz * cos(tilt)
                val tx = sx
                val rx = tx * cos(rotation) - tz * sin(rotation)
                val rz = tx * sin(rotation) + tz * cos(rotation)
                val longitude = atan2(rx, rz)
                val latitude = asin(ty.coerceIn(-1.0, 1.0))
                val u = ((longitude / (2.0 * PI) + 0.5) * source.width).toInt().floorMod(source.width)
                val v = ((0.5 - latitude / PI) * (source.height - 1)).toInt().coerceIn(0, source.height - 1)
                val sample = texturePixels[v * source.width + u]

                val diffuse = (sx * light[0] + sy * light[1] + sz * light[2]).coerceAtLeast(0.0)
                val rim = (1.0 - sz).pow(2.6)
                val illumination = (0.075 + diffuse * 0.96).coerceAtMost(1.0)
                val atmosphere = (rim * 72).toInt()
                val red = (Color.red(sample) * illumination + atmosphere * 0.32).toInt().coerceIn(0, 255)
                val green = (Color.green(sample) * illumination + atmosphere * 0.52).toInt().coerceIn(0, 255)
                val blue = (Color.blue(sample) * illumination + atmosphere).toInt().coerceIn(0, 255)
                val edgeAlpha = ((1.0 - ((rr - 0.94) / 0.06).coerceIn(0.0, 1.0)) * 255).toInt()
                pixels[py * safeSize + px] = Color.argb(edgeAlpha, red, green, blue)
            }
        }
        output.setPixels(pixels, 0, safeSize, 0, 0, safeSize, safeSize)
        cached?.recycle()
        cached = output
        cachedSize = safeSize
        cachedRotationBucket = bucket
        cachedNorth = north
        return output
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
