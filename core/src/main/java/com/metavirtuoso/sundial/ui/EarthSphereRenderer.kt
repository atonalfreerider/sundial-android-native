package com.metavirtuoso.sundial.ui

import android.graphics.Bitmap
import com.metavirtuoso.sundial.astronomy.Astronomy
import com.metavirtuoso.sundial.astronomy.Zodiac
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
 *
 * The globe is always rendered at one fixed resolution and drawn scaled (with mipmaps), so the
 * tiny planet in the solar view, the full Earth view and every frame of the flight between them
 * share a single cached frame instead of re-rendering whenever the on-screen size changes.
 */
class EarthSphereRenderer(private val source: Bitmap, val size: Int = DEFAULT_SIZE) {
    private data class FrameKey(val timeBucket: Long, val north: Boolean, val highlightOffsetMinutes: Int?)

    /**
     * The planet glyph (no zone strip) and the Earth view's globe (with it) are both on screen
     * during the camera flight, so the cache holds a few variants rather than alternating between
     * two full renders every frame.
     */
    private val frames = object : LinkedHashMap<FrameKey, Bitmap>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FrameKey, Bitmap>?) = this.size > 3
    }

    private val texturePixels: IntArray by lazy {
        IntArray(source.width * source.height).also {
            source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
        }
    }

    /**
     * Everything that depends only on the pixel position, not on time: the view ray, sunlight,
     * atmospheric rim and antialiased edge.
     */
    private class SphereSamples(size: Int) {
        val index: IntArray
        val sx: DoubleArray
        val sy: DoubleArray
        val sz: DoubleArray
        val illumination: FloatArray
        val atmosphere: FloatArray
        val alpha: IntArray

        init {
            val center = (size - 1) / 2.0
            val radius = size * 0.485
            val indices = ArrayList<Int>()
            for (py in 0 until size) for (px in 0 until size) {
                val x = (px - center) / radius
                val y = -(py - center) / radius
                if (x * x + y * y <= 1.0) indices += py * size + px
            }
            index = indices.toIntArray()
            sx = DoubleArray(index.size)
            sy = DoubleArray(index.size)
            sz = DoubleArray(index.size)
            illumination = FloatArray(index.size)
            atmosphere = FloatArray(index.size)
            alpha = IntArray(index.size)
            index.forEachIndexed { i, pixel ->
                val x = (pixel % size - center) / radius
                val y = -(pixel / size - center) / radius
                val rr = x * x + y * y
                val z = sqrt(1.0 - rr)
                sx[i] = x
                sy[i] = y
                sz[i] = z
                // From the ecliptic pole, sunlight is in the screen plane. This produces the
                // required half-lit globe and a terminator through its center.
                illumination[i] = (0.10 + y.coerceIn(0.0, 1.0) * 0.90).toFloat()
                atmosphere[i] = (1.0 - z).pow(2.6).times(72).toInt().toFloat()
                alpha[i] = ((1.0 - ((rr - 0.94) / 0.06).coerceIn(0.0, 1.0)) * 255).toInt()
            }
        }
    }

    private val samples by lazy { SphereSamples(size) }

    /**
     * The satellite texture has near-black oceans. Lift its photographic floor before lighting so
     * every longitude still reads as Earth, while the terminator remains.
     */
    private val lift = FloatArray(256) { (255.0 * (it / 255.0).pow(0.52)).toFloat() }

    /**
     * Renders the globe as Unity's Earth camera saw it: from the ecliptic pole with the Sun at the
     * top. Solar noon faces the Sun, the axis keeps its fixed tilt in space (so it leans toward the
     * Sun in June and away in December), and the surface turns with sidereal time.
     *
     * [highlightOffsetMinutes] paints Unity's red time-zone strip along that zone's meridian band.
     */
    fun render(instant: Instant, north: Boolean, highlightOffsetMinutes: Int? = null): Bitmap {
        val key = FrameKey(instant.epochSecond / 30L, north, highlightOffsetMinutes)
        frames[key]?.let { return it }

        val s = samples
        val pixels = IntArray(size * size)
        val texture = texturePixels
        val textureWidth = source.width
        val textureHeight = source.height
        val sidereal = Math.toRadians(Astronomy.greenwichMeanSiderealDegrees(instant))
        val sunLongitude = Math.toRadians(Zodiac.sunLongitude(instant))
        val highlightCenter = highlightOffsetMinutes?.let { Math.toRadians(it / 4.0) }
        val highlightHalfWidth = Math.toRadians(7.5)
        // Inlined EarthOrientation.screenToEquatorial: the per-pixel path must not allocate.
        val sinSun = kotlin.math.sin(sunLongitude)
        val cosSun = kotlin.math.cos(sunLongitude)
        val sinObliquity = kotlin.math.sin(Math.toRadians(EarthOrientation.OBLIQUITY_DEGREES))
        val cosObliquity = kotlin.math.cos(Math.toRadians(EarthOrientation.OBLIQUITY_DEGREES))
        val mirror = if (north) 1.0 else -1.0
        val twoPi = 2.0 * PI

        for (i in s.index.indices) {
            val right = s.sx[i] * mirror
            val up = s.sy[i]
            val eclX = right * sinSun + up * cosSun
            val eclY = -right * cosSun + up * sinSun
            val eclZ = s.sz[i] * mirror
            val eqY = eclY * cosObliquity - eclZ * sinObliquity
            val eqZ = eclY * sinObliquity + eclZ * cosObliquity
            val longitude = atan2(eqY, eclX) - sidereal
            val latitude = asin(eqZ.coerceIn(-1.0, 1.0))
            val turns = longitude / twoPi
            val wrapped = turns - floor(turns + 0.5)
            val u = ((wrapped + 0.5) * textureWidth).toInt().floorMod(textureWidth)
            val v = ((0.5 - latitude / PI) * (textureHeight - 1)).toInt().coerceIn(0, textureHeight - 1)
            val sample = texture[v * textureWidth + u]

            val light = s.illumination[i]
            val glow = s.atmosphere[i]
            var red = lift[sample shr 16 and 0xFF] * light + 10f + glow * 0.32f
            var green = lift[sample shr 8 and 0xFF] * light + 14f + glow * 0.52f
            var blue = lift[sample and 0xFF] * light + 20f + glow
            if (highlightCenter != null) {
                val delta = (longitude - highlightCenter) - twoPi * floor((longitude - highlightCenter) / twoPi + 0.5)
                if (kotlin.math.abs(delta) <= highlightHalfWidth) {
                    val strength = 0.62f * (0.45f + light * 0.55f)
                    red = red * (1 - strength) + 235 * strength
                    green = green * (1 - strength) + 22 * strength
                    blue = blue * (1 - strength) + 30 * strength
                }
            }
            pixels[s.index[i]] = (s.alpha[i] shl 24) or
                (red.toInt().coerceIn(0, 255) shl 16) or
                (green.toInt().coerceIn(0, 255) shl 8) or
                blue.toInt().coerceIn(0, 255)
        }

        // Never mutate a frame that may still be referenced by a recorded display list.
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, size, 0, 0, size, size)
        output.setHasMipMap(true)
        frames[key] = output
        return output
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    companion object {
        /** Enough for the Earth view's full-width globe on a phone. */
        const val DEFAULT_SIZE = 512
    }
}
