package hu.bdev.stylusnote.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.bdev.stylusnote.core.ai.FunctionEvaluator
import hu.bdev.stylusnote.core.ai.FunctionParseException
import hu.bdev.stylusnote.core.ai.GeminiClient
import hu.bdev.stylusnote.core.ai.UserInitiatedAiTrigger
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.ColorLuminanceInverter
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.export.StrokeOutlineExtractor
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Structural + optional native `jlatexmath-android` syntax validator for Gemini LaTeX outputs
 * (Section 2 & 6). Malformed formulas fail safely without replacing handwriting strokes.
 */
object LatexSyntaxValidator {

    data class ValidationOutcome(
        val isValid: Boolean,
        val normalizedLatex: String,
        val errorMessageHu: String? = null
    )

    fun validate(rawLatex: String, context: Context? = null): ValidationOutcome {
        val normalized = stripMathDelimiters(rawLatex)
        if (normalized.isEmpty()) {
            return ValidationOutcome(false, "", "Üres LaTeX kimenet érkezett.")
        }
        val structuralError = validateStructuralSyntax(normalized)
        if (structuralError != null) {
            return ValidationOutcome(false, normalized, structuralError)
        }
        if (context != null) {
            try {
                // Verify directly against native JLaTeXMath parser (WebView forbidden per Section 1).
                JLatexMathDrawable.builder(normalized)
                    .textSize(36f)
                    .color(AndroidColor.BLACK)
                    .build()
            } catch (t: Throwable) {
                return ValidationOutcome(
                    isValid = false,
                    normalizedLatex = normalized,
                    errorMessageHu = "Hibás LaTeX szintaxis: ${t.message ?: "nem renderelhető képlet"}"
                )
            }
        }
        return ValidationOutcome(isValid = true, normalizedLatex = normalized)
    }

    internal fun stripMathDelimiters(raw: String): String {
        var s = GeminiClient.stripMarkdownCodeFences(raw).trim()
        if (s.startsWith("$$") && s.endsWith("$$") && s.length >= 4) {
            s = s.substring(2, s.length - 2).trim()
        } else if (s.startsWith("$") && s.endsWith("$") && s.length >= 2) {
            s = s.substring(1, s.length - 1).trim()
        } else if (s.startsWith("\\(") && s.endsWith("\\)") && s.length >= 4) {
            s = s.substring(2, s.length - 2).trim()
        } else if (s.startsWith("\\[") && s.endsWith("\\]") && s.length >= 4) {
            s = s.substring(2, s.length - 2).trim()
        }
        return s
    }

    private fun validateStructuralSyntax(latex: String): String? {
        var braceBalance = 0
        var i = 0
        while (i < latex.length) {
            val ch = latex[i]
            if (ch == '\\') {
                if (i == latex.lastIndex) {
                    return "Befejezetlen LaTeX escape '\\' a képlet végén."
                }
                i += 2
                continue
            }
            if (ch == '{') {
                braceBalance++
            } else if (ch == '}') {
                braceBalance--
                if (braceBalance < 0) {
                    return "Páratlan '}' kapcsos zárójel a LaTeX képletben."
                }
            }
            i++
        }
        if (braceBalance != 0) {
            return "Lezáratlan '{' kapcsos zárójel a LaTeX képletben."
        }

        // Verify matching \begin{...} / \end{...} environments
        val envStack = ArrayDeque<String>()
        for (match in ENV_REGEX.findAll(latex)) {
            val kind = match.groupValues[1]
            val envName = match.groupValues[2]
            if (kind == "begin") {
                envStack.addLast(envName)
            } else {
                val top = envStack.removeLastOrNull()
                if (top != envName) {
                    return "Eltérő LaTeX környezet: '\\end{$envName}'."
                }
            }
        }
        if (envStack.isNotEmpty()) {
            return "Lezáratlan '\\begin{${envStack.last()}}' környezet."
        }

        // Verify common two-argument commands like \frac{...}{...} have both groups
        var fracSearchIdx = 0
        while (true) {
            val idx = latex.indexOf("\\frac", fracSearchIdx)
            if (idx < 0) break
            val afterCmd = idx + 5
            if (afterCmd < latex.length && latex[afterCmd].isLetter()) {
                fracSearchIdx = afterCmd
                continue
            }
            val endFirst = consumeBraceGroup(latex, afterCmd)
                ?: return "Hiányos '\\frac' számláló a LaTeX képletben."
            consumeBraceGroup(latex, endFirst)
                ?: return "Hiányos '\\frac' nevező a LaTeX képletben."
            fracSearchIdx = endFirst
        }

        return null
    }

