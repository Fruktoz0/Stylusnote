package hu.bdev.stylusnote.core.telemetry

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Snapshot of live display VSYNC cadence and isolated CPU + GPU frame work durations for the debug HUD.
 */
data class FrameTimingSnapshot(
    val vsyncFps: Float = 0f,
    val lastVsyncMs: Float = 0f,
    val vsyncP95Ms: Float = 0f,
    val lastRenderWorkMs: Float = 0f,
    val cpuWorkP50Ms: Float = 0f,
    val cpuWorkP95Ms: Float = 0f,
    val gpuWorkP95Ms: Float = 0f,
    val hasGpuSamples: Boolean = false,
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
 * (Choreographer cadence: ~8.33 ms @ 120 Hz, ~6.94 ms @ 144 Hz) from isolated CPU stage
 * work and GPU completion durations, with time-based expiry so old transient spikes do not
 * linger when the UI is idle.
 */
class FrameTimingAccumulator(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val maxSampleAgeNanos: Long = DEFAULT_MAX_SAMPLE_AGE_NANOS,
    private val clockNanos: () -> Long = { System.nanoTime() }
) {
    private val vsyncIntervalsMs = FloatArray(windowSize)
    private var vsyncFilled = 0
    private var vsyncWriteIdx = 0
    private var lastVsyncFrameNanos = 0L
    private var lastVsyncDeltaMs = 0f

    private val cpuWorkMs = FloatArray(windowSize)
    private val gpuWorkMs = FloatArray(windowSize)
    private val effectiveWorkMs = FloatArray(windowSize)
    private val workTimestampsNanos = LongArray(windowSize)
    private var workFilled = 0
    private var workWriteIdx = 0
    private var lastWorkMs = 0f
    private var totalWorkSampleCount = 0

    private val sortScratch = FloatArray(windowSize)
    private val activeCpuScratch = FloatArray(windowSize)
    private val activeGpuScratch = FloatArray(windowSize)
    private val activeEffectiveScratch = FloatArray(windowSize)

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
        recordFrameMetrics(cpuWorkNanos = durationNanos)
    }

    /**
     * Records active UI/RenderThread CPU stage time and optional GPU execution time for one frame.
     * Negative GPU duration means unavailable on the current OS/driver.
     */
    fun recordFrameMetrics(
        cpuWorkNanos: Long,
        gpuDurationNanos: Long = -1L,
        sampleTimeNanos: Long = clockNanos()
    ) {
        if (cpuWorkNanos < 0L && gpuDurationNanos < 0L) return
        val cpuMs = cpuWorkNanos.coerceAtLeast(0L) / 1_000_000f
        val gpuMs = if (gpuDurationNanos >= 0L) gpuDurationNanos / 1_000_000f else -1f
        // CPU UI/RT and GPU stages are pipelined across frame boundaries, so frame throughput is limited by max stage.
        val effectiveMs = max(cpuMs, gpuMs.coerceAtLeast(0f))

        lastWorkMs = effectiveMs
        cpuWorkMs[workWriteIdx] = cpuMs
        gpuWorkMs[workWriteIdx] = gpuMs
        effectiveWorkMs[workWriteIdx] = effectiveMs
        workTimestampsNanos[workWriteIdx] = sampleTimeNanos
        workWriteIdx = (workWriteIdx + 1) % windowSize
        if (workFilled < windowSize) workFilled++
        totalWorkSampleCount++
    }

    fun snapshot(
        displayRefreshHz: Float,
        lockedModeHz: Float,
        nowNanos: Long = clockNanos()
    ): FrameTimingSnapshot {
        val safeRefreshHz = max(1f, displayRefreshHz)
        var vsyncAvgMs = 0f
        var vsyncP95Ms = 0f
        if (vsyncFilled > 0) {
            var sum = 0f
            for (i in 0 until vsyncFilled) sum += vsyncIntervalsMs[i]
            vsyncAvgMs = sum / vsyncFilled
            vsyncP95Ms = computeQuantile(vsyncIntervalsMs, vsyncFilled, 0.95f)
        }
        val vsyncFps = if (vsyncAvgMs > 0f) 1000f / vsyncAvgMs else 0f

        var activeCount = 0
        var activeGpuCount = 0
        for (i in 0 until workFilled) {
            val ageNanos = nowNanos - workTimestampsNanos[i]
            if (maxSampleAgeNanos <= 0L || ageNanos <= maxSampleAgeNanos) {
                activeCpuScratch[activeCount] = cpuWorkMs[i]
                activeEffectiveScratch[activeCount] = effectiveWorkMs[i]
                activeCount++
                val gpu = gpuWorkMs[i]
                if (gpu >= 0f) {
                    activeGpuScratch[activeGpuCount++] = gpu
                }
            }
        }

        val cpuP50Ms = computeQuantile(activeCpuScratch, activeCount, 0.50f)
        val cpuP95Ms = computeQuantile(activeCpuScratch, activeCount, 0.95f)
        val gpuP95Ms = computeQuantile(activeGpuScratch, activeGpuCount, 0.95f)
        val workP95Ms = computeQuantile(activeEffectiveScratch, activeCount, 0.95f)

        return FrameTimingSnapshot(
            vsyncFps = vsyncFps,
            lastVsyncMs = lastVsyncDeltaMs,
            vsyncP95Ms = vsyncP95Ms,
            lastRenderWorkMs = if (activeCount > 0) lastWorkMs else 0f,
            cpuWorkP50Ms = cpuP50Ms,
            cpuWorkP95Ms = cpuP95Ms,
            gpuWorkP95Ms = gpuP95Ms,
            hasGpuSamples = activeGpuCount > 0,
            renderWorkP95Ms = workP95Ms,
            hasRenderWorkSamples = activeCount > 0,
            renderSampleCount = totalWorkSampleCount,
            displayRefreshHz = safeRefreshHz,
            lockedModeHz = max(1f, lockedModeHz)
        )
    }

    private fun computeQuantile(source: FloatArray, count: Int, quantile: Float): Float {
        if (count <= 0) return 0f
        System.arraycopy(source, 0, sortScratch, 0, count)
        sortScratch.sort(0, count)
        val idx = ((count - 1) * quantile).roundToInt().coerceIn(0, count - 1)
        return sortScratch[idx]
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 120
        const val DEFAULT_MAX_SAMPLE_AGE_NANOS = 4_000_000_000L
    }
}
