package hu.bdev.stylusnote.data.local

import hu.bdev.stylusnote.core.hardware.PressureCurve
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

class StrokeJournalRoundTripTest {

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("snot_journal_test_").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun sampleStroke(
        id: String = UUID.randomUUID().toString(),
        toolType: BrushToolType = BrushToolType.FOUNTAIN_PEN,
        layer: StrokeLayerType = StrokeLayerType.INK,
        colorArgb: Int = 0xFF1E3A8A.toInt(),
        withShapeMeta: Boolean = false
    ): PersistedStrokeRecord {
        val pts = listOf(
            StylusPointSample(10f, 20f, 0L, 0.4f, 0.1f, 0.2f, false),
            StylusPointSample(55f, 45f, 12L, 0.7f, 0.15f, 0.3f, false),
            StylusPointSample(110f, 20f, 24L, 0.6f, 0.12f, 0.25f, false)
        )
        val meta = if (withShapeMeta) {
            ShapeMeta(
                shapeType = RecognizedShapeType.TRIANGLE,
                controlPoints = listOf(
                    DocPoint(10f, 20f),
                    DocPoint(55f, 45f),
                    DocPoint(110f, 20f)
                ),
                rotationRadians = 0.25f
            )
        } else {
            null
        }
        return PersistedStrokeRecord(
            id = id,
            layer = layer,
            zOrder = 42L,
            brushConfig = BrushConfig(
                toolType = toolType,
                sizePt = 4.5f,
                colorArgb = colorArgb,
                opacity = if (layer == StrokeLayerType.HIGHLIGHTER) 0.35f else 1.0f,
                stabilizationPercent = 30f,
                pressureCurve = PressureCurve.HARD,
                holdToSnapEnabled = true,
                eraserRadiusDp = 22f
            ),
            canonicalColorArgb = colorArgb,
            sizePt = 4.5f,
            inkEncodedInputs = byteArrayOf(1, 2, 3, 4),
            samples = pts,
            bounds = DocRect(10f, 20f, 110f, 45f),
            shapeMeta = meta,
            serializedBrushFamily = null,
            usedFallbackBrush = false
        )
    }

    @Test
    fun binarySerializerRoundTripsStrokeAndAllFourPageObjectsExactly() {
        val stroke = sampleStroke(
            toolType = BrushToolType.PENCIL,
            withShapeMeta = true
        )
        val encodedStroke = StrokeBinarySerializer.encodeStrokeRecord(stroke)
        val decodedStroke = StrokeBinarySerializer.decodeStrokeRecord(encodedStroke)
        assertEquals(stroke, decodedStroke)

        val objects: List<PageObject> = listOf(
            PageObject.LatexObject(
                id = UUID.randomUUID().toString(),
                zOrder = 100L,
                bounds = DocRect(50f, 80f, 240f, 140f),
                latexSource = "\\int_0^{\\infty} e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}",
                fontSizePt = 20f,
                canonicalColorArgb = 0xFF000000.toInt(),
                rotationRadians = 0.05f,
                sourceStrokeIds = listOf(stroke.id)
            ),
            PageObject.FunctionPlotObject(
                id = "plot-1",
                zOrder = 101L,
                bounds = DocRect(100f, 200f, 400f, 450f),
                expression = "sin(x) + 0.5*x^2",
                domainMinX = -6f,
                domainMaxX = 6f,
                rangeMinY = -4f,
                rangeMaxY = 12f,
                canonicalColorArgb = 0xFF0B57D0.toInt(),
                strokeWidthPt = 2.0f,
                showGrid = true
            ),
            PageObject.DerivationCardObject(
                id = "card-1",
                zOrder = 102L,
                bounds = DocRect(60f, 480f, 380f, 620f),
                title = "Másodfokú egyenlet levezetése",
                stepsMarkdownLatex = "1. Szorozzunk 4a-val...\n2. \$x_{1,2} = \\frac{-b \\pm \\sqrt{\\Delta}}{2a}\$",
                relatedLatexObjectId = null,
                isCollapsed = true
            ),
            PageObject.PdfPageRefObject(
                id = "pdf-ref-0",
                zOrder = 1L,
                bounds = DocRect(0f, 0f, 595f, 842f),
                pdfAssetRelativePath = "assets/analizis_1.pdf",
                pageIndexZeroBased = 3,
                invertInOledMode = true
            )
        )

        for (obj in objects) {
            val encoded = StrokeBinarySerializer.encodePageObject(obj)
            val decoded = StrokeBinarySerializer.decodePageObject(encoded)
            assertEquals(obj, decoded)
        }
    }

