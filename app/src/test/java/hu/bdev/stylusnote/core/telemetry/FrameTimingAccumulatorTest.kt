package hu.bdev.stylusnote.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimingAccumulatorTest {

    @Test
    fun separates120HzVsyncCadenceFromActualSubTwoMsRenderWork() {
        val acc = FrameTimingAccumulator(windowSize = 120)

        // Simulate 120 Hz VSYNC callbacks (8.333 ms cadence) while actual FrameMetrics render work is 1.6 ms - 3.2 ms.
        var tNs = 1_000_000_000L
        repeat(60) { idx ->
            acc.recordVsyncTimestampNanos(tNs)
            tNs += 8_333_333L
            val workNs = 1_600_000L + (idx % 5) * 400_000L // max 3.2 ms
            acc.recordRenderWorkDurationNanos(workNs)
        }

        val snap = acc.snapshot(displayRefreshHz = 120f, lockedModeHz = 144f)
        assertEquals(120f, snap.vsyncFps, 0.5f)
        assertEquals(8.333f, snap.vsyncP95Ms, 0.05f)
        assertTrue(snap.hasRenderWorkSamples)
        assertTrue(snap.renderWorkP95Ms <= 3.21f)
        assertEquals(8.333f, snap.frameBudgetMs, 0.05f)
        assertTrue(snap.isRenderBudgetMet)
    }

    @Test
    fun detectsRenderBudgetOverrunAgainst144HzDeadline() {
        val acc = FrameTimingAccumulator(windowSize = 40)
        repeat(20) {
            acc.recordRenderWorkDurationNanos(8_200_000L) // 8.2 ms > 6.94 ms (144 Hz)
        }

        val snap144 = acc.snapshot(displayRefreshHz = 144f, lockedModeHz = 144f)
        assertEquals(6.944f, snap144.frameBudgetMs, 0.01f)
        assertFalse(snap144.isRenderBudgetMet)

        // Against 120 Hz budget (8.33 ms), 8.2 ms is within budget.
        val snap120 = acc.snapshot(displayRefreshHz = 120f, lockedModeHz = 144f)
        assertTrue(snap120.isRenderBudgetMet)
    }

    @Test
    fun ignoresHugeBackgroundPauseBetweenVsyncCallbacks() {
        val acc = FrameTimingAccumulator(windowSize = 10)
        acc.recordVsyncTimestampNanos(1_000_000_000L)
        acc.recordVsyncTimestampNanos(1_006_944_444L) // 6.94 ms
        acc.recordVsyncTimestampNanos(5_006_944_444L) // 4 second pause -> ignored

        val snap = acc.snapshot(displayRefreshHz = 144f, lockedModeHz = 144f)
        assertEquals(6.944f, snap.vsyncP95Ms, 0.05f)
    }
}
