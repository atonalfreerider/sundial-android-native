package com.primesoftwaresystems.sundial.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.primesoftwaresystems.sundial.astronomy.Astronomy
import com.primesoftwaresystems.sundial.astronomy.Zodiac
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Small native software 3D renderer. Rays intersect a sphere, normals are lit by the Sun at the top
 * of the frame, and the equirectangular map is sampled after inverse axial/diurnal rotation.
 */
class EarthSphereRenderer(private val source: Bitmap) {
    private var cachedSize = 0
    private var cachedTimeBucket = Long.MIN_VALUE
    private var cachedNorth = true
    private var cachedHighlight: Int? = null
    private var cached: Bitmap? = null
    // Hardware display lists may retain several old frames. Strongly retaining the recent bitmaps
    // prevents a driver from briefly sampling reclaimed storage during fast time scrubbing.
    private val retainedFrames = ArrayDeque<Bitmap>(48)
    private val texturePixels: IntArray by lazy {
        IntArray(source.width * source.height).also {
            source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
        }
    }

    /**
     * Renders the globe as Unity's Earth camera saw it: from the ecliptic pole with the Sun at the
     * top. Solar noon faces the Sun, the axis keeps its fixed tilt in space (so it leans toward the
     * Sun in June and away in December), and the surface turns with sidereal time.
     *
     * [highlightOffsetMinutes] paints Unity's red time-zone strip along that zone's meridian band.
     */
    fun render(size: Int, instant: Instant, north: Boolean, highlightOffsetMinutes: Int? = null): Bitmap {
        val safeSize = size.coerceIn(48, 420)
        val bucket = instant.epochSecond / 30L
        cached?.let {
            if (cachedSize == safeSize && cachedTimeBucket == bucket && cachedNorth == north &&
                cachedHighlight == highlightOffsetMinutes) return it
        }

        // Do not recycle or mutate the previous frame: a hardware Canvas display list can still
        // reference it while the next rotation is being prepared.
        val output = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeSize * safeSize)
        val texture = texturePixels
        val sidereal = Math.toRadians(Astronomy.greenwichMeanSiderealDegrees(instant))
        val sunLongitude = Zodiac.sunLongitude(instant)
        val center = (safeSize - 1) / 2.0
        val radius = safeSize * 0.485
        val highlightCenter = highlightOffsetMinutes?.let { Math.toRadians(it / 4.0) }
        val highlightHalfWidth = Math.toRadians(7.5)
        // Inlined EarthOrientation.screenToEquatorial: the per-pixel path must not allocate.
        val sinSun = kotlin.math.sin(Math.toRadians(sunLongitude))
        val cosSun = kotlin.math.cos(Math.toRadians(sunLongitude))
        val sinObliquity = kotlin.math.sin(Math.toRadians(EarthOrientation.OBLIQUITY_DEGREES))
        val cosObliquity = kotlin.math.cos(Math.toRadians(EarthOrientation.OBLIQUITY_DEGREES))
        val mirror = if (north) 1.0 else -1.0

        for (py in 0 until safeSize) {
            val sy = -(py - center) / radius
            for (px in 0 until safeSize) {
                val sx = (px - center) / radius
                val rr = sx * sx + sy * sy
                if (rr > 1.0) continue
                val sz = sqrt(1.0 - rr)

                val right = sx * mirror
                val eclX = right * sinSun + sy * cosSun
                val eclY = -right * cosSun + sy * sinSun
                val eclZ = sz * mirror
                val eqY = eclY * cosObliquity - eclZ * sinObliquity
                val eqZ = eclY * sinObliquity + eclZ * cosObliquity
                val longitude = atan2(eqY, eclX) - sidereal
                val latitude = asin(eqZ.coerceIn(-1.0, 1.0))
                val wrappedLongitude = longitude / (2.0 * PI) - floor(longitude / (2.0 * PI) + 0.5)
                val u = ((wrappedLongitude + 0.5) * source.width).toInt().floorMod(source.width)
                val v = ((0.5 - latitude / PI) * (source.height - 1)).toInt().coerceIn(0, source.height - 1)
                val sample = texture[v * source.width + u]

                // From the ecliptic pole, sunlight is in the screen plane. This produces the
                // required half-lit globe and a terminator through its center.
                val diffuse = sy.coerceIn(0.0, 1.0)
                val rim = (1.0 - sz).pow(2.6)
                val illumination = (0.10 + diffuse * 0.90).coerceAtMost(1.0)
                val atmosphere = (rim * 72).toInt()
                // The satellite texture has near-black oceans. Lift its photographic floor before
                // lighting so every longitude still reads as Earth, while the terminator remains.
                fun lift(channel: Int) = 255.0 * (channel / 255.0).pow(0.52)
                var red = lift(Color.red(sample)) * illumination + 10 + atmosphere * 0.32
                var green = lift(Color.green(sample)) * illumination + 14 + atmosphere * 0.52
                var blue = lift(Color.blue(sample)) * illumination + 20 + atmosphere
                if (highlightCenter != null) {
                    var delta = (longitude - highlightCenter) % (2.0 * PI)
                    if (delta > PI) delta -= 2.0 * PI
                    if (delta < -PI) delta += 2.0 * PI
                    if (kotlin.math.abs(delta) <= highlightHalfWidth) {
                        val strength = 0.62 * (0.45 + illumination * 0.55)
                        red = red * (1 - strength) + 235 * strength
                        green = green * (1 - strength) + 22 * strength
                        blue = blue * (1 - strength) + 30 * strength
                    }
                }
                val edgeAlpha = ((1.0 - ((rr - 0.94) / 0.06).coerceIn(0.0, 1.0)) * 255).toInt()
                pixels[py * safeSize + px] = Color.argb(
                    edgeAlpha,
                    red.toInt().coerceIn(0, 255),
                    green.toInt().coerceIn(0, 255),
                    blue.toInt().coerceIn(0, 255),
                )
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
        cachedHighlight = highlightOffsetMinutes
        return output
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
