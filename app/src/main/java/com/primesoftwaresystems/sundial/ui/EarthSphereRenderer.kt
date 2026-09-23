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
    private val retainedFrames = ArrayDeque<Bitmap>(12)

    fun render(size: Int, rotationDegrees: Double, north: Boolean): Bitmap {
        val safeSize = size.coerceIn(48, 420)
        val bucket = (rotationDegrees * 2.0).toInt() // half-degree cache resolution
        cached?.let {
            if (cachedSize == safeSize && cachedRotationBucket == bucket && cachedNorth == north) return it
        }

        // Do not recycle or mutate the previous frame: a hardware Canvas display list can still
        // reference it while the next rotation is being prepared.
        val output = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        val texturePixels = IntArray(source.width * source.height)
        source.getPixels(texturePixels, 0, source.width, 0, 0, source.width, source.height)
        val rotation = Math.toRadians(bucket / 2.0)
        val tilt = Math.toRadians(23.43928)
        // The camera is over solar north and the Sun is at the top of the instrument. Keep a small
        // camera-facing component for relief, but let the in-plane component form a real terminator.
        val light = doubleArrayOf(0.03, 0.93, 0.365)
        val center = (safeSize - 1) / 2.0
        val radius = safeSize * 0.485

        for (py in 0 until safeSize) {
            val sy = -(py - center) / radius
            for (px in 0 until safeSize) {
                val sx = (px - center) / radius
                val rr = sx * sx + sy * sy
                if (rr > 1.0) continue
                val sz = sqrt(1.0 - rr)

                // Camera looks down from ecliptic north/south. Earth's geographic pole is offset
                // by its 23.4° obliquity, so it appears above center instead of facing the camera.
                val sign = if (north) 1.0 else -1.0
                val worldX = sx
                val worldY = sy * sign * sin(tilt) + sz * sign * cos(tilt)
                val worldZ = sy * -cos(tilt) + sz * sin(tilt)
                val rx = worldX * cos(rotation) - worldZ * sin(rotation)
                val rz = worldX * sin(rotation) + worldZ * cos(rotation)
                val longitude = atan2(rx, rz)
                val latitude = asin(worldY.coerceIn(-1.0, 1.0))
                val u = ((longitude / (2.0 * PI) + 0.5) * source.width).toInt().floorMod(source.width)
                val v = ((0.5 - latitude / PI) * (source.height - 1)).toInt().coerceIn(0, source.height - 1)
                val sample = texturePixels[v * source.width + u]

                val rawDiffuse = sx * light[0] + sy * light[1] + sz * light[2]
                val diffuse = ((rawDiffuse + 0.12) / 1.12).coerceIn(0.0, 1.0)
                val rim = (1.0 - sz).pow(2.6)
                val illumination = (0.64 + diffuse * 0.36).coerceAtMost(1.0)
                val atmosphere = (rim * 72).toInt()
                // The satellite texture has near-black oceans. Lift its photographic floor before
                // lighting so every longitude still reads as Earth, while the terminator remains.
                fun lift(channel: Int) = 255.0 * (channel / 255.0).pow(0.52)
                val red = (lift(Color.red(sample)) * illumination + 28 + atmosphere * 0.32).toInt().coerceIn(0, 255)
                val green = (lift(Color.green(sample)) * illumination + 38 + atmosphere * 0.52).toInt().coerceIn(0, 255)
                val blue = (lift(Color.blue(sample)) * illumination + 54 + atmosphere).toInt().coerceIn(0, 255)
                val edgeAlpha = ((1.0 - ((rr - 0.94) / 0.06).coerceIn(0.0, 1.0)) * 255).toInt()
                pixels[py * safeSize + px] = Color.argb(edgeAlpha, red, green, blue)
            }
        }
        output.setPixels(pixels, 0, safeSize, 0, 0, safeSize, safeSize)
        cached?.let {
            retainedFrames.addLast(it)
            while (retainedFrames.size > 12) retainedFrames.removeFirst()
        }
        cached = output
        cachedSize = safeSize
        cachedRotationBucket = bucket
        cachedNorth = north
        return output
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