    @Test
    fun journalReplaysAddRemoveTransformRecolorAndObjectsAfterReopen() = runBlocking {
        val pageDir = File(tempRoot, "page-replay")
        val s1 = sampleStroke(id = UUID.randomUUID().toString(), withShapeMeta = true)
        val s2 = sampleStroke(
            id = UUID.randomUUID().toString(),
            toolType = BrushToolType.HIGHLIGHTER,
            layer = StrokeLayerType.HIGHLIGHTER,
            colorArgb = 0xFFFFEB3B.toInt()
        )
        val latex = PageObject.LatexObject(
            id = "latex-1",
            zOrder = 10L,
            bounds = DocRect(40f, 40f, 160f, 90f),
            latexSource = "E = mc^2"
        )

        val journal1 = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        journal1.openAndRecover()
        journal1.appendAddStrokes(listOf(s1, s2))
        journal1.appendTransformStrokes(
            listOf(s1.id),
            AffineTransform2D.translation(dxPt = 25f, dyPt = -10f)
        )
        journal1.appendRecolorStrokes(listOf(s2.id), 0xFF00C853.toInt())
        journal1.appendAddObject(latex)
        journal1.appendRemoveStrokes(listOf(s2.id))
        journal1.closeBlocking(compactBeforeClose = false)

        val journal2 = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        val recovery = journal2.openAndRecover()
        journal2.closeBlocking(compactBeforeClose = false)

        assertFalse(recovery.isReadOnly)
        assertTrue(recovery.hadUncompactedJournal)
        assertEquals(0, recovery.discardedCorruptRecords)
        assertEquals(1, recovery.strokes.size)
        val recoveredStroke = recovery.strokes.first()
        assertEquals(s1.id, recoveredStroke.id)
        assertEquals(35f, recoveredStroke.samples[0].xPt, 1e-3f)
        assertEquals(10f, recoveredStroke.samples[0].yPt, 1e-3f)
        assertNotNull(recoveredStroke.shapeMeta)
        assertEquals(35f, recoveredStroke.shapeMeta!!.controlPoints[0].xPt, 1e-3f)
        assertEquals(listOf(latex), recovery.objects)
    }

    @Test
    fun crashRecoveryDiscardsTruncatedAndCrcCorruptedJournalTailRecords() = runBlocking {
        val pageDir = File(tempRoot, "page-crash")
        val s1 = sampleStroke()
        val s2 = sampleStroke()

        val j1 = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        j1.openAndRecover()
        j1.appendAddStrokes(listOf(s1))
        j1.closeBlocking(compactBeforeClose = false)

        // Append a valid s2 frame first, then flip a CRC byte so the second record is corrupt.
        val validFrame = PageFileFormat.encodeFrameBytes(
            SnotRecordType.ADD_STROKE,
            StrokeBinarySerializer.encodeStrokeRecord(s2)
        )
        validFrame[validFrame.size - 1] = (validFrame.last().toInt() xor 0xFF).toByte()
        FileOutputStream(File(pageDir, StrokeJournal.JOURNAL_FILE_NAME), true).use { fos ->
            fos.write(validFrame)
            // Also append a few truncated bytes as if process died mid-frame
            fos.write(byteArrayOf(0x10, 0x00, 0x00))
        }

        val j2 = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        val recovered = j2.openAndRecover()
        j2.closeBlocking(compactBeforeClose = false)

        assertEquals(1, recovered.discardedCorruptRecords)
        assertEquals(1, recovered.strokes.size)
        assertEquals(s1.id, recovered.strokes[0].id)
    }

    @Test
    fun atomicCompactionCreatesSnapshotTruncatesJournalAndTriggersAtThreshold() = runBlocking {
        val pageDir = File(tempRoot, "page-compact")
        val j1 = StrokeJournal(
            pageDirectory = pageDir,
            compactionThresholdRecords = 3,
            fsyncDebounceMs = 0L
        )
        j1.openAndRecover()

        val strokes = listOf(sampleStroke(), sampleStroke(), sampleStroke())
        j1.appendAddStrokes(listOf(strokes[0], strokes[1]))
        // 3rd record crosses compactionThresholdRecords = 3 and triggers compactLocked automatically.
        j1.appendAddStrokes(listOf(strokes[2]))
        j1.closeBlocking(compactBeforeClose = false)

        val snapshot = File(pageDir, StrokeJournal.SNAPSHOT_FILE_NAME)
        val journal = File(pageDir, StrokeJournal.JOURNAL_FILE_NAME)
        assertTrue(snapshot.exists() && snapshot.length() > 50L)
        assertFalse(File(pageDir, StrokeJournal.SNAPSHOT_TEMP_FILE_NAME).exists())
        assertTrue(journal.exists() && journal.length() <= 32L)

        val j2 = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        val loaded = j2.openAndRecover()
        j2.closeBlocking(compactBeforeClose = false)

        assertFalse(loaded.hadUncompactedJournal)
        assertEquals(strokes.map { it.id }.toSet(), loaded.strokes.map { it.id }.toSet())
    }

