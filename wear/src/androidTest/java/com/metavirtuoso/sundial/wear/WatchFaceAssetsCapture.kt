package com.metavirtuoso.sundial.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metavirtuoso.sundial.astronomy.Astronomy
import com.metavirtuoso.sundial.astronomy.Zodiac
import com.metavirtuoso.sundial.ui.CelestialStyle
import com.metavirtuoso.sundial.ui.DialGeometry
import com.metavirtuoso.sundial.ui.InstrumentLayout
import com.metavirtuoso.sundial.ui.SundialView
import com.metavirtuoso.sundial.ui.WatchFaceLayer
import com.metavirtuoso.sundial.ui.ZodiacHand
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the Watch Face Format face's images from the instrument's own drawing code, each
 * cropped to its content, and a layers.json describing where each one sits on the 450 × 450
 * face. watchface/tools/generate.py turns them into the face. Opt-in:
 *
 *   adb shell am instrument -w -e watchFaceAssets true \
 *     -e class com.metavirtuoso.sundial.wear.WatchFaceAssetsCapture \
 *     com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Files land in /sdcard/Android/data/com.metavirtuoso.sundial/files/watchface/.
 */
@RunWith(AndroidJUnit4::class)
class WatchFaceAssetsCapture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var view: SundialView
    private lateinit var out: File
    private val images = JSONObject()

    private enum class Mode(val key: String, val astrology: Boolean) { ASTRONOMY("a", false), ASTROLOGY("z", true) }

    @Test fun captureWatchFaceAssets() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("watchFaceAssets") == "true")
        out = File(context.getExternalFilesDir(null), "watchface").apply { deleteRecursively(); mkdirs() }
        instrumentation.runOnMainSync {
            view = SundialView(context, InstrumentLayout.WATCH_ROUND)
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(SIZE, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(SIZE, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, SIZE, SIZE)
            val styles = JSONObject()
            CelestialStyle.entries.forEach { style ->
                captureStyle(style)
                styles.put(styleKey(style), JSONObject().apply {
                    put("name", style.displayName)
                    put("ink", hex(style.instrumentColor))
                    put("brass", style.brassFace)
                    put("hands", JSONObject().apply {
                        ZodiacHand.entries.forEach { put(it.name.lowercase(), hex(it.colorIn(style))) }
                    })
                })
            }
            captureShared()
            captureAmbient()

            val (cx, cy, r) = view.dialGeometry
            File(out, "layers.json").writeText(JSONObject().apply {
                put("size", SIZE)
                put("density", context.resources.displayMetrics.density.toDouble())
                put("cx", cx.toDouble()); put("cy", cy.toDouble()); put("r", r.toDouble())
                put("orbits", JSONObject().apply {
                    put("mercury", DialGeometry.MERCURY_ORBIT.toDouble())
                    put("venus", DialGeometry.VENUS_ORBIT.toDouble())
                    put("earth", DialGeometry.EARTH_ORBIT.toDouble())
                    put("mars", DialGeometry.MARS_ORBIT.toDouble())
                })
                put("moonTrack", DialGeometry.SUBDIAL_MOON_TRACK.toDouble())
                put("zodiacHandEnd", DialGeometry.ZODIAC_HAND_END.toDouble())
                put("januaryFirstAngle", DialGeometry.JANUARY_FIRST_ANGLE)
                put("handStyles", JSONObject().apply {
                    ZodiacHand.entries.forEach {
                        put(it.name.lowercase(), JSONObject().apply {
                            put("alpha", it.alpha)
                            put("stroke", it.strokeRatio.toDouble())
                            put("dash", it.dashRatio.toDouble())
                            put("gap", it.gapRatio.toDouble())
                        })
                    }
                })
                put("styles", styles)
                put("images", images)
            }.toString(2))
        }
    }

    private fun captureStyle(style: CelestialStyle) {
        val s = styleKey(style)
        Zodiac.Season.entries.forEach { season ->
            save("base_${s}_${season.name.lowercase()}", style, false,
                WatchFaceLayer.Sky, WatchFaceLayer.Seasons(season), WatchFaceLayer.Dial)
        }
        save("sundays_$s", style, false, WatchFaceLayer.Sundays)
        save("spike_$s", style, false, WatchFaceLayer.EarthSpike)
        save("zodiac_$s", style, true, WatchFaceLayer.ZodiacRing)
        Zodiac.Sign.entries.forEach { sign ->
            val k = sign.ordinal
            save("zlit_${s}_$k", style, true, WatchFaceLayer.ZodiacHighlight(sign))
            save("zglyph_${s}_$k", style, true, WatchFaceLayer.ZodiacGlyph(sign, active = false))
            save("zglyph_${s}_${k}_lit", style, true, WatchFaceLayer.ZodiacGlyph(sign, active = true))
        }
        BODIES.forEach { body -> save("orbit_${s}_${body.name.lowercase()}", style, false, WatchFaceLayer.Orbit(body)) }
        save("earthsymbol_$s", style, true, WatchFaceLayer.EarthSymbol)
        Mode.entries.forEach { mode ->
            val m = mode.key
            PLANETS.forEach { body ->
                save("planet_${s}_${m}_${body.name.lowercase()}", style, mode.astrology, WatchFaceLayer.Planet(body))
            }
            save("subdial_${s}_$m", style, mode.astrology, WatchFaceLayer.Subdial)
            save("moonhand_${s}_$m", style, mode.astrology, WatchFaceLayer.MoonHand)
            save("moon_${s}_$m", style, mode.astrology, WatchFaceLayer.Moon)
            save("earth_${s}_$m", style, mode.astrology, WatchFaceLayer.EarthCentre)
            save("sun_${s}_$m", style, mode.astrology, WatchFaceLayer.Sun)
        }
    }

    /** Parts that look the same in every style. */
    private fun captureShared() {
        val style = CelestialStyle.VOID_BLACK
        for (frame in 0 until GLOBE_FRAMES) {
            save("globe_%02d".format(frame), style, false, WatchFaceLayer.Globe(frame * 360.0 / GLOBE_FRAMES))
        }
        save("localhour", style, false, WatchFaceLayer.LocalHour)
        Zodiac.Sign.entries.forEach { save("marker_${it.ordinal}", style, true, WatchFaceLayer.SignMarker(it)) }
    }

    /**
     * The always-on face: the instrument in grey on black, as the watch app's ambient mode draws
     * it, without the sky, season band, brass face or any lit sign.
     */
    private fun captureAmbient() {
        val style = CelestialStyle.VOID_BLACK
        val dial = arrayOf(WatchFaceLayer.Seasons(null, band = false), WatchFaceLayer.Dial)
        save("amb_dial_a", style, false, *dial, ambient = true)
        save("amb_dial_z", style, true, *dial, WatchFaceLayer.ZodiacRing,
            *Zodiac.Sign.entries.map { WatchFaceLayer.ZodiacGlyph(it, active = false) }.toTypedArray(), ambient = true)
        save("amb_sundays", style, false, WatchFaceLayer.Sundays, ambient = true)
        save("amb_spike", style, false, WatchFaceLayer.EarthSpike, ambient = true)
        BODIES.forEach { body -> save("amb_orbit_${body.name.lowercase()}", style, false, WatchFaceLayer.Orbit(body), ambient = true) }
        save("amb_earthsymbol", style, true, WatchFaceLayer.EarthSymbol, ambient = true)
        save("amb_localhour", style, false, WatchFaceLayer.LocalHour, ambient = true)
        Mode.entries.forEach { mode ->
            val m = mode.key
            PLANETS.forEach { body ->
                save("amb_planet_${m}_${body.name.lowercase()}", style, mode.astrology, WatchFaceLayer.Planet(body), ambient = true)
            }
            save("amb_subdial_$m", style, mode.astrology, WatchFaceLayer.Subdial, ambient = true)
            save("amb_moonhand_$m", style, mode.astrology, WatchFaceLayer.MoonHand, ambient = true)
            save("amb_moon_$m", style, mode.astrology, WatchFaceLayer.Moon, ambient = true)
            save("amb_earth_$m", style, mode.astrology, WatchFaceLayer.EarthCentre, ambient = true)
            save("amb_sun_$m", style, mode.astrology, WatchFaceLayer.Sun, ambient = true)
        }
    }

    private fun save(
        name: String,
        style: CelestialStyle,
        astrology: Boolean,
        vararg layers: WatchFaceLayer,
        ambient: Boolean = false,
    ) {
        val frame = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        layers.forEach { view.drawWatchFaceLayer(canvas, it, style, astrology, ambient) }
        val bounds = contentBounds(frame) ?: error("$name is empty")
        val cropped = Bitmap.createBitmap(frame, bounds.left, bounds.top, bounds.width(), bounds.height())
        File(out, "$name.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        images.put(name, JSONArray(listOf(bounds.left, bounds.top, bounds.width(), bounds.height())))
    }

    /** The smallest rectangle holding every visible pixel, with a pixel to spare for filtering. */
    private fun contentBounds(bitmap: Bitmap): Rect? {
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        var left = SIZE; var top = SIZE; var right = -1; var bottom = -1
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (Color.alpha(pixels[y * SIZE + x]) == 0) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (right < 0) return null
        return Rect((left - 1).coerceAtLeast(0), (top - 1).coerceAtLeast(0),
            (right + 2).coerceAtMost(SIZE), (bottom + 2).coerceAtMost(SIZE))
    }

    private fun styleKey(style: CelestialStyle): String = when (style) {
        CelestialStyle.VOID_BLACK -> "void"
        CelestialStyle.CRIMSON_NEBULA -> "crimson"
        CelestialStyle.DEEP_SPACE_BLUE -> "blue"
        CelestialStyle.COSMIC_VIOLET -> "violet"
        CelestialStyle.SOLAR_BRONZE -> "bronze"
        CelestialStyle.BRASS_WATCH -> "brass"
    }

    private fun hex(color: Int) = "#%08X".format(color)

    private companion object {
        /** The Watch Face Format canvas; watches scale it to their screens. */
        const val SIZE = 450
        const val GLOBE_FRAMES = 24
        val PLANETS = listOf(Astronomy.Body.MERCURY, Astronomy.Body.VENUS, Astronomy.Body.MARS)
        val BODIES = PLANETS + Astronomy.Body.EARTH
    }
}
