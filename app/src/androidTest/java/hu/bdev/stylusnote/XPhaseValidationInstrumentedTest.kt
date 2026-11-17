package hu.bdev.stylusnote

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.ink.authoring.InProgressStrokesView
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.hardware.StylusToggleDebouncer
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.ContinuousPageLayout
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.core.math.SavedViewportAnchor
import hu.bdev.stylusnote.data.export.PdfTileRenderer
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.ui.canvas.MultiPageSessionManager
import hu.bdev.stylusnote.ui.canvas.StylusDrawingSurface
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.nio.file.Files
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class XPhaseValidationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory(context.cacheDir.toPath(), "xphase_inst_").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun migration3To4AddsViewportColumnsAndPreservesUpdatedAtOnViewportSave() = runBlocking {
        val dbName = "migration-v3-v4-xphase.db"
        context.deleteDatabase(dbName)
        migrationHelper.createDatabase(dbName, 3).apply {
            execSQL(
                """
                INSERT INTO notebooks (id, title, isFavorite, defaultCanvasMode, defaultPageSizePreset, defaultTemplateType, ocrLanguage, lastOpenedPageId, createdAtEpochMs, updatedAtEpochMs, cover_texture, cover_primaryColorArgb, cover_spineColorArgb, cover_labelLayout, cover_typography, cover_subtitle)
                VALUES ('nb-m3', 'Migrált v3 PDF', 0, 'FIXED_PAGE', 'A4_PORTRAIT', 'BLANK', 'HU', 'pg-m3-1', 1000, 5000, 'SOLID_MATTE', -13936540, -14991780, 'CENTERED', 'MODERN_SANS', '')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO pages (id, notebookId, pageIndexInNotebook, title, canvasMode, pageSizePreset, templateType, invertPdfInOledMode, snapshotRelativePath, journalRelativePath, needsOcrSync, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('pg-m3-1', 'nb-m3', 0, 'Oldal 1', 'FIXED_PAGE', 'A4_PORTRAIT', 'BLANK', 1, 'pages/pg-m3-1/page.snote', 'pages/pg-m3-1/page.journal', 0, 1000, 5000)
                """.trimIndent()
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            dbName,
            4,
            true,
            AppDatabase.MIGRATION_3_4
        )
        migrated.query(
            "SELECT lastOpenedPageId, lastViewportPageId, lastViewportCenterXPt, lastViewportCenterYPt, lastViewportZoomFactor FROM notebooks WHERE id = 'nb-m3'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("pg-m3-1", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        migrated.close()

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            roomDb.notebookDao().updateNotebookViewportState(
                notebookId = "nb-m3",
                lastOpenedPageId = "pg-m3-1",
                viewportPageId = "pg-m3-1",
                centerXPt = 310.5f,
                centerYPt = 490.25f,
                zoomFactor = 1.75f
            )
            val updated = roomDb.notebookDao().getNotebookById("nb-m3")
            assertNotNull(updated)
            assertEquals(5000L, updated!!.updatedAtEpochMs)
            assertEquals("pg-m3-1", updated.lastViewportPageId)
            assertEquals(310.5f, updated.lastViewportCenterXPt ?: 0f, 1e-4f)
            assertEquals(490.25f, updated.lastViewportCenterYPt ?: 0f, 1e-4f)
            assertEquals(1.75f, updated.lastViewportZoomFactor ?: 0f, 1e-4f)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun delayedMetadataLandscapePdfOpensFittedAndHorizontallyCentered() = runBlocking {
        val pdfBytes = createMixedOrientationPdfBytes()
        val pdfRenderer = PdfTileRenderer(filesDir = tempRoot)
        val imported = pdfRenderer.importPdf(
            inputStream = ByteArrayInputStream(pdfBytes),
            pdfAssetId = "pdf-xphase-center"
        )
        try {
            assertEquals(2, imported.pageCount)
            assertEquals(PageDimensionsPt.LANDSCAPE_16_10, imported.dimensionsForPage(0))
            assertEquals(PageDimensionsPt.A4_PORTRAIT, imported.dimensionsForPage(1))

            val pages = listOf(
                PageEntity(
                    id = "pdf-pg-0",
                    notebookId = "nb-pdf",
                    pageIndexInNotebook = 0,
                    canvasMode = CanvasMode.FIXED_PAGE,
                    pageSizePreset = PageSizePreset.LANDSCAPE_16_10,
                    templateType = PaperTemplateType.BLANK,
                    pdfAssetId = imported.pdfAssetId,
                    pdfPageIndex = 0,
                    createdAtEpochMs = 1L,
                    updatedAtEpochMs = 1L
                ),
                PageEntity(
                    id = "pdf-pg-1",
                    notebookId = "nb-pdf",
                    pageIndexInNotebook = 1,
                    canvasMode = CanvasMode.FIXED_PAGE,
                    pageSizePreset = PageSizePreset.A4_PORTRAIT,
                    templateType = PaperTemplateType.BLANK,
                    pdfAssetId = imported.pdfAssetId,
                    pdfPageIndex = 1,
                    createdAtEpochMs = 2L,
                    updatedAtEpochMs = 2L
                )
            )
            val realLayout = ContinuousPageLayout.build(pages)
            val transformMatrix = CanvasTransformMatrix()
            var isMetadataReady by mutableStateOf(false)
            var activeLayout by mutableStateOf<ContinuousPageLayout?>(null)
            var vpSize by mutableStateOf(IntSize.Zero)

            composeRule.setContent {
                StylusNoteTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        StylusDrawingSurface(
                            transformMatrix = transformMatrix,
                            paperConfig = PagePaperConfig(
                                canvasMode = CanvasMode.FIXED_PAGE,
                                pageDimensions = PageDimensionsPt.A4_PORTRAIT,
                                templateType = PaperTemplateType.BLANK
                            ),
                            multiPageLayout = activeLayout,
                            initialPageId = "pdf-pg-0",
                            pdfTileRenderer = pdfRenderer,
                            isViewportMetadataReady = isMetadataReady,
                            restoreSavedViewportOnInit = false,
                            onViewportSizeChanged = { vpSize = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                vpSize.width > 0 && vpSize.height > 0
            }
            composeRule.waitForIdle()
            // Camera must stay neutral while metadata hydration is still pending.
            assertEquals(1.0f, transformMatrix.scale, 1e-4f)
            assertEquals(0f, transformMatrix.panXPx, 1e-4f)

            composeRule.runOnIdle {
                activeLayout = realLayout
                isMetadataReady = true
            }

            val slot0 = realLayout.slotAt(0)!!
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                transformMatrix.panXPx != 0f || transformMatrix.panYPx != 0f
            }
            composeRule.waitForIdle()

            val vpW = vpSize.width.toFloat()
            val topLeftScreen = transformMatrix.docToScreen(slot0.leftPt, slot0.topPt)
            val bottomRightScreen = transformMatrix.docToScreen(slot0.rightPt, slot0.bottomPt)
            val centerScreen = transformMatrix.docToScreen(slot0.centerXPt, slot0.centerYPt)

            assertTrue("Left margin must be non-negative: ${topLeftScreen.xPx}", topLeftScreen.xPx >= 0f)
            assertTrue("Right edge must stay inside viewport: ${bottomRightScreen.xPx} <= $vpW", bottomRightScreen.xPx <= vpW)
            assertEquals(
                "Left and right margins must be symmetric",
                vpW - bottomRightScreen.xPx,
                topLeftScreen.xPx,
                2.5f
            )
            assertEquals(
                "Landscape PDF page horizontal center must match viewport center",
                vpW * 0.5f,
                centerScreen.xPx,
                2.5f
            )
            assertEquals(100, transformMatrix.relativeZoomPercent)
        } finally {
            pdfRenderer.close()
        }
    }

    @Test
    fun reopenRestoresExactPageIndexLocalSectionAndZoomFromRoom() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val nbId = "nb-restore-e2e"
        val createdAt = 1_760_000_000_000L
        val pages = (0..2).map { index ->
            PageEntity(
                id = "restore-pg-$index",
                notebookId = nbId,
                pageIndexInNotebook = index,
                canvasMode = CanvasMode.FIXED_PAGE,
                pageSizePreset = PageSizePreset.A4_PORTRAIT,
                templateType = PaperTemplateType.GRID_5MM,
                createdAtEpochMs = createdAt + index,
                updatedAtEpochMs = createdAt + index
            )
        }
        db.notebookDao().upsertNotebook(
            NotebookEntity(
                id = nbId,
                title = "Visszatöltési füzet",
                lastOpenedPageId = pages.first().id,
                defaultCanvasMode = CanvasMode.FIXED_PAGE,
                defaultPageSizePreset = PageSizePreset.A4_PORTRAIT,
                defaultTemplateType = PaperTemplateType.GRID_5MM,
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = createdAt
            )
        )
        pages.forEach { db.pageDao().upsertPage(it) }

        val layout = ContinuousPageLayout.build(pages)
        val slot2 = layout.slotAt(2)!!
        val session1 = MultiPageSessionManager(
            filesDir = tempRoot,
            activeBrushConfigProvider = { BrushConfig() },
            onLocalMutationCommitted = {},
            onPageReadyCallback = { _, _ -> }
        )
        val session2 = MultiPageSessionManager(
            filesDir = tempRoot,
            activeBrushConfigProvider = { BrushConfig() },
            onLocalMutationCommitted = {},
            onPageReadyCallback = { _, _ -> }
        )
        session1.updatePagesAndFocus(pages, activePageIndex = 0)
        session2.updatePagesAndFocus(pages, activePageIndex = 2)

        try {
            var sessionKey by mutableStateOf(1)
            var vpSize1 by mutableStateOf(IntSize.Zero)
            var vpSize2 by mutableStateOf(IntSize.Zero)
            val matrix1 = CanvasTransformMatrix()
            val matrix2 = CanvasTransformMatrix()
            var loadedAnchor by mutableStateOf<SavedViewportAnchor?>(null)
            var restoredInitialPageId by mutableStateOf<String?>(null)
            var observedSlot2PageId by mutableStateOf<String?>(null)

            composeRule.setContent {
                StylusNoteTheme {
                    key(sessionKey) {
                        if (sessionKey == 1) {
                            StylusDrawingSurface(
                                transformMatrix = matrix1,
                                multiPageSessionManager = session1,
                                multiPageLayout = layout,
                                initialPageId = pages.first().id,
                                isViewportMetadataReady = true,
                                restoreSavedViewportOnInit = false,
                                onViewportSizeChanged = { vpSize1 = it },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            StylusDrawingSurface(
                                transformMatrix = matrix2,
                                multiPageSessionManager = session2,
                                multiPageLayout = layout,
                                initialPageId = restoredInitialPageId,
                                isViewportMetadataReady = true,
                                savedViewportAnchor = loadedAnchor,
                                restoreSavedViewportOnInit = true,
                                onViewportSizeChanged = { vpSize2 = it },
                                onActiveSlotChanged = { observedSlot2PageId = it.pageId },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                vpSize1.width > 0 && vpSize1.height > 0 && (matrix1.panXPx != 0f || matrix1.panYPx != 0f)
            }

            // Simulate user scrolling to page 3 (index 2) section (365pt, 540pt) at ~220% zoom.
            val targetLocalX = 365f
            val targetLocalY = 540f
            val targetRelZoom = 2.2f
            val vpW1 = vpSize1.width.toFloat()
            val vpH1 = vpSize1.height.toFloat()
            composeRule.runOnIdle {
                assertTrue(
                    matrix1.applySavedPageLocalViewport(
                        pageOriginXPt = slot2.leftPt,
                        pageOriginYPt = slot2.topPt,
                        pageWidthPt = slot2.widthPt,
                        pageHeightPt = slot2.heightPt,
                        localCenterXPt = targetLocalX,
                        localCenterYPt = targetLocalY,
                        relativeZoomFactor = targetRelZoom,
                        viewportWidthPx = vpW1,
                        viewportHeightPx = vpH1
                    )
                )
            }

            val captured = matrix1.capturePageLocalViewportAnchor(
                pageId = slot2.pageId,
                pageOriginXPt = slot2.leftPt,
                pageOriginYPt = slot2.topPt,
                viewportWidthPx = vpW1,
                viewportHeightPx = vpH1
            )
            assertNotNull(captured)

            db.notebookDao().updateNotebookViewportState(
                notebookId = nbId,
                lastOpenedPageId = captured!!.pageId,
                viewportPageId = captured.pageId,
                centerXPt = captured.localCenterXPt,
                centerYPt = captured.localCenterYPt,
                zoomFactor = captured.relativeZoomFactor
            )
            val reloaded = db.notebookDao().getNotebookById(nbId)!!
            assertEquals(createdAt, reloaded.updatedAtEpochMs)
            val anchorFromDb = SavedViewportAnchor(
                pageId = reloaded.lastViewportPageId!!,
                localCenterXPt = reloaded.lastViewportCenterXPt!!,
                localCenterYPt = reloaded.lastViewportCenterYPt!!,
                relativeZoomFactor = reloaded.lastViewportZoomFactor!!
            )

            // Reopen notebook in a completely new StylusDrawingSurface + CanvasTransformMatrix instance.
            composeRule.runOnIdle {
                restoredInitialPageId = reloaded.lastOpenedPageId
                loadedAnchor = anchorFromDb
                sessionKey = 2
            }

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                vpSize2.width > 0 && observedSlot2PageId == slot2.pageId && matrix2.relativeZoomPercent >= 200
            }
            composeRule.waitForIdle()

            val vpW2 = vpSize2.width.toFloat()
            val vpH2 = vpSize2.height.toFloat()
            val restoredCenterDoc = matrix2.screenToDoc(vpW2 * 0.5f, vpH2 * 0.5f)
            assertEquals(slot2.leftPt + targetLocalX, restoredCenterDoc.xPt, 1.5f)
            assertEquals(slot2.topPt + targetLocalY, restoredCenterDoc.yPt, 1.5f)
            assertTrue(
                "Expected relative zoom ~220%, was ${matrix2.relativeZoomPercent}",
                abs(matrix2.relativeZoomPercent - 220) <= 6
            )
        } finally {
            session1.disposeSuspending()
            session2.disposeSuspending()
            db.close()
        }
    }

    @Test
    fun stylusHardwareKeyAndHoverButtonToggleToolWithDebouncing() {
        val debouncer = StylusToggleDebouncer(minIntervalMs = 180L)
        var fakeElapsedMs = 10_000L
        var toggleCount = 0
        var rootHostView: View? = null
        val transformMatrix = CanvasTransformMatrix()

        composeRule.setContent {
            rootHostView = LocalView.current
            StylusNoteTheme {
                StylusDrawingSurface(
                    transformMatrix = transformMatrix,
                    onTogglePenEraser = {
                        if (debouncer.tryAcquireToggle(fakeElapsedMs)) {
                            toggleCount++
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            findInProgressViews(rootHostView).size == 1
        }
        val wetView = findInProgressViews(rootHostView).single()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("wetView must accept focus in touch mode", wetView.requestFocus())
            // 1. Standard stylus secondary button down toggles once.
            assertTrue(
                wetView.dispatchKeyEvent(
                    KeyEvent(fakeElapsedMs, fakeElapsedMs, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, 0)
                )
            )
            assertEquals(1, toggleCount)

            // 2. Key-repeat is consumed without double-toggling.
            assertTrue(
                wetView.dispatchKeyEvent(
                    KeyEvent(fakeElapsedMs, fakeElapsedMs + 20L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY, 1)
                )
            )
            assertEquals(1, toggleCount)

            // 3. Burst duplicate within 180ms is suppressed by StylusToggleDebouncer.
            fakeElapsedMs += 40L
            assertTrue(
                wetView.dispatchKeyEvent(
                    KeyEvent(fakeElapsedMs, fakeElapsedMs, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, 0)
                )
            )
            assertEquals(1, toggleCount)

            // 4. Tail eraser key after debounce window toggles cleanly.
            fakeElapsedMs += 200L
            assertTrue(
                wetView.dispatchKeyEvent(
                    KeyEvent(fakeElapsedMs, fakeElapsedMs, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL, 0)
                )
            )
            assertEquals(2, toggleCount)

            // 5. Stylus hover motion carrying BUTTON_STYLUS_SECONDARY edge toggles after cooldown and skips steady hold.
            fakeElapsedMs += 200L
            val hoverPress = MotionEvent.obtain(
                fakeElapsedMs,
                fakeElapsedMs,
                MotionEvent.ACTION_HOVER_MOVE,
                1,
                arrayOf(MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    x = 240f
                    y = 260f
                }),
                0,
                MotionEvent.BUTTON_STYLUS_SECONDARY,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_STYLUS,
                0
            )
            wetView.dispatchGenericMotionEvent(hoverPress)
            hoverPress.recycle()
            assertEquals(3, toggleCount)

            fakeElapsedMs += 200L
            val hoverHeld = MotionEvent.obtain(
                fakeElapsedMs,
                fakeElapsedMs,
                MotionEvent.ACTION_HOVER_MOVE,
                1,
                arrayOf(MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }),
                arrayOf(MotionEvent.PointerCoords().apply {
                    x = 260f
                    y = 270f
                }),
                0,
                MotionEvent.BUTTON_STYLUS_SECONDARY,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_STYLUS,
                0
            )
            wetView.dispatchGenericMotionEvent(hoverHeld)
            hoverHeld.recycle()
            assertEquals(3, toggleCount)
        }
    }

    private fun createMixedOrientationPdfBytes(): ByteArray {
        val doc = PdfDocument()
        try {
            val page0 = doc.startPage(PdfDocument.PageInfo.Builder(1280, 800, 1).create())
            page0.canvas.drawColor(Color.WHITE)
            page0.canvas.drawRect(80f, 80f, 1200f, 720f, Paint().apply { color = Color.BLUE })
            doc.finishPage(page0)

            val page1 = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 2).create())
            page1.canvas.drawColor(Color.WHITE)
            page1.canvas.drawCircle(297f, 421f, 120f, Paint().apply { color = Color.RED })
            doc.finishPage(page1)

            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun findInProgressViews(root: View?): List<InProgressStrokesView> {
        val result = ArrayList<InProgressStrokesView>()
        fun walk(v: View?) {
            if (v is InProgressStrokesView) {
                result.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))
                }
            }
        }
        walk(root)
        return result
    }
}
