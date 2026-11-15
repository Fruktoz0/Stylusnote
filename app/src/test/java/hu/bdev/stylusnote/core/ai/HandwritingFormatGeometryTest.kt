package hu.bdev.stylusnote.core.ai

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class HandwritingFormatGeometryTest {

    private fun strokeFromPoints(id: String, points: List<Pair<Float, Float>>): PersistedStrokeRecord {
        val samples = points.mapIndexed { idx, (x, y) ->
            StylusPointSample(
                xPt = x,
                yPt = y,
                elapsedTimeMillis = idx * 8L,
                pressure = 0.6f,
                tiltRadians = 0f,
                orientationRadians = 0f,
                isEraserTool = false
            )
        }
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        return PersistedStrokeRecord(
            id = id,
            layer = StrokeLayerType.INK,
            zOrder = 1L,
            brushConfig = BrushConfig(),
            canonicalColorArgb = 0xFF14171C.toInt(),
            sizePt = 2.5f,
            samples = samples,
            bounds = DocRect(minX, minY, maxX.coerceAtLeast(minX + 1f), maxY.coerceAtLeast(minY + 1f))
        )
    }

    @Test
    fun diagonalUpLeftAndReversedArrowsSnapOntoExactInkEndpointsAndOrientation() {
        // Diagonal arrow going bottom-right (320, 280) -> top-left (120, 80) with two barb strokes at tip.
        val shaft = strokeFromPoints(
            id = "shaft",
            points = listOf(
                320f to 280f,
                270f to 230f,
                220f to 180f,
                170f to 130f,
                120f to 80f
            )
        )
        val barbA = strokeFromPoints("barbA", listOf(120f to 80f, 144f to 86f))
        val barbB = strokeFromPoints("barbB", listOf(120f to 80f, 126f to 104f))
        val strokes = listOf(shaft, barbA, barbB)
        val bounds = DocRect(120f, 80f, 320f, 280f)

        val meta = HandwritingFormatGeometry.buildShapeMetaForElement(
            shapeType = RecognizedShapeType.ARROW,
            strokes = strokes,
            bounds = bounds,
            aiStartPt = DocPoint(314f, 276f),
            aiEndPt = DocPoint(125f, 85f)
        )

        assertEquals(RecognizedShapeType.ARROW, meta.shapeType)
        assertEquals(2, meta.controlPoints.size)
        // Exact tail at (320, 280) and exact tip at (120, 80) — NOT flattened to horizontal!
        assertEquals(320f, meta.controlPoints[0].xPt, 1.5f)
        assertEquals(280f, meta.controlPoints[0].yPt, 1.5f)
        assertEquals(120f, meta.controlPoints[1].xPt, 1.5f)
        assertEquals(80f, meta.controlPoints[1].yPt, 1.5f)
        // Angle must match -3*PI/4 (-135 deg)
        assertTrue(abs(meta.rotationRadians - (-3f * PI.toFloat() / 4f)) < 0.05f)

        // Horizontal right-to-left backward arrow (260, 150) -> (80, 150)
        val rtlShaft = strokeFromPoints("rtl", listOf(260f to 150f, 170f to 150f, 80f to 150f, 96f to 138f))
        val rtlMeta = HandwritingFormatGeometry.buildShapeMetaForElement(
            shapeType = RecognizedShapeType.ARROW,
            strokes = listOf(rtlShaft),
            bounds = DocRect(80f, 138f, 260f, 150f),
            aiStartPt = DocPoint(258f, 150f),
            aiEndPt = DocPoint(82f, 150f)
        )
        assertEquals(260f, rtlMeta.controlPoints[0].xPt, 1.5f)
        assertEquals(80f, rtlMeta.controlPoints[1].xPt, 1.5f)

        // Vertical bottom-to-top upward arrow (180, 340) -> (180, 120)
        val upShaft = strokeFromPoints("up", listOf(180f to 340f, 180f to 230f, 180f to 120f))
        val upMeta = HandwritingFormatGeometry.buildShapeMetaForElement(
            shapeType = RecognizedShapeType.ARROW,
            strokes = listOf(upShaft),
            bounds = DocRect(179f, 120f, 181f, 340f),
            aiStartPt = DocPoint(180f, 336f),
            aiEndPt = DocPoint(180f, 124f)
        )
        assertEquals(340f, upMeta.controlPoints[0].yPt, 1.5f)
        assertEquals(120f, upMeta.controlPoints[1].yPt, 1.5f)
    }

    @Test
    fun diagonalArrowMatcherCapturesShaftAndBarbsWhileExcludingNearbyText() {
        val start = DocPoint(100f, 300f)
        val end = DocPoint(300f, 100f)

        val diagonalShaft = strokeFromPoints("diag", listOf(100f to 300f, 200f to 200f, 300f to 100f))
        val tipBarb = strokeFromPoints("barb", listOf(300f to 100f, 282f to 106f))
        val nearbyText = strokeFromPoints(
            "nearby-word",
            listOf(250f to 260f, 265f to 258f, 280f to 262f)
        )

        assertTrue(HandwritingFormatGeometry.doesStrokeMatchLinearSegment(diagonalShaft, start, end))
        assertTrue(HandwritingFormatGeometry.doesStrokeMatchLinearSegment(tipBarb, start, end))
        assertFalse(
            "Nearby text inside bounding box must not be swallowed by diagonal arrow",
            HandwritingFormatGeometry.doesStrokeMatchLinearSegment(nearbyText, start, end)
        )
    }

    @Test
    fun normalizedYxPointToDocMapsBoundsCorrectly() {
        val frame = DocRect(100f, 200f, 500f, 1000f) // width=400, height=800
        val center = HandwritingFormatGeometry.normalizedYxPointToDoc(500f, 250f, frame)
        assertEquals(200f, center.xPt, 1e-3f)
        assertEquals(600f, center.yPt, 1e-3f)
    }
}
