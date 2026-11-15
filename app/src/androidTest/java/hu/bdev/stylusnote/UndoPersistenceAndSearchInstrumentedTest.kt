package hu.bdev.stylusnote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.ai.InNotebookSearchCoordinator
import hu.bdev.stylusnote.core.ai.LineInkRecognizer
import hu.bdev.stylusnote.core.ai.SearchIndexState
import hu.bdev.stylusnote.core.ai.SearchMatchSource
import hu.bdev.stylusnote.core.hardware.PressureCurve
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeBinarySerializer
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.components.SearchHighlightCanvasOverlay
import hu.bdev.stylusnote.ui.shelf.CanvasTopToolbar
import hu.bdev.stylusnote.ui.shelf.NotebookSearchUiState
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class UndoPersistenceAndSearchInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempRoot = Files.createTempDirectory(context.cacheDir.toPath(), "undo_search_inst_").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun aiConversionUndoAndRedoPersistOriginalInkColorAndAbsentAiObjectAcrossReopen() = runBlocking {
        val pageDir = File(tempRoot, "page-ai-undo-real-ink")
        val redPenColor = 0xFFD32F2F.toInt()
        val yellowHlColor = 0xFFF59E0B.toInt()
        val currentBlueBrush = BrushConfig(
            toolType = BrushToolType.FOUNTAIN_PEN,
            sizePt = 2.5f,
            colorArgb = 0xFF2563EB.toInt()
        )

        val recRed = PersistedStrokeRecord(
            id = "stroke-red-1",
            layer = StrokeLayerType.INK,
            zOrder = 1L,
            brushConfig = BrushConfig(
                toolType = BrushToolType.BALLPOINT_PEN,
                sizePt = 3.0f,
                colorArgb = redPenColor,
                opacity = 1.0f,
                stabilizationPercent = 25f,
                pressureCurve = PressureCurve.LINEAR,
                holdToSnapEnabled = true,
                eraserRadiusDp = 18f
            ),
            canonicalColorArgb = redPenColor,
            sizePt = 3.0f,
            samples = listOf(
                StylusPointSample(40f, 80f, 0L, 0.6f, 0f, 0f, false),
                StylusPointSample(140f, 82f, 20L, 0.7f, 0f, 0f, false)
            ),
            bounds = DocRect(40f, 80f, 140f, 82f)
        )
        val recHl = PersistedStrokeRecord(
            id = "stroke-hl-2",
            layer = StrokeLayerType.HIGHLIGHTER,
            zOrder = 2L,
            brushConfig = BrushConfig(
                toolType = BrushToolType.HIGHLIGHTER,
                sizePt = 12.0f,
                colorArgb = yellowHlColor,
                opacity = BrushConfig.HIGHLIGHTER_OPACITY,
                stabilizationPercent = 10f,
                pressureCurve = PressureCurve.LINEAR,
                holdToSnapEnabled = false,
                eraserRadiusDp = 18f
            ),
            canonicalColorArgb = yellowHlColor,
            sizePt = 12.0f,
            samples = listOf(
                StylusPointSample(40f, 110f, 30L, 0.5f, 0f, 0f, false),
                StylusPointSample(150f, 112f, 50L, 0.5f, 0f, 0f, false)
            ),
            bounds = DocRect(40f, 110f, 150f, 112f)
        )

        val dryRed = StrokeBinarySerializer.toIndexedDryStroke(recRed).first
        val dryHl = StrokeBinarySerializer.toIndexedDryStroke(recHl).first

        val holder = CanvasStateHolder()
        val journal1 = StrokeJournal(
            pageDirectory = pageDir,
            fsyncDebounceMs = 0L,
            activeBrushConfigProvider = { currentBlueBrush },
            shapeMetaProvider = { holder.getShapeMeta(it) }
        )
        journal1.openAndRecover()

        holder.addCommittedStrokes(listOf(dryRed, dryHl))
        journal1.appendAddStrokes(listOf(recRed, recHl))

        // Simulate AI recognition replacing handwriting strokes with FormattedTextObject
        val aiTextObj = PageObject.FormattedTextObject(
            id = "ai-fmt-1",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(40f, 75f, 160f, 120f),
            text = "Jegyzet szöveg",
            sourceStrokeIds = listOf(dryRed.id, dryHl.id),
            originalStrokes = listOf(recRed, recHl)
        )
        holder.replaceStrokesWithPageObject(listOf(dryRed.id, dryHl.id), aiTextObj)
        journal1.appendRemoveStrokes(listOf(dryRed.id, dryHl.id))
        journal1.appendAddObject(aiTextObj)

        // User presses Undo, then immediately closes notebook (triggering compactBeforeClose = true)
        val undoOutcome = holder.undoWithAction()
        assertNotNull(undoOutcome.action)
        journal1.enqueueCanvasActionInverse(undoOutcome.action!!)
        journal1.closeBlocking(compactBeforeClose = true)

        // Reopen notebook: AI object must NOT return, and original strokes + colors must be intact
        val journal2 = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        val recoveredAfterUndo = journal2.openAndRecover()
        assertTrue(
            "AI text must stay removed after Undo + close + reopen",
            recoveredAfterUndo.objects.isEmpty()
        )
        assertEquals(2, recoveredAfterUndo.strokes.size)
        val restoredRed = recoveredAfterUndo.strokes.first { it.id == "stroke-red-1" }
        val restoredHl = recoveredAfterUndo.strokes.first { it.id == "stroke-hl-2" }
        assertEquals(redPenColor, restoredRed.canonicalColorArgb)
        assertEquals(yellowHlColor, restoredHl.canonicalColorArgb)
        assertEquals(StrokeLayerType.HIGHLIGHTER, restoredHl.layer)

        // Now verify Redo in the original session pattern persists back to disk cleanly too
        journal2.enqueueCanvasActionForward(undoOutcome.action!!)
        journal2.closeBlocking(compactBeforeClose = true)

        val journal3 = StrokeJournal(pageDirectory = pageDir, fsyncDebounceMs = 0L)
        val recoveredAfterRedo = journal3.openAndRecover()
        journal3.closeBlocking(compactBeforeClose = false)

        assertTrue(recoveredAfterRedo.strokes.isEmpty())
        assertEquals(1, recoveredAfterRedo.objects.size)
        assertEquals("ai-fmt-1", recoveredAfterRedo.objects.single().id)
    }

    @Test
    fun inNotebookSearchCoordinatorIndexesLiveOpenPageAndClosedDiskPageAndTriggersModelDownload() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val nbId = "nb-search-e2e"
                val page1Id = "p-search-1"
                val page2Id = "p-search-2"
                db.notebookDao().upsertNotebook(
                    NotebookEntity(
                        id = nbId,
                        title = "Analízis jegyzet",
                        ocrLanguage = OcrLanguageTag.HUNGARIAN,
                        lastOpenedPageId = page1Id,
                        createdAtEpochMs = 100L,
                        updatedAtEpochMs = 200L
                    )
                )
                val page1 = PageEntity(
                    id = page1Id,
                    notebookId = nbId,
                    pageIndexInNotebook = 0,
                    title = "Bevezető",
                    needsOcrSync = false,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 200L
                )
                val page2 = PageEntity(
                    id = page2Id,
                    notebookId = nbId,
                    pageIndexInNotebook = 1,
                    title = "Integrálszámítás",
                    needsOcrSync = true,
                    createdAtEpochMs = 100L,
                    updatedAtEpochMs = 250L
                )
                db.pageDao().upsertPage(page1)
                db.pageDao().upsertPage(page2)

                // Live unsaved stroke + FormattedTextObject on open Page 1
                val liveStroke = PersistedStrokeRecord(
                    id = "live-hw-1",
                    layer = StrokeLayerType.INK,
                    zOrder = 1L,
                    brushConfig = BrushConfig(toolType = BrushToolType.FOUNTAIN_PEN),
                    canonicalColorArgb = 0xFF111827.toInt(),
                    sizePt = 2.5f,
                    samples = listOf(
                        StylusPointSample(60f, 120f, 10L, 0.6f, 0f, 0f, false),
                        StylusPointSample(220f, 142f, 40L, 0.7f, 0f, 0f, false)
                    ),
                    bounds = DocRect(60f, 120f, 220f, 142f)
                )
                val liveObj = PageObject.FormattedTextObject(
                    id = "obj-search-1",
                    zOrder = 2L,
                    bounds = DocRect(60f, 200f, 260f, 240f),
                    text = "Fontos deriválási szabály"
                )

                // Closed dirty Page 2 strokes persisted to disk journal only
                val page2Dir = File(tempRoot, "pages/$page2Id").apply { mkdirs() }
                val diskStroke = PersistedStrokeRecord(
                    id = "disk-hw-2",
                    layer = StrokeLayerType.INK,
                    zOrder = 1L,
                    brushConfig = BrushConfig(toolType = BrushToolType.BALLPOINT_PEN),
                    canonicalColorArgb = 0xFF1E3A8A.toInt(),
                    sizePt = 2.0f,
                    samples = listOf(
                        StylusPointSample(80f, 320f, 10L, 0.6f, 0f, 0f, false),
                        StylusPointSample(260f, 344f, 45L, 0.7f, 0f, 0f, false)
                    ),
                    bounds = DocRect(80f, 320f, 260f, 344f)
                )
                val p2Journal = StrokeJournal(pageDirectory = page2Dir, fsyncDebounceMs = 0L)
                p2Journal.openAndRecover()
                p2Journal.appendAddStrokes(listOf(diskStroke))
                p2Journal.closeBlocking(compactBeforeClose = true)

                var downloaded = false
                var downloadCallCount = 0
                val fakeRecognizer = LineInkRecognizer { line, _, _ ->
                    if (line.bounds.topPt < 200f) "Függvény derivált képlet"
                    else "Határozatlan integrál derivált"
                }
                val coordinator = InNotebookSearchCoordinator(
                    database = db,
                    filesDir = tempRoot,
                    lineRecognizer = fakeRecognizer,
                    modelAlreadyDownloadedCheck = { downloaded },
                    modelDownloader = {
                        downloadCallCount++
                        downloaded = true
                        true
                    },
                    aiTextBoxExtractor = { _, _ ->
                        listOf("Különleges másodlagos kifejezés" to DocRect(60f, 120f, 220f, 142f))
                    }
                )

                val firstSearch = coordinator.prepareAndSearch(
                    notebookId = nbId,
                    ocrLanguage = OcrLanguageTag.HUNGARIAN,
                    pages = listOf(page1, page2),
                    currentPageId = page1Id,
                    liveStrokes = listOf(liveStroke),
                    liveObjects = listOf(liveObj),
                    livePageTitle = page1.title,
                    query = "derivált"
                )
                assertEquals(1, downloadCallCount)
                assertEquals(2, firstSearch.matches.size)
                assertEquals(page1Id, firstSearch.matches[0].pageId)
                assertEquals(SearchMatchSource.HANDWRITING_OCR, firstSearch.matches[0].source)
                assertNotNull(firstSearch.matches[0].highlightDocRect)
                assertEquals(page2Id, firstSearch.matches[1].pageId)
                assertEquals(SearchMatchSource.HANDWRITING_OCR, firstSearch.matches[1].source)

                val objSearch = coordinator.prepareAndSearch(
                    notebookId = nbId,
                    ocrLanguage = OcrLanguageTag.HUNGARIAN,
                    pages = listOf(page1, page2),
                    currentPageId = page1Id,
                    liveStrokes = listOf(liveStroke),
                    liveObjects = listOf(liveObj),
                    livePageTitle = page1.title,
                    query = "szabály"
                )
                assertEquals(1, objSearch.matches.size)
                assertEquals(SearchMatchSource.FORMATTED_TEXT, objSearch.matches.single().source)

                val titleSearch = coordinator.prepareAndSearch(
                    notebookId = nbId,
                    ocrLanguage = OcrLanguageTag.HUNGARIAN,
                    pages = listOf(page1, page2),
                    currentPageId = page1Id,
                    liveStrokes = listOf(liveStroke),
                    liveObjects = listOf(liveObj),
                    livePageTitle = page1.title,
                    query = "Integrálszámítás"
                )
                assertEquals(1, titleSearch.matches.size)
                assertEquals(SearchMatchSource.PAGE_TITLE, titleSearch.matches.single().source)
                assertEquals(page2Id, titleSearch.matches.single().pageId)

                // Manual BYOK AI deep index augments search matches without touching strokes
                val deepSearch = coordinator.runManualAiDeepIndex(
                    notebookId = nbId,
                    ocrLanguage = OcrLanguageTag.HUNGARIAN,
                    pages = listOf(page1, page2),
                    currentPageId = page1Id,
                    liveStrokes = listOf(liveStroke),
                    liveObjects = listOf(liveObj),
                    livePageTitle = page1.title,
                    query = "másodlagos"
                )
                assertEquals(1, deepSearch.matches.size)
                assertEquals(page1Id, deepSearch.matches.single().pageId)
                assertNotNull(deepSearch.matches.single().highlightDocRect)
            } finally {
                db.close()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun canvasTopToolbarSearchBarOpensNavigatesMatchesAndClosesOnEscape() {
        val sampleRects = listOf(
            DocRect(100f, 120f, 210f, 150f),
            DocRect(150f, 400f, 260f, 430f)
        )
        composeRule.setContent {
            var isOpen by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var activeIdx by remember { mutableIntStateOf(0) }
            val matches = remember(query) {
                if (query.equals("derivált", ignoreCase = true)) sampleRects else emptyList()
            }
            val transformSnapshot = remember { CanvasTransformMatrix().snapshot() }
            StylusNoteTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isOpen && matches.isNotEmpty()) {
                        SearchHighlightCanvasOverlay(
                            highlightDocRect = matches[activeIdx],
                            transformSnapshot = transformSnapshot,
                            isOledMode = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("in_notebook_search_highlight")
                        )
                    }
                    CanvasTopToolbar(
                        notebookTitle = "Teszt füzet",
                        zoomPercentProvider = { 100 },
                        searchUiState = NotebookSearchUiState(
                            isOpen = isOpen,
                            query = query,
                            activeMatchIndex = activeIdx,
                            matchesCount = matches.size,
                            indexState = SearchIndexState.READY,
                            canRunAiDeepIndex = true,
                            onOpen = { isOpen = true },
                            onQueryChange = {
                                query = it
                                activeIdx = 0
                            },
                            onPreviousMatch = {
                                if (matches.isNotEmpty()) {
                                    activeIdx = (activeIdx - 1).mod(matches.size)
                                }
                            },
                            onNextMatch = {
                                if (matches.isNotEmpty()) {
                                    activeIdx = (activeIdx + 1).mod(matches.size)
                                }
                            },
                            onClose = {
                                isOpen = false
                                query = ""
                                activeIdx = 0
                            }
                        ),
                        onBackToShelf = {},
                        onOpenCloudBackupDialog = {},
                        onCloudQuickSyncTriggered = {},
                        onZoomOut = {},
                        onResetZoom = {},
                        onZoomIn = {},
                        onOpenAiCopilot = {}
                    )
                }
            }
        }

        val aiBounds = composeRule.onNodeWithText("AI").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val triggerBounds = composeRule
            .onNodeWithTag("in_notebook_search_trigger")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Search trigger must sit immediately beside the AI pill",
            triggerBounds.left >= aiBounds.right - 2f
        )

        composeRule.onNodeWithTag("in_notebook_search_trigger").performClick()
        composeRule.onNodeWithTag("in_notebook_search_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("in_notebook_search_input").performTextInput("derivált")

        composeRule.onNodeWithTag("in_notebook_search_counter").assertTextEquals("1/2")
        composeRule.onNodeWithTag("in_notebook_search_highlight").assertIsDisplayed()

        composeRule.onNodeWithTag("in_notebook_search_next").performClick()
        composeRule.onNodeWithTag("in_notebook_search_counter").assertTextEquals("2/2")

        composeRule.onNodeWithTag("in_notebook_search_prev").performClick()
        composeRule.onNodeWithTag("in_notebook_search_counter").assertTextEquals("1/2")

        composeRule.onNodeWithTag("in_notebook_search_input").performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.onNodeWithTag("in_notebook_search_bar").assertDoesNotExist()
        composeRule.onNodeWithTag("in_notebook_search_highlight").assertDoesNotExist()
        composeRule.onNodeWithTag("in_notebook_search_trigger").assertIsDisplayed()

        // Also verify explicit X button closes cleanly
        composeRule.onNodeWithTag("in_notebook_search_trigger").performClick()
        composeRule.onNodeWithTag("in_notebook_search_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("in_notebook_search_close").performClick()
        composeRule.onNodeWithTag("in_notebook_search_bar").assertDoesNotExist()
    }
}
