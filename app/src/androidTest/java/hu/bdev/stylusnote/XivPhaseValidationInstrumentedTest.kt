package hu.bdev.stylusnote

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.LatexEditDialog
import hu.bdev.stylusnote.ui.canvas.MultiPageSessionManager
import hu.bdev.stylusnote.ui.canvas.PageObjectsOverlayLayer
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import hu.bdev.stylusnote.ui.components.FloatingToolDock
import hu.bdev.stylusnote.ui.components.ToolDockPrefs
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class XivPhaseValidationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun buildLiveInkStroke(
        id: String,
        samples: List<StylusPointSample>,
        attachedObjectId: String? = null
    ): IndexedDryStroke {
        val brush = Brush.createWithColorIntArgb(
            family = BrushConfig.resolveBrushFamily(BrushToolType.BALLPOINT_PEN),
            colorIntArgb = 0xFF1D4ED8.toInt(),
            size = 4f,
            epsilon = BrushConfig.DEFAULT_BRUSH_EPSILON
        )
        val stroke = Stroke(
            brush = brush,
            inputs = samples.toStrokeInputBatch(InputToolType.STYLUS)
        )
        return IndexedDryStroke(
            id = id,
            stroke = stroke,
            bounds = computeStrokeDocBounds(stroke),
            zIndex = CanvasStateHolder.INK_Z_ORDER_BASE + 1L,
            isHighlighter = false,
            attachedObjectId = attachedObjectId
        )
    }

    @Test
    fun lassoCapturesTableWithLegacyAndAttachedCellInkAndTransfersAcrossPagesWithAtomicUndoRedo() = runBlocking {
        val tempRoot = Files.createTempDirectory("xiv_table_lasso_").toFile()
        val session = MultiPageSessionManager(
            filesDir = tempRoot,
            activeBrushConfigProvider = { BrushConfig() },
            onLocalMutationCommitted = {},
            onPageReadyCallback = { _, _ -> },
            ioDispatcher = Dispatchers.IO
        )
        try {
            val pages = listOf(
                PageEntity(
                    id = "pg-a",
                    notebookId = "nb-xiv",
                    pageIndexInNotebook = 0,
                    createdAtEpochMs = 1000L,
                    updatedAtEpochMs = 1000L
                ),
                PageEntity(
                    id = "pg-b",
                    notebookId = "nb-xiv",
                    pageIndexInNotebook = 1,
                    createdAtEpochMs = 2000L,
                    updatedAtEpochMs = 2000L
                )
            )
            val layout = session.updatePagesAndFocus(pages, activePageIndex = 0)
            val rtA = session.ensurePageReady("pg-a")
            val rtB = session.ensurePageReady("pg-b")

            val table = PageObject.TableObject(
                id = "tbl-move-1",
                zOrder = rtA.canvasStateHolder.allocateObjectZOrder(),
                bounds = DocRect(40f, 650f, 340f, 820f),
                rows = 3,
                columns = 3,
                hasHeaderRow = true
            )
            rtA.canvasStateHolder.addPageObject(table)

            val attachedCellStroke = buildLiveInkStroke(
                id = "cell-attached",
                samples = listOf(
                    StylusPointSample(70f, 700f, 10L, 0.7f, 0f, 0f, false),
                    StylusPointSample(120f, 715f, 25L, 0.7f, 0f, 0f, false)
                ),
                attachedObjectId = table.id
            )
            val legacyCellStroke = buildLiveInkStroke(
                id = "cell-legacy",
                samples = listOf(
                    StylusPointSample(180f, 745f, 30L, 0.7f, 0f, 0f, false),
                    StylusPointSample(240f, 765f, 45L, 0.7f, 0f, 0f, false)
                ),
                attachedObjectId = null
            )
            val outsideStroke = buildLiveInkStroke(
                id = "outside-1",
                samples = listOf(
                    StylusPointSample(430f, 100f, 50L, 0.7f, 0f, 0f, false),
                    StylusPointSample(490f, 120f, 65L, 0.7f, 0f, 0f, false)
                ),
                attachedObjectId = null
            )
            rtA.canvasStateHolder.addCommittedStrokes(
                listOf(attachedCellStroke, legacyCellStroke, outsideStroke)
            )

            // Tight single-cell lasso does not capture the table
            val singleCellPoly = listOf(
                DocPoint(155f, 735f),
                DocPoint(255f, 735f),
                DocPoint(255f, 775f),
                DocPoint(155f, 775f)
            )
            assertTrue(rtA.canvasStateHolder.selectPageObjectsByLasso(singleCellPoly).isEmpty())

            // Wide table lasso captures the table and gathers BOTH attached + legacy cell strokes
            val wideTablePoly = listOf(
                DocPoint(25f, 635f),
                DocPoint(355f, 635f),
                DocPoint(355f, 835f),
                DocPoint(25f, 835f)
            )
            val objectHits = rtA.canvasStateHolder.selectPageObjectsByLasso(wideTablePoly)
            assertEquals(listOf(table.id), objectHits.map { it.id })

            val bundledStrokeIds = rtA.canvasStateHolder.strokeIdsInsideOrAttachedToObject(table.id)
            assertEquals(setOf("cell-attached", "cell-legacy"), bundledStrokeIds)

            val slotA = layout.slotForPageId("pg-a")!!
            val slotB = layout.slotForPageId("pg-b")!!
            val offset = CanvasStateHolder.computeCrossPageTransferOffset(
                sourceOffsetXPt = slotA.leftPt,
                sourceOffsetYPt = slotA.topPt,
                destOffsetXPt = slotB.leftPt,
                destOffsetYPt = slotB.topPt,
                dragDeltaXPt = 25f,
                dragDeltaYPt = slotB.topPt - 600f
            )

            val transfer = session.transferSelectionBetweenPages(
                sourcePageId = "pg-a",
                destPageId = "pg-b",
                strokeIds = bundledStrokeIds.toList(),
                objectIds = listOf(table.id),
                offsetXPt = offset.xPt,
                offsetYPt = offset.yPt
            )
            assertNotNull(transfer)

            // Source keeps only outsideStroke; destination receives table + both cell strokes
            assertEquals(1, rtA.canvasStateHolder.strokeCount)
            assertTrue(rtA.canvasStateHolder.allPageObjects().isEmpty())
            val destTable = rtB.canvasStateHolder.allPageObjects().single() as PageObject.TableObject
            assertEquals(2, rtB.canvasStateHolder.strokeCount)
            val migratedStrokes = rtB.canvasStateHolder.allStrokesSorted()
            assertTrue(migratedStrokes.any { it.attachedObjectId == destTable.id })

            // Single atomic Undo restores table + both cell strokes back to pg-a
            val undoRes = session.applyUndo()
            assertNotNull(undoRes)
            assertEquals(3, rtA.canvasStateHolder.strokeCount)
            assertEquals(1, rtA.canvasStateHolder.allPageObjects().size)
            assertEquals(0, rtB.canvasStateHolder.strokeCount)
            assertTrue(rtB.canvasStateHolder.allPageObjects().isEmpty())

            // Single Redo re-applies across both pages
            val redoRes = session.applyRedo()
            assertNotNull(redoRes)
            assertEquals(1, rtA.canvasStateHolder.strokeCount)
            assertEquals(2, rtB.canvasStateHolder.strokeCount)
            assertEquals(1, rtB.canvasStateHolder.allPageObjects().size)
        } finally {
            session.disposeSuspending()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun floatingObjectActionPillStaysClampedWithinViewportOnBottomRightLatexAndTriggersEdit() {
        val latexObj = PageObject.LatexObject(
            id = "ltx-corner-1",
            zOrder = 10L,
            bounds = DocRect(460f, 320f, 550f, 368f),
            latexSource = "\\frac{a}{b}"
        )
        val matrix = CanvasTransformMatrix().apply {
            setTransform(scale = 1f, panXPx = 0f, panYPx = 0f)
        }
        var editedLatex by mutableStateOf<PageObject.LatexObject?>(null)

        composeRule.setContent {
            StylusNoteTheme {
                Box(
                    modifier = Modifier
                        .size(560.dp, 380.dp)
                        .testTag("xiv_overlay_root")
                ) {
                    PageObjectsOverlayLayer(
                        pageObjects = listOf(latexObj),
                        transformMatrix = matrix,
                        isOledMode = false,
                        selectedObjectId = latexObj.id,
                        onSelectObject = {},
                        onUpdateObject = {},
                        onDeleteObject = {},
                        onRequestStepByStep = {},
                        onEditLatex = { editedLatex = it }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val rootBounds = composeRule.onNodeWithTag("xiv_overlay_root")
            .fetchSemanticsNode()
            .boundsInRoot
        val pillBounds = composeRule.onNodeWithTag("floating_object_action_pill")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue("Pill left must stay inside root", pillBounds.left >= rootBounds.left)
        assertTrue("Pill top must stay inside root", pillBounds.top >= rootBounds.top)
        assertTrue("Pill right must stay inside root", pillBounds.right <= rootBounds.right + 1f)
        assertTrue("Pill bottom must stay inside root", pillBounds.bottom <= rootBounds.bottom + 1f)

        composeRule.onNodeWithTag("latex_pill_edit_button").performClick()
        composeRule.waitForIdle()
        assertEquals(latexObj.id, editedLatex?.id)
    }

    @Test
    fun latexEditDialogInsertsBracedTemplateAtCursorAndSwitchesSymbolCategories() {
        val initial = PageObject.LatexObject(
            id = "ltx-dialog-xiv",
            zOrder = 10L,
            bounds = DocRect(40f, 40f, 180f, 90f),
            latexSource = "f(x) = "
        )
        var savedObj by mutableStateOf<PageObject.LatexObject?>(null)

        composeRule.setContent {
            StylusNoteTheme {
                LatexEditDialog(
                    initialObject = initial,
                    isOledMode = false,
                    onDismiss = {},
                    onSave = { savedObj = it }
                )
            }
        }
        composeRule.waitForIdle()

        // Insert fraction template at cursor, switch tab to Greek, insert \pi inside numerator braces
        composeRule.onNodeWithTag("latex_snippet_Tört").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Görög").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("latex_snippet_π").performClick()
        composeRule.waitForIdle()

        val inputNode = composeRule.onNodeWithTag("latex_editor_input").fetchSemanticsNode()
        val editableText = inputNode.config[SemanticsProperties.EditableText].text
        assertEquals("f(x) = \\frac{\\pi}{}", editableText)

        composeRule.onNodeWithTag("latex_editor_input")
            .performTextReplacement("f(x) = \\frac{\\pi}{2}")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("latex_editor_save").performClick()
        composeRule.waitForIdle()
        assertEquals("f(x) = \\frac{\\pi}{2}", savedObj?.latexSource)
    }

    @Test
    fun expandedHorizontalFloatingToolDockSpansFullViewportWidthWithZeroLeftMargin() {
        var viewportPx by mutableStateOf(IntSize.Zero)
        var prefs by mutableStateOf(
            ToolDockPrefs(
                isCollapsed = false,
                isVertical = false,
                yFraction = 0.08f
            )
        )

        composeRule.setContent {
            StylusNoteTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewportPx = it }
                        .testTag("xiv_dock_root")
                ) {
                    FloatingToolDock(
                        brushConfig = BrushConfig(),
                        lastDrawingTool = BrushToolType.FOUNTAIN_PEN,
                        lastEraserTool = BrushToolType.VECTOR_ERASER,
                        dockPrefs = prefs,
                        avoidTopLeftRectPx = Rect.Zero,
                        viewportSize = viewportPx,
                        canUndo = false,
                        canRedo = false,
                        isDarkTheme = false,
                        showDebugOverlay = false,
                        onUpdateDockPrefs = { prefs = it },
                        onSelectTool = {},
                        onOpenSettings = {},
                        onUndo = {},
                        onRedo = {},
                        onFitPage = {},
                        onToggleTheme = {},
                        onToggleDebugOverlay = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val rootBounds = composeRule.onNodeWithTag("xiv_dock_root")
            .fetchSemanticsNode()
            .boundsInRoot
        val dockBounds = composeRule.onNodeWithTag("floating_tool_dock_surface")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue("Root must measure non-zero width", rootBounds.width > 0f)
        assertTrue("Dock left must align to screen edge x=0 (was ${dockBounds.left})", dockBounds.left <= 1.5f)
        assertTrue(
            "Expanded horizontal dock must span full width (dock=${dockBounds.width}, root=${rootBounds.width})",
            abs(dockBounds.width - rootBounds.width) <= 2f
        )
    }
}
