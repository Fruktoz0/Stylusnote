package hu.bdev.stylusnote.domain

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasAction
import hu.bdev.stylusnote.domain.model.GeometricShapeType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.domain.repository.PrecisionStrokeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun lassoSelectsShapesImagesAndFormattedTextWhileSkippingBackgroundPdf() {
        val holder = CanvasStateHolder()
        val pdfBg = PageObject.PdfPageRefObject(
            id = "pdf-1",
            zOrder = 1L,
            bounds = DocRect(0f, 0f, 595f, 842f),
            pdfAssetRelativePath = "pdfs/doc.pdf",
            pageIndexZeroBased = 0
        )
        val shape = PageObject.GeometricShapeObject(
            id = "shape-1",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(100f, 100f, 220f, 220f),
            shapeType = GeometricShapeType.RECTANGLE
        )
        val image = PageObject.ImageObject(
            id = "img-1",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(260f, 100f, 380f, 220f),
            imageAssetRelativePath = "images/photo.png"
        )
        val formattedText = PageObject.FormattedTextObject(
            id = "txt-1",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(110f, 320f, 290f, 380f),
            text = "Jegyzet blokk"
        )
        holder.addPageObject(pdfBg)
        holder.addPageObject(shape)
        holder.addPageObject(image)
        holder.addPageObject(formattedText)

        // Lasso enclosing shape + image only -> FormattedText & PdfPageRef excluded
        val hits = holder.selectPageObjectsByLasso(
            listOf(
                DocPoint(80f, 80f),
                DocPoint(400f, 80f),
                DocPoint(400f, 250f),
                DocPoint(80f, 250f)
            )
        )
        assertEquals(listOf("shape-1", "img-1"), hits.map { it.id })

        // Small lasso inside bottom-right corner of empty background must NOT grab background PDF
        val bgHits = holder.selectPageObjectsByLasso(
            listOf(
                DocPoint(420f, 400f),
                DocPoint(550f, 400f),
                DocPoint(550f, 520f),
                DocPoint(420f, 520f)
            )
        )
        assertTrue(bgHits.isEmpty())
    }

    @Test
    fun lassoTransformsDuplicatesRecolorsAndDeletesObjectsAtomicallyWithUndoRedo() {
        var nextId = 1
        val holder = CanvasStateHolder(uuidGenerator = { "gen-${nextId++}" })
        val shape = PageObject.GeometricShapeObject(
            id = "shape-a",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(100f, 100f, 200f, 200f),
            shapeType = GeometricShapeType.CIRCLE,
            canonicalStrokeColorArgb = 0xFF111827.toInt()
        )
        val text = PageObject.FormattedTextObject(
            id = "text-a",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(240f, 100f, 360f, 160f),
            text = "E = mc^2",
            canonicalColorArgb = 0xFF111827.toInt()
        )
        holder.addPageObject(shape)
        holder.addPageObject(text)

        // Translate + rotate combined selection -> atomic undo reverts both
        val combined = AffineTransform2D.translation(50f, 30f).then(
            AffineTransform2D.rotationAround(
                angleRad = (PI / 4.0).toFloat(),
                pivotXPt = 200f,
                pivotYPt = 150f
            )
        )
        val txRes = holder.transformSelection(emptyList(), listOf("shape-a", "text-a"), combined)
        assertEquals(2, txRes.updatedObjects.size)
        assertTrue((holder.getPageObject("shape-a") as PageObject.GeometricShapeObject).rotationRadians > 0.7f)
        assertTrue((holder.getPageObject("text-a") as PageObject.FormattedTextObject).rotationRadians > 0.7f)

        assertNotNull(holder.undo())
        assertEquals(0f, (holder.getPageObject("shape-a") as PageObject.GeometricShapeObject).rotationRadians, 1e-5f)
        assertEquals(240f, holder.getPageObject("text-a")!!.bounds.leftPt, 1e-3f)

        // Recolor -> atomic
        val targetColor = 0xFFDC2626.toInt()
        val recolorRes = holder.recolorSelection(emptyList(), listOf("shape-a", "text-a"), targetColor)
        assertEquals(2, recolorRes.updatedObjects.size)
        assertEquals(targetColor, (holder.getPageObject("shape-a") as PageObject.GeometricShapeObject).canonicalStrokeColorArgb)
        assertEquals(targetColor, (holder.getPageObject("text-a") as PageObject.FormattedTextObject).canonicalColorArgb)

        // Duplicate -> creates 2 offset copies, Undo removes both duplicates atomically
        val dupRes = holder.duplicateSelection(emptyList(), listOf("shape-a", "text-a"), offsetXPt = 25f, offsetYPt = 25f)
        assertEquals(2, dupRes.duplicatedObjects.size)
        assertEquals(4, holder.allPageObjects().size)
        assertNotNull(holder.undo())
        assertEquals(2, holder.allPageObjects().size)

        // Delete -> removes both atomically, Undo restores both
        assertNotNull(holder.deleteSelection(emptyList(), listOf("shape-a", "text-a")))
        assertTrue(holder.allPageObjects().isEmpty())
        assertNull(holder.getPageObject("shape-a"))
        assertNotNull(holder.undo())
        assertEquals(2, holder.allPageObjects().size)
        assertNotNull(holder.redo())
        assertFalse(holder.allPageObjects().isNotEmpty())
    }

    @Test
    fun undoWithActionAndRedoWithActionReturnExactActionDeltas() {
        val holder = CanvasStateHolder()
        val initialObj = PageObject.FormattedTextObject(
            id = "fmt-delta-1",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(40f, 50f, 200f, 90f),
            text = "Kezdeti",
            sourceStrokeIds = listOf("stroke-1")
        )
        holder.addPageObject(initialObj)
        val updatedObj = initialObj.copy(text = "Módosított")
        holder.updatePageObject(updatedObj)

        val undoOutcome = holder.undoWithAction()
        assertNotNull(undoOutcome.affectedBounds)
        assertTrue(undoOutcome.action is CanvasAction.UpdatedObject)
        assertEquals("Kezdeti", (holder.getPageObject("fmt-delta-1") as PageObject.FormattedTextObject).text)

        val redoOutcome = holder.redoWithAction()
        assertNotNull(redoOutcome.affectedBounds)
        assertTrue(redoOutcome.action is CanvasAction.UpdatedObject)
        assertEquals("Módosított", (holder.getPageObject("fmt-delta-1") as PageObject.FormattedTextObject).text)
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

