package hu.bdev.stylusnote.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class OneEuroFilterTest {

    @Test
    fun zeroPercentStabilizationBypassesFilterWithoutModification() {
        val filter = OneEuroFilter(initialStabilizationPercent = 0f)
        assertTrue(filter.isBypassed)

        val p1 = filter.filter(x = 123.45f, y = 678.9f, timestampMs = 10L)
        val p2 = filter.filter(x = 129.1f, y = 665.2f, timestampMs = 14L)

        assertEquals(123.45f, p1.x, 0f)
        assertEquals(678.9f, p1.y, 0f)
        assertEquals(129.1f, p2.x, 0f)
        assertEquals(665.2f, p2.y, 0f)
    }

    @Test
    fun higherStabilizationReducesSlowJitterMoreAggressively() {
        val lightFilter = OneEuroFilter(initialStabilizationPercent = 20f)
        val strongFilter = OneEuroFilter(initialStabilizationPercent = 100f)

        var rawTotalDeviation = 0f
        var lightTotalDeviation = 0f
        var strongTotalDeviation = 0f

        // Stationary target at (100, 200) perturbed by high-frequency hand tremor at 240 Hz.
        for (i in 0 until 200) {
            val timestampNs = i * 4_166_667L
            val jitter = (sin(i * 1.9) * 3.5).toFloat()
            val rawX = 100f + jitter
            val rawY = 200f - jitter

            val lightOut = lightFilter.filterNanos(rawX, rawY, timestampNs)
            val strongOut = strongFilter.filterNanos(rawX, rawY, timestampNs)

            rawTotalDeviation += abs(rawX - 100f) + abs(rawY - 200f)
            lightTotalDeviation += abs(lightOut.x - 100f) + abs(lightOut.y - 200f)
            strongTotalDeviation += abs(strongOut.x - 100f) + abs(strongOut.y - 200f)
        }

        assertTrue(lightTotalDeviation < rawTotalDeviation)
        assertTrue(strongTotalDeviation < lightTotalDeviation)
    }

    @Test
    fun fastStrokeTracksWithLowLagComparedToSlowJitter() {
        val filter = OneEuroFilter(initialStabilizationPercent = 60f)
        assertFalse(filter.isBypassed)

        // Rapid linear sweep across 600 pt in 100 ms (~6000 pt/s) at 240 Hz.
        var lastOut = FilteredPoint(0f, 0f)
        var lastRawX = 0f
        for (i in 0..24) {
            val tNs = i * 4_166_667L
            lastRawX = i * 25f
            lastOut = filter.filterNanos(lastRawX, lastRawX * 0.5f, tNs)
        }

        val lagPt = abs(lastRawX - lastOut.x)
        assertTrue("Expected low lag during fast stroke, got $lagPt pt", lagPt < 15f)
    }

    @Test
    fun resetClearsHistoryBetweenStrokes() {
        val filter = OneEuroFilter(initialStabilizationPercent = 80f)
        filter.filter(10f, 10f, timestampMs = 100L)
        filter.filter(12f, 12f, timestampMs = 104L)

        filter.reset()
        val firstAfterReset = filter.filter(500f, 700f, timestampMs = 108L)
        assertEquals(500f, firstAfterReset.x, 1e-4f)
        assertEquals(700f, firstAfterReset.y, 1e-4f)
    }

    @Test
    fun duplicateTimestampsNeverProduceNaNOrInfinity() {
        val filter = OneEuroFilter(initialStabilizationPercent = 50f)
        val p1 = filter.filterNanos(10f, 20f, 1_000_000L)
        val p2 = filter.filterNanos(15f, 25f, 1_000_000L)
        val p3 = filter.filterNanos(20f, 30f, 999_000L)

        assertTrue(p1.x.isFinite() && p1.y.isFinite())
        assertTrue(p2.x.isFinite() && p2.y.isFinite())
        assertTrue(p3.x.isFinite() && p3.y.isFinite())
    }

    @Test
    fun parameterMappingMonotonicallyDecreasesMinCutoffAndBeta() {
        val low = OneEuroFilter(10f)
        val mid = OneEuroFilter(50f)
        val high = OneEuroFilter(100f)

        assertTrue(low.currentMinCutoffHz > mid.currentMinCutoffHz)
        assertTrue(mid.currentMinCutoffHz > high.currentMinCutoffHz)
        assertTrue(low.currentBeta > mid.currentBeta)
        assertTrue(mid.currentBeta > high.currentBeta)
        assertTrue(high.currentMinCutoffHz > 0.3 && high.currentMinCutoffHz < 0.4)
        assertEquals(0.008, high.currentBeta, 1e-6)
        assertTrue(PI > 3.14)
    }
}