    private fun consumeBraceGroup(src: String, startIndex: Int): Int? {
        var pos = startIndex
        while (pos < src.length && src[pos].isWhitespace()) pos++
        if (pos >= src.length) return null
        if (src[pos] != '{') return null
        var depth = 0
        while (pos < src.length) {
            val ch = src[pos]
            if (ch == '\\') {
                pos += 2
                continue
            }
            if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) return pos + 1
            }
            pos++
        }
        return null
    }

    private val ENV_REGEX = Regex("""\\(begin|end)\{([a-zA-Z*]+)\}""")
}

sealed interface LatexConversionResult {
    data class Success(
        val latexObject: PageObject.LatexObject,
        val modelIdUsed: String,
        val modelSwitchedNoticeHu: String?
    ) : LatexConversionResult

    data class InvalidLatexError(val messageHu: String) : LatexConversionResult
}

/**
 * Orchestrates Lasso -> canonical light-mode Bitmap -> Gemini JSON -> native JLaTeXMath validation ->
 * atomic [CanvasAction.CompositeAction] stroke replacement and step-by-step derivation cards
 * (Section 6).
 */
object HandwritingToLatexConverter {

    /**
     * Always renders strokes onto a pure white background with their canonical light-mode colors
     * (never OLED-inverted) before submission to Gemini (Section 6).
     */
    fun renderStrokesToCanonicalLightBitmap(
        strokes: List<PersistedStrokeRecord>,
        targetLongEdgePx: Int = GeminiClient.DEFAULT_INLINE_IMAGE_LONG_EDGE_PX
    ): Bitmap {
        require(strokes.isNotEmpty()) { "Legalább egy vonás szükséges a bitmap előállításához." }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            val pad = s.sizePt
            minX = min(minX, s.bounds.leftPt - pad)
            minY = min(minY, s.bounds.topPt - pad)
            maxX = max(maxX, s.bounds.rightPt + pad)
            maxY = max(maxY, s.bounds.bottomPt + pad)
        }

        val docW = (maxX - minX).coerceAtLeast(24f) + 24f
        val docH = (maxY - minY).coerceAtLeast(24f) + 24f
        val originX = minX - 12f
        val originY = minY - 12f

