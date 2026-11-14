package hu.bdev.stylusnote.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTransformMatrixTest {

    @Test
    fun roundTripDocToScreenAndBackAt400PercentZoom() {
        val matrix = CanvasTransformMatrix(
            initialScale = 4.0f,
            initialPanXPx = -124.5f,
            initialPanYPx = 312.25f
        )

        val original = DocPoint(xPt = 297.5f, yPt = 421.0f)
        val screen = matrix.docToScreen(original.xPt, original.yPt)
        val roundTrip = matrix.screenToDoc(screen.xPx, screen.yPx)

        assertEquals(original.xPt, roundTrip.xPt, 1e-3f)
        assertEquals(original.yPt, roundTrip.yPt, 1e-3f)
    }

    @Test
    fun scaleIsClampedBetweenMinAndMaxBounds() {
        val matrix = CanvasTransformMatrix(initialScale = 0.01f)
        assertEquals(CanvasTransformMatrix.MIN_SCALE, matrix.scale, 1e-6f)

        matrix.setTransform(scale = 20.0f, panXPx = 0f, panYPx = 0f)
        assertEquals(CanvasTransformMatrix.MAX_SCALE, matrix.scale, 1e-6f)
    }

    @Test
    fun zoomAroundScreenPointKeepsFocalDocPointStationary() {
        val matrix = CanvasTransformMatrix(
            initialScale = 1.5f,
            initialPanXPx = 80f,
            initialPanYPx = 120f
        )
        val focalX = 640f
        val focalY = 480f

        val docBefore = matrix.screenToDoc(focalX, focalY)
        matrix.zoomAroundScreenPoint(focalXPx = focalX, focalYPx = focalY, zoomFactor = 2.5f)
        val docAfter = matrix.screenToDoc(focalX, focalY)

        assertEquals(docBefore.xPt, docAfter.xPt, 1e-3f)
        assertEquals(docBefore.yPt, docAfter.yPt, 1e-3f)
        assertEquals(3.75f, matrix.scale, 1e-5f)
    }

    @Test
    fun matrixValuesMatchDocAndScreenInverseConsistency() {
        val matrix = CanvasTransformMatrix(
            initialScale = 2.5f,
            initialPanXPx = 150f,
            initialPanYPx = -40f
        )
        val forward = FloatArray(9)
        val inverse = FloatArray(9)
        matrix.writeDocToScreenMatrixValues(forward)
        matrix.writeScreenToDocMatrixValues(inverse)

        val docPt = DocPoint(120f, 340f)
        val mappedScreenX = forward[0] * docPt.xPt + forward[2]
        val mappedScreenY = forward[4] * docPt.yPt + forward[5]
        val directScreen = matrix.docToScreen(docPt.xPt, docPt.yPt)

        assertEquals(directScreen.xPx, mappedScreenX, 1e-4f)
        assertEquals(directScreen.yPx, mappedScreenY, 1e-4f)

        val mappedBackX = inverse[0] * mappedScreenX + inverse[2]
        val mappedBackY = inverse[4] * mappedScreenY + inverse[5]

        assertEquals(docPt.xPt, mappedBackX, 1e-3f)
        assertEquals(docPt.yPt, mappedBackY, 1e-3f)
    }

    @Test
    fun fitPageToViewportCentersA4PageWithinBounds() {
        val matrix = CanvasTransformMatrix()
        val a4 = PageDimensionsPt.A4_PORTRAIT
        matrix.fitPageToViewport(
            pageWidthPt = a4.widthPt,
            pageHeightPt = a4.heightPt,
            viewportWidthPx = 2880f,
            viewportHeightPx = 1800f,
            paddingPx = 48f
        )

        val topLeft = matrix.docToScreen(0f, 0f)
        val bottomRight = matrix.docToScreen(a4.widthPt, a4.heightPt)

        assertTrue(topLeft.xPx >= 48f - 1e-3f)
        assertTrue(topLeft.yPx >= 48f - 1e-3f)
        assertTrue(bottomRight.xPx <= 2880f - 48f + 1e-3f)
        assertTrue(bottomRight.yPx <= 1800f - 48f + 1e-3f)
        assertEquals(2880f - bottomRight.xPx, topLeft.xPx, 1e-2f)
        assertEquals(1800f - bottomRight.yPx, topLeft.yPx, 1e-2f)
    }

    @Test
    fun pinchZoomOutIsClampedAt65Percent() {
        val matrix = CanvasTransformMatrix(initialScale = 1.0f)
        matrix.zoomAroundScreenPoint(focalXPx = 500f, focalYPx = 500f, zoomFactor = 0.1f)
        assertEquals(0.65f, matrix.scale, 1e-6f)
    }
}

