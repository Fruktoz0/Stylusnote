package hu.bdev.stylusnote.domain

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import hu.bdev.stylusnote.core.ai.LiveMathSuggestionEngine
import hu.bdev.stylusnote.core.ai.MathFractionLayoutAnalyzer
import hu.bdev.stylusnote.core.ai.OcrLineSegmenter
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.FingerVelocityTracker
import hu.bdev.stylusnote.core.math.ScrollFlingState
import hu.bdev.stylusnote.core.math.computeVerticalPanBoundsPx
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.ObjectAttachmentGeometry
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.computeClampedActionPillOffsetPx
import hu.bdev.stylusnote.ui.canvas.insertLatexSnippetAtCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class XivPhaseValidationUnitTest {

    private fun sampleInkStroke(
        id: String,
        zOrder: Long,
        leftPt: Float,
        topPt: Float,
        rightPt: Float,
        bottomPt: Float,
        toolType: BrushToolType = BrushToolType.FOUNTAIN_PEN,
        layer: StrokeLayerType = StrokeLayerType.INK
    ): PersistedStrokeRecord = PersistedStrokeRecord(
        id = id,
        layer = layer,
        zOrder = zOrder,
        brushConfig = BrushConfig(toolType = toolType, sizePt = 2f),
        canonicalColorArgb = BrushConfig.DEFAULT_INK_COLOR_ARGB,
        sizePt = 2f,
        inkEncodedInputs = ByteArray(0),
        samples = listOf(
            StylusPointSample(leftPt, topPt, 0L, 0.7f, 0f, 0f, false),
            StylusPointSample(rightPt, bottomPt, 0L, 0.7f, 0f, 0f, false)
        ),
        bounds = DocRect(leftPt, topPt, rightPt, bottomPt)
    )

    @Test
    fun tableLassoCapturesPerimeterAndInteriorButExcludesSingleCellTightLassoAndMovesAtomically() {
        var nextId = 1
        val holder = CanvasStateHolder(uuidGenerator = { "tbl-gen-${nextId++}" })
        val table = PageObject.TableObject(
            id = "tbl-xiv-1",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(60f, 100f, 360f, 280f),
            rows = 3,
            columns = 3,
            hasHeaderRow = true
        )
        holder.addPageObject(table)

        // Single-cell tight lasso strictly inside row 1 col 1 -> must NOT grab the whole table
        val singleCellLasso = listOf(
            DocPoint(75f, 170f),
            DocPoint(145f, 170f),
            DocPoint(145f, 210f),
            DocPoint(75f, 210f)
        )
        assertTrue(holder.selectPageObjectsByLasso(singleCellLasso).isEmpty())

        // Interior wide lasso touching >= 3 interior 3x3 lattice points (without outer perimeter)
        val interiorWideLasso = listOf(
            DocPoint(100f, 125f),
            DocPoint(320f, 125f),
            DocPoint(320f, 255f),
            DocPoint(100f, 255f)
        )
        assertEquals(listOf(table.id), holder.selectPageObjectsByLasso(interiorWideLasso).map { it.id })

        // Cell ink point attaches cleanly to TableObject
        val attachedTarget = ObjectAttachmentGeometry.findTopmostUnderInkObjectForStroke(
            points = listOf(DocPoint(110f, 185f), DocPoint(130f, 195f)),
            objects = listOf(table)
        )
        assertEquals(table.id, attachedTarget?.id)

        // Move, duplicate, delete and undo/redo cleanly in CanvasStateHolder
        val moved = holder.transformSelection(
            strokeIds = emptyList(),
            objectIds = listOf(table.id),
            transform = AffineTransform2D.translation(45f, 30f)
        )
        assertEquals(1, moved.updatedObjects.size)
        assertEquals(105f, holder.getPageObject(table.id)!!.bounds.leftPt, 0.01f)

        assertNotNull(holder.undo())
        assertEquals(60f, holder.getPageObject(table.id)!!.bounds.leftPt, 0.01f)
    }

    @Test
    fun buildMathLineForRecognitionPreservesMonotonicTimestampsAndFiltersLassoAndHighlighter() {
        val segmenter = OcrLineSegmenter()
        val ink1 = sampleInkStroke("ink-1", 1L, 40f, 100f, 80f, 120f, toolType = BrushToolType.FOUNTAIN_PEN)
        val ink2 = sampleInkStroke("ink-2", 2L, 90f, 100f, 130f, 120f, toolType = BrushToolType.PENCIL)
        val lassoStroke = sampleInkStroke("lasso-1", 3L, 20f, 80f, 150f, 140f, toolType = BrushToolType.LASSO)
        val highlighter = sampleInkStroke(
            id = "hl-1",
            zOrder = 4L,
            leftPt = 40f,
            topPt = 100f,
            rightPt = 130f,
            bottomPt = 120f,
            toolType = BrushToolType.HIGHLIGHTER,
            layer = StrokeLayerType.HIGHLIGHTER
        )

        val line = segmenter.buildMathLineForRecognition(listOf(highlighter, ink2, lassoStroke, ink1))
        assertNotNull(line)
        assertEquals(listOf("ink-1", "ink-2"), line!!.strokes.map { it.strokeId })

        val allTimestamps = line.strokes.flatMap { stroke -> stroke.points.map { it.timestampMs } }
        for (i in 1 until allTimestamps.size) {
            assertTrue(
                "Timestamps must strictly increase even when raw samples share t=0; got $allTimestamps",
                allTimestamps[i] > allTimestamps[i - 1]
            )
        }
    }

    @Test
    fun mathFractionLayoutAnalyzerSplitsFractionAndIgnoresEqualsWhileToBasicLatexFormatsExpressions() {
        val prefix = sampleInkStroke("pref", 1L, 30f, 130f, 65f, 150f)
        val numerator = sampleInkStroke("num", 2L, 95f, 98f, 165f, 122f)
        val fracBar = sampleInkStroke("bar", 3L, 80f, 140f, 185f, 142f)
        val denominator = sampleInkStroke("den", 4L, 100f, 156f, 170f, 184f)
        val eqTop = sampleInkStroke("eq-t", 5L, 200f, 135f, 224f, 136f)
        val eqBot = sampleInkStroke("eq-b", 6L, 200f, 146f, 224f, 147f)
        val suffix = sampleInkStroke("suf", 7L, 240f, 128f, 265f, 152f)

        val split = MathFractionLayoutAnalyzer.detectTopLevelFraction(
            listOf(prefix, numerator, fracBar, denominator, eqTop, eqBot, suffix)
        )
        assertNotNull(split)
        assertEquals("bar", split!!.barStroke.id)
        assertEquals(listOf("pref"), split.prefixStrokes.map { it.id })
        assertEquals(listOf("num"), split.numeratorStrokes.map { it.id })
        assertEquals(listOf("den"), split.denominatorStrokes.map { it.id })
        assertEquals(setOf("eq-t", "eq-b", "suf"), split.suffixStrokes.map { it.id }.toSet())

        // Paired '=' bars without numerator/denominator above & below must NOT split as a fraction
        val leftX = sampleInkStroke("lhs", 10L, 40f, 120f, 65f, 155f)
        val eq1 = sampleInkStroke("eq1", 11L, 80f, 132f, 110f, 133f)
        val eq2 = sampleInkStroke("eq2", 12L, 80f, 144f, 110f, 145f)
        val right3 = sampleInkStroke("rhs", 13L, 125f, 120f, 150f, 155f)
        assertNull(MathFractionLayoutAnalyzer.detectTopLevelFraction(listOf(leftX, eq1, eq2, right3)))

        val latex = LiveMathSuggestionEngine.toBasicLatex("alpha <= x -> (a+1)/(b-1) + sqrt(x^2) + x_i != 0")
        assertTrue(latex.contains("\\alpha"))
        assertTrue(latex.contains("\\le"))
        assertTrue(latex.contains("\\to"))
        assertTrue(latex.contains("\\frac{a+1}{b-1}"))
        assertTrue(latex.contains("\\sqrt{x^{2}}"))
        assertTrue(latex.contains("x_{i}"))
        assertTrue(latex.contains("\\ne"))
    }

    @Test
    fun cursorAwareLatexInsertionPlacesCaretInsideBracesAndReplacesSelection() {
        val initial = TextFieldValue("y =  + 1", selection = TextRange(4))
        val withFrac = insertLatexSnippetAtCursor(initial, "\\frac{}{}", cursorBackOffset = 3)
        assertEquals("y = \\frac{}{} + 1", withFrac.text)
        assertEquals(10, withFrac.selection.start)

        // Next symbol inserts directly inside the numerator braces without spurious leading space
        val withPiInNumerator = insertLatexSnippetAtCursor(withFrac, "\\pi", cursorBackOffset = 0)
        assertEquals("y = \\frac{\\pi}{} + 1", withPiInNumerator.text)
        assertEquals(13, withPiInNumerator.selection.start)

        // Active text selection is replaced cleanly in-place
        val highlighted = TextFieldValue("x + old + 1", selection = TextRange(4, 7))
        val replaced = insertLatexSnippetAtCursor(highlighted, "\\beta", cursorBackOffset = 0)
        assertEquals("x + \\beta + 1", replaced.text)
    }

    @Test
    fun floatingActionPillClampsWithinViewportAndFlipsAboveAnchorNearBottomRightEdge() {
        val offset = computeClampedActionPillOffsetPx(
            anchorLeftPx = 1200f,
            anchorTopPx = 740f,
            anchorHeightPx = 40f,
            pillWidthPx = 320,
            pillHeightPx = 56,
            viewportWidthPx = 1280,
            viewportHeightPx = 800,
            marginPx = 12,
            gapPx = 8
        )
        assertTrue("Pill right edge must stay inside viewport", offset.x + 320 <= 1280 - 12)
        assertTrue("Pill left edge must respect margin", offset.x >= 12)
        assertTrue("Pill must flip above anchor near bottom edge", offset.y < 740)
        assertTrue("Pill top must remain on-screen", offset.y >= 12)
    }

    @Test
    fun fingerVelocityTrackerScrollFlingPhysicsAndBoundaryClampBehaveAccurately() {
        val tracker = FingerVelocityTracker()
        tracker.addSample(800f, 100L)
        tracker.addSample(680f, 120L)
        tracker.addSample(520f, 140L)
        val swipeVelocity = tracker.computeVelocityYPxPerSec(upTimeMs = 148L)
        assertTrue("Upward finger swipe should yield strong negative velocity", swipeVelocity < -5000f)

        // Finger paused for > 80ms before lift-off must cancel fling
        assertEquals(0f, tracker.computeVelocityYPxPerSec(upTimeMs = 260L), 0.01f)

        val flingA = ScrollFlingState()
        val flingB = ScrollFlingState()
        assertFalse(flingA.start(100f, 1_000_000L))
        assertTrue(flingA.start(swipeVelocity, 0L))
        assertTrue(flingB.start(swipeVelocity, 0L))

        val single16Ms = flingA.step(16_000_000L)
        val two8Ms = flingB.step(8_000_000L) + flingB.step(16_000_000L)
        assertTrue(single16Ms < 0f)
        assertEquals(
            "Analytical exponential decay must be framerate-independent across 8ms/16ms steps",
            single16Ms,
            two8Ms,
            0.05f
        )

        flingA.cancel()
        assertFalse(flingA.isActive)
        assertEquals(0f, flingA.step(32_000_000L), 0.001f)

        val matrix = CanvasTransformMatrix()
        matrix.setTransform(scale = 1f, panXPx = 0f, panYPx = 0f)
        val (minPan, maxPan) = computeVerticalPanBoundsPx(
            docTopPt = 0f,
            docBottomPt = 2000f,
            scale = 1f,
            viewportHeightPx = 1000,
            overscrollPadPx = 48f
        )
        assertFalse(matrix.panYWithBoundaryClamp(-500f, minPan, maxPan))
        assertEquals(-500f, matrix.panYPx, 0.01f)
        assertTrue(matrix.panYWithBoundaryClamp(-2000f, minPan, maxPan))
        assertEquals(minPan, matrix.panYPx, 0.01f)
        assertTrue(abs(minPan) > 0f)
    }
}
