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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.bdev.stylusnote.core.code.CodeLanguage
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.export.CanvasPageVectorRenderer
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.ClozeTapePattern
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.ui.canvas.CodeBlockEditorDialog
import hu.bdev.stylusnote.ui.canvas.LatexEditDialog
import hu.bdev.stylusnote.ui.components.BrushPresetStore
import hu.bdev.stylusnote.ui.components.BrushSettingsPopover
import hu.bdev.stylusnote.ui.shelf.CanvasTopToolbar
import hu.bdev.stylusnote.ui.theme.StylusNoteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XiiPhaseValidationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun vectorRendererPlacesCodeBlockUnderInkAndClozeTapeAboveInk() {
        val bitmap = Bitmap.createBitmap(360, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val codeBlock = PageObject.CodeBlockObject(
            id = "code-render-1",
            zOrder = 10L,
            bounds = DocRect(30f, 30f, 320f, 150f),
            language = CodeLanguage.PYTHON,
            codeText = "print(42)",
            fontSizePt = 11f,
            title = "Demo"
        )
        // Red ink crossing directly across the center of the CodeBlock
        val inkOnCode = PersistedStrokeRecord(
            id = "ink-over-code",
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
                StylusPointSample(70f, 110f, 10L, 0.8f, 0f, 0f, false),
                StylusPointSample(260f, 110f, 30L, 0.8f, 0f, 0f, false)
            ),
            bounds = DocRect(65f, 105f, 265f, 115f)
        )

        // Deep blue ink underneath an opaque amber Cloze Tape in Layer 5b
        val hiddenAnswerInk = PersistedStrokeRecord(
            id = "ink-under-tape",
            layer = StrokeLayerType.INK,
            zOrder = 60L,
            brushConfig = BrushConfig(
                toolType = BrushToolType.BALLPOINT_PEN,
                sizePt = 8f,
                colorArgb = 0xFF1D4ED8.toInt()
            ),
            canonicalColorArgb = 0xFF1D4ED8.toInt(),
            sizePt = 8f,
            inkEncodedInputs = ByteArray(0),
            samples = listOf(
                StylusPointSample(70f, 198f, 40L, 0.8f, 0f, 0f, false),
                StylusPointSample(250f, 198f, 60L, 0.8f, 0f, 0f, false)
            ),
            bounds = DocRect(65f, 194f, 255f, 202f)
        )
        val clozeTape = PageObject.ClozeTapeObject(
            id = "cloze-render-1",
            zOrder = 70L,
            bounds = DocRect(50f, 184f, 270f, 212f),
            canonicalColorArgb = 0xFFFDE68A.toInt(),
            patternStyle = ClozeTapePattern.SOLID
        )

        CanvasPageVectorRenderer.drawPageForegroundLayers(
            canvas = canvas,
            strokes = listOf(inkOnCode, hiddenAnswerInk),
            objects = listOf(codeBlock, clozeTape),
            liveInkStrokesById = emptyMap(),
            isOledMode = false,
            context = context
        )

        // Pixel at (165, 110) must show red ink ON TOP of the light code block card
        val pixelOnCode = bitmap.getPixel(165, 110)
        assertTrue(
            "Red ink must render above Layer 2b CodeBlock; pixel=${Integer.toHexString(pixelOnCode)}",
            Color.red(pixelOnCode) > 180 && Color.blue(pixelOnCode) < 90
        )

        // Pixel at (165, 198) must show amber Cloze tape covering the blue ink underneath
        val pixelOnTape = bitmap.getPixel(165, 198)
        assertTrue(
            "Solid amber ClozeTape in Layer 5b must occlude blue ink underneath; pixel=${Integer.toHexString(pixelOnTape)}",
            Color.red(pixelOnTape) > 220 && Color.green(pixelOnTape) > 190 && Color.blue(pixelOnTape) < 170
        )
        bitmap.recycle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun codeBlockEditorDialogSavesChangesAndDismissesOnEscAndBackdrop() {
        val initial = PageObject.CodeBlockObject(
            id = "code-dlg-1",
            zOrder = 10L,
            bounds = DocRect(40f, 80f, 280f, 160f),
            language = CodeLanguage.PYTHON,
            codeText = "x = 1"
        )
        var showDialog by mutableStateOf(true)
        var savedBlock by mutableStateOf<PageObject.CodeBlockObject?>(null)

        composeRule.setContent {
            StylusNoteTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showDialog) {
                        CodeBlockEditorDialog(
                            initialBlock = initial,
                            isOledMode = false,
                            onDismiss = { showDialog = false },
                            onSave = {
                                savedBlock = it
                                showDialog = false
                            }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("code_lang_SQL").performClick()
        composeRule.onNodeWithTag("code_editor_input").performTextReplacement(
            "SELECT *\nFROM notes\nWHERE pinned = 1\nORDER BY updated DESC\nLIMIT 5\nOFFSET 0;"
        )
        composeRule.onNodeWithTag("code_editor_save").performClick()
        composeRule.waitForIdle()

        assertEquals(CodeLanguage.SQL, savedBlock?.language)
        assertTrue(savedBlock?.codeText?.contains("SELECT *") == true)
        assertEquals(initial.bounds.topPt, savedBlock!!.bounds.topPt, 0.01f)
        assertEquals(initial.bounds.leftPt, savedBlock!!.bounds.leftPt, 0.01f)
        assertTrue(savedBlock!!.bounds.bottomPt >= initial.bounds.bottomPt)
        assertFalse(showDialog)

        // Re-open and verify backdrop dismissal
        composeRule.runOnIdle { showDialog = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("code_editor_backdrop").performTouchInput { click(Offset(8f, 8f)) }
        composeRule.waitForIdle()
        assertFalse(showDialog)

        // Re-open and verify ESC key dismissal (Rule 11)
        composeRule.runOnIdle { showDialog = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("code_editor_backdrop").performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.waitForIdle()
        assertFalse(showDialog)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun latexEditDialogSavesFormulaAndDismissesOnEscAndBackdrop() {
        val initial = PageObject.LatexObject(
            id = "latex-dlg-1",
            zOrder = 20L,
            bounds = DocRect(50f, 100f, 220f, 150f),
            latexSource = "x + 1",
            fontSizePt = 18f
        )
        var showDialog by mutableStateOf(true)
        var savedLatex by mutableStateOf<PageObject.LatexObject?>(null)

        composeRule.setContent {
            StylusNoteTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showDialog) {
                        LatexEditDialog(
                            initialObject = initial,
                            isOledMode = false,
                            onDismiss = { showDialog = false },
                            onSave = {
                                savedLatex = it
                                showDialog = false
                            }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("latex_editor_input").performTextReplacement("\\frac{x^{2}}{2}")
        composeRule.onNodeWithTag("latex_editor_save").performClick()
        composeRule.waitForIdle()

        assertEquals("\\frac{x^{2}}{2}", savedLatex?.latexSource)
        assertFalse(showDialog)

        // Backdrop dismissal
        composeRule.runOnIdle { showDialog = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("latex_editor_backdrop").performTouchInput { click(Offset(8f, 8f)) }
        composeRule.waitForIdle()
        assertFalse(showDialog)

        // ESC dismissal
        composeRule.runOnIdle { showDialog = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("latex_editor_backdrop").performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.waitForIdle()
        assertFalse(showDialog)
    }

    @Test
    fun brushPopoverShowsHalfPtHighlighterAutoSwitchAndClozeControls() {
        var currentConfig by mutableStateOf(BrushConfig(toolType = BrushToolType.FOUNTAIN_PEN, sizePt = 2f))
        var highlighterAuto by mutableStateOf(true)
        var pattern by mutableStateOf(ClozeTapePattern.DIAGONAL_STRIPES)
        var revealedAllCalled by mutableStateOf<Boolean?>(null)

        composeRule.setContent {
            StylusNoteTheme {
                BrushSettingsPopover(
                    currentConfig = currentConfig,
                    presets = BrushPresetStore.defaultPresets,
                    highlighterAutoSize = highlighterAuto,
                    onToggleHighlighterAutoSize = { highlighterAuto = it },
                    clozeTapePattern = pattern,
                    onClozeTapePatternChange = { pattern = it },
                    revealedClozeCount = 0,
                    totalClozeCount = 2,
                    onToggleRevealAllCloze = { revealedAllCalled = it },
                    onConfigChange = { currentConfig = it },
                    onSavePresetSlot = { _, _ -> },
                    onApplyPreset = {},
                    onDismiss = {}
                )
            }
        }

        // Select 0.5pt pen preset
        composeRule.onNodeWithText("0.5").performClick()
        composeRule.waitForIdle()
        assertEquals(0.5f, currentConfig.sizePt, 0.01f)

        // Switch to Highlighter -> Auto-size switch visible & interactive
        composeRule.runOnIdle {
            currentConfig = BrushConfig(toolType = BrushToolType.HIGHLIGHTER, sizePt = 9.75f)
        }
        composeRule.onNodeWithText("Tollhoz igazítás (Auto)").assertIsDisplayed()
        composeRule.onNodeWithTag("highlighter_auto_size_switch").performClick()
        composeRule.waitForIdle()
        assertFalse(highlighterAuto)

        // Switch to Cloze Tape -> height, pattern, and reveal-all button visible
        composeRule.runOnIdle {
            currentConfig = BrushConfig(
                toolType = BrushToolType.CLOZE_TAPE,
                sizePt = BrushConfig.DEFAULT_CLOZE_TAPE_SIZE_PT
            )
        }
        composeRule.onNodeWithText("Pontozott").performClick()
        composeRule.waitForIdle()
        assertEquals(ClozeTapePattern.DOTS, pattern)

        composeRule.onNodeWithText("Összes szalag felfedése").performClick()
        composeRule.waitForIdle()
        assertEquals(true, revealedAllCalled)
    }

    @Test
    fun canvasTopToolbarAutoHideToggleTogglesPinState() {
        var autoHideEnabled by mutableStateOf(false)
        composeRule.setContent {
            StylusNoteTheme {
                CanvasTopToolbar(
                    notebookTitle = "Teszt jegyzet",
                    zoomPercentProvider = { 100 },
                    isAutoHideEnabled = autoHideEnabled,
                    onToggleAutoHide = { autoHideEnabled = !autoHideEnabled },
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

        composeRule.onNodeWithTag("top_toolbar_autohide_toggle").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(autoHideEnabled)
        composeRule.onNodeWithTag("top_toolbar_autohide_toggle").performClick()
        composeRule.waitForIdle()
        assertFalse(autoHideEnabled)
    }
}