    @Test
    fun higherFormatVersionOpensReadOnlyAndUnknownBrushFallsBackSafely() = runBlocking {
        val futureDir = File(tempRoot, "page-future")
        futureDir.mkdirs()
        val futureSnap = File(futureDir, StrokeJournal.SNAPSHOT_FILE_NAME)

        // Craft a record payload whose brush tool code is a synthetic unknown future value (999)
        val basePayload = StrokeBinarySerializer.encodeStrokeRecord(sampleStroke())
        // Replace rawToolCode bytes after [id(1+16) + layer(1) + zOrder(8)] -> offset 26
        val mutatedPayload = basePayload.copyOf()
        val toolCodeBaos = ByteArrayOutputStream(4)
        DataOutputStream(toolCodeBaos).use { it.writeInt(999) }
        val codeBytes = toolCodeBaos.toByteArray()
        System.arraycopy(codeBytes, 0, mutatedPayload, 26, 4)

        FileOutputStream(futureSnap).use { fos ->
            PageFileFormat.writeHeader(
                fos,
                SnotFileHeader(
                    formatVersion = 99,
                    minReaderVersion = 99,
                    inkVersion = "2.0.0",
                    flags = PageFileFormat.FLAG_SNAPSHOT
                )
            )
            PageFileFormat.writeFrame(fos, SnotRecordType.STROKE_SNAPSHOT, mutatedPayload)
        }
        val originalBytes = futureSnap.readBytes()

        val jFuture = StrokeJournal(pageDirectory = futureDir, fsyncDebounceMs = 0L)
        val recovery = jFuture.openAndRecover()
        assertTrue(recovery.isReadOnly)
        assertEquals(99, recovery.sourceFormatVersion)
        assertTrue(recovery.usedBrushFallback)
        assertEquals(BrushToolType.FOUNTAIN_PEN, recovery.strokes.first().brushConfig.toolType)

        try {
            jFuture.appendAddStrokes(listOf(sampleStroke()))
            fail("Expected ReadOnlyPageFormatException on write to higher version page")
        } catch (_: ReadOnlyPageFormatException) {
            // Expected read-only protection
        }

        try {
            jFuture.compactNow()
            fail("Expected ReadOnlyPageFormatException on compacting higher version page")
        } catch (_: ReadOnlyPageFormatException) {
            // Expected read-only protection
        }

        jFuture.closeBlocking(compactBeforeClose = false)
        assertTrue(
            "Snapshot file from newer formatVersion must never be overwritten",
            originalBytes.contentEquals(futureSnap.readBytes())
        )
    }

    @Test
    fun startupRecoveryDiscoversAndCompactsAllUncompactedPageJournals() = runBlocking {
        val dirA = File(tempRoot, "page-a")
        val dirB = File(tempRoot, "page-b")

        val jA = StrokeJournal(pageDirectory = dirA, fsyncDebounceMs = 0L)
        jA.openAndRecover()
        jA.appendAddStrokes(listOf(sampleStroke()))
        jA.closeBlocking(compactBeforeClose = false)

        val jB = StrokeJournal(pageDirectory = dirB, fsyncDebounceMs = 0L)
        jB.openAndRecover()
        jB.appendAddObject(
            PageObject.FunctionPlotObject(
                id = "f1",
                zOrder = 1L,
                bounds = DocRect(0f, 0f, 200f, 200f),
                expression = "x^2"
            )
        )
        jB.closeBlocking(compactBeforeClose = false)

        val summary = StrokeJournal.recoverAllPagesOnStartup(tempRoot, compactRecoveredPages = true)
        assertEquals(2, summary.scannedPageCount)
        assertEquals(setOf("page-a", "page-b"), summary.recoveredPageIds.toSet())
        assertEquals(0, summary.totalDiscardedCorruptRecords)
        assertTrue(File(dirA, StrokeJournal.SNAPSHOT_FILE_NAME).exists())
        assertTrue(File(dirB, StrokeJournal.SNAPSHOT_FILE_NAME).exists())
    }
}

