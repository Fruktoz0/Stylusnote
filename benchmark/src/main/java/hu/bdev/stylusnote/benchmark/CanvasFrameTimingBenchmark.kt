package hu.bdev.stylusnote.benchmark

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 144 Hz frame timing benchmark verifying Xiaomi Pad 8 p95 <= 6.94 ms frame budget during
 * two-finger pan and pinch-to-zoom gestures on a 10,000-stroke canvas page (Section 11).
 */
@RunWith(AndroidJUnit4::class)
class CanvasFrameTimingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun panAndZoom10kStrokesCanvasFrameTiming() {
        val launchIntent = Intent().apply {
            component = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity")
            putExtra(EXTRA_BENCHMARK_STROKE_COUNT, BENCHMARK_STROKE_COUNT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(launchIntent)
                // Wait until all 10,000 strokes and background tile prewarming have finished.
                device.wait(Until.hasObject(By.textContains("Kész")), 15_000L)
                SystemClock.sleep(400L)
                device.waitForIdle()
            }
        ) {
            val displayWidth = device.displayWidth.toFloat()
            val displayHeight = device.displayHeight.toFloat()

            // Exercise two-finger pinch-zoom + diagonal pan and pan-back at ~144 Hz event cadence.
            performTwoFingerPanAndZoom(
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                deltaCenterX = -displayWidth * 0.16f,
                deltaCenterY = -displayHeight * 0.14f,
                startHalfSpan = displayWidth * 0.08f,
                endHalfSpan = displayWidth * 0.13f,
                steps = 60
            )
            performTwoFingerPanAndZoom(
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                deltaCenterX = displayWidth * 0.16f,
                deltaCenterY = displayHeight * 0.14f,
                startHalfSpan = displayWidth * 0.13f,
                endHalfSpan = displayWidth * 0.08f,
                steps = 60
            )
            device.waitForIdle()
        }
    }

    private fun performTwoFingerPanAndZoom(
        displayWidth: Float,
        displayHeight: Float,
        deltaCenterX: Float,
        deltaCenterY: Float,
        startHalfSpan: Float,
        endHalfSpan: Float,
        steps: Int
    ) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val startCx = displayWidth * 0.52f
        val startCy = displayHeight * 0.52f

        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = startCx - startHalfSpan
                y = startCy
                pressure = 1f
                size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = startCx + startHalfSpan
                y = startCy
                pressure = 1f
                size = 1f
            }
        )

        val downTime = SystemClock.uptimeMillis()
        injectTouchEvent(
            uiAutomation,
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            1,
            props,
            coords,
            sync = true
        )

        SystemClock.sleep(7L)
        val pDownTime = SystemClock.uptimeMillis()
        val pointerDownAction =
            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) or MotionEvent.ACTION_POINTER_DOWN
        injectTouchEvent(
            uiAutomation,
            downTime,
            pDownTime,
            pointerDownAction,
            2,
            props,
            coords,
            sync = true
        )

        for (step in 1..steps) {
            SystemClock.sleep(7L)
            val t = step.toFloat() / steps
            val cx = startCx + deltaCenterX * t
            val cy = startCy + deltaCenterY * t
            val halfSpan = startHalfSpan + (endHalfSpan - startHalfSpan) * t
            coords[0].x = cx - halfSpan
            coords[0].y = cy
            coords[1].x = cx + halfSpan
            coords[1].y = cy
            injectTouchEvent(
                uiAutomation,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                2,
                props,
                coords,
                sync = false
            )
        }

        SystemClock.sleep(7L)
        val pointerUpAction =
            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) or MotionEvent.ACTION_POINTER_UP
        injectTouchEvent(
            uiAutomation,
            downTime,
            SystemClock.uptimeMillis(),
            pointerUpAction,
            2,
            props,
            coords,
            sync = true
        )

        SystemClock.sleep(7L)
        injectTouchEvent(
            uiAutomation,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            1,
            props,
            coords,
            sync = true
        )
    }

    private fun injectTouchEvent(
        uiAutomation: android.app.UiAutomation,
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointerCount: Int,
        props: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>,
        sync: Boolean = false
    ) {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointerCount,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        try {
            uiAutomation.injectInputEvent(event, sync)
        } finally {
            event.recycle()
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "hu.bdev.stylusnote"
        private const val EXTRA_BENCHMARK_STROKE_COUNT =
            "hu.bdev.stylusnote.EXTRA_BENCHMARK_STROKE_COUNT"
        private const val BENCHMARK_STROKE_COUNT = 10_000
    }
}

