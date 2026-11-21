package hu.bdev.stylusnote.core.math

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, zero-allocation vertical velocity tracker for single-finger touch panning.
 * Retains the most recent [CAPACITY] samples within [VELOCITY_WINDOW_MS] to produce smooth,
 * Chromium-like fling initiation velocities upon `ACTION_UP`.
 */
class FingerVelocityTracker {
    private val ySamples = FloatArray(CAPACITY)
    private val timeSamplesMs = LongArray(CAPACITY)
    private var head: Int = 0
    private var count: Int = 0

    fun reset() {
        head = 0
        count = 0
    }

    fun addSample(yPx: Float, timeMs: Long) {
        if (!yPx.isFinite()) return
        if (count > 0) {
            val prevIdx = (head - 1 + CAPACITY) % CAPACITY
            val prevTime = timeSamplesMs[prevIdx]
            if (timeMs < prevTime) return
            if (timeMs == prevTime) {
                ySamples[prevIdx] = yPx
                return
            }
        }
        ySamples[head] = yPx
        timeSamplesMs[head] = timeMs
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /**
     * Computes weighted vertical release velocity in pixels per second, or `0f` if the finger
     * paused before lift-off or moved too slowly to trigger a fling.
     */
    fun computeVelocityYPxPerSec(upTimeMs: Long): Float {
        if (count < 2) return 0f
        val newestIdx = (head - 1 + CAPACITY) % CAPACITY
        val newestTime = timeSamplesMs[newestIdx]
        if (upTimeMs - newestTime > MAX_PAUSE_BEFORE_UP_MS) return 0f

        var weightedVelSum = 0f
        var weightTotal = 0f
        var oldestInWindowIdx = newestIdx
        var i = 1
        while (i < count) {
            val idx = (head - 1 - i + CAPACITY * 2) % CAPACITY
            val t = timeSamplesMs[idx]
            if (newestTime - t > VELOCITY_WINDOW_MS) break
            oldestInWindowIdx = idx

            val nextIdx = (idx + 1) % CAPACITY
            val dtMs = timeSamplesMs[nextIdx] - t
            if (dtMs in 1L..VELOCITY_WINDOW_MS) {
                val segVel = (ySamples[nextIdx] - ySamples[idx]) * 1000f / dtMs.toFloat()
                val recencyWeight = max(0.25f, 1f - (newestTime - timeSamplesMs[nextIdx]).toFloat() / VELOCITY_WINDOW_MS)
                weightedVelSum += segVel * recencyWeight
                weightTotal += recencyWeight
            }
            i++
        }

        if (weightTotal <= 0f) {
            // Fallback single-segment velocity if only 2 recent points are within window.
            val totalDt = newestTime - timeSamplesMs[oldestInWindowIdx]
            if (totalDt !in 1L..VELOCITY_WINDOW_MS) return 0f
            val raw = (ySamples[newestIdx] - ySamples[oldestInWindowIdx]) * 1000f / totalDt.toFloat()
            return raw.coerceIn(-ScrollFlingState.MAX_FLING_VELOCITY_PX_PER_S, ScrollFlingState.MAX_FLING_VELOCITY_PX_PER_S)
        }

        val velocity = weightedVelSum / weightTotal
        return if (abs(velocity) < ScrollFlingState.MIN_FLING_VELOCITY_PX_PER_S) {
            0f
        } else {
            velocity.coerceIn(-ScrollFlingState.MAX_FLING_VELOCITY_PX_PER_S, ScrollFlingState.MAX_FLING_VELOCITY_PX_PER_S)
        }
    }

    private companion object {
        const val CAPACITY: Int = 12
        const val VELOCITY_WINDOW_MS: Long = 110L
        const val MAX_PAUSE_BEFORE_UP_MS: Long = 80L
    }
}

/**
 * Pure-Kotlin analytical exponential-decay fling physics tuned for 120/144 Hz scrolling
 * (`deltaY = v * (1 - exp(-friction * dt)) / friction`, `v *= exp(-friction * dt)`), ensuring
 * framerate-independent deceleration regardless of VSYNC frequency.
 */
class ScrollFlingState(
    private val frictionPerSec: Float = DEFAULT_FRICTION_PER_SEC,
    private val stopThresholdPxPerSec: Float = STOP_VELOCITY_PX_PER_SEC
) {
    var isActive: Boolean = false
        private set

    var currentVelocityPxPerSec: Float = 0f
        private set

    private var lastFrameTimeNanos: Long = 0L

    fun start(initialVelocityPxPerSec: Float, startTimeNanos: Long): Boolean {
        val clamped = initialVelocityPxPerSec.coerceIn(-MAX_FLING_VELOCITY_PX_PER_S, MAX_FLING_VELOCITY_PX_PER_S)
        if (!clamped.isFinite() || abs(clamped) < MIN_FLING_VELOCITY_PX_PER_S) {
            cancel()
            return false
        }
        currentVelocityPxPerSec = clamped
        lastFrameTimeNanos = startTimeNanos
        isActive = true
        return true
    }

    fun cancel() {
        isActive = false
        currentVelocityPxPerSec = 0f
        lastFrameTimeNanos = 0L
    }

    /** Advances fling simulation to [frameTimeNanos] and returns screen-Y translation delta in pixels. */
    fun step(frameTimeNanos: Long): Float {
        if (!isActive) return 0f
        val rawDtNs = (frameTimeNanos - lastFrameTimeNanos).coerceAtLeast(0L)
        lastFrameTimeNanos = frameTimeNanos
        val dtSec = (rawDtNs / 1_000_000_000f).coerceIn(0f, MAX_FRAME_DT_SEC)
        if (dtSec <= 0f) return 0f

        val decay = exp(-frictionPerSec * dtSec)
        val deltaYPx = currentVelocityPxPerSec * (1f - decay) / frictionPerSec
        currentVelocityPxPerSec *= decay

        if (abs(currentVelocityPxPerSec) < stopThresholdPxPerSec) {
            isActive = false
            currentVelocityPxPerSec = 0f
        }
        return deltaYPx
    }

    companion object {
        const val DEFAULT_FRICTION_PER_SEC: Float = 2.35f
        const val STOP_VELOCITY_PX_PER_SEC: Float = 28f
        const val MIN_FLING_VELOCITY_PX_PER_S: Float = 180f
        const val MAX_FLING_VELOCITY_PX_PER_S: Float = 14_000f
        private const val MAX_FRAME_DT_SEC: Float = 0.05f
    }
}

/**
 * Computes allowable `(minPanYPx, maxPanYPx)` viewport translation range for a document spanning
 * vertically from [docTopPt] to [docBottomPt] in PDF points at zoom [scale].
 */
fun computeVerticalPanBoundsPx(
    docTopPt: Float,
    docBottomPt: Float,
    scale: Float,
    viewportHeightPx: Int,
    overscrollPadPx: Float = 48f
): Pair<Float, Float> {
    val topLimitPan = overscrollPadPx - docTopPt * scale
    val bottomLimitPan = (viewportHeightPx.toFloat() - overscrollPadPx) - docBottomPt * scale
    return if (bottomLimitPan <= topLimitPan) {
        bottomLimitPan to topLimitPan
    } else {
        val centeredMid = (topLimitPan + bottomLimitPan) * 0.5f
        (centeredMid - overscrollPadPx) to (centeredMid + overscrollPadPx)
    }
}