        val longDoc = max(docW, docH)
        val clampedLongPx = targetLongEdgePx.coerceIn(
            GeminiClient.MIN_INLINE_IMAGE_LONG_EDGE_PX,
            GeminiClient.MAX_INLINE_IMAGE_LONG_EDGE_PX
        )
        val scale = clampedLongPx.toFloat() / longDoc
        val bmpW = (docW * scale).roundToInt().coerceIn(64, clampedLongPx)
        val bmpH = (docH * scale).roundToInt().coerceIn(64, clampedLongPx)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.drawColor(AndroidColor.WHITE)

        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-originX, -originY)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (record in strokes.sortedBy { it.zOrder }) {
            val loops = StrokeOutlineExtractor.buildAnalyticalStrokeOutlineLoops(
                samples = record.samples,
                baseSizePt = record.sizePt,
                toolType = record.brushConfig.toolType
            )
            val path = StrokeOutlineExtractor.loopsToAndroidPath(loops)
            if (path.isEmpty) continue
            // Render in canonical light ink color with high contrast for reliable math OCR.
            fillPaint.color = canonicalDarkExportInk(record.canonicalColorArgb)
            canvas.drawPath(path, fillPaint)
        }
        canvas.restore()
        return bitmap
    }

    suspend fun convertLassoToLatex(
        selectedStrokes: List<PersistedStrokeRecord>,
        geminiClient: GeminiClient,
        canvasStateHolder: CanvasStateHolder,
        context: Context? = null,
        journalSink: StrokeJournal? = null
    ): LatexConversionResult {
        if (selectedStrokes.isEmpty()) {
            return LatexConversionResult.InvalidLatexError("Nincs kijelölt kézírási vonás.")
        }

        val bitmap = renderStrokesToCanonicalLightBitmap(selectedStrokes)
        val pngBytes = try {
            GeminiClient.prepareInlinePngBytes(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        val response = geminiClient.generateContent(
            trigger = UserInitiatedAiTrigger.LASSO_TO_LATEX,
            promptText = "Alakítsd át a képen látható kézzel írt matematikai kifejezést pontos LaTeX-re. " +
                "Kizárólag érvényes JSON objektumot adj vissza egyetlen \"latex\" szöveges mezővel.",
            inlinePngImages = listOf(pngBytes),
            responseMimeType = "application/json",
            responseSchemaJson = LATEX_RESPONSE_SCHEMA,
            systemInstruction = "Matematikai OCR asszisztens vagy. Mindig egyetlen {\"latex\": \"...\"} JSON objektummal válaszolj."
        )

        val extracted = GeminiClient.extractJsonStringField(response.text, "latex")
            ?: GeminiClient.stripMarkdownCodeFences(response.text)

        val validation = LatexSyntaxValidator.validate(extracted, context)
        if (!validation.isValid) {
            return LatexConversionResult.InvalidLatexError(
                validation.errorMessageHu ?: "Érvénytelen LaTeX kimenet; a kézírás érintetlen maradt."
            )
        }

        val bounds = mergeStrokeDocRects(selectedStrokes)
        val latexObj = PageObject.LatexObject(
            id = java.util.UUID.randomUUID().toString(),
            zOrder = canvasStateHolder.allocateObjectZOrder(),
            bounds = bounds,
            latexSource = validation.normalizedLatex,
            fontSizePt = (bounds.heightPt * 0.65f).coerceIn(14f, 42f),
            canonicalColorArgb = selectedStrokes.first().canonicalColorArgb,
            sourceStrokeIds = selectedStrokes.map { it.id }
        )

        val strokeIds = selectedStrokes.map { it.id }
        canvasStateHolder.replaceStrokesWithPageObject(strokeIds, latexObj)
        journalSink?.appendRemoveStrokes(strokeIds)
        journalSink?.appendAddObject(latexObj)

        return LatexConversionResult.Success(
            latexObject = latexObj,
            modelIdUsed = response.modelIdUsed,
            modelSwitchedNoticeHu = response.modelSwitchedNoticeHu
        )
    }

    suspend fun generateStepByStepDerivationCard(
        latexObject: PageObject.LatexObject,
        geminiClient: GeminiClient,
        canvasStateHolder: CanvasStateHolder,
        journalSink: StrokeJournal? = null
    ): Result<PageObject.DerivationCardObject> = runCatching {
        val response = geminiClient.generateContent(
            trigger = UserInitiatedAiTrigger.STEP_BY_STEP_DERIVATION,
            promptText = "Vezesd le vagy oldd meg lépésről lépésre magyarul a következő LaTeX kifejezést: ${latexObject.latexSource}. " +
                "Adj vissza JSON-t \"title\" és \"steps\" (Markdown + rövid LaTeX lépések) mezőkkel.",
            responseMimeType = "application/json",
            responseSchemaJson = DERIVATION_RESPONSE_SCHEMA,
            systemInstruction = "Precíz magyar matematika tanár vagy. Rövid, áttekinthető levezetési lépéseket adj."
        )

        val title = GeminiClient.extractJsonStringField(response.text, "title")
            ?: "Lépésről lépésre levezetés"
        val steps = GeminiClient.extractJsonStringField(response.text, "steps")
            ?: GeminiClient.stripMarkdownCodeFences(response.text)

        val cardBounds = DocRect(
            leftPt = latexObject.bounds.leftPt,
            topPt = latexObject.bounds.bottomPt + 14f,
            rightPt = max(latexObject.bounds.rightPt + 120f, latexObject.bounds.leftPt + 260f),
            bottomPt = latexObject.bounds.bottomPt + 170f
        )

        val card = PageObject.DerivationCardObject(
            id = java.util.UUID.randomUUID().toString(),
            zOrder = canvasStateHolder.allocateObjectZOrder(),
            bounds = cardBounds,
            title = title,
            stepsMarkdownLatex = steps,
            relatedLatexObjectId = latexObject.id
        )
        canvasStateHolder.addPageObject(card)
        journalSink?.appendAddObject(card)
        card
    }

    private fun canonicalDarkExportInk(colorArgb: Int): Int {
        // Ensure near-white colors remain crisp dark ink on white PNG background.
        val r = (colorArgb ushr 16) and 0xFF
        val g = (colorArgb ushr 8) and 0xFF
        val b = colorArgb and 0xFF
        val brightness = (r * 299 + g * 587 + b * 114) / 1000
        return if (brightness > 215) 0xFF14171C.toInt() else ((0xFF shl 24) or (colorArgb and 0x00FFFFFF))
    }

    private fun mergeStrokeDocRects(strokes: List<PersistedStrokeRecord>): DocRect {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            minX = min(minX, s.bounds.leftPt)
            minY = min(minY, s.bounds.topPt)
            maxX = max(maxX, s.bounds.rightPt)
            maxY = max(maxY, s.bounds.bottomPt)
        }
        return DocRect(
            leftPt = minX - 6f,
            topPt = minY - 6f,
            rightPt = max(maxX + 6f, minX + 48f),
            bottomPt = max(maxY + 6f, minY + 32f)
        )
    }

    private const val LATEX_RESPONSE_SCHEMA =
        """{"type":"object","properties":{"latex":{"type":"string"}},"required":["latex"]}"""
    private const val DERIVATION_RESPONSE_SCHEMA =
        """{"type":"object","properties":{"title":{"type":"string"},"steps":{"type":"string"}},"required":["title","steps"]}"""
}

