package hu.bdev.stylusnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.export.CanvasPageVectorRenderer
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.MultiPageSessionManager
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import hu.bdev.stylusnote.ui.components.TableCreationConfig
import hu.bdev.stylusnote.ui.components.TablePickerPopover
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class XiiiPhaseValidationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun buildLiveInkStroke(
        id: String,
        samples: List<StylusPointSample>,
        colorArgb: Int = 0xFF1D4ED8.toInt()
    ): IndexedDryStroke {
        val brush = Brush.createWithColorIntArgb(
            family = BrushConfig.resolveBrushFamily(BrushToolType.BALLPOINT_PEN),
            colorIntArgb = colorArgb,
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
            isHighlighter = false
        )
    }

    @Test
    fun multiPageSessionManagerTransfersInkStrokeAcrossPagesWithAtomicUndoRedo() = runBlocking {
        val tempRoot = Files.createTempDirectory("xiii_mp_transfer_").toFile()
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
                    notebookId = "nb-xiii",
                    pageIndexInNotebook = 0,
                    createdAtEpochMs = 1000L,
                    updatedAtEpochMs = 1000L
                ),
                PageEntity(
                    id = "pg-b",
                    notebookId = "nb-xiii",
                    pageIndexInNotebook = 1,
                    createdAtEpochMs = 2000L,
                    updatedAtEpochMs = 2000L
                )
            )
            val layout = session.updatePagesAndFocus(pages, activePageIndex = 0)
            val rtA = session.ensurePageReady("pg-a")
            val rtB = session.ensurePageReady("pg-b")

            val srcStroke = buildLiveInkStroke(
                id = "ink-src-1",
                samples = listOf(
                    StylusPointSample(80f, 760f, 10L, 0.7f, 0f, 0f, false),
                    StylusPointSample(160f, 780f, 25L, 0.7f, 0f, 0f, false)
                )
            )
            val srcText = PageObject.FormattedTextObject(
                id = "txt-src-1",
                zOrder = rtA.canvasStateHolder.allocateObjectZOrder(),
                bounds = DocRect(80f, 790f, 210f, 825f),
                text = "Átvitt jegyzet"
            )
            rtA.canvasStateHolder.addCommittedStrokes(listOf(srcStroke))
            rtA.canvasStateHolder.addPageObject(srcText)

            val slotA = layout.slotForPageId("pg-a")!!
            val slotB = layout.slotForPageId("pg-b")!!
            val offset = CanvasStateHolder.computeCrossPageTransferOffset(
                sourceOffsetXPt = slotA.leftPt,
                sourceOffsetYPt = slotA.topPt,
                destOffsetXPt = slotB.leftPt,
                destOffsetYPt = slotB.topPt,
                dragDeltaXPt = 20f,
                dragDeltaYPt = slotB.topPt - 700f
            )

            val transfer = session.transferSelectionBetweenPages(
                sourcePageId = "pg-a",
                destPageId = "pg-b",
                strokeIds = listOf(srcStroke.id),
                objectIds = listOf(srcText.id),
                offsetXPt = offset.xPt,
                offsetYPt = offset.yPt
            )
            assertNotNull(transfer)
            assertEquals(0, rtA.canvasStateHolder.strokeCount)
            assertTrue(rtA.canvasStateHolder.allPageObjects().isEmpty())
            assertEquals(1, rtB.canvasStateHolder.strokeCount)
            assertEquals(1, rtB.canvasStateHolder.allPageObjects().size)

            val migratedStroke = rtB.canvasStateHolder.allStrokesSorted().single()
            assertEquals(100f, migratedStroke.bounds.leftPt, 4f)
            assertEquals(60f, migratedStroke.bounds.topPt, 4f)

            // Single Undo reverts BOTH pages atomically
            val undoRes = session.applyUndo()
            assertNotNull(undoRes)
            assertEquals(setOf("pg-a", "pg-b"), undoRes!!.mutatedPageIds)
            assertEquals(1, rtA.canvasStateHolder.strokeCount)
            assertEquals(1, rtA.canvasStateHolder.allPageObjects().size)
            assertEquals(0, rtB.canvasStateHolder.strokeCount)
            assertTrue(rtB.canvasStateHolder.allPageObjects().isEmpty())

            // Single Redo restores the cross-page move on BOTH pages
            val redoRes = session.applyRedo()
            assertNotNull(redoRes)
            assertEquals(setOf("pg-a", "pg-b"), redoRes!!.mutatedPageIds)
            assertEquals(0, rtA.canvasStateHolder.strokeCount)
            assertEquals(1, rtB.canvasStateHolder.strokeCount)
        } finally {
            session.disposeSuspending()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun vectorRendererPlacesTableObjectUnderInkAndExcludesItFromLassoSelection() {
        val bitmap = Bitmap.createBitmap(360, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val table = PageObject.TableObject(
            id = "table-render-1",
            zOrder = 10L,
            bounds = DocRect(30f, 30f, 330f, 210f),
            rows = 3,
            columns = 3,
            borderStrokeWidthPt = 3f,
            canonicalBorderColorArgb = 0xFF0F172A.toInt(),
            hasHeaderRow = true
        )
        // Red ink crossing directly across the first row divider (y = 90)
        val inkAcrossTable = PersistedStrokeRecord(
            id = "ink-over-table",
            layer = StrokeLayerType.INK,
            zOrder = 50L,
            brushConfig = BrushConfig(
                toolType = BrushToolType.BALLPOINT_PEN,
                sizePt = 10f,
                colorArgb = 0xFFDC2626.toInt()
            ),
            canonicalColorArgb = 0xFFDC2626.toInt(),
            sizePt = 10f,
            inkEncodedInputs = ByteArray(0),
            samples = listOf(
                StylusPointSample(80f, 90f, 10L, 0.8f, 0f, 0f, false),
                StylusPointSample(280f, 90f, 30L, 0.8f, 0f, 0f, false)
            ),
            bounds = DocRect(75f, 85f, 285f, 95f)
        )

        CanvasPageVectorRenderer.drawPageForegroundLayers(
            canvas = canvas,
            strokes = listOf(inkAcrossTable),
            objects = listOf(table),
            isOledMode = false,
            context = context
        )

        // Outer top border pixel at (100, 30) must be dark slate border (not white background)
        val borderPixel = bitmap.getPixel(100, 30)
        assertTrue(
            "Table outer border must be visible; pixel=${Integer.toHexString(borderPixel)}",
            Color.red(borderPixel) < 80 && Color.blue(borderPixel) < 100
        )

        // Intersection pixel at (180, 90) must be red ink painted ABOVE the dark table divider
        val crossingPixel = bitmap.getPixel(180, 90)
        assertTrue(
            "Red ink must render over Layer 2b TableObject divider; pixel=${Integer.toHexString(crossingPixel)}",
            Color.red(crossingPixel) > 180 && Color.blue(crossingPixel) < 80
        )
        bitmap.recycle()

        // Wide table lasso captures the TableObject together with the cell grid
        val holder = CanvasStateHolder().apply { addPageObject(table) }
        val hits = holder.selectPageObjectsByLasso(
            listOf(
                DocPoint(0f, 0f),
                DocPoint(360f, 0f),
                DocPoint(360f, 260f),
                DocPoint(0f, 260f)
            )
        )
        assertEquals(1, hits.size)
        assertEquals(table.id, hits.first().id)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tablePickerPopoverConfiguresGridSteppersAndDismissesOnEscAndBackdrop() {
        var showPopover by mutableStateOf(true)
        var confirmedConfig by mutableStateOf<TableCreationConfig?>(null)

        composeRule.setContent {
            StylusNoteTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showPopover) {
                        TablePickerPopover(
                            onConfirm = {
                                confirmedConfig = it
                                showPopover = false
                            },
                            onDismiss = { showPopover = false }
                        )
                    }
                }
            }
        }

        // Select 3x4 via quick grid
        composeRule.onNodeWithTag("table_grid_cell_3_4").performClick()
        composeRule.onNodeWithTag("table_rows_value").assertTextEquals("3")
        composeRule.onNodeWithTag("table_cols_value").assertTextEquals("4")

        // Step rows & cols up to 4x5 and confirm
        composeRule.onNodeWithTag("table_rows_inc").performClick()
        composeRule.onNodeWithTag("table_cols_inc").performClick()
        composeRule.onNodeWithTag("table_picker_confirm").performClick()
        composeRule.waitForIdle()

        assertEquals(4, confirmedConfig?.rows)
        assertEquals(5, confirmedConfig?.columns)
        assertFalse(showPopover)

        // Tapping inside card keeps popover open; tapping exposed backdrop margin dismisses it
        composeRule.runOnIdle { showPopover = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("table_picker_card").performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue(showPopover)

        composeRule.onNodeWithTag("table_picker_backdrop").performTouchInput { click(Offset(8f, 8f)) }
        composeRule.waitForIdle()
        assertFalse(showPopover)

        // ESC key dismisses (Rule 11)
        composeRule.runOnIdle { showPopover = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("table_picker_backdrop").performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.waitForIdle()
        assertFalse(showPopover)
    }
}
