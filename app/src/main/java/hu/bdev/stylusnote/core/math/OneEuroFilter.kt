package hu.bdev.stylusnote.core.math

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Filtered 2D coordinate output from [OneEuroFilter].
 */
data class FilteredPoint(
    val x: Float,
    val y: Float
)

/**
 * Speed-adaptive 2D One-Euro (1€) low-pass filter for stylus (x, y) coordinates.
 *
 * Smooths hand jitter during slow, precise writing while reducing phase lag during fast strokes.
 *
 * ### Stabilization Slider Mapping (`0f..100f` -> `minCutoff`, `beta`)
 * - `percent <= 0f`: Filter is bypassed (`isBypassed == true`); raw coordinates are returned
 *   untouched with zero latency (Section 4 & 5.1).
 * - For `percent in (0f, 100f]`, let normalized factor `u = (percent / 100.0).coerceIn(0.0, 1.0)`:
 *   - `minCutoff(u) = 4.5 * (0.35 / 4.5)^u` Hz
 *     Exponentially maps from `4.5 Hz` (light smoothing at 1%) down to `0.35 Hz` (heavy
 *     smoothing at 100%) for steady slow strokes.
 *   - `beta(u) = 0.08 - 0.072 * u`
 *     Linearly scales from `0.08` down to `0.008` so higher stabilization dampens sudden jitter
 *     spikes while still elevating cutoff proportionally to stylus speed.
 *   - `dCutoff = 1.0` Hz (constant cutoff for the velocity derivative low-pass filter).
 *
 * Call [reset] on every `ACTION_DOWN` / new stroke start. Feed only real stylus samples (never
 * predicted samples from `MotionEventPredictor`, per Section 4).
 */
class OneEuroFilter(
    initialStabilizationPercent: Float = 0f
) {
    private var stabilizationPercent: Float = initialStabilizationPercent.coerceIn(0f, 100f)
    private var minCutoffHz: Double = DEFAULT_MIN_CUTOFF_HZ
    private var beta: Double = DEFAULT_BETA
    private val derivativeCutoffHz: Double = DERIVATIVE_CUTOFF_HZ

    private val xFilter = LowPassState()
    private val yFilter = LowPassState()
    private val dxFilter = LowPassState()
    private val dyFilter = LowPassState()

    private var prevRawX: Double = 0.0
    private var prevRawY: Double = 0.0
    private var prevTimestampNanos: Long = Long.MIN_VALUE
    private var initialized: Boolean = false

    init {
        recomputeParameters()
    }

    val currentStabilizationPercent: Float
        get() = stabilizationPercent

    val isBypassed: Boolean
        get() = stabilizationPercent <= 0f

    val currentMinCutoffHz: Double
        get() = minCutoffHz

    val currentBeta: Double
        get() = beta

    fun setStabilizationPercent(percent: Float) {
        val clamped = percent.coerceIn(0f, 100f)
        if (clamped == stabilizationPercent) return
        stabilizationPercent = clamped
        recomputeParameters()
    }

    fun reset() {
        initialized = false
        prevTimestampNanos = Long.MIN_VALUE
        prevRawX = 0.0
        prevRawY = 0.0
        xFilter.reset()
        yFilter.reset()
        dxFilter.reset()
        dyFilter.reset()
    }

    fun filter(x: Float, y: Float, timestampMs: Long): FilteredPoint =
        filterNanos(x = x, y = y, timestampNanos = timestampMs * 1_000_000L)

    fun filterNanos(x: Float, y: Float, timestampNanos: Long): FilteredPoint {
        if (isBypassed || !x.isFinite() || !y.isFinite()) {
            return FilteredPoint(x, y)
        }

        val rx = x.toDouble()
        val ry = y.toDouble()

        if (!initialized) {
            initialized = true
            prevRawX = rx
            prevRawY = ry
            prevTimestampNanos = timestampNanos
            xFilter.seed(rx)
            yFilter.seed(ry)
            dxFilter.seed(0.0)
            dyFilter.seed(0.0)
            return FilteredPoint(x, y)
        }

        val deltaNanos = timestampNanos - prevTimestampNanos
        // Duplicate or non-monotonic timestamps can appear in batched historical MotionEvents;
        // fall back to a nominal 240 Hz sample period instead of dividing by zero.
        val dtSeconds = if (deltaNanos > 0L) {
            (deltaNanos / 1_000_000_000.0).coerceAtMost(MAX_DT_SECONDS)
        } else {
            NOMINAL_240HZ_DT_SECONDS
        }
        if (timestampNanos > prevTimestampNanos) {
            prevTimestampNanos = timestampNanos
        }

        val rawDx = (rx - prevRawX) / dtSeconds
        val rawDy = (ry - prevRawY) / dtSeconds
        prevRawX = rx
        prevRawY = ry

        val dAlpha = smoothingAlpha(cutoffHz = derivativeCutoffHz, dtSeconds = dtSeconds)
        val filteredDx = dxFilter.filter(rawDx, dAlpha)
        val filteredDy = dyFilter.filter(rawDy, dAlpha)

        // Use Euclidean speed so diagonal strokes get rotation-invariant responsiveness.
        val speed = hypot(filteredDx, filteredDy)
        val dynamicCutoff = minCutoffHz + beta * speed
        val alpha = smoothingAlpha(cutoffHz = dynamicCutoff, dtSeconds = dtSeconds)

        val outX = xFilter.filter(rx, alpha)
        val outY = yFilter.filter(ry, alpha)
        return FilteredPoint(outX.toFloat(), outY.toFloat())
    }

    private fun recomputeParameters() {
        if (stabilizationPercent <= 0f) {
            minCutoffHz = BYPASSED_CUTOFF_HZ
            beta = 0.0
            return
        }
        val u = (stabilizationPercent / 100.0).coerceIn(0.0, 1.0)
        minCutoffHz = MIN_CUTOFF_AT_LOW_HZ * (MIN_CUTOFF_AT_HIGH_HZ / MIN_CUTOFF_AT_LOW_HZ).pow(u)
        beta = BETA_AT_LOW - (BETA_AT_LOW - BETA_AT_HIGH) * u
    }

    private fun smoothingAlpha(cutoffHz: Double, dtSeconds: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoffHz)
        return 1.0 / (1.0 + tau / dtSeconds)
    }

    private class LowPassState {
        private var hasValue = false
        private var lastFiltered = 0.0

        fun seed(value: Double) {
            hasValue = true
            lastFiltered = value
        }

        fun filter(value: Double, alpha: Double): Double {
            if (!hasValue) {
                seed(value)
                return value
            }
            val result = alpha * value + (1.0 - alpha) * lastFiltered
            lastFiltered = result
            return result
        }

        fun reset() {
            hasValue = false
            lastFiltered = 0.0
        }
    }

    companion object {
        private const val MIN_CUTOFF_AT_LOW_HZ = 4.5
        private const val MIN_CUTOFF_AT_HIGH_HZ = 0.35
        private const val BETA_AT_LOW = 0.08
        private const val BETA_AT_HIGH = 0.008
        private const val DERIVATIVE_CUTOFF_HZ = 1.0
        private const val DEFAULT_MIN_CUTOFF_HZ = MIN_CUTOFF_AT_LOW_HZ
        private const val DEFAULT_BETA = BETA_AT_LOW
        private const val BYPASSED_CUTOFF_HZ = Double.POSITIVE_INFINITY
        private const val NOMINAL_240HZ_DT_SECONDS = 1.0 / 240.0
        private const val MAX_DT_SECONDS = 0.25
    }
}