/**
 * Interactive Layer-5 compose overlay rendering [PageObject.LatexObject],
 * [PageObject.DerivationCardObject], and [PageObject.FunctionPlotObject] elements synced 1:1 with
 * [transformMatrix] (Section 3 & 6).
 */
@Composable
fun PageObjectsOverlayLayer(
    pageObjects: List<PageObject>,
    transformMatrix: CanvasTransformMatrix,
    isOledMode: Boolean,
    selectedObjectId: String?,
    onSelectObject: (String?) -> Unit,
    onUpdateObject: (PageObject) -> Unit,
    onDeleteObject: (String) -> Unit,
    onRequestStepByStep: (PageObject.LatexObject) -> Unit,
    onPlotFromLatex: (PageObject.LatexObject) -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = transformMatrix.snapshot()
    val scale = snapshot.scale.coerceAtLeast(0.1f)

    Box(modifier = modifier.fillMaxSize()) {
        for (obj in pageObjects) {
            if (obj is PageObject.PdfPageRefObject) continue

            val screenTopLeft = transformMatrix.docToScreen(obj.bounds.leftPt, obj.bounds.topPt)
            val widthPx = (obj.bounds.widthPt * scale).coerceAtLeast(36f)
            val heightPx = (obj.bounds.heightPt * scale).coerceAtLeast(28f)
            val isSelected = (obj.id == selectedObjectId)

            val density = androidx.compose.ui.platform.LocalDensity.current
            val widthDp = with(density) { widthPx.toDp() }
            val heightDp = with(density) { heightPx.toDp() }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = screenTopLeft.xPx.roundToInt(),
                            y = screenTopLeft.yPx.roundToInt()
                        )
                    }
                    .size(width = widthDp, height = heightDp)
                    .pointerInput(obj.id, obj.bounds, scale) {
                        detectDragGestures(
                            onDragStart = { onSelectObject(obj.id) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dxDoc = dragAmount.x / scale
                                val dyDoc = dragAmount.y / scale
                                onUpdateObject(
                                    obj.transform(AffineTransform2D.translation(dxDoc, dyDoc))
                                )
                            }
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onSelectObject(if (isSelected) null else obj.id)
                    }
            ) {
                when (obj) {
                    is PageObject.LatexObject -> LatexOverlayNode(
                        latexObject = obj,
                        scale = scale,
                        isOledMode = isOledMode,
                        isSelected = isSelected,
                        onUpdateObject = onUpdateObject,
                        onDelete = { onDeleteObject(obj.id) },
                        onRequestStepByStep = { onRequestStepByStep(obj) },
                        onPlotFromLatex = { onPlotFromLatex(obj) }
                    )

                    is PageObject.DerivationCardObject -> DerivationCardNode(
                        card = obj,
                        isOledMode = isOledMode,
                        isSelected = isSelected,
                        onToggleCollapse = {
                            onUpdateObject(obj.copy(isCollapsed = !obj.isCollapsed))
                        },
                        onDelete = { onDeleteObject(obj.id) }
                    )

                    is PageObject.FunctionPlotObject -> FunctionPlotNode(
                        plot = obj,
                        isOledMode = isOledMode,
                        isSelected = isSelected,
                        onDelete = { onDeleteObject(obj.id) }
                    )

                    is PageObject.PdfPageRefObject -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LatexOverlayNode(
    latexObject: PageObject.LatexObject,
    scale: Float,
    isOledMode: Boolean,
    isSelected: Boolean,
    onUpdateObject: (PageObject.LatexObject) -> Unit,
    onDelete: () -> Unit,
    onRequestStepByStep: () -> Unit,
    onPlotFromLatex: () -> Unit
) {
    LocalContext.current
    val effectiveColorArgb = remember(latexObject.canonicalColorArgb, isOledMode) {
        if (isOledMode) {
            ColorLuminanceInverter.invertArgbLuminance(latexObject.canonicalColorArgb)
        } else {
            latexObject.canonicalColorArgb
        }
    }

    val drawable = remember(latexObject.latexSource, latexObject.fontSizePt, scale, effectiveColorArgb) {
        runCatching {
            JLatexMathDrawable.builder(latexObject.latexSource)
                .textSize((latexObject.fontSizePt * scale).coerceIn(10f, 180f))
                .color(effectiveColorArgb)
                .padding(4)
                .build()
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isSelected) {
                    Modifier.border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (drawable != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
                    drawable.draw(drawContext.canvas.nativeCanvas)
                }
            } else {
                Text(
                    text = latexObject.latexSource,
                    color = Color(effectiveColorArgb),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (isSelected) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onRequestStepByStep) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Levezetés")
                    }
                    TextButton(onClick = onPlotFromLatex) {
                        Icon(
                            imageVector = Icons.Default.ShowChart,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Grafikon")
                    }
                    // Color swatches (canonical light colors)
                    LATEX_COLOR_SWATCHES.forEach { swatchArgb ->
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(swatchArgb))
                                .border(
                                    width = if (latexObject.canonicalColorArgb == swatchArgb) 2.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable {
                                    onUpdateObject(latexObject.copy(canonicalColorArgb = swatchArgb))
                                }
                        )
                    }
                    IconButton(
                        onClick = {
                            val centerX = (latexObject.bounds.leftPt + latexObject.bounds.rightPt) * 0.5f
                            val centerY = (latexObject.bounds.topPt + latexObject.bounds.bottomPt) * 0.5f
                            onUpdateObject(
                                latexObject.transform(
                                    AffineTransform2D.scalingAround(1.15f, 1.15f, centerX, centerY)
                                ) as PageObject.LatexObject
                            )
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Nagyítás", modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = {
                            val centerX = (latexObject.bounds.leftPt + latexObject.bounds.rightPt) * 0.5f
                            val centerY = (latexObject.bounds.topPt + latexObject.bounds.bottomPt) * 0.5f
                            onUpdateObject(
                                latexObject.transform(
                                    AffineTransform2D.scalingAround(0.87f, 0.87f, centerX, centerY)
                                ) as PageObject.LatexObject
                            )
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Kicsinyítés", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Képlet törlése",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DerivationCardNode(
    card: PageObject.DerivationCardObject,
    isOledMode: Boolean,
    isSelected: Boolean,
    onToggleCollapse: () -> Unit,
    onDelete: () -> Unit
) {
    val bgColor = if (isOledMode) Color(0xFF161B22) else Color(0xFFF6F8FA)
    val fgColor = if (isOledMode) Color(0xFFE6EDF3) else Color(0xFF1F2328)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF8B949E)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .border(1.2.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = fgColor,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleCollapse, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (card.isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = null,
                        tint = fgColor
                    )
                }
                if (isSelected) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Levezetés törlése",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (!card.isCollapsed) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = card.stepsMarkdownLatex,
                    style = MaterialTheme.typography.bodySmall,
                    color = fgColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
fun FunctionPlotNode(
    plot: PageObject.FunctionPlotObject,
    isOledMode: Boolean,
    isSelected: Boolean,
    onDelete: () -> Unit
) {
    val curveColor = remember(plot.canonicalColorArgb, isOledMode) {
        val argb = if (isOledMode) {
            ColorLuminanceInverter.invertArgbLuminance(plot.canonicalColorArgb)
        } else {
            plot.canonicalColorArgb
        }
        Color(argb)
    }
    val axisColor = if (isOledMode) Color(0xFFB0BAC5) else Color(0xFF4B5563)
    val gridColor = axisColor.copy(alpha = 0.22f)
    val cardBg = if (isOledMode) Color(0xFF0E1117).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.92f)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else axisColor.copy(alpha = 0.55f)

    val segments = remember(
        plot.expression,
        plot.domainMinX,
        plot.domainMaxX,
        plot.rangeMinY,
        plot.rangeMaxY
    ) {
        runCatching {
            val compiled = FunctionEvaluator.compile(plot.expression)
            FunctionEvaluator.sampleFunctionSegments(
                compiled = compiled,
                domainMinX = plot.domainMinX.toDouble(),
                domainMaxX = plot.domainMaxX.toDouble(),
                rangeMinY = plot.rangeMinY.toDouble(),
                rangeMaxY = plot.rangeMaxY.toDouble()
            )
        }.getOrDefault(emptyList())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cardBg, RoundedCornerShape(10.dp))
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clipToBounds()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val xSpan = (plot.domainMaxX - plot.domainMinX).coerceAtLeast(1e-3f)
            val ySpan = (plot.rangeMaxY - plot.rangeMinY).coerceAtLeast(1e-3f)

            if (plot.showGrid) {
                val divisions = 8
                for (k in 1 until divisions) {
                    val gx = size.width * (k.toFloat() / divisions)
                    val gy = size.height * (k.toFloat() / divisions)
                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                }
            }

            if (plot.domainMinX <= 0f && plot.domainMaxX >= 0f) {
                val xZero = ((0f - plot.domainMinX) / xSpan) * size.width
                drawLine(axisColor, Offset(xZero, 0f), Offset(xZero, size.height), strokeWidth = 1.8f)
            }
            if (plot.rangeMinY <= 0f && plot.rangeMaxY >= 0f) {
                val yZero = size.height - ((0f - plot.rangeMinY) / ySpan) * size.height
                drawLine(axisColor, Offset(0f, yZero), Offset(size.width, yZero), strokeWidth = 1.8f)
            }

            val composePath = Path()
            for (seg in segments) {
                if (seg.points.size < 2) continue
                for ((idx, pt) in seg.points.withIndex()) {
                    val sx = (((pt.x.toFloat() - plot.domainMinX) / xSpan) * size.width)
                    val sy = size.height - (((pt.y.toFloat() - plot.rangeMinY) / ySpan) * size.height)
                    if (idx == 0) composePath.moveTo(sx, sy) else composePath.lineTo(sx, sy)
                }
            }
            drawPath(
                path = composePath,
                color = curveColor,
                style = Stroke(
                    width = 3f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "f(x) = ${plot.expression}",
                style = MaterialTheme.typography.labelSmall,
                color = curveColor
            )
        }

        if (isSelected) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Grafikon törlése",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Interactive vector function plotter dialog supporting ESC and backdrop dismiss (UX Rule 11).
 */
@Composable
fun FunctionPlotterDialog(
    initialExpression: String = "sin(x)",
    onDismiss: () -> Unit,
    onInsertPlot: (expression: String, xMin: Float, xMax: Float, yMin: Float, yMax: Float) -> Unit
) {
    var expressionInput by remember(initialExpression) { mutableStateOf(initialExpression) }
    var xMinInput by remember { mutableStateOf("-6.28") }
    var xMaxInput by remember { mutableStateOf("6.28") }
    var yMinInput by remember { mutableStateOf("-3") }
    var yMaxInput by remember { mutableStateOf("3") }
    var errorHu by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Functions, contentDescription = null)
                            Text(
                                text = "Vektoros függvényrajzoló",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Bezárás")
                        }
                    }

                    OutlinedTextField(
                        value = expressionInput,
                        onValueChange = {
                            expressionInput = it
                            errorHu = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Képlet f(x) = ... (pl. 2x^2 - 3, sin(x)/x, 1/x)") }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = xMinInput,
                            onValueChange = { xMinInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("X min") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = xMaxInput,
                            onValueChange = { xMaxInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("X max") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = yMinInput,
                            onValueChange = { yMinInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Y min") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = yMaxInput,
                            onValueChange = { yMaxInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Y max") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }

                    errorHu?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = onDismiss) {
                            Text("Mégse")
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                val xMin = xMinInput.trim().replace(',', '.').toFloatOrNull()
                                val xMax = xMaxInput.trim().replace(',', '.').toFloatOrNull()
                                val yMin = yMinInput.trim().replace(',', '.').toFloatOrNull()
                                val yMax = yMaxInput.trim().replace(',', '.').toFloatOrNull()
                                if (xMin == null || xMax == null || yMin == null || yMax == null ||
                                    xMax <= xMin || yMax <= yMin
                                ) {
                                    errorHu = "Érvénytelen tartomány (min < max szükséges)."
                                    return@Button
                                }
                                try {
                                    FunctionEvaluator.compile(expressionInput)
                                    onInsertPlot(expressionInput.trim(), xMin, xMax, yMin, yMax)
                                    onDismiss()
                                } catch (e: FunctionParseException) {
                                    errorHu = e.message ?: "Hibás kifejezés."
                                }
                            }
                        ) {
                            Text("Beszúrás a lapra")
                        }
                    }
                }
            }
        }
    }
}

private val LATEX_COLOR_SWATCHES = listOf(
    BrushConfig.DEFAULT_INK_COLOR_ARGB,
    0xFF1D4ED8.toInt(),
    0xFF047857.toInt(),
    0xFFB91C1C.toInt()
)

