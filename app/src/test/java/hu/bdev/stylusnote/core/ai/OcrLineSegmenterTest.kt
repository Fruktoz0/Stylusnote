package hu.bdev.stylusnote.core.ai

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLineSegmenterTest {

    private val segmenter = OcrLineSegmenter()

    @Test
    fun segmenterExcludesHighlightersLasersSnappedShapesLargeDiagramsAndLatexOverlaps() {
        val textStroke = stroke(
            id = "text-1",
            zOrder = 1L,
            x1 = 40f,
            y1 = 100f,
            x2 = 85f,
            y2 = 116f,
            baseTimeMs = 100L
        )
        val highlighterStroke = stroke(
            id = "hl-1",
            zOrder = 2L,
            x1 = 40f,
            y1 = 100f,
            x2 = 85f,
            y2 = 116f,
            baseTimeMs = 200L,
            layer = StrokeLayerType.HIGHLIGHTER,
            toolType = BrushToolType.HIGHLIGHTER
        )
        val snappedTriangle = stroke(
            id = "shape-1",
            zOrder = 4L,
            x1 = 150f,
            y1 = 95f,
            x2 = 190f,
            y2 = 130f,
            baseTimeMs = 400L,
            shapeMeta = ShapeMeta(
                shapeType = RecognizedShapeType.TRIANGLE,
                controlPoints = listOf(DocPoint(150f, 130f), DocPoint(170f, 95f), DocPoint(190f, 130f))
            )
        )
        val largeDiagram = stroke(
            id = "diagram-1",
            zOrder = 5L,
            x1 = 50f,
            y1 = 220f,
            x2 = 340f,
            y2 = 390f,
            baseTimeMs = 500L
        )
        val overlappedByLatex = stroke(
            id = "latex-overlap",
            zOrder = 6L,
            x1 = 210f,
            y1 = 102f,
            x2 = 240f,
            y2 = 118f,
            baseTimeMs = 600L
        )

        val objects = listOf(
            // Full-page PDF backdrop must NOT exclude overlying handwriting strokes
            PageObject.PdfPageRefObject(
                id = "pdf-bg",
                zOrder = 0L,
                bounds = DocRect(0f, 0f, 595f, 842f),
                pdfAssetRelativePath = "pdfs/lecture.pdf",
                pageIndexZeroBased = 0
            ),
            PageObject.LatexObject(
                id = "latex-obj-1",
                zOrder = 10L,
                bounds = DocRect(200f, 95f, 300f, 130f),
                latexSource = "\\frac{a}{b}"
            )
        )

        val lines = segmenter.segmentIntoLines(
            strokes = listOf(
                textStroke,
                highlighterStroke,
                snappedTriangle,
                largeDiagram,
                overlappedByLatex
            ),
            pageObjects = objects
        )

        assertEquals(1, lines.size)
        val ids = lines.single().strokes.map { it.strokeId }
        assertEquals(listOf("text-1"), ids)
    }

    @Test
    fun segmenterGroupsHandwritingIntoTopToBottomLinesAndAttachesHungarianDiacritics() {
        val line1Word1 = stroke(
            id = "l1-w1",
            zOrder = 1L,
            x1 = 40f,
            y1 = 100f,
            x2 = 95f,
            y2 = 118f,
            baseTimeMs = 1000L
        )
        val line1Diacritic = stroke(
            id = "l1-accent",
            zOrder = 2L,
            x1 = 76f,
            y1 = 91f,
            x2 = 82f,
            y2 = 95f,
            baseTimeMs = 1150L
        )
        val line1Word2 = stroke(
            id = "l1-w2",
            zOrder = 3L,
            x1 = 110f,
            y1 = 102f,
            x2 = 175f,
            y2 = 117f,
            baseTimeMs = 1300L
        )

        val line2Word1 = stroke(
            id = "l2-w1",
            zOrder = 4L,
            x1 = 42f,
            y1 = 160f,
            x2 = 120f,
            y2 = 178f,
            baseTimeMs = 2000L
        )
        val line2Word2 = stroke(
            id = "l2-w2",
            zOrder = 5L,
            x1 = 132f,
            y1 = 161f,
            x2 = 195f,
            y2 = 176f,
            baseTimeMs = 2200L
        )

        // Pass strokes out of order to ensure spatial clustering + zOrder sorting are deterministic.
        val lines = segmenter.segmentIntoLines(
            strokes = listOf(line2Word2, line1Diacritic, line1Word2, line2Word1, line1Word1)
        )

        assertEquals(2, lines.size)

        val firstLine = lines[0]
        assertEquals(listOf("l1-w1", "l1-accent", "l1-w2"), firstLine.strokes.map { it.strokeId })
        assertTrue(firstLine.writingAreaWidthPt >= 120f)
        assertTrue(firstLine.writingAreaHeightPt > 0f)

        val secondLine = lines[1]
        assertEquals(listOf("l2-w1", "l2-w2"), secondLine.strokes.map { it.strokeId })
        assertTrue(firstLine.bounds.bottomPt < secondLine.bounds.topPt)

        // Verify strictly increasing per-stroke timestamps (including duplicated input timestamps)
        for (line in lines) {
            for (s in line.strokes) {
                for (i in 1 until s.points.size) {
                    assertTrue(
                        "Timestamps inside stroke ${s.strokeId} must be strictly increasing",
                        s.points[i].timestampMs > s.points[i - 1].timestampMs
                    )
                }
                assertFalse(s.points.isEmpty())
            }
        }
    }

    private fun stroke(
        id: String,
        zOrder: Long,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        baseTimeMs: Long,
        layer: StrokeLayerType = StrokeLayerType.INK,
        toolType: BrushToolType = BrushToolType.FOUNTAIN_PEN,
        shapeMeta: ShapeMeta? = null
    ): PersistedStrokeRecord {
        val midX = (x1 + x2) * 0.5f
        val midY = (y1 + y2) * 0.5f
        return PersistedStrokeRecord(
            id = id,
            layer = layer,
            zOrder = zOrder,
            brushConfig = BrushConfig(toolType = toolType, sizePt = 2.0f),
            canonicalColorArgb = 0xFF1A1D24.toInt(),
            sizePt = 2.0f,
            samples = listOf(
                StylusPointSample(x1, y1, baseTimeMs, 0.5f, 0f, 0f, isEraserTool = false),
                StylusPointSample(midX, midY, baseTimeMs, 0.55f, 0f, 0f, isEraserTool = false),
                StylusPointSample(x2, y2, baseTimeMs + 15L, 0.5f, 0f, 0f, isEraserTool = false)
            ),
            bounds = DocRect.fromPoints(x1, y1, x2, y2),
            shapeMeta = shapeMeta
        )
    }
}

