package hu.bdev.stylusnote.domain

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.repository.PrecisionStrokeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CanvasStateHolderTest {

    @Test
    fun brushConfigClampsSizeOpacityAndEffectiveColors() {
        val tinyFountain = BrushConfig(
            toolType = BrushToolType.FOUNTAIN_PEN,
            sizePt = 0.05f,
            opacity = 0.01f
        )
        assertEquals(BrushConfig.MIN_BRUSH_SIZE_PT, tinyFountain.clampedSizePt, 1e-4f)
        assertEquals(BrushConfig.MIN_OPACITY, tinyFountain.effectiveOpacity(), 1e-4f)
        assertEquals(BrushToolType.FOUNTAIN_PEN.isDrawingBrush, true)

        val giantHighlighter = BrushConfig(
            toolType = BrushToolType.HIGHLIGHTER,
            sizePt = 120f,
            colorArgb = BrushConfig.DEFAULT_HIGHLIGHTER_COLOR_ARGB,
            opacity = 1.0f
        )
        assertEquals(BrushConfig.MAX_BRUSH_SIZE_PT, giantHighlighter.clampedSizePt, 1e-4f)
        assertEquals(BrushConfig.HIGHLIGHTER_OPACITY, giantHighlighter.effectiveOpacity(), 1e-4f)
        val expectedAlpha = (BrushConfig.HIGHLIGHTER_OPACITY * 255f).toInt()
        val actualAlpha = (giantHighlighter.effectiveColorArgb() ushr 24) and 0xFF
        assertTrue(kotlin.math.abs(expectedAlpha - actualAlpha) <= 1)
    }

    @Test
    fun affineTransform2DTranslationScalingRotationAndInverseAreExact() {
        val translate = AffineTransform2D.translation(dxPt = 15f, dyPt = -8f)
        val scale = AffineTransform2D.scalingAround(scaleX = 2f, scaleY = 3f, pivotXPt = 10f, pivotYPt = 20f)
        val rotate = AffineTransform2D.rotationAround(
            angleRad = (PI / 2.0).toFloat(),
            pivotXPt = 10f,
            pivotYPt = 20f
        )
        val combined = translate.then(scale).then(rotate)
        val inverse = combined.invert()
        assertNotNull(inverse)

        val originalX = 42.5f
        val originalY = -17.25f
        val forward = combined.transformPoint(originalX, originalY)
        val roundTrip = inverse!!.transformPoint(forward.xPt, forward.yPt)

        assertEquals(originalX, roundTrip.xPt, 1e-3f)
        assertEquals(originalY, roundTrip.yPt, 1e-3f)
    }

    @Test
    fun precisionStrokeGeometrySplitsSingleStrokeIntoN2AndN3WithInterpolatedBoundaries() {
        val samples = listOf(
            sample(x = 0f, y = 0f, t = 0L, pressure = 0.2f),
            sample(x = 50f, y = 0f, t = 50L, pressure = 0.5f),
            sample(x = 100f, y = 0f, t = 100L, pressure = 0.8f),
            sample(x = 150f, y = 0f, t = 150L, pressure = 1.0f),
            sample(x = 200f, y = 0f, t = 200L, pressure = 0.6f)
        )

        // First cut centered at x=60, radius=10 -> cuts [50..70] out of [0..200], producing 2 segments.
        val firstCut = PrecisionStrokeGeometry.splitPointsByCircle(
            points = samples,
            centerXPt = 60f,
            centerYPt = 0f,
            cutRadiusPt = 10f
        )
        assertTrue(firstCut.didCut)
        assertEquals(2, firstCut.segments.size)

        val leftSeg = firstCut.segments[0]
        val rightSeg = firstCut.segments[1]
        assertEquals(0f, leftSeg.first().xPt, 1e-3f)
        assertEquals(50f, leftSeg.last().xPt, 1e-2f)
        assertEquals(70f, rightSeg.first().xPt, 1e-2f)
        assertEquals(200f, rightSeg.last().xPt, 1e-3f)

        // Pressure on interpolated boundary x=70 along [50..100] (t = 0.4) must be 0.5 + 0.4 * (0.8 - 0.5) = 0.62
        assertEquals(0.62f, rightSeg.first().pressure, 1e-3f)

        // Second cut on the right segment centered at x=140, radius=10 -> total N=3 segments across original line.
        val secondCut = PrecisionStrokeGeometry.splitPointsByCircle(
            points = rightSeg,
            centerXPt = 140f,
            centerYPt = 0f,
            cutRadiusPt = 10f
        )
        assertTrue(secondCut.didCut)
        assertEquals(2, secondCut.segments.size)

        val totalSegments = listOf(leftSeg) + secondCut.segments
        assertEquals(3, totalSegments.size)
        assertEquals(70f, totalSegments[1].first().xPt, 1e-2f)
        assertEquals(130f, totalSegments[1].last().xPt, 1e-2f)
        assertEquals(150f, totalSegments[2].first().xPt, 1e-2f)
        assertEquals(200f, totalSegments[2].last().xPt, 1e-3f)
    }

    @Test
    fun precisionStrokeGeometrySplitsFastTwoPointStrokeAcrossCircle() {
        val fastTwoPointStroke = listOf(
            sample(x = 0f, y = 0f, t = 0L, pressure = 0.2f),
            sample(x = 100f, y = 0f, t = 8L, pressure = 0.8f)
        )

        val split = PrecisionStrokeGeometry.splitPointsByCircle(
            points = fastTwoPointStroke,
            centerXPt = 50f,
            centerYPt = 0f,
            cutRadiusPt = 10f
        )
        assertTrue(split.didCut)
        assertEquals(2, split.segments.size)
        assertEquals(40f, split.segments[0].last().xPt, 1e-2f)
        assertEquals(60f, split.segments[1].first().xPt, 1e-2f)
    }

    private fun sample(
        x: Float,
        y: Float,
        t: Long,
        pressure: Float = 0.5f
    ): StylusPointSample = StylusPointSample(
        xPt = x,
        yPt = y,
        elapsedTimeMillis = t,
        pressure = pressure,
        tiltRadians = 0.1f,
        orientationRadians = 0f,
        isEraserTool = false
    )
}

