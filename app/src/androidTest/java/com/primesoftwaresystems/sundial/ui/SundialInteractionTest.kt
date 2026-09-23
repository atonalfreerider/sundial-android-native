package com.primesoftwaresystems.sundial.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.primesoftwaresystems.sundial.calendar.CalendarOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SundialInteractionTest {
    @Test fun annualTimeOnlyMovesAfterDraggingFromTheEarthHandle() {
        onLaidOutView { view ->
            val before = view.selectedInstantForTest
            val earth = view.earthPointForTest
            send(view, MotionEvent.ACTION_DOWN, earth.first + 5f, earth.second + 5f)
            assertEquals("Pressing the Earth handle must not jump time", before, view.selectedInstantForTest)

            val (cx, cy) = 540f to 2_424f * .47f
            send(view, MotionEvent.ACTION_MOVE, cx, cy - 250f)
            assertNotEquals("Dragging the captured Earth handle must move time", before, view.selectedInstantForTest)
            send(view, MotionEvent.ACTION_UP, cx, cy - 250f)
        }
    }

    @Test fun tappingAnnualRingAwayFromEarthDoesNotMoveTime() {
        onLaidOutView { view ->
            val before = view.selectedInstantForTest
            send(view, MotionEvent.ACTION_DOWN, 540f, 640f)
            send(view, MotionEvent.ACTION_UP, 540f, 640f)
            assertEquals(before, view.selectedInstantForTest)
        }
    }

    @Test fun holdingAndScrubbingOverlappingEventsCyclesTheCenterInspection() {
        onLaidOutView { view ->
            val zone = ZoneId.systemDefault()
            val year = view.displayedYear
            val start = LocalDate.of(year, 9, 1)
            val end = LocalDate.of(year, 10, 1)
            fun event(id: Long, title: String) = CalendarOccurrence(
                id, 7L, title,
                start.atStartOfDay(zone), end.atStartOfDay(zone),
                start, end, 0xFFFFA040.toInt(),
            )
            view.setSelectedCalendarIds(setOf(7L))
            view.setCalendarOccurrences(listOf(event(1, "First event"), event(2, "Second event")))
            val fraction = (start.dayOfYear - 1 + 15.0) / (if (start.isLeapYear) 366.0 else 365.0)
            val angle = Math.toRadians(DialGeometry.annualAngle(fraction, north = true))
            val radius = DialGeometry.yearEventBand(507.6f, 0).centerRadius
            val x = 540f + kotlin.math.cos(angle).toFloat() * radius
            val y = 2_424f * .47f + kotlin.math.sin(angle).toFloat() * radius

            send(view, MotionEvent.ACTION_DOWN, x, y)
            assertEquals("First event", view.inspectedEventTitleForTest)
            val movedAngle = angle + Math.toRadians(10.0)
            send(view, MotionEvent.ACTION_MOVE,
                540f + kotlin.math.cos(movedAngle).toFloat() * radius,
                2_424f * .47f + kotlin.math.sin(movedAngle).toFloat() * radius)
            assertEquals("Second event", view.inspectedEventTitleForTest)
            send(view, MotionEvent.ACTION_UP, x, y)
            assertNull(view.inspectedEventTitleForTest)
        }
    }

    private fun onLaidOutView(block: (SundialView) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = SundialView(context).apply {
                pauseClock()
                measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1_080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(2_424, android.view.View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 1_080, 2_424)
            }
            val frame = Bitmap.createBitmap(1_080, 2_424, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(frame))
            try {
                block(view)
            } finally {
                frame.recycle()
            }
        }
    }

    private fun send(view: SundialView, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 16L, action, x, y, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
