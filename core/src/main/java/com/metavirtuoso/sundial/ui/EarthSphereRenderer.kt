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
    private data class FrameKey(val timeBucket: Long, val north: Boolean, val highlightOffsetMinutes: Int?, val brass: Boolean)

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
                // required half-lit globe and a terminator through its center. The night side
                // keeps a third of the light, so every continent still reads in the dark.
                illumination[i] = (NIGHT_LIGHT.toDouble() + y.coerceIn(0.0, 1.0) *
                    (1.0 - NIGHT_LIGHT.toDouble())).toFloat()
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
    private val lift = FloatArray(256) { (255.0 * (it / 255.0).pow(0.46)).toFloat() }

    /** Land, from the texture: its oceans are a flat deep blue. */
    private val land: BooleanArray by lazy {
        val pixels = texturePixels
        BooleanArray(pixels.size) { i ->
            val p = pixels[i]
            val red = p shr 16 and 0xFF
            val green = p shr 8 and 0xFF
            val blue = p and 0xFF
            !(blue - maxOf(red, green) > 12 && red < 48)
        }
    }

    /** Texels on a coastline, for the brass globe's etched outlines. */
    private val coast: BooleanArray by lazy {
        val w = source.width
        val h = source.height
        val mask = land
        val reach = maxOf(1, w / 1024)
        BooleanArray(mask.size) { i ->
            val x = i % w
            val y = i / w
            val here = mask[i]
            (y >= reach && mask[i - reach * w] != here) ||
                (y < h - reach && mask[i + reach * w] != here) ||
                mask[y * w + (x + reach) % w] != here ||
                mask[y * w + (x - reach + w) % w] != here
        }
    }

    /**
     * Renders the globe as Unity's Earth camera saw it: from the ecliptic pole with the Sun at the
     * top. Solar noon faces the Sun, the axis keeps its fixed tilt in space (so it leans toward the
     * Sun in June and away in December), and the surface turns with sidereal time.
     *
     * [highlightOffsetMinutes] paints Unity's red time-zone strip along that zone's meridian band.
     */
    fun render(instant: Instant, north: Boolean, highlightOffsetMinutes: Int? = null, brass: Boolean = false): Bitmap {
        val key = FrameKey(instant.epochSecond / 30L, north, highlightOffsetMinutes, brass)
        frames[key]?.let { return it }
        val output = renderFrame(
            Astronomy.greenwichMeanSiderealDegrees(instant), Zodiac.sunLongitude(instant), north,
            highlightOffsetMinutes, lit = true, brass = brass,
        )
        frames[key] = output
        return output
    }

    /**
     * The globe's surface without sunlight, turned [siderealDegrees] and oriented like the solar
     * view's dial (ecliptic longitude L at canvas angle 180° − L). The watch face turns the Earth
     * by choosing one of these frames and lays [renderNightShade] over it toward the Sun.
     */
    fun renderSurface(siderealDegrees: Double): Bitmap =
        // With the Sun at longitude 270° the Sun-up frame is already the dial's orientation.
        renderFrame(siderealDegrees, 270.0, north = true, highlightOffsetMinutes = null, lit = false)

    /** The night side as a black veil in Sun-up coordinates: [render]'s lighting, apart from the surface. */
    fun renderNightShade(): Bitmap {
        val s = samples
        val pixels = IntArray(size * size)
        for (i in s.index.indices) {
            pixels[s.index[i]] = ((1f - s.illumination[i]) * s.alpha[i]).toInt().coerceIn(0, 255) shl 24
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    }

    private fun renderFrame(
        siderealDegrees: Double,
        sunLongitudeDegrees: Double,
        north: Boolean,
        highlightOffsetMinutes: Int?,
        lit: Boolean,
        brass: Boolean = false,
    ): Bitmap {
        val s = samples
        val pixels = IntArray(size * size)
        val texture = texturePixels
        val textureWidth = source.width
        val textureHeight = source.height
        val sidereal = Math.toRadians(siderealDegrees)
        val sunLongitude = Math.toRadians(sunLongitudeDegrees)
        val highlightCenter = highlightOffsetMinutes?.let { Math.toRadians(it / 4.0) }
        val highlightHalfWidth = Math.toRadians(7.5)
        // Inlined EarthOrientation.screenToEquatorial: the per-pixel path must not allocate.
        val sinSun = kotlin.math.sin(sunLongitude)
        val cosSun = kotlin.math.cos(sunLongitude)
        val sinObliquity = kotlin.math.sin(Math.toRadians(EarthOrientation.OBLIQUITY_DEGREES))
        val cosObliquity = kotlin.math.cos(Math.toRadians(EarthOrientation.OBLIQUITY_DEGREES))
        val mirror = if (north) 1.0 else -1.0
        val twoPi = 2.0 * PI
        val landMask = if (brass) land else null
        val coastMask = if (brass) coast else null
        // Graticule lines about a pixel and a half wide at the centre of the globe.
        val lineWidth = Math.toRadians(1.5 * 57.3 / (size * .485))
        val step = Math.toRadians(15.0)

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

            val light = if (lit) s.illumination[i] else 1f
            if (landMask != null && coastMask != null) {
                val texel = v * textureWidth + u
                fun nearLine(angle: Double, width: Double): Boolean {
                    val offset = angle - step * floor(angle / step + 0.5)
                    return kotlin.math.abs(offset) < width
                }
                // Lines thicken toward the limb as the surface turns away; the equator and
                // prime meridian are cut deeper.
                val width = lineWidth / s.sz[i].coerceAtLeast(.25)
                val majorLatitude = kotlin.math.abs(latitude) < width * 1.8
                val majorLongitude = kotlin.math.abs(wrapped * twoPi) < width * 1.8
                val etched = coastMask[texel] || majorLatitude || majorLongitude ||
                    nearLine(latitude, width) ||
                    (kotlin.math.abs(latitude) < Math.toRadians(80.0) && nearLine(wrapped * twoPi, width / kotlin.math.cos(latitude).coerceAtLeast(.2)))
                pixels[s.index[i]] = (s.alpha[i] shl 24) or brassPixel(landMask[texel], etched, light, s.sx[i] * mirror, s.sy[i], s.sz[i], highlightCenter, longitude, highlightHalfWidth)
                continue
            }
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
        return output
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    /**
     * One pixel of the brass globe: polished metal seas, matte land a shade darker, engraved
     * lines in dark ink, lit from the Sun with a soft specular sheen on the day side.
     */
    private fun brassPixel(
        isLand: Boolean, etched: Boolean, light: Float, x: Double, y: Double, z: Double,
        highlightCenter: Double?, longitude: Double, highlightHalfWidth: Double,
    ): Int {
        var red = if (isLand) 207f else 244f
        var green = if (isLand) 164f else 209f
        var blue = if (isLand) 82f else 126f
        // Brass reflects the surrounding face even on the night side. Remap the photographic
        // light floor to a warm half-light instead of multiplying the metal almost to black; the
        // remaining range is still large enough for the solar terminator to read immediately.
        val daylight = ((light - NIGHT_LIGHT) / (1f - NIGHT_LIGHT)).coerceIn(0f, 1f)
        val metalLight = .56f + daylight * .44f
        val shade = metalLight * (.86f + .14f * z.toFloat())
        val sheen = (maxOf(0.0, y * .55 + z * .55 - x * .2).pow(18) * 120).toFloat()
        red = red * shade + sheen
        green = green * shade + sheen * .9f
        blue = blue * shade + sheen * .6f
        if (highlightCenter != null) {
            val delta = (longitude - highlightCenter) - 2 * PI * floor((longitude - highlightCenter) / (2 * PI) + 0.5)
            if (kotlin.math.abs(delta) <= highlightHalfWidth) {
                // Red enamel filled into the zone's band.
                red = red * .45f + 200f * .55f
                green = green * .45f + 30f * .55f
                blue = blue * .45f + 28f * .55f
            }
        }
        if (etched) {
            red = red * .24f + 69f * .76f * metalLight
            green = green * .24f + 44f * .76f * metalLight
            blue = blue * .24f + 14f * .76f * metalLight
        }
        return (red.toInt().coerceIn(0, 255) shl 16) or (green.toInt().coerceIn(0, 255) shl 8) or blue.toInt().coerceIn(0, 255)
    }

    companion object {
        /** Enough for the Earth view's full-width globe on a phone. */
        const val DEFAULT_SIZE = 512
        /** Share of full sunlight on the night side. */
        private const val NIGHT_LIGHT = .32f
    }
}
