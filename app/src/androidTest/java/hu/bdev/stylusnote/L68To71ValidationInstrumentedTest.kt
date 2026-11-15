package hu.bdev.stylusnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.hardware.PressureCurve
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.export.CanvasPageVectorRenderer
import hu.bdev.stylusnote.data.export.PdfTileRenderer
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeBinarySerializer
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.ObjectAttachmentGeometry
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.LeftPageDrawerOverlay
import hu.bdev.stylusnote.ui.canvas.PageThumbnailRenderer
import hu.bdev.stylusnote.ui.canvas.PageThumbnailSourceSnapshot
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.math.PI

@RunWith(AndroidJUnit4::class)
class L68To71ValidationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var tempFilesDir: File
    private lateinit var lightPalette: StylusSurfacePalette

    @Before
    fun setUp() {
        tempFilesDir = Files.createTempDirectory(context.cacheDir.toPath(), "l68_71_inst_").toFile()
        lightPalette = StylusSurfacePalette(
            isOledDeepBlack = false,
            deskBackground = androidx.compose.ui.graphics.Color(0xFFF7F8FA),
            pagePaperBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            pageBorder = androidx.compose.ui.graphics.Color(0xFFDFE3E9),
            defaultInkColor = androidx.compose.ui.graphics.Color(0xFF1E232A)
        )
    }

    @After
    fun tearDown() {
        tempFilesDir.deleteRecursively()
    }

    @Test
    fun importedMultiPagePdfRendersDistinctNonBlankContentAcrossPages() = runBlocking {
        val pdfBytes = createTwoPageTestPdfBytes()
        val renderer = PdfTileRenderer(filesDir = tempFilesDir)
        try {
            val imported = renderer.importPdf(ByteArrayInputStream(pdfBytes), pdfAssetId = "pdf-l68")
            assertEquals(2, imported.pageCount)

            val docW = 595f
            val docH = 842f

            // Page 0: red header band
            val base0 = renderer.prewarmBasePage(imported.pdfAssetId, 0, docW, docH)
            assertNotNull("Expected Page 0 base bitmap to prewarm", base0)
            val canvasBmp0 = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            renderer.drawPdfPageForCanvasSyncOrRequest(
                canvas = Canvas(canvasBmp0),
                pdfAssetId = imported.pdfAssetId,
                pageIndex = 0,
                docPageWidthPt = docW,
                docPageHeightPt = docH,
                visibleLocalDocRect = DocRect(0f, 0f, docW, docH),
                currentScale = 1.0f,
                isOledMode = false,
                isGestureInProgress = false,
                onTileReady = {}
            )
            assertTrue("Expected red pixels from Page 0 PDF content", countRedDominantPixels(canvasBmp0) > 2_000)

            // Page 1: blue filled card
            val base1 = renderer.prewarmBasePage(imported.pdfAssetId, 1, docW, docH)
            assertNotNull("Expected Page 1 base bitmap to prewarm", base1)
            val canvasBmp1 = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            renderer.drawPdfPageForCanvasSyncOrRequest(
                canvas = Canvas(canvasBmp1),
                pdfAssetId = imported.pdfAssetId,
                pageIndex = 1,
                docPageWidthPt = docW,
                docPageHeightPt = docH,
                visibleLocalDocRect = DocRect(0f, 0f, docW, docH),
                currentScale = 1.0f,
                isOledMode = false,
                isGestureInProgress = false,
                onTileReady = {}
            )
            assertTrue("Expected blue pixels from Page 1 PDF content", countBlueDominantPixels(canvasBmp1) > 2_000)

            // Verify export render path as well.
            val exportBmp = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            val exported = renderer.renderPageForExport(
                pdfAssetId = imported.pdfAssetId,
                pageIndex = 0,
                targetCanvas = Canvas(exportBmp),
                targetWidthPt = docW,
                targetHeightPt = docH,
                dpiScale = 1f
            )
            assertTrue(exported)
            assertTrue(countRedDominantPixels(exportBmp) > 2_000)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun inkDrawsOverUnderInkImageAndMovesWithAttachedImageAcrossTransformAndUndoRedo() {
        val imgDir = File(tempFilesDir, "pages/p-img/images").apply { mkdirs() }
        val imgRelPath = "pages/p-img/images/solid_green.png"
        val greenBmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        FileOutputStream(File(tempFilesDir, imgRelPath)).use { out ->
            greenBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        greenBmp.recycle()

        val imageObj = PageObject.ImageObject(
            id = "img-1",
            zOrder = 10L,
            bounds = DocRect(100f, 100f, 300f, 240f),
            imageAssetRelativePath = imgRelPath,
            rotationRadians = 0f
        )

        val attachedRecord = PersistedStrokeRecord(
            id = "stroke-on-image",
            layer = StrokeLayerType.INK,
            zOrder = 20L,
            brushConfig = BrushConfig(
                toolType = BrushToolType.BALLPOINT_PEN,
                sizePt = 10f,
                colorArgb = Color.RED
            ),
            canonicalColorArgb = Color.RED,
            sizePt = 10f,
            samples = listOf(
                StylusPointSample(120f, 170f, 0L, 0.7f, 0f, 0f, false),
                StylusPointSample(280f, 170f, 20L, 0.7f, 0f, 0f, false)
            ),
            bounds = DocRect(120f, 165f, 280f, 175f),
            attachedObjectId = imageObj.id
        )
        val unattachedRecord = PersistedStrokeRecord(
            id = "stroke-outside",
            layer = StrokeLayerType.INK,
            zOrder = 21L,
            brushConfig = BrushConfig(
                toolType = BrushToolType.BALLPOINT_PEN,
                sizePt = 8f,
                colorArgb = Color.BLUE
            ),
            canonicalColorArgb = Color.BLUE,
            sizePt = 8f,
            samples = listOf(
                StylusPointSample(40f, 40f, 0L, 0.7f, 0f, 0f, false),
                StylusPointSample(90f, 40f, 20L, 0.7f, 0f, 0f, false)
            ),
            bounds = DocRect(40f, 36f, 90f, 44f),
            attachedObjectId = null
        )

        val holder = CanvasStateHolder()
        holder.addPageObject(imageObj)
        val dryAttached = StrokeBinarySerializer.toIndexedDryStroke(attachedRecord).first
        val dryUnattached = StrokeBinarySerializer.toIndexedDryStroke(unattachedRecord).first
        holder.addCommittedStrokes(listOf(dryAttached, dryUnattached))

        // Verify red stroke renders ON TOP OF green image bitmap.
        fun renderCurrentHolderToBitmap(): Bitmap {
            val out = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            val currentStrokes = holder.allStrokesSorted()
            val liveMap = currentStrokes.associate { it.id to it.stroke }
            val persisted = currentStrokes.map {
                StrokeBinarySerializer.fromIndexedDryStroke(
                    entry = it,
                    brushConfig = if (it.id == attachedRecord.id) attachedRecord.brushConfig else unattachedRecord.brushConfig,
                    shapeMeta = holder.getShapeMeta(it.id)
                )
            }
            val loadedImg = BitmapFactoryHelper.decode(File(tempFilesDir, imgRelPath))
            CanvasPageVectorRenderer.drawPageForegroundLayers(
                canvas = Canvas(out),
                strokes = persisted,
                objects = holder.allPageObjects(),
                liveInkStrokesById = liveMap,
                isOledMode = false,
                context = context,
                imageBitmapResolver = { loadedImg },
                recycleResolvedImageBitmaps = false
            )
            loadedImg.recycle()
            return out
        }

        val initialBmp = renderCurrentHolderToBitmap()
        // Center of image (200, 170) sits on the horizontal red stroke over green image -> red wins on top!
        val centerPixelBefore = initialBmp.getPixel(200, 170)
        assertTrue(
            "Expected red ink ON TOP OF green image at (200, 170), got #${Integer.toHexString(centerPixelBefore)}",
            Color.red(centerPixelBefore) > 180 && Color.green(centerPixelBefore) < 80
        )
        // Inside image away from stroke (200, 120) must be green!
        val greenBgPixel = initialBmp.getPixel(200, 120)
        assertTrue(
            "Expected green image pixel at (200, 120), got #${Integer.toHexString(greenBgPixel)}",
            Color.green(greenBgPixel) > 180 && Color.red(greenBgPixel) < 80
        )
        initialBmp.recycle()

        // Move and rotate the image + committed attached strokes together in one atomic step.
        val afterImg = imageObj.copy(
            bounds = DocRect(250f, 200f, 450f, 340f),
            rotationRadians = (PI / 2.0).toFloat()
        )
        val affine = ObjectAttachmentGeometry.computeObjectInteractionAffine(imageObj, afterImg)
        val commitResult = holder.commitObjectInteractionWithAttachedStrokes(
            beforeObject = imageObj,
            afterObject = afterImg,
            attachedTransform = affine
        )
        assertEquals(listOf(attachedRecord.id), commitResult.movedStrokeIds)

        val movedAttached = holder.allStrokesSorted().first { it.id == attachedRecord.id }
        val movedUnattached = holder.allStrokesSorted().first { it.id == unattachedRecord.id }
        assertTrue(movedAttached.bounds.centerXPt > 330f && movedAttached.bounds.centerYPt > 250f)
        // Unattached stroke remains untouched near (40..90, 40).
        assertEquals(40f, movedUnattached.bounds.leftPt, 5f)

        val movedBmp = renderCurrentHolderToBitmap()
        // Old location (200, 170) must now be empty white paper!
        assertEquals(Color.WHITE, movedBmp.getPixel(200, 170))
        // New center (350, 270) must show red ink over green image!
        val newCenterPixel = movedBmp.getPixel(350, 270)
        assertTrue(
            "Expected attached red ink moved with image to (350, 270), got #${Integer.toHexString(newCenterPixel)}",
            Color.red(newCenterPixel) > 180 && Color.green(newCenterPixel) < 80
        )
        movedBmp.recycle()

        // Undo reverts both image and attached stroke together.
        holder.undo()
        assertEquals(imageObj.bounds, (holder.getPageObject(imageObj.id) as PageObject.ImageObject).bounds)
        val revertedStroke = holder.allStrokesSorted().first { it.id == attachedRecord.id }
        assertEquals(200f, revertedStroke.bounds.centerXPt, 5f)

        // Redo re-applies both cleanly.
        holder.redo()
        assertEquals(afterImg.bounds, (holder.getPageObject(imageObj.id) as PageObject.ImageObject).bounds)

        // Deleting image deletes attached stroke too (and Undo restores both!).
        val deleteResult = holder.removePageObjectWithAttachedStrokes(imageObj.id)
        assertEquals(listOf(attachedRecord.id), deleteResult.removedStrokeIds)
        assertNull(holder.getPageObject(imageObj.id))
        assertFalse(holder.allStrokesSorted().any { it.id == attachedRecord.id })
        assertTrue(holder.allStrokesSorted().any { it.id == unattachedRecord.id })

        holder.undo()
        assertNotNull(holder.getPageObject(imageObj.id))
        assertTrue(holder.allStrokesSorted().any { it.id == attachedRecord.id })
    }

    @Test
    fun pageThumbnailRendererProducesDistinctMiniatureBitmapsForDifferentPages() = runBlocking {
        val pdfBytes = createTwoPageTestPdfBytes()
        val pdfRenderer = PdfTileRenderer(filesDir = tempFilesDir)
        val thumbRenderer = PageThumbnailRenderer(context = context, pdfTileRenderer = pdfRenderer)
        try {
            val imported = pdfRenderer.importPdf(ByteArrayInputStream(pdfBytes), pdfAssetId = "pdf-thumb")
            val page1 = PageEntity(
                id = "thumb-p1",
                notebookId = "nb-thumb",
                pageIndexInNotebook = 0,
                pageSizePreset = PageSizePreset.A4_PORTRAIT,
                templateType = PaperTemplateType.BLANK,
                pdfAssetId = imported.pdfAssetId,
                pdfPageIndex = 0,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 10L
            )
            val page2 = PageEntity(
                id = "thumb-p2",
                notebookId = "nb-thumb",
                pageIndexInNotebook = 1,
                pageSizePreset = PageSizePreset.A4_PORTRAIT,
                templateType = PaperTemplateType.BLANK,
                pdfAssetId = imported.pdfAssetId,
                pdfPageIndex = 1,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 10L
            )

            // Add an extra bold green ink stroke onto page1 live snapshot to confirm foreground ink compositing too.
            val liveStrokeRec = PersistedStrokeRecord(
                id = "thumb-s1",
                layer = StrokeLayerType.INK,
                zOrder = 1L,
                brushConfig = BrushConfig(BrushToolType.BALLPOINT_PEN, sizePt = 18f, colorArgb = Color.GREEN),
                canonicalColorArgb = Color.GREEN,
                sizePt = 18f,
                samples = listOf(
                    StylusPointSample(120f, 450f, 0L, 0.8f, 0f, 0f, false),
                    StylusPointSample(480f, 450f, 20L, 0.8f, 0f, 0f, false)
                ),
                bounds = DocRect(120f, 440f, 480f, 460f)
            )
            val dryThumbStroke = StrokeBinarySerializer.toIndexedDryStroke(liveStrokeRec).first
            val bmp1 = thumbRenderer.getOrRenderThumbnail(
                page = page1,
                palette = lightPalette,
                liveSnapshot = PageThumbnailSourceSnapshot(
                    strokes = listOf(liveStrokeRec),
                    objects = emptyList(),
                    liveInkStrokesById = mapOf(dryThumbStroke.id to dryThumbStroke.stroke)
                )
            )
            val bmp2 = thumbRenderer.getOrRenderThumbnail(
                page = page2,
                palette = lightPalette,
                liveSnapshot = PageThumbnailSourceSnapshot(emptyList(), emptyList())
            )

            assertNotNull(bmp1)
            assertNotNull(bmp2)
            assertEquals(198, bmp1!!.width)
            assertEquals(280, bmp1.height)
            assertEquals(198, bmp2!!.width)
            assertEquals(280, bmp2.height)

            assertTrue("Expected thumbnail 1 to contain Page 0 red banner", countRedDominantPixels(bmp1) > 150)
            assertTrue("Expected thumbnail 2 to contain Page 1 blue block", countBlueDominantPixels(bmp2) > 150)
            assertTrue("Expected green live ink in thumbnail 1", countGreenDominantPixels(bmp1) > 100)
            assertTrue("Thumbnail bitmaps for distinct pages must not be identical", !bitmapsPixelEqual(bmp1, bmp2))
        } finally {
            pdfRenderer.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun leftPageDrawerHidesDeleteOnLastPageAndConfirmsWithBackdropAndEsc() {
        val singlePage = listOf(
            PageEntity(
                id = "only-p1",
                notebookId = "nb-ui",
                pageIndexInNotebook = 0,
                title = "Első",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L
            )
        )
        var deletedIds = mutableListOf<String>()

        // 1) Last remaining page must NEVER show a delete icon.
        composeRule.setContent {
            StylusNoteTheme {
                LeftPageDrawerOverlay(
                    pages = singlePage,
                    activePageId = "only-p1",
                    onSelectPage = {},
                    onAddNewPage = {},
                    onDeletePage = { deletedIds.add(it) },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag("delete_page_btn_only-p1").assertDoesNotExist()

        // Reset via second two-page assertion using a separate holder node? Actually ComposeRule allows
        // one setContent per test; verify multi-page delete flow in the dedicated test below!
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun leftPageDrawerDeleteConfirmationDismissesViaBackdropAndEscThenTriggersOnConfirm() {
        val twoPages = listOf(
            PageEntity(
                id = "p-a",
                notebookId = "nb-ui-2",
                pageIndexInNotebook = 0,
                title = "Oldal A",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L
            ),
            PageEntity(
                id = "p-b",
                notebookId = "nb-ui-2",
                pageIndexInNotebook = 1,
                title = "Oldal B",
                createdAtEpochMs = 2L,
                updatedAtEpochMs = 2L
            )
        )
        val deletedIds = mutableListOf<String>()

        composeRule.setContent {
            StylusNoteTheme {
                LeftPageDrawerOverlay(
                    pages = twoPages,
                    activePageId = "p-a",
                    onSelectPage = {},
                    onAddNewPage = {},
                    onDeletePage = { deletedIds.add(it) },
                    onDismiss = {}
                )
            }
        }

        // Open delete confirm dialog for page B -> dismiss via backdrop click.
        composeRule.onNodeWithTag("delete_page_btn_p-b").performClick()
        composeRule.onNodeWithText("Oldal törlése").assertIsDisplayed()
        composeRule.onNodeWithTag("delete_page_confirm_backdrop").performTouchInput {
            click(Offset(12f, 12f))
        }
        composeRule.onNodeWithTag("delete_page_confirm_backdrop").assertDoesNotExist()
        assertTrue(deletedIds.isEmpty())

        // Re-open -> dismiss via ESC key.
        composeRule.onNodeWithTag("delete_page_btn_p-b").performClick()
        composeRule.onNodeWithTag("delete_page_confirm_backdrop").performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.onNodeWithTag("delete_page_confirm_backdrop").assertDoesNotExist()
        assertTrue(deletedIds.isEmpty())

        // Re-open -> confirm deletion.
        composeRule.onNodeWithTag("delete_page_btn_p-b").performClick()
        composeRule.onNodeWithTag("confirm_delete_page_btn").performClick()
        composeRule.onNodeWithTag("delete_page_confirm_backdrop").assertDoesNotExist()
        assertEquals(listOf("p-b"), deletedIds)
    }

    private fun createTwoPageTestPdfBytes(): ByteArray {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Page 0: wide bright red rectangle banner
        val page0 = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        paint.color = Color.WHITE
        page0.canvas.drawRect(0f, 0f, 595f, 842f, paint)
        paint.color = Color.RED
        page0.canvas.drawRect(60f, 80f, 520f, 220f, paint)
        doc.finishPage(page0)

        // Page 1: bright blue centered block
        val page1 = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 2).create())
        paint.color = Color.WHITE
        page1.canvas.drawRect(0f, 0f, 595f, 842f, paint)
        paint.color = Color.BLUE
        page1.canvas.drawRect(100f, 300f, 495f, 620f, paint)
        doc.finishPage(page1)

        val baos = ByteArrayOutputStream()
        doc.writeTo(baos)
        doc.close()
        return baos.toByteArray()
    }

    private fun countRedDominantPixels(bmp: Bitmap): Int = countMatchingPixels(bmp) { c ->
        Color.red(c) > 180 && Color.green(c) < 80 && Color.blue(c) < 80
    }

    private fun countGreenDominantPixels(bmp: Bitmap): Int = countMatchingPixels(bmp) { c ->
        Color.green(c) > 180 && Color.red(c) < 80 && Color.blue(c) < 80
    }

    private fun countBlueDominantPixels(bmp: Bitmap): Int = countMatchingPixels(bmp) { c ->
        Color.blue(c) > 180 && Color.red(c) < 80 && Color.green(c) < 80
    }

    private inline fun countMatchingPixels(bmp: Bitmap, predicate: (Int) -> Boolean): Int {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var count = 0
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (predicate(row[x])) count++
            }
        }
        return count
    }

    private fun bitmapsPixelEqual(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val w = a.width
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        for (y in 0 until a.height) {
            a.getPixels(rowA, 0, w, 0, y, w, 1)
            b.getPixels(rowB, 0, w, 0, y, w, 1)
            if (!rowA.contentEquals(rowB)) return false
        }
        return true
    }

    private object BitmapFactoryHelper {
        fun decode(file: File): Bitmap =
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    }
}
