package com.primesoftwaresystems.sundial.ui

import android.graphics.Bitmap
import android.graphics.Color
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Small native software 3D renderer. Rays intersect a sphere, normals are lit by the central sun,
 * and the equirectangular map is sampled after inverse axial/diurnal rotation.
 */
class EarthSphereRenderer(private val source: Bitmap) {
    private var cachedSize = 0
    private var cachedTimeBucket = Long.MIN_VALUE
    private var cachedNorth = true
    private var cached: Bitmap? = null
    // Hardware display lists may retain several old frames. Strongly retaining the recent bitmaps
    // prevents a driver from briefly sampling reclaimed storage during fast time scrubbing.
    private val retainedFrames = ArrayDeque<Bitmap>(48)

    fun render(size: Int, instant: Instant, north: Boolean): Bitmap {
        val safeSize = size.coerceIn(48, 420)
        val bucket = instant.epochSecond / 30L
        cached?.let {
            if (cachedSize == safeSize && cachedTimeBucket == bucket && cachedNorth == north) return it
        }

        // Do not recycle or mutate the previous frame: a hardware Canvas display list can still
        // reference it while the next rotation is being prepared.
        val output = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        val texturePixels = IntArray(source.width * source.height)
        source.getPixels(texturePixels, 0, source.width, 0, 0, source.width, source.height)
        val frame = EarthOrientation.frame(instant, north)
        val center = (safeSize - 1) / 2.0
        val radius = safeSize * 0.485

        for (py in 0 until safeSize) {
            val sy = -(py - center) / radius
            for (px in 0 until safeSize) {
                val sx = (px - center) / radius
                val rr = sx * sx + sy * sy
                if (rr > 1.0) continue
                val sz = sqrt(1.0 - rr)

                val surface = frame.right * sx + frame.sunward * sy + frame.viewer * sz
                val latitude = asin(surface.dot(frame.northPole).coerceIn(-1.0, 1.0))
                val longitude = atan2(
                    surface.dot(frame.eastAtPrimeMeridian),
                    surface.dot(frame.primeMeridian),
                )
                val u = ((longitude / (2.0 * PI) + 0.5) * source.width).toInt().floorMod(source.width)
                val v = ((0.5 - latitude / PI) * (source.height - 1)).toInt().coerceIn(0, source.height - 1)
                val sample = texturePixels[v * source.width + u]

                // From solar north, sunlight is exactly in the screen plane. This produces the
                // required half-lit globe and a terminator through its center.
                val diffuse = sy.coerceIn(0.0, 1.0)
                val rim = (1.0 - sz).pow(2.6)
                val illumination = (0.10 + diffuse * 0.90).coerceAtMost(1.0)
                val atmosphere = (rim * 72).toInt()
                // The satellite texture has near-black oceans. Lift its photographic floor before
                // lighting so every longitude still reads as Earth, while the terminator remains.
                fun lift(channel: Int) = 255.0 * (channel / 255.0).pow(0.52)
                val red = (lift(Color.red(sample)) * illumination + 10 + atmosphere * 0.32).toInt().coerceIn(0, 255)
                val green = (lift(Color.green(sample)) * illumination + 14 + atmosphere * 0.52).toInt().coerceIn(0, 255)
                val blue = (lift(Color.blue(sample)) * illumination + 20 + atmosphere).toInt().coerceIn(0, 255)
                val edgeAlpha = ((1.0 - ((rr - 0.94) / 0.06).coerceIn(0.0, 1.0)) * 255).toInt()
                pixels[py * safeSize + px] = Color.argb(edgeAlpha, red, green, blue)
            }
        }
        output.setPixels(pixels, 0, safeSize, 0, 0, safeSize, safeSize)
        cached?.let {
            retainedFrames.addLast(it)
            while (retainedFrames.size > 48) retainedFrames.removeFirst()
        }
        cached = output
        cachedSize = safeSize
        cachedTimeBucket = bucket
        cachedNorth = north
        return output
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
