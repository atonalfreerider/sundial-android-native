package com.metavirtuoso.sundial.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.metavirtuoso.sundial.core.R as CoreR
import com.metavirtuoso.sundial.ui.CelestialStyle
import com.metavirtuoso.sundial.ui.InstrumentLayout
import com.metavirtuoso.sundial.ui.SundialView
import com.metavirtuoso.sundial.ui.ZodiacProfile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Renders the instrument in fixed scenes for comparing the Swift port (sundial-apple's
 * sundial-render draws the same scenes), plus the launcher icon at 1024 px for iOS. Opt-in:
 *
 *   adb shell am instrument -w -e referenceRenders true \
 *     -e class com.metavirtuoso.sundial.wear.ReferenceRenderCapture \
 *     com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Files land in /sdcard/Android/data/com.metavirtuoso.sundial/files/reference-renders/, with
 * scenes.tsv listing each scene's parameters. The device's zone must be America/Los_Angeles.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceRenderCapture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val instant = Instant.parse("2026-09-26T03:30:00Z")
    private val horoscope = "The Moon waxes toward full in your house of craft, and a patient hand " +
        "finds the grain of the work. Let an old plan rest one more day; tomorrow it will ask for you."

    private data class Scene(
        val name: String,
        val layout: InstrumentLayout,
        val width: Int,
        val height: Int,
        val state: SundialView.ViewState,
        val style: CelestialStyle,
        val astrology: Boolean = false,
        val clock: Boolean = false,
        val ambient: Boolean = false,
    )

    private val scenes = buildList {
        val layouts = listOf(
            Triple("phone", InstrumentLayout.PHONE, 1080 to 2160),
            Triple("round", InstrumentLayout.WATCH_ROUND, 454 to 454),
            Triple("rect", InstrumentLayout.WATCH_RECT, 400 to 480),
        )
        for ((key, layout, size) in layouts) {
            for (state in SundialView.ViewState.entries) {
                for (style in listOf(CelestialStyle.VOID_BLACK, CelestialStyle.CRIMSON_NEBULA, CelestialStyle.BRASS_WATCH)) {
                    for (astrology in listOf(false, true)) {
                        add(Scene("$key-${state.name.lowercase()}-${style.name.lowercase()}-${if (astrology) "astrology" else "astronomy"}",
                            layout, size.first, size.second, state, style, astrology, clock = layout != InstrumentLayout.PHONE))
                    }
                }
            }
        }
        add(Scene("phone-helio-deep-clock", InstrumentLayout.PHONE, 1080, 2160, SundialView.ViewState.HELIOCENTRIC,
            CelestialStyle.DEEP_SPACE_BLUE, clock = true))
        add(Scene("round-ambient-brass-astrology", InstrumentLayout.WATCH_ROUND, 454, 454, SundialView.ViewState.HELIOCENTRIC,
            CelestialStyle.BRASS_WATCH, astrology = true, clock = true, ambient = true))
        add(Scene("round-ambient-void", InstrumentLayout.WATCH_ROUND, 454, 454, SundialView.ViewState.HELIOCENTRIC,
            CelestialStyle.VOID_BLACK, clock = true, ambient = true))
    }

    @Test fun captureReferenceRenders() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("referenceRenders") == "true")
        val out = File(context.getExternalFilesDir(null), "reference-renders").apply { deleteRecursively(); mkdirs() }
        val table = StringBuilder("name\tlayout\twidth\theight\tstate\tstyle\tastrology\tclock\tambient\tdensity\n")
        instrumentation.runOnMainSync {
            scenes.forEach { scene ->
                val view = SundialView(context, scene.layout)
                view.setAstrologyContentForTest(
                    ZodiacProfile(enabled = scene.astrology, birthDate = LocalDate.of(1990, 4, 18)),
                    if (scene.astrology) horoscope else null)
                view.setClockVisible(scene.clock)
                view.freezeForCapture(instant, scene.state, scene.style)
                if (scene.ambient) view.setAmbient(true)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(scene.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(scene.height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, scene.width, scene.height)
                val frame = Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(frame))
                File(out, "${scene.name}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                table.append(listOf(scene.name, scene.layout.name, scene.width, scene.height, scene.state.name,
                    scene.style.name, scene.astrology, scene.clock, scene.ambient,
                    context.resources.displayMetrics.density).joinToString("\t")).append('\n')
            }

            // The launcher icon's artwork, full bleed: iOS applies its own rounded mask.
            val icon = context.getDrawable(CoreR.mipmap.ic_launcher) as AdaptiveIconDrawable
            val size = 1024
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Adaptive layers are 108 dp with the visible 72 dp in the middle: draw the middle.
            val bleed = (size * 18 / 72.0).toInt()
            listOf(icon.background, icon.foreground).forEach { layer ->
                layer.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                layer.draw(canvas)
            }
            File(out, "icon-1024.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(out, "scenes.tsv").writeText(table.toString())
    }
}
