package hu.bdev.stylusnote.core.math

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {

    private fun sample(x: Float, y: Float, tMs: Long, pressure: Float = 0.6f) = StylusPointSample(
        xPt = x,
        yPt = y,
        elapsedTimeMillis = tMs,
        pressure = pressure,
        tiltRadians = 0f,
        orientationRadians = 0f,
        isEraserTool = false
    )

    private fun appendHold(
        base: MutableList<StylusPointSample>,
        holdMs: Long,
        driftPt: Float = 1.2f
    ) {
        val last = base.last()
        val steps = 4
        for (i in 1..steps) {
            val offset = if (i % 2 == 0) driftPt * 0.5f else -driftPt * 0.4f
            base.add(
                sample(
                    x = last.xPt + offset,
                    y = last.yPt + offset * 0.5f,
                    tMs = last.elapsedTimeMillis + (holdMs * i) / steps
                )
            )
        }
    }

    private fun lineSamples(holdMs: Long = 340L, lengthPt: Float = 140f): List<StylusPointSample> {
        val pts = mutableListOf<StylusPointSample>()
        val count = 20
        val dx = lengthPt * 0.96f
        val dy = lengthPt * 0.28f
        for (i in 0..count) {
            val t = i.toFloat() / count
            pts.add(sample(x = 50f + t * dx, y = 80f + t * dy, tMs = i * 10L))
        }
        if (holdMs > 0L) appendHold(pts, holdMs)
        return pts
    }

    private fun circleSamples(holdMs: Long = 350L): List<StylusPointSample> {
        val pts = mutableListOf<StylusPointSample>()
        val cx = 200f
        val cy = 220f
        val r = 65f
        val steps = 48
        for (i in 0..steps) {
            val theta = (2.0 * PI * i / steps).toFloat()
            pts.add(sample(x = cx + r * cos(theta), y = cy + r * sin(theta), tMs = i * 8L))
        }
        if (holdMs > 0L) appendHold(pts, holdMs)
        return pts
    }

    private fun ellipseSamples(holdMs: Long = 350L): List<StylusPointSample> {
        val pts = mutableListOf<StylusPointSample>()
        val cx = 240f
        val cy = 240f
        val rx = 110f
        val ry = 45f
        val steps = 52
        for (i in 0..steps) {
            val theta = (2.0 * PI * i / steps).toFloat()
            pts.add(sample(x = cx + rx * cos(theta), y = cy + ry * sin(theta), tMs = i * 8L))
        }
        if (holdMs > 0L) appendHold(pts, holdMs)
        return pts
    }

    private fun polygonSamples(vertices: List<Pair<Float, Float>>, holdMs: Long = 360L): List<StylusPointSample> {
        val pts = mutableListOf<StylusPointSample>()
        var time = 0L
        for (seg in 0 until vertices.size - 1) {
            val (ax, ay) = vertices[seg]
            val (bx, by) = vertices[seg + 1]
            val start = if (seg == 0) 0 else 1
            for (k in start..12) {
                val t = k / 12f
                pts.add(sample(ax + (bx - ax) * t, ay + (by - ay) * t, time))
                time += 10L
            }
        }
        if (holdMs > 0L) appendHold(pts, holdMs)
        return pts
    }

    @Test
    fun holdGateRejectsTooShortStrokeOrInsufficientHoldDurationOrHighDrift() {
        val shortLine = lineSamples(holdMs = 350L, lengthPt = 28f)
        assertNull("Strokes <= 40 pt must not trigger Hold-to-Snap", ShapeRecognizer.recognize(shortLine))

        val fastLift = lineSamples(holdMs = 180L, lengthPt = 140f)
        assertFalse(ShapeRecognizer.isHoldToSnapTriggered(fastLift))
        assertNull("Hold < 300 ms must not snap", ShapeRecognizer.recognize(fastLift))

        val driftingTail = lineSamples(holdMs = 0L, lengthPt = 140f).toMutableList()
        appendHold(driftingTail, holdMs = 380L, driftPt = 12f)
        assertFalse(ShapeRecognizer.isHoldToSnapTriggered(driftingTail))
        assertNull("Tail drift >= 4 pt must not snap", ShapeRecognizer.recognize(driftingTail))
    }

    @Test
    fun recognizesAllSixGeometricPrimitives() {
        val line = ShapeRecognizer.recognize(lineSamples())
        assertNotNull(line)
        assertEquals(RecognizedShapeType.LINE, line!!.shapeMeta.shapeType)
        assertEquals(2, line.shapeMeta.controlPoints.size)

        val circle = ShapeRecognizer.recognize(circleSamples())
        assertNotNull(circle)
        assertEquals(RecognizedShapeType.CIRCLE, circle!!.shapeMeta.shapeType)

        val ellipse = ShapeRecognizer.recognize(ellipseSamples())
        assertNotNull(ellipse)
        assertEquals(RecognizedShapeType.ELLIPSE, ellipse!!.shapeMeta.shapeType)

        val triangle = ShapeRecognizer.recognize(
            polygonSamples(
                listOf(100f to 220f, 175f to 80f, 250f to 220f, 100f to 220f)
            )
        )
        assertNotNull(triangle)
        assertEquals(RecognizedShapeType.TRIANGLE, triangle!!.shapeMeta.shapeType)
        assertEquals(3, triangle.shapeMeta.controlPoints.size)

        val rectangle = ShapeRecognizer.recognize(
            polygonSamples(
                listOf(90f to 90f, 260f to 90f, 260f to 190f, 90f to 190f, 90f to 90f)
            )
        )
        assertNotNull(rectangle)
        assertEquals(RecognizedShapeType.RECTANGLE, rectangle!!.shapeMeta.shapeType)
        assertEquals(4, rectangle.shapeMeta.controlPoints.size)

        val arrow = ShapeRecognizer.recognize(
            polygonSamples(
                listOf(
                    80f to 160f,
                    220f to 160f,
                    192f to 136f,
                    220f to 160f,
                    192f to 184f
                )
            )
        )
        assertNotNull(arrow)
        assertEquals(RecognizedShapeType.ARROW, arrow!!.shapeMeta.shapeType)
        assertEquals(2, arrow.shapeMeta.controlPoints.size)
    }

    @Test
    fun lineOnlyHighlighterRejectsClosedShapesAndAllowsStraightLines() {
        val line = ShapeRecognizer.recognize(lineSamples(), lineOnly = true)
        assertNotNull(line)
        assertEquals(RecognizedShapeType.LINE, line!!.shapeMeta.shapeType)

        val rect = ShapeRecognizer.recognize(
            polygonSamples(listOf(90f to 90f, 260f to 90f, 260f to 190f, 90f to 190f, 90f to 90f)),
            lineOnly = true
        )
        assertNull("Highlighter line-only mode should ignore closed rectangles", rect)
    }

    @Test
    fun shapeMetaTransformsAndGeneratesValidSamplesForLaterEditing() {
        val result = ShapeRecognizer.recognize(circleSamples())!!
        val translated = result.shapeMeta.transform(AffineTransform2D(translateXPt = 40f, translateYPt = -25f))
        assertEquals(result.shapeMeta.controlPoints[0].xPt + 40f, translated.controlPoints[0].xPt, 1e-3f)
        assertEquals(result.shapeMeta.controlPoints[0].yPt - 25f, translated.controlPoints[0].yPt, 1e-3f)

        val regenerated = translated.generateSamples(sample(0f, 0f, 0L))
        assertTrue(regenerated.size > 30)
        assertTrue(regenerated.zipWithNext().all { (a, b) -> b.elapsedTimeMillis >= a.elapsedTimeMillis })
    }

    @Test
    fun colorLuminanceInverterPreservesHueSaturationAlphaAndInvertsLightness() {
        // Dark ink blue (#FF112266): low lightness in light mode -> high lightness in OLED mode
        val darkBlue = 0xFF112266.toInt()
        val origHsl = ColorLuminanceInverter.rgbToHsl(0x11 / 255f, 0x22 / 255f, 0x66 / 255f)

        val inverted = ColorLuminanceInverter.invertArgbLuminance(darkBlue)
        assertEquals(0xFF, (inverted ushr 24) and 0xFF)

        val invR = ((inverted ushr 16) and 0xFF) / 255f
        val invG = ((inverted ushr 8) and 0xFF) / 255f
        val invB = (inverted and 0xFF) / 255f
        val invHsl = ColorLuminanceInverter.rgbToHsl(invR, invG, invB)

        assertEquals(1.0f - origHsl[2], invHsl[2], 0.02f)
        assertEquals(origHsl[0], invHsl[0], 3.0f)
        assertEquals(origHsl[1], invHsl[1], 0.03f)

        // Semitransparent highlighter yellow preserves alpha and stays symmetric under double inversion
        val highlighter = 0x59FFEB3B
        val once = ColorLuminanceInverter.invertArgbLuminance(highlighter)
        val twice = ColorLuminanceInverter.invertArgbLuminance(once)
        assertEquals(0x59, (once ushr 24) and 0xFF)
        assertTrue(abs(((twice ushr 16) and 0xFF) - 0xFF) <= 3)
        assertTrue(abs(((twice ushr 8) and 0xFF) - 0xEB) <= 3)
        assertTrue(abs((twice and 0xFF) - 0x3B) <= 3)
    }
}

