package hu.bdev.stylusnote.core.telemetry

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Snapshot of live display VSYNC cadence and true CPU+GPU frame render durations for the debug HUD.
 */
data class FrameTimingSnapshot(
    val vsyncFps: Float = 0f,
    val lastVsyncMs: Float = 0f,
    val vsyncP95Ms: Float = 0f,
    val lastRenderWorkMs: Float = 0f,
    val renderWorkP95Ms: Float = 0f,
    val hasRenderWorkSamples: Boolean = false,
    val renderSampleCount: Int = 0,
    val displayRefreshHz: Float = 60f,
    val lockedModeHz: Float = 60f
) {
    val frameBudgetMs: Float
        get() = 1000f / displayRefreshHz.coerceAtLeast(60f)

    val isRenderBudgetMet: Boolean
        get() = !hasRenderWorkSamples || renderWorkP95Ms <= frameBudgetMs
}

/**
 * Lock-free fixed-capacity rolling quantile accumulator separating display VSYNC intervals
 * (Choreographer cadence: ~8.33 ms @ 120 Hz, ~6.94 ms @ 144 Hz) from actual per-frame render
 * production work (`Window.OnFrameMetricsAvailableListener` `TOTAL_DURATION`).
 */
class FrameTimingAccumulator(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE
) {
    private val vsyncIntervalsMs = FloatArray(windowSize)
    private var vsyncFilled = 0
    private var vsyncWriteIdx = 0
    private var lastVsyncFrameNanos = 0L
    private var lastVsyncDeltaMs = 0f

    private val renderWorkMs = FloatArray(windowSize)
    private var workFilled = 0
    private var workWriteIdx = 0
    private var lastWorkMs = 0f
    private var totalWorkSampleCount = 0

    private val sortScratch = FloatArray(windowSize)

    fun recordVsyncTimestampNanos(frameTimeNanos: Long) {
        if (lastVsyncFrameNanos != 0L && frameTimeNanos > lastVsyncFrameNanos) {
            val deltaMs = (frameTimeNanos - lastVsyncFrameNanos) / 1_000_000f
            // Ignore multi-frame background pauses so app switches do not skew live vsync P95.
            if (deltaMs in 0.5f..500f) {
                lastVsyncDeltaMs = deltaMs
                vsyncIntervalsMs[vsyncWriteIdx] = deltaMs
                vsyncWriteIdx = (vsyncWriteIdx + 1) % windowSize
                if (vsyncFilled < windowSize) vsyncFilled++
            }
        }
        lastVsyncFrameNanos = frameTimeNanos
    }

    fun recordRenderWorkDurationNanos(durationNanos: Long) {
        if (durationNanos < 0L) return
        val ms = durationNanos / 1_000_000f
        lastWorkMs = ms
        renderWorkMs[workWriteIdx] = ms
        workWriteIdx = (workWriteIdx + 1) % windowSize
        if (workFilled < windowSize) workFilled++
        totalWorkSampleCount++
    }

    fun snapshot(
        displayRefreshHz: Float,
        lockedModeHz: Float
    ): FrameTimingSnapshot {
        val safeRefreshHz = max(1f, displayRefreshHz)
        var vsyncAvgMs = 0f
        var vsyncP95Ms = 0f
        if (vsyncFilled > 0) {
            var sum = 0f
            for (i in 0 until vsyncFilled) sum += vsyncIntervalsMs[i]
            vsyncAvgMs = sum / vsyncFilled
            vsyncP95Ms = computeP95(vsyncIntervalsMs, vsyncFilled)
        }
        val vsyncFps = if (vsyncAvgMs > 0f) 1000f / vsyncAvgMs else 0f

        val workP95Ms = if (workFilled > 0) {
            computeP95(renderWorkMs, workFilled)
        } else {
            0f
        }

        return FrameTimingSnapshot(
            vsyncFps = vsyncFps,
            lastVsyncMs = lastVsyncDeltaMs,
            vsyncP95Ms = vsyncP95Ms,
            lastRenderWorkMs = lastWorkMs,
            renderWorkP95Ms = workP95Ms,
            hasRenderWorkSamples = workFilled > 0,
            renderSampleCount = totalWorkSampleCount,
            displayRefreshHz = safeRefreshHz,
            lockedModeHz = max(1f, lockedModeHz)
        )
    }

    private fun computeP95(source: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        System.arraycopy(source, 0, sortScratch, 0, count)
        sortScratch.sort(0, count)
        val idx = ((count - 1) * 0.95f).roundToInt().coerceIn(0, count - 1)
        return sortScratch[idx]
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 120
    }
}
