package hu.bdev.stylusnote

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.ContinuousPageLayout
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.MultiPageSessionManager
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import hu.bdev.stylusnote.ui.shelf.NotebookWorkspaceScreen
import hu.bdev.stylusnote.ui.shelf.OpenedPageTarget
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class MultiPageContinuousScrollInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory(context.cacheDir.toPath(), "mp_cont_scroll_").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun buildInkEntryForWorkspaceSlot(
        strokeId: String,
        wsStartX: Float,
        wsStartY: Float,
        slotLeftPt: Float,
        slotTopPt: Float,
        zOrder: Long,
        brushConfig: BrushConfig
    ): IndexedDryStroke {
        val localSamples = listOf(
            StylusPointSample(wsStartX - slotLeftPt, wsStartY - slotTopPt, 0L, 0.6f, 0f, 0f, false),
            StylusPointSample(wsStartX - slotLeftPt + 40f, wsStartY - slotTopPt + 12f, 12L, 0.65f, 0f, 0f, false),
            StylusPointSample(wsStartX - slotLeftPt + 95f, wsStartY - slotTopPt + 18f, 24L, 0.7f, 0f, 0f, false)
        )
        val inkStroke = Stroke(
            brush = brushConfig.toInkBrush(),
            inputs = localSamples.toStrokeInputBatch(InputToolType.STYLUS)
        )
        return IndexedDryStroke(
            id = strokeId,
            stroke = inkStroke,
            bounds = computeStrokeDocBounds(inkStroke),
            zIndex = zOrder,
            isHighlighter = false
        )
    }

    private fun findAllInProgressViews(root: View?): List<InProgressStrokesView> {
        val result = ArrayList<InProgressStrokesView>(2)
        fun visit(v: View?) {
            if (v is InProgressStrokesView) {
                result.add(v)
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
        }
        visit(root)
        return result
    }

    @Test
    fun ninePageContinuousSessionNormalizesWorkspaceCoordinatesAndPersistsCrossPageUndoRedo() = runBlocking {
        val brush = BrushConfig(toolType = BrushToolType.FOUNTAIN_PEN, sizePt = 2.5f)
        val pages = (0 until 9).map { idx ->
            PageEntity(
                id = "cont-pg-$idx",
                notebookId = "cont-nb",
                pageIndexInNotebook = idx,
                canvasMode = CanvasMode.FIXED_PAGE,
                pageSizePreset = PageSizePreset.A4_PORTRAIT,
                templateType = PaperTemplateType.RULED_8MM,
                createdAtEpochMs = 1_000L + idx,
                updatedAtEpochMs = 1_000L + idx
            )
        }
        val layout = ContinuousPageLayout.build(pages)
        assertEquals(9, layout.pageCount)

        val session = MultiPageSessionManager(
            filesDir = tempRoot,
            activeBrushConfigProvider = { brush },
            onLocalMutationCommitted = {},
            onPageReadyCallback = { _, _ -> },
            ioDispatcher = Dispatchers.IO
        )

        try {
            session.updatePagesAndFocus(pages, activePageIndex = 0)
            var waitAttempts = 0
            while (session.getReadyRuntime("cont-pg-0") == null && waitAttempts < 40) {
                delay(25)
                waitAttempts++
            }
            val slot0 = layout.slotAt(0)!!
            val runtime0 = session.getReadyRuntime("cont-pg-0")!!
            val entry0 = buildInkEntryForWorkspaceSlot(
                strokeId = "stroke-p0",
                wsStartX = slot0.leftPt + 60f,
                wsStartY = slot0.topPt + 110f,
                slotLeftPt = slot0.leftPt,
                slotTopPt = slot0.topPt,
                zOrder = 1L,
                brushConfig = brush
            )
            runtime0.canvasStateHolder.addCommittedStrokes(listOf(entry0))
            runtime0.strokeJournal.onStrokesCommitted(listOf(entry0))

            // Scroll to page 3 (middle) and draw in workspace space -> normalized onto cont-pg-3 local space
            session.updateActiveFocus(3)
            waitAttempts = 0
            while (session.getReadyRuntime("cont-pg-3") == null && waitAttempts < 40) {
                delay(25)
                waitAttempts++
            }
            val slot3 = layout.slotAt(3)!!
            val runtime3 = session.getReadyRuntime("cont-pg-3")!!
            val entry3 = buildInkEntryForWorkspaceSlot(
                strokeId = "stroke-p3",
                wsStartX = slot3.leftPt + 75f,
                wsStartY = slot3.topPt + 240f,
                slotLeftPt = slot3.leftPt,
                slotTopPt = slot3.topPt,
                zOrder = 2L,
                brushConfig = brush
            )
            runtime3.canvasStateHolder.addCommittedStrokes(listOf(entry3))
            runtime3.strokeJournal.onStrokesCommitted(listOf(entry3))

            // Scroll deep to page 8 (evicts cont-pg-0 and cont-pg-3 asynchronously) and draw on cont-pg-8
            session.updateActiveFocus(8)
            waitAttempts = 0
            while (
                (session.getReadyRuntime("cont-pg-8") == null || session.getRuntime("cont-pg-3") != null) &&
                waitAttempts < 50
            ) {
                delay(25)
                waitAttempts++
            }
            val slot8 = layout.slotAt(8)!!
            val runtime8 = session.getReadyRuntime("cont-pg-8")!!
            val entry8 = buildInkEntryForWorkspaceSlot(
                strokeId = "stroke-p8",
                wsStartX = slot8.leftPt + 90f,
                wsStartY = slot8.topPt + 150f,
                slotLeftPt = slot8.leftPt,
                slotTopPt = slot8.topPt,
                zOrder = 3L,
                brushConfig = brush
            )
            runtime8.canvasStateHolder.addCommittedStrokes(listOf(entry8))
            runtime8.strokeJournal.onStrokesCommitted(listOf(entry8))

            // Undo #1 reverts page 8; Undo #2 must rehydrate evicted page 3 and revert it;
            // Redo re-applies page 3 cleanly.
            val undo8 = session.applyUndo()
            assertEquals("cont-pg-8", undo8?.pageId)
            val undo3 = session.applyUndo()
            assertEquals("cont-pg-3", undo3?.pageId)
            assertTrue(undo3?.didRehydratePage == true)
            assertEquals(0, session.getReadyRuntime("cont-pg-3")?.canvasStateHolder?.allStrokesSorted()?.size)

            val redo3 = session.applyRedo()
            assertEquals("cont-pg-3", redo3?.pageId)
            assertEquals(1, session.getReadyRuntime("cont-pg-3")?.canvasStateHolder?.allStrokesSorted()?.size)

            session.flushAllJournals()
            session.disposeSuspending()

            // Verify disk journals contain purely page-local coordinates and final Undo/Redo states.
            val check0 = StrokeJournal(File(tempRoot, "pages/cont-pg-0"))
            val rec0 = check0.openAndRecover()
            check0.closeBlocking(compactBeforeClose = false)
            assertEquals(1, rec0.strokes.size)

            val check3 = StrokeJournal(File(tempRoot, "pages/cont-pg-3"))
            val rec3 = check3.openAndRecover()
            check3.closeBlocking(compactBeforeClose = false)
            assertEquals(1, rec3.strokes.size)
            assertTrue(rec3.strokes.single().bounds.topPt in 0f..842f)

            val check8 = StrokeJournal(File(tempRoot, "pages/cont-pg-8"))
            val rec8 = check8.openAndRecover()
            check8.closeBlocking(compactBeforeClose = false)
            assertTrue(rec8.strokes.isEmpty())
        } finally {
            session.disposeSuspending()
        }
    }

    @Test
    fun continuousNinePageWorkspaceSwitchesPagesWithoutRecreatingAndroidView() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val nbId = "inst-nb-nine-persistent"
        val pageIds = (0 until 9).map { "inst-cont-pg-$it" }
        try {
            val now = 1_750_000_000_000L
            runBlocking {
                db.notebookDao().upsertNotebook(
                    NotebookEntity(
                        id = nbId,
                        title = "Kilenc oldalas tesztfüzet",
                        lastOpenedPageId = pageIds.first(),
                        defaultCanvasMode = CanvasMode.FIXED_PAGE,
                        defaultPageSizePreset = PageSizePreset.A4_PORTRAIT,
                        defaultTemplateType = PaperTemplateType.RULED_8MM,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
                pageIds.forEachIndexed { i, pageId ->
                    db.pageDao().upsertPage(
                        PageEntity(
                            id = pageId,
                            notebookId = nbId,
                            pageIndexInNotebook = i,
                            canvasMode = CanvasMode.FIXED_PAGE,
                            pageSizePreset = PageSizePreset.A4_PORTRAIT,
                            templateType = PaperTemplateType.RULED_8MM,
                            createdAtEpochMs = now + i,
                            updatedAtEpochMs = now + i
                        )
                    )
                }
            }

            var rootHostView: View? = null
            composeRule.setContent {
                rootHostView = LocalView.current
                var activeTarget by remember {
                    mutableStateOf(OpenedPageTarget(notebookId = nbId, pageId = pageIds.first()))
                }
                StylusNoteTheme {
                    NotebookWorkspaceScreen(
                        database = db,
                        target = activeTarget,
                        penButtonToggleTick = 0L,
                        isDarkTheme = false,
                        onToggleTheme = {},
                        onSelectPageTarget = { next -> activeTarget = next },
                        onExitToBookshelf = {}
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                findAllInProgressViews(rootHostView).size == 1
            }
            composeRule.onNodeWithText("1/9").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Új oldal hozzáadása").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Következő oldal").assertDoesNotExist()
            val initialWetView = findAllInProgressViews(rootHostView).single()
            assertNotNull(initialWetView)

            // Open left drawer via pull tab -> step to page 2 -> same AndroidView instance
            composeRule.onNodeWithContentDescription("Oldalak panel megnyitása").performClick()
            composeRule.onNodeWithText("Oldalak (9)").assertIsDisplayed()
            composeRule.onNodeWithTag("left_page_drawer_list", useUnmergedTree = true)
                .performScrollToNode(hasText("2. oldal"))
            composeRule.onNodeWithText("2. oldal").performClick()
            composeRule.onNodeWithText("2/9").assertIsDisplayed()
            val wetViewAfterStep2 = findAllInProgressViews(rootHostView).single()
            assertSame("Page 1->2 must keep the exact same AndroidView", initialWetView, wetViewAfterStep2)

            // Jump to page 5 via left drawer -> same AndroidView instance
            composeRule.onNodeWithContentDescription("Oldalak panel megnyitása").performClick()
            composeRule.onNodeWithTag("left_page_drawer_list", useUnmergedTree = true)
                .performScrollToNode(hasText("5. oldal"))
            composeRule.onNodeWithText("5. oldal").performClick()
            composeRule.onNodeWithText("5/9").assertIsDisplayed()
            val wetViewAfterJump5 = findAllInProgressViews(rootHostView).single()
            assertSame("Jump to page 5 must keep the exact same AndroidView", initialWetView, wetViewAfterJump5)

            // Jump all the way to page 9 via left drawer -> same AndroidView instance
            composeRule.onNodeWithContentDescription("Oldalak panel megnyitása").performClick()
            composeRule.onNodeWithTag("left_page_drawer_list", useUnmergedTree = true)
                .performScrollToNode(hasText("9. oldal"))
            composeRule.onNodeWithText("9. oldal").performClick()
            composeRule.onNodeWithText("9/9").assertIsDisplayed()
            val wetViewAfterPage9 = findAllInProgressViews(rootHostView).single()
            assertSame("Reaching page 9 must keep the exact same AndroidView", initialWetView, wetViewAfterPage9)
        } finally {
            composeRule.waitForIdle()
            db.close()
            for (pageId in pageIds) {
                File(context.filesDir, "pages/$pageId").deleteRecursively()
            }
        }
    }
}
