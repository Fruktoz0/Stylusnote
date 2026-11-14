package hu.bdev.stylusnote

import android.content.Context
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasAction
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class StylusNoteInstrumentedValidationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fts4AccentInsensitiveHungarianSearchFindsPagesInRoomDatabase() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val now = 1_700_000_000_000L
            db.notebookDao().upsertNotebook(
                NotebookEntity(
                    id = "nb-hu-1",
                    title = "Analízis Jegyzetek",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            db.pageDao().upsertPage(
                PageEntity(
                    id = "pg-hu-1",
                    notebookId = "nb-hu-1",
                    pageIndexInNotebook = 0,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            db.pageTextSearchDao().replacePageOcrText(
                pageId = "pg-hu-1",
                notebookId = "nb-hu-1",
                recognizedText = "Elsőfokú differenciálegyenlet és tűzoltó függvénytáblázat őrült űrhajó"
            )

            val hitsNoAccents = db.pageTextSearchDao().searchAccentInsensitive("elsofoku tuzolto fuggveny urhajo")
            assertEquals(1, hitsNoAccents.size)
            assertEquals("pg-hu-1", hitsNoAccents.first().pageId)

            val hitsWithAccents = db.pageTextSearchDao().searchAccentInsensitive("tűzoltó őrült")
            assertEquals(1, hitsWithAccents.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun strokeJournalCrashRecoveryRestoresUncompactedStrokesAndDropsTruncatedTail() = runBlocking {
        val tempDir = File(context.cacheDir, "journal_crash_recovery_${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val firstJournal = StrokeJournal(tempDir, fsyncDebounceMs = 0L)
            firstJournal.openAndRecover()

            val stroke = PersistedStrokeRecord(
                id = "stroke-rec-1",
                layer = StrokeLayerType.INK,
                zOrder = 1_000_000_001L,
                brushConfig = BrushConfig(toolType = BrushToolType.FOUNTAIN_PEN, sizePt = 2.5f),
                canonicalColorArgb = BrushConfig.DEFAULT_INK_COLOR_ARGB,
                sizePt = 2.5f,
                samples = listOf(
                    StylusPointSample(10f, 20f, 100L, 0.5f, 0f, 0f, isEraserTool = false),
                    StylusPointSample(50f, 60f, 112L, 0.6f, 0f, 0f, isEraserTool = false)
                ),
                bounds = DocRect(10f, 20f, 50f, 60f)
            )
            val latex = PageObject.LatexObject(
                id = "latex-rec-1",
                zOrder = 2_000_000_001L,
                bounds = DocRect(80f, 80f, 180f, 120f),
                latexSource = "\\sum_{i=1}^n i = \\frac{n(n+1)}{2}"
            )
            firstJournal.appendAddStrokes(listOf(stroke))
            firstJournal.appendAddObject(latex)
            firstJournal.flushAndSync()
            firstJournal.closeBlocking(compactBeforeClose = false)

            // Simulate sudden process kill mid-write by appending partial garbage bytes to page.journal
            FileOutputStream(File(tempDir, StrokeJournal.JOURNAL_FILE_NAME), true).use { fos ->
                fos.write(byteArrayOf(0x01, 0x00, 0x00, 0x7F, 0x11, 0x22))
                fos.flush()
            }

            val recoveredJournal = StrokeJournal(tempDir)
            val result = try {
                recoveredJournal.openAndRecover()
            } finally {
                recoveredJournal.closeBlocking(compactBeforeClose = false)
            }

            assertEquals(1, result.discardedCorruptRecords)
            assertEquals(1, result.strokes.size)
            assertEquals("stroke-rec-1", result.strokes.single().id)
            assertEquals(1, result.objects.size)
            assertEquals("latex-rec-1", result.objects.single().id)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun zoom400PercentCoordinateRoundTripSubPixelAccuracyUnderOnePixel() {
        val matrix = CanvasTransformMatrix(
            initialScale = 4.0f,
            initialPanXPx = 320.5f,
            initialPanYPx = -180.25f
        )

        val testPoints = listOf(
            0f to 0f,
            142.37f to 289.91f,
            595f to 842f,
            1024.5f to 640.75f
        )

        for ((sx, sy) in testPoints) {
            val doc = matrix.screenToDoc(sx, sy)
            val roundTrip = matrix.docToScreen(doc.xPt, doc.yPt)
            val errorPx = hypot(roundTrip.xPx - sx, roundTrip.yPx - sy)
            assertTrue("400%-os nagyításnál a round-trip eltérés > 1 px: $errorPx", errorPx <= 1.0f)
        }
    }

    @Test
    fun brushConfigCreatesAllFiveInkBrushesAndHighlighterUsesDiscardSelfOverlap() {
        val brushTypes = listOf(
            BrushToolType.FOUNTAIN_PEN,
            BrushToolType.BALLPOINT_PEN,
            BrushToolType.PENCIL,
            BrushToolType.HIGHLIGHTER,
            BrushToolType.LASER
        )
        for (type in brushTypes) {
            val inkBrush = BrushConfig(toolType = type, sizePt = 4f).toInkBrush()
            assertEquals(4f, inkBrush.size, 1e-4f)
        }
        assertEquals(
            StockBrushes.highlighter(selfOverlap = SelfOverlap.DISCARD),
            BrushConfig.resolveBrushFamily(BrushToolType.HIGHLIGHTER)
        )
    }

    @Test
    fun highlightersAlwaysSortBelowNormalInkStrokesRegardlessOfCreationOrder() {
        val holder = CanvasStateHolder()
        val inkFirst = createDryStroke(
            id = "ink-1",
            points = listOf(inkSample(0f, 0f, 0L), inkSample(40f, 0f, 10L)),
            zIndex = holder.allocateZIndex(isHighlighter = false),
            isHighlighter = false
        )
        val highlighterSecond = createDryStroke(
            id = "hl-1",
            points = listOf(inkSample(0f, 0f, 0L), inkSample(40f, 0f, 10L)),
            zIndex = holder.allocateZIndex(isHighlighter = true),
            isHighlighter = true
        )

        holder.addCommittedStrokes(listOf(inkFirst))
        holder.addCommittedStrokes(listOf(highlighterSecond))

        val sorted = holder.allStrokesSorted()
        assertEquals(listOf("hl-1", "ink-1"), sorted.map { it.id })
    }

    @Test
    fun deltaUndoRedoSupportsAllActionTypesAndRetainsAtLeast50Steps() {
        val holder = CanvasStateHolder(
            minUndoSteps = 50,
            maxUndoSteps = 200,
            maxPointBudget = 50
        )

        for (i in 1..60) {
            val y = i * 10f
            val stroke = createDryStroke(
                id = "s-$i",
                points = listOf(
                    inkSample(0f, y, 0L),
                    inkSample(20f, y, 5L),
                    inkSample(40f, y, 10L)
                ),
                zIndex = holder.allocateZIndex(isHighlighter = false),
                isHighlighter = false
            )
            holder.addCommittedStrokes(listOf(stroke))
        }

        assertEquals(50, holder.undoDepth)

        val transformDirty = holder.transformStrokes(
            strokeIds = listOf("s-60"),
            transform = AffineTransform2D.translation(dxPt = 100f, dyPt = 50f)
        )
        assertNotNull(transformDirty)
        val movedPoints = CanvasStateHolder.extractStylusPoints(holder.getStroke("s-60")!!.stroke)
        assertEquals(100f, movedPoints[0].xPt, 1e-2f)

        val newColor = 0xFFD50000.toInt()
        val recolorDirty = holder.recolorStrokes(listOf("s-60"), newColor)
        assertNotNull(recolorDirty)
        assertEquals(newColor, holder.getStroke("s-60")!!.stroke.brush.colorIntArgb)

        assertNotNull(holder.undo())
        assertNotEquals(newColor, holder.getStroke("s-60")!!.stroke.brush.colorIntArgb)
        assertTrue(holder.canRedo)

        assertNotNull(holder.undo())
        val restoredPoints = CanvasStateHolder.extractStylusPoints(holder.getStroke("s-60")!!.stroke)
        assertEquals(0f, restoredPoints[0].xPt, 1e-2f)

        assertNotNull(holder.redo())
        val reappliedPoints = CanvasStateHolder.extractStylusPoints(holder.getStroke("s-60")!!.stroke)
        assertEquals(100f, reappliedPoints[0].xPt, 1e-2f)

        val extraStroke = createDryStroke(
            id = "s-extra",
            points = listOf(inkSample(0f, 999f, 0L), inkSample(10f, 999f, 5L)),
            zIndex = holder.allocateZIndex(false),
            isHighlighter = false
        )
        holder.addCommittedStrokes(listOf(extraStroke))
        assertFalse(holder.canRedo)
    }

    @Test
    fun vectorEraserDeletesOnlyIntersectingStrokesAndUndoesCleanly() {
        val holder = CanvasStateHolder()
        val hitStroke = createDryStroke(
            id = "hit",
            points = listOf(inkSample(0f, 0f, 0L), inkSample(100f, 0f, 20L)),
            zIndex = holder.allocateZIndex(false),
            isHighlighter = false
        )
        val farStroke = createDryStroke(
            id = "far",
            points = listOf(inkSample(0f, 300f, 0L), inkSample(100f, 300f, 20L)),
            zIndex = holder.allocateZIndex(false),
            isHighlighter = false
        )
        holder.addCommittedStrokes(listOf(hitStroke, farStroke))

        holder.beginEraserSession()
        val dirty = holder.eraseWholeStrokesAt(centerXPt = 50f, centerYPt = 2f, radiusPt = 12f)
        val sessionAction = holder.finishEraserSession()

        assertNotNull(dirty)
        assertTrue(sessionAction is CanvasAction.RemovedStrokes)
        assertNull(holder.getStroke("hit"))
        assertNotNull(holder.getStroke("far"))
        assertEquals(1, holder.strokeCount)
        assertEquals(1, holder.spatialIndex.size)

        holder.undo()
        assertNotNull(holder.getStroke("hit"))
        assertNotNull(holder.spatialIndex.get("hit"))
        assertEquals(2, holder.strokeCount)
    }

    @Test
    fun precisionEraserSplitsStrokeIntoThreeSegmentsAcrossSessionAndUndoesInOneStep() {
        val seq = AtomicInteger(1)
        val holder = CanvasStateHolder(
            uuidGenerator = { "split-${seq.getAndIncrement()}" }
        )

        val longStroke = createDryStroke(
            id = "orig-1",
            points = listOf(
                inkSample(0f, 0f, 0L, 0.5f),
                inkSample(50f, 0f, 10L, 0.6f),
                inkSample(100f, 0f, 20L, 0.7f),
                inkSample(150f, 0f, 30L, 0.8f),
                inkSample(200f, 0f, 40L, 0.9f)
            ),
            zIndex = holder.allocateZIndex(false),
            isHighlighter = false,
            brushSizePt = 2f
        )
        holder.addCommittedStrokes(listOf(longStroke))
        val undoDepthBeforeErase = holder.undoDepth

        holder.beginEraserSession()
        val step1 = holder.erasePartialStrokesAt(centerXPt = 50f, centerYPt = 0f, radiusPt = 10f)
        assertEquals(1, step1.removedStrokeCount)
        assertEquals(2, step1.createdSegmentCount)

        val step2 = holder.erasePartialStrokesAt(centerXPt = 150f, centerYPt = 0f, radiusPt = 10f)
        assertEquals(1, step2.removedStrokeCount)
        assertEquals(2, step2.createdSegmentCount)

        val sessionAction = holder.finishEraserSession()
        assertNotNull(sessionAction)
        assertTrue(sessionAction is CanvasAction.CompositeAction)

        assertNull(holder.getStroke("orig-1"))
        assertEquals(3, holder.strokeCount)
        assertEquals(3, holder.spatialIndex.size)
        assertEquals(undoDepthBeforeErase + 1, holder.undoDepth)

        val pieces = holder.allStrokesSorted()
        assertEquals(3, pieces.size)
        assertTrue(pieces.all { it.id.startsWith("split-") && it.stroke.brush.size == 2f })

        val undoDirty = holder.undo()
        assertNotNull(undoDirty)
        assertEquals(1, holder.strokeCount)
        assertNotNull(holder.getStroke("orig-1"))
        assertNull(holder.getStroke(pieces[0].id))
        assertNull(holder.spatialIndex.get(pieces[0].id))

        val redoDirty = holder.redo()
        assertNotNull(redoDirty)
        assertEquals(3, holder.strokeCount)
        assertNull(holder.getStroke("orig-1"))
    }

    private fun inkSample(
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

    private fun createDryStroke(
        id: String,
        points: List<StylusPointSample>,
        zIndex: Long,
        isHighlighter: Boolean,
        brushSizePt: Float = 2f
    ): IndexedDryStroke {
        val brushConfig = BrushConfig(
            toolType = if (isHighlighter) BrushToolType.HIGHLIGHTER else BrushToolType.FOUNTAIN_PEN,
            sizePt = brushSizePt,
            colorArgb = if (isHighlighter) BrushConfig.DEFAULT_HIGHLIGHTER_COLOR_ARGB else BrushConfig.DEFAULT_INK_COLOR_ARGB
        )
        val brush = brushConfig.toInkBrush()
        val stroke = Stroke(
            brush = brush,
            inputs = points.toStrokeInputBatch(InputToolType.STYLUS)
        )
        val bounds: DocRect = computeStrokeDocBounds(stroke)
        return IndexedDryStroke(
            id = id,
            stroke = stroke,
            bounds = bounds,
            zIndex = zIndex,
            isHighlighter = isHighlighter
        )
    }
}

