package hu.bdev.stylusnote.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke as InkStroke
import hu.bdev.stylusnote.core.ai.FunctionEvaluator
import hu.bdev.stylusnote.core.ai.FunctionParseException
import hu.bdev.stylusnote.core.ai.GeminiClient
import hu.bdev.stylusnote.core.ai.UserInitiatedAiTrigger
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.ColorLuminanceInverter
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.GeometricShapeRenderer
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.core.math.ShapeRecognizer
import hu.bdev.stylusnote.data.export.StrokeOutlineExtractor
import hu.bdev.stylusnote.data.local.StrokeBinarySerializer
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.theme.HandwritingFontFamily
import org.json.JSONObject
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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

sealed interface LassoAutoFormatResult {
    data class Success(
        val noticeHu: String,
        val createdObjectId: String? = null
    ) : LassoAutoFormatResult

    data class Failure(val messageHu: String) : LassoAutoFormatResult
}

data class PageFormatSummary(
    val formattedTextCount: Int,
    val formattedFormulaCount: Int,
    val formattedShapeCount: Int,
    val modelIdUsed: String? = null
)

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
            sourceStrokeIds = selectedStrokes.map { it.id },
            originalStrokes = selectedStrokes
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
    private const val LASSO_AUTO_FORMAT_SCHEMA =
        """{"type":"object","properties":{"kind":{"type":"string","enum":["formula","text","shape"]},"latex":{"type":"string"},"text":{"type":"string"},"shapeType":{"type":"string","enum":["line","rectangle","circle","ellipse","triangle","arrow"]}},"required":["kind"]}"""
    private const val PAGE_FORMAT_SCHEMA =
        """{"type":"object","properties":{"elements":{"type":"array","items":{"type":"object","properties":{"kind":{"type":"string","enum":["text","formula","shape"]},"box":{"type":"array","items":{"type":"number"}},"text":{"type":"string"},"latex":{"type":"string"},"shapeType":{"type":"string","enum":["line","rectangle","circle","ellipse","triangle","arrow"]}},"required":["kind","box"]}}},"required":["elements"]}"""

    private const val LASSO_FAST_EDGE_PX = 768

    /**
     * Quickly classifies and formats the active Lasso stroke selection:
     * 1) Runs <10 ms local geometric recognition via [ShapeRecognizer] on single strokes first;
     * 2) Falls back to a compact 768 px Gemini Flash JSON classification producing a
     *    [PageObject.LatexObject], a handwriting-styled [PageObject.FormattedTextObject], or a
     *    snapped vector shape.
     */
    suspend fun autoFormatLassoSelection(
        selectedStrokes: List<PersistedStrokeRecord>,
        geminiClient: GeminiClient,
        canvasStateHolder: CanvasStateHolder,
        context: Context? = null,
        journalSink: StrokeJournal? = null
    ): LassoAutoFormatResult {
        if (selectedStrokes.isEmpty()) {
            return LassoAutoFormatResult.Failure("Nincs kijelölt vonás.")
        }

        // Fast local path (<10 ms) when a single freehand stroke is already a clean geometric primitive.
        if (selectedStrokes.size == 1) {
            val single = selectedStrokes.first()
            val localShape = ShapeRecognizer.recognize(
                samples = single.samples,
                requireHoldAtEnd = false,
                minConfidence = 0.74f
            )
            if (localShape != null) {
                val snappedRecord = buildSnappedStrokeRecord(
                    templateRecord = single,
                    shapeMeta = localShape.shapeMeta,
                    snappedSamples = localShape.snappedSamples
                )
                val (snappedEntry, _) = StrokeBinarySerializer.toIndexedDryStroke(snappedRecord)
                canvasStateHolder.replaceStrokesWithSnappedShape(
                    strokeIds = listOf(single.id),
                    snappedStroke = snappedEntry.stroke,
                    shapeMeta = localShape.shapeMeta
                )
                journalSink?.appendRemoveStrokes(listOf(single.id))
                journalSink?.appendAddStrokes(listOf(snappedRecord))
                return LassoAutoFormatResult.Success(
                    noticeHu = "Alakzat kiegyenesítve (${localShape.shapeMeta.shapeType.displayName})."
                )
            }
        }

        val bitmap = renderStrokesToCanonicalLightBitmap(
            strokes = selectedStrokes,
            targetLongEdgePx = LASSO_FAST_EDGE_PX
        )
        val pngBytes = try {
            GeminiClient.prepareInlinePngBytes(bitmap, targetLongEdgePx = LASSO_FAST_EDGE_PX)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        val response = runCatching {
            geminiClient.generateContent(
                trigger = UserInitiatedAiTrigger.LASSO_AUTO_FORMAT,
                promptText = "Osztályozd a képen látható kijelölést (`formula`, `text`, vagy `shape`), " +
                    "majd add vissza a tisztázott eredményt JSON-ban. " +
                    "Ha `formula`: töltsd ki a `latex` mezőt pontos LaTeX-szel (határolók nélkül). " +
                    "Ha `text`: töltsd ki a `text` mezőt pontos magyar/angol szöveggel sortöréseket megőrizve. " +
                    "Ha `shape`: válassz `shapeType`-ot (`line`, `rectangle`, `circle`, `ellipse`, `triangle`, `arrow`).",
                inlinePngImages = listOf(pngBytes),
                responseMimeType = "application/json",
                responseSchemaJson = LASSO_AUTO_FORMAT_SCHEMA,
                systemInstruction = "Villámgyors jegyzet-digitalizáló asszisztens vagy. Mindig rövid, pontos JSON objektummal válaszolj."
            )
        }.getOrElse { err ->
            return LassoAutoFormatResult.Failure(err.message ?: "Sikertelen AI felismerés.")
        }

        val bounds = mergeStrokeDocRects(selectedStrokes)
        val strokeIds = selectedStrokes.map { it.id }
        val canonicalColor = selectedStrokes.first().canonicalColorArgb
        val json = runCatching { JSONObject(GeminiClient.stripMarkdownCodeFences(response.text)) }
            .getOrNull()
            ?: return LassoAutoFormatResult.Failure("Érvénytelen AI válaszformátum.")

        val kind = json.optString("kind").trim().lowercase()
        val latexRaw = json.optString("latex").trim()
        val textRaw = json.optString("text").trim()
        val shapeRaw = json.optString("shapeType").trim()

        return when (kind) {
            "shape" -> {
                val shapeType = parseRecognizedShapeType(shapeRaw) ?: RecognizedShapeType.RECTANGLE
                val meta = buildShapeMetaForBounds(shapeType, bounds)
                val tpl = selectedStrokes.first().samples.firstOrNull() ?: defaultTemplateSample(bounds)
                val snappedSamples = meta.generateSamples(tpl)
                val snappedRecord = buildSnappedStrokeRecord(selectedStrokes.first(), meta, snappedSamples)
                val (snappedEntry, _) = StrokeBinarySerializer.toIndexedDryStroke(snappedRecord)
                canvasStateHolder.replaceStrokesWithSnappedShape(
                    strokeIds = strokeIds,
                    snappedStroke = snappedEntry.stroke,
                    shapeMeta = meta
                )
                journalSink?.appendRemoveStrokes(strokeIds)
                journalSink?.appendAddStrokes(listOf(snappedRecord))
                LassoAutoFormatResult.Success("Alakzat formázva (${shapeType.displayName}).")
            }

            "formula", "latex" -> {
                val validation = LatexSyntaxValidator.validate(latexRaw, context)
                if (validation.isValid) {
                    val latexObj = PageObject.LatexObject(
                        id = java.util.UUID.randomUUID().toString(),
                        zOrder = canvasStateHolder.allocateObjectZOrder(),
                        bounds = bounds,
                        latexSource = validation.normalizedLatex,
                        fontSizePt = (bounds.heightPt * 0.65f).coerceIn(14f, 42f),
                        canonicalColorArgb = canonicalColor,
                        sourceStrokeIds = strokeIds,
                        originalStrokes = selectedStrokes
                    )
                    canvasStateHolder.replaceStrokesWithPageObject(strokeIds, latexObj)
                    journalSink?.appendRemoveStrokes(strokeIds)
                    journalSink?.appendAddObject(latexObj)
                    LassoAutoFormatResult.Success("Képlet LaTeX formátumra alakítva.", latexObj.id)
                } else {
                    val fallbackText = textRaw.ifBlank { latexRaw }
                    if (fallbackText.isBlank()) {
                        return LassoAutoFormatResult.Failure(
                            validation.errorMessageHu ?: "Nem sikerült felismerni a kijelölést."
                        )
                    }
                    val textObj = buildFormattedTextObject(
                        bounds = bounds,
                        text = fallbackText,
                        zOrder = canvasStateHolder.allocateObjectZOrder(),
                        canonicalColorArgb = canonicalColor,
                        sourceStrokeIds = strokeIds,
                        originalStrokes = selectedStrokes
                    )
                    canvasStateHolder.replaceStrokesWithPageObject(strokeIds, textObj)
                    journalSink?.appendRemoveStrokes(strokeIds)
                    journalSink?.appendAddObject(textObj)
                    LassoAutoFormatResult.Success("Szöveg rendezett kézírásos stílusra alakítva.", textObj.id)
                }
            }

            else -> {
                val cleanedText = textRaw.ifBlank { latexRaw }
                if (cleanedText.isBlank()) {
                    return LassoAutoFormatResult.Failure("Nem található felismerhető szöveg a kijelölésben.")
                }
                val textObj = buildFormattedTextObject(
                    bounds = bounds,
                    text = cleanedText,
                    zOrder = canvasStateHolder.allocateObjectZOrder(),
                    canonicalColorArgb = canonicalColor,
                    sourceStrokeIds = strokeIds,
                    originalStrokes = selectedStrokes
                )
                canvasStateHolder.replaceStrokesWithPageObject(strokeIds, textObj)
                journalSink?.appendRemoveStrokes(strokeIds)
                journalSink?.appendAddObject(textObj)
                LassoAutoFormatResult.Success("Szöveg rendezett kézírásos stílusra alakítva.", textObj.id)
            }
        }
    }

    /**
     * Formats all elements on a page using Vision AI (`formatText = true` converts handwritten prose
     * to [PageObject.FormattedTextObject], math to [PageObject.LatexObject], and diagrams/lines to
     * crisp vector shapes; `formatText = false` preserves handwritten prose and only formats formulas
     * and geometric figures).
     *
     * Pass [canvasStateHolder] when mutating the currently displayed page, or omit it to mutate a
     * closed workbook page directly through [journalSink].
     */
    suspend fun formatPageStrokesStructured(
        pageStrokes: List<PersistedStrokeRecord>,
        formatText: Boolean,
        geminiClient: GeminiClient,
        canvasStateHolder: CanvasStateHolder? = null,
        journalSink: StrokeJournal? = null,
        context: Context? = null,
        startingZOrder: Long = 1_000_000L,
        ocrLanguage: OcrLanguageTag = OcrLanguageTag.HUNGARIAN
    ): Result<PageFormatSummary> = runCatching {
        if (pageStrokes.isEmpty()) {
            return@runCatching PageFormatSummary(0, 0, 0)
        }
        executeStructuredFormattingPass(
            targetStrokes = pageStrokes,
            formatText = formatText,
            textOnlyOcr = false,
            ocrLanguage = ocrLanguage,
            geminiClient = geminiClient,
            canvasStateHolder = canvasStateHolder,
            journalSink = journalSink,
            context = context,
            startingZOrder = startingZOrder
        )
    }

    /**
     * Incremental whole-notebook OCR pass: converts only handwritten ink strokes that have NOT
     * yet been turned into [PageObject.FormattedTextObject] or [PageObject.LatexObject], respecting
     * the workbook's configured [ocrLanguage]. Already formatted blocks remain untouched.
     */
    suspend fun formatUnformattedStrokesInPage(
        pageStrokes: List<PersistedStrokeRecord>,
        existingObjects: List<PageObject>,
        ocrLanguage: OcrLanguageTag,
        geminiClient: GeminiClient,
        canvasStateHolder: CanvasStateHolder? = null,
        journalSink: StrokeJournal? = null,
        startingZOrder: Long = 1_000_000L
    ): Result<PageFormatSummary> = runCatching {
        val formattedObjects = existingObjects.filter {
            it is PageObject.FormattedTextObject || it is PageObject.LatexObject
        }
        val excludedStrokeIds = HashSet<String>()
        val excludedRects = ArrayList<DocRect>(formattedObjects.size)
        for (obj in formattedObjects) {
            excludedRects.add(obj.bounds.inflate(8f))
            when (obj) {
                is PageObject.FormattedTextObject -> excludedStrokeIds.addAll(obj.sourceStrokeIds)
                is PageObject.LatexObject -> excludedStrokeIds.addAll(obj.sourceStrokeIds)
                else -> Unit
            }
        }
        val unformattedStrokes = pageStrokes.filter { stroke ->
            stroke.layer != StrokeLayerType.HIGHLIGHTER &&
                !excludedStrokeIds.contains(stroke.id) &&
                excludedRects.none { rect ->
                    rect.contains(
                        (stroke.bounds.leftPt + stroke.bounds.rightPt) * 0.5f,
                        (stroke.bounds.topPt + stroke.bounds.bottomPt) * 0.5f
                    )
                }
        }
        if (unformattedStrokes.isEmpty()) {
            return@runCatching PageFormatSummary(0, 0, 0)
        }
        executeStructuredFormattingPass(
            targetStrokes = unformattedStrokes,
            formatText = true,
            textOnlyOcr = true,
            ocrLanguage = ocrLanguage,
            geminiClient = geminiClient,
            canvasStateHolder = canvasStateHolder,
            journalSink = journalSink,
            context = null,
            startingZOrder = startingZOrder
        )
    }

    private suspend fun executeStructuredFormattingPass(
        targetStrokes: List<PersistedStrokeRecord>,
        formatText: Boolean,
        textOnlyOcr: Boolean,
        ocrLanguage: OcrLanguageTag,
        geminiClient: GeminiClient,
        canvasStateHolder: CanvasStateHolder?,
        journalSink: StrokeJournal?,
        context: Context?,
        startingZOrder: Long
    ): PageFormatSummary {
        val frame = computeStrokeCanvasFrame(targetStrokes)
        val bitmap = renderStrokesToCanonicalLightBitmap(
            strokes = targetStrokes,
            targetLongEdgePx = GeminiClient.DEFAULT_INLINE_IMAGE_LONG_EDGE_PX
        )
        val pngBytes = try {
            GeminiClient.prepareInlinePngBytes(bitmap, targetLongEdgePx = GeminiClient.DEFAULT_INLINE_IMAGE_LONG_EDGE_PX)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        val modeInstruction = when {
            textOnlyOcr ->
                "Az oldalon látható kézzel írott szövegeket írd át pontosan ${ocrLanguage.displayName} (${ocrLanguage.bcp47Tag}) nyelven, " +
                    "kizárólag `text` elemekként! A rajzolt ábrákat, alakzatokat és kereteket hagyd figyelmen kívül! "
            formatText ->
                "Az oldalon látható ÖSSZES elemet azonosítsd (${ocrLanguage.displayName} / ${ocrLanguage.bcp47Tag} kontextusban): " +
                    "`text` (kézzel írt bekezdések/listák), `formula` (matematikai képletek LaTeX-ben), és `shape` (vonalak, nyilak, téglalapok, körök, háromszögek). "
            else ->
                "Az oldalon kizárólag a `formula` (matematikai képletek LaTeX-ben) és `shape` (ábrák, nyilak, téglalapok, körök, háromszögek, vonalak) elemeket add vissza; " +
                    "a sima kézírott folyószöveget hagyd figyelmen kívül! "
        }

        val response = geminiClient.generateContent(
            trigger = UserInitiatedAiTrigger.HANDWRITING_TO_MARKDOWN,
            promptText = modeInstruction +
                "Minden elemhez add meg a `box` [yMin, xMin, yMax, xMax] határoló dobozát 0..1000 skálán a képhez képest, " +
                "továbbá a megfelelő `text`, `latex` vagy `shapeType` mezőt.",
            inlinePngImages = listOf(pngBytes),
            responseMimeType = "application/json",
            responseSchemaJson = PAGE_FORMAT_SCHEMA,
            systemInstruction = "Precíz jegyzet-, kézírás- és ábrafelismerő vagy. Pontos 0..1000-es koordinátájú JSON-t adj vissza."
        )

        val root = JSONObject(GeminiClient.stripMarkdownCodeFences(response.text))
        val array = root.optJSONArray("elements")
            ?: return PageFormatSummary(0, 0, 0, response.modelIdUsed)

        val consumedStrokeIds = LinkedHashSet<String>()
        var nextZ = startingZOrder
        var textCount = 0
        var formulaCount = 0
        var shapeCount = 0

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val kind = item.optString("kind").trim().lowercase()
            if (textOnlyOcr && kind != "text") continue
            if (!formatText && kind == "text") continue

            val boxArr = item.optJSONArray("box") ?: continue
            if (boxArr.length() < 4) continue
            val yMin = boxArr.optDouble(0, 0.0).toFloat().coerceIn(0f, 1000f) / 1000f
            val xMin = boxArr.optDouble(1, 0.0).toFloat().coerceIn(0f, 1000f) / 1000f
            val yMax = boxArr.optDouble(2, 1000.0).toFloat().coerceIn(0f, 1000f) / 1000f
            val xMax = boxArr.optDouble(3, 1000.0).toFloat().coerceIn(0f, 1000f) / 1000f
            val rawBox = DocRect(
                leftPt = frame.leftPt + min(xMin, xMax) * frame.widthPt,
                topPt = frame.topPt + min(yMin, yMax) * frame.heightPt,
                rightPt = max(frame.leftPt + min(xMin, xMax) * frame.widthPt + 28f, frame.leftPt + max(xMin, xMax) * frame.widthPt),
                bottomPt = max(frame.topPt + min(yMin, yMax) * frame.heightPt + 24f, frame.topPt + max(yMin, yMax) * frame.heightPt)
            )

            val matchBox = rawBox.inflate(14f)
            val matchedStrokes = targetStrokes.filter { s ->
                !consumedStrokeIds.contains(s.id) &&
                    matchBox.contains((s.bounds.leftPt + s.bounds.rightPt) * 0.5f, (s.bounds.topPt + s.bounds.bottomPt) * 0.5f)
            }
            if (matchedStrokes.isEmpty() && array.length() > 1) continue

            val activeGroupStrokes = matchedStrokes.ifEmpty {
                targetStrokes.filter { !consumedStrokeIds.contains(it.id) }
            }
            if (activeGroupStrokes.isEmpty()) continue
            val targetIds = activeGroupStrokes.map { it.id }
            val tightBounds = mergeStrokeDocRects(activeGroupStrokes)
            val canonicalColor = activeGroupStrokes.first().canonicalColorArgb
            val zOrder = canvasStateHolder?.allocateObjectZOrder() ?: (++nextZ)

            when (kind) {
                "shape" -> {
                    val shapeType = parseRecognizedShapeType(item.optString("shapeType"))
                        ?: RecognizedShapeType.RECTANGLE
                    val meta = buildShapeMetaForBounds(shapeType, tightBounds)
                    val tpl = activeGroupStrokes.first().samples.firstOrNull() ?: defaultTemplateSample(tightBounds)
                    val snappedSamples = meta.generateSamples(tpl)
                    val snappedRecord = buildSnappedStrokeRecord(activeGroupStrokes.first(), meta, snappedSamples)
                    if (canvasStateHolder != null) {
                        val (snappedEntry, _) = StrokeBinarySerializer.toIndexedDryStroke(snappedRecord)
                        canvasStateHolder.replaceStrokesWithSnappedShape(targetIds, snappedEntry.stroke, meta)
                    }
                    journalSink?.appendRemoveStrokes(targetIds)
                    journalSink?.appendAddStrokes(listOf(snappedRecord))
                    consumedStrokeIds.addAll(targetIds)
                    shapeCount++
                }

                "formula", "latex" -> {
                    val rawLatex = item.optString("latex").trim()
                    val validation = LatexSyntaxValidator.validate(rawLatex, context)
                    if (validation.isValid) {
                        val obj = PageObject.LatexObject(
                            id = java.util.UUID.randomUUID().toString(),
                            zOrder = zOrder,
                            bounds = tightBounds,
                            latexSource = validation.normalizedLatex,
                            fontSizePt = (tightBounds.heightPt * 0.65f).coerceIn(14f, 42f),
                            canonicalColorArgb = canonicalColor,
                            sourceStrokeIds = targetIds,
                            originalStrokes = activeGroupStrokes
                        )
                        canvasStateHolder?.replaceStrokesWithPageObject(targetIds, obj)
                        journalSink?.appendRemoveStrokes(targetIds)
                        journalSink?.appendAddObject(obj)
                        consumedStrokeIds.addAll(targetIds)
                        formulaCount++
                    }
                }

                "text" -> if (formatText) {
                    val textVal = item.optString("text").trim()
                    if (textVal.isNotEmpty()) {
                        val obj = buildFormattedTextObject(
                            bounds = tightBounds,
                            text = textVal,
                            zOrder = zOrder,
                            canonicalColorArgb = canonicalColor,
                            sourceStrokeIds = targetIds,
                            originalStrokes = activeGroupStrokes
                        )
                        canvasStateHolder?.replaceStrokesWithPageObject(targetIds, obj)
                        journalSink?.appendRemoveStrokes(targetIds)
                        journalSink?.appendAddObject(obj)
                        consumedStrokeIds.addAll(targetIds)
                        textCount++
                    }
                }
            }
        }

        return PageFormatSummary(
            formattedTextCount = textCount,
            formattedFormulaCount = formulaCount,
            formattedShapeCount = shapeCount,
            modelIdUsed = response.modelIdUsed
        )
    }

    private fun computeStrokeCanvasFrame(strokes: List<PersistedStrokeRecord>): DocRect {
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
        return DocRect(originX, originY, originX + docW, originY + docH)
    }

    private fun buildFormattedTextObject(
        bounds: DocRect,
        text: String,
        zOrder: Long,
        canonicalColorArgb: Int,
        sourceStrokeIds: List<String>,
        originalStrokes: List<PersistedStrokeRecord> = emptyList()
    ): PageObject.FormattedTextObject {
        val lines = text.lines().size.coerceAtLeast(1)
        val rawLineHeightPt = bounds.heightPt / lines
        val estimatedPt = (rawLineHeightPt * 0.72f).coerceIn(11f, 56f)
        return PageObject.FormattedTextObject(
            id = java.util.UUID.randomUUID().toString(),
            zOrder = zOrder,
            bounds = bounds,
            text = text,
            fontSizePt = estimatedPt,
            canonicalColorArgb = canonicalColorArgb,
            sourceStrokeIds = sourceStrokeIds,
            originalStrokes = originalStrokes
        )
    }

    private fun parseRecognizedShapeType(raw: String): RecognizedShapeType? = when (raw.trim().lowercase()) {
        "line" -> RecognizedShapeType.LINE
        "arrow" -> RecognizedShapeType.ARROW
        "circle" -> RecognizedShapeType.CIRCLE
        "ellipse", "oval" -> RecognizedShapeType.ELLIPSE
        "triangle" -> RecognizedShapeType.TRIANGLE
        "rectangle", "rect", "square", "box" -> RecognizedShapeType.RECTANGLE
        else -> null
    }

    private fun buildShapeMetaForBounds(shapeType: RecognizedShapeType, bounds: DocRect): ShapeMeta {
        val cx = (bounds.leftPt + bounds.rightPt) * 0.5f
        val cy = (bounds.topPt + bounds.bottomPt) * 0.5f
        val pts: List<DocPoint> = when (shapeType) {
            RecognizedShapeType.LINE, RecognizedShapeType.ARROW -> {
                if (bounds.widthPt >= bounds.heightPt * 0.6f) {
                    listOf(DocPoint(bounds.leftPt, cy), DocPoint(bounds.rightPt, cy))
                } else {
                    listOf(DocPoint(cx, bounds.topPt), DocPoint(cx, bounds.bottomPt))
                }
            }
            RecognizedShapeType.CIRCLE -> {
                val r = (min(bounds.widthPt, bounds.heightPt) * 0.5f).coerceAtLeast(10f)
                listOf(DocPoint(cx, cy), DocPoint(cx + r, cy))
            }
            RecognizedShapeType.ELLIPSE -> listOf(
                DocPoint(cx, cy),
                DocPoint(bounds.rightPt, cy),
                DocPoint(cx, bounds.bottomPt)
            )
            RecognizedShapeType.TRIANGLE -> listOf(
                DocPoint(cx, bounds.topPt),
                DocPoint(bounds.rightPt, bounds.bottomPt),
                DocPoint(bounds.leftPt, bounds.bottomPt)
            )
            RecognizedShapeType.RECTANGLE -> listOf(
                DocPoint(bounds.leftPt, bounds.topPt),
                DocPoint(bounds.rightPt, bounds.topPt),
                DocPoint(bounds.rightPt, bounds.bottomPt),
                DocPoint(bounds.leftPt, bounds.bottomPt)
            )
        }
        return ShapeMeta(shapeType = shapeType, controlPoints = pts, rotationRadians = 0f)
    }

    private fun defaultTemplateSample(bounds: DocRect) = StylusPointSample(
        xPt = bounds.leftPt,
        yPt = bounds.topPt,
        elapsedTimeMillis = 0L,
        pressure = 0.6f,
        tiltRadians = 0f,
        orientationRadians = 0f,
        isEraserTool = false
    )

    private fun buildSnappedStrokeRecord(
        templateRecord: PersistedStrokeRecord,
        shapeMeta: ShapeMeta,
        snappedSamples: List<StylusPointSample>
    ): PersistedStrokeRecord {
        val newId = java.util.UUID.randomUUID().toString()
        val inkInputs = runCatching {
            snappedSamples.toStrokeInputBatch(InputToolType.STYLUS)
        }.getOrNull()
        val encodedInputs = inkInputs?.let { batch ->
            runCatching {
                val inkStroke = InkStroke(
                    brush = templateRecord.brushConfig.toInkBrush(templateRecord.canonicalColorArgb),
                    inputs = batch
                )
                StrokeBinarySerializer.fromIndexedDryStroke(
                    entry = IndexedDryStroke(
                        id = newId,
                        stroke = inkStroke,
                        bounds = mergeSamplesBounds(snappedSamples),
                        zIndex = templateRecord.zOrder,
                        isHighlighter = templateRecord.layer == hu.bdev.stylusnote.domain.model.StrokeLayerType.HIGHLIGHTER
                    ),
                    brushConfig = templateRecord.brushConfig,
                    shapeMeta = shapeMeta
                )
            }.getOrNull()
        }
        return encodedInputs ?: templateRecord.copy(
            id = newId,
            inkEncodedInputs = null,
            samples = snappedSamples,
            bounds = mergeSamplesBounds(snappedSamples),
            shapeMeta = shapeMeta
        )
    }

    private fun mergeSamplesBounds(samples: List<StylusPointSample>): DocRect {
        if (samples.isEmpty()) return DocRect(0f, 0f, 24f, 24f)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (s in samples) {
            minX = min(minX, s.xPt)
            minY = min(minY, s.yPt)
            maxX = max(maxX, s.xPt)
            maxY = max(maxY, s.yPt)
        }
        return DocRect(minX, minY, max(maxX, minX + 4f), max(maxY, minY + 4f))
    }
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
    onRestoreOriginalStrokes: (PageObject) -> Unit = {},
    lassoPreviewObjectIds: Set<String> = emptySet(),
    lassoPreviewDeltaXPt: Float = 0f,
    lassoPreviewDeltaYPt: Float = 0f,
    lassoPreviewRotationRadians: Float = 0f,
    lassoPreviewRotationCenter: DocPoint? = null,
    viewportTransformTick: Long = 0L,
    modifier: Modifier = Modifier
) {
    // Reading viewportTransformTick forces Compose to track pan/zoom changes on the paper so objects stick 1:1.
    viewportTransformTick
    val snapshot = transformMatrix.snapshot()
    val scale = snapshot.scale.coerceAtLeast(0.1f)
    val currentScale by rememberUpdatedState(scale)
    var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayCoords = it }
    ) {
        val liveLassoAffine = remember(
            lassoPreviewDeltaXPt,
            lassoPreviewDeltaYPt,
            lassoPreviewRotationRadians,
            lassoPreviewRotationCenter
        ) {
            when {
                abs(lassoPreviewRotationRadians) > 1e-4f && lassoPreviewRotationCenter != null ->
                    AffineTransform2D.rotationAround(
                        lassoPreviewRotationRadians,
                        lassoPreviewRotationCenter.xPt,
                        lassoPreviewRotationCenter.yPt
                    )
                abs(lassoPreviewDeltaXPt) > 1e-4f || abs(lassoPreviewDeltaYPt) > 1e-4f ->
                    AffineTransform2D.translation(lassoPreviewDeltaXPt, lassoPreviewDeltaYPt)
                else -> null
            }
        }

        for (rawObj in pageObjects) {
            if (rawObj is PageObject.PdfPageRefObject) continue
            val obj = if (liveLassoAffine != null && rawObj.id in lassoPreviewObjectIds) {
                rawObj.transform(liveLassoAffine)
            } else {
                rawObj
            }

            val screenTopLeft = transformMatrix.docToScreen(obj.bounds.leftPt, obj.bounds.topPt)
            val widthPx = (obj.bounds.widthPt * scale).coerceAtLeast(36f)
            val heightPx = (obj.bounds.heightPt * scale).coerceAtLeast(28f)
            val isSelected = (obj.id == selectedObjectId)

            val density = LocalDensity.current
            val widthDp = with(density) { widthPx.toDp() }
            val heightDp = with(density) { heightPx.toDp() }
            val latestObj by rememberUpdatedState(obj)

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = screenTopLeft.xPx.roundToInt(),
                            y = screenTopLeft.yPx.roundToInt()
                        )
                    }
                    .size(width = widthDp, height = heightDp)
                    // Unselected objects pass all stylus/touch input directly through to the canvas below;
                    // objects only accept drag gestures while explicitly selected (post-insert or via Lasso).
                    .then(
                        if (isSelected) {
                            Modifier.pointerInput(obj.id) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val dxDoc = dragAmount.x / currentScale
                                        val dyDoc = dragAmount.y / currentScale
                                        onUpdateObject(
                                            latestObj.transform(AffineTransform2D.translation(dxDoc, dyDoc))
                                        )
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                when (obj) {
                    is PageObject.LatexObject -> LatexOverlayNode(
                        latexObject = obj,
                        scale = scale,
                        isOledMode = isOledMode,
                        isSelected = isSelected,
                        onUpdateObject = onUpdateObject,
                        onDelete = { onDeleteObject(obj.id) },
                        onRequestStepByStep = { onRequestStepByStep(obj) }
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

                    is PageObject.FormattedTextObject -> FormattedTextOverlayNode(
                        textObject = obj,
                        scale = scale,
                        isOledMode = isOledMode,
                        isSelected = isSelected
                    )

                    is PageObject.GeometricShapeObject -> GeometricShapeOverlayNode(
                        shapeObject = obj,
                        scale = scale,
                        isOledMode = isOledMode,
                        isSelected = isSelected
                    )

                    is PageObject.ImageObject -> ImageOverlayNode(
                        imageObject = obj,
                        isSelected = isSelected
                    )
                }
            }

            val boxRotationRadians = when (obj) {
                is PageObject.GeometricShapeObject -> obj.rotationRadians
                is PageObject.ImageObject -> obj.rotationRadians
                else -> null
            }
            if (isSelected && boxRotationRadians != null) {
                RotatableBoxInteractiveHandles(
                    objectId = obj.id,
                    bounds = obj.bounds,
                    rotationRadians = boxRotationRadians,
                    transformMatrix = transformMatrix,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    overlayCoordsProvider = { overlayCoords },
                    onResizeBounds = { newBounds ->
                        val updated = when (val cur = latestObj) {
                            is PageObject.GeometricShapeObject -> cur.copy(bounds = newBounds)
                            is PageObject.ImageObject -> cur.copy(bounds = newBounds)
                            else -> cur
                        }
                        onUpdateObject(updated)
                    },
                    onRotateRadians = { newAngle ->
                        val updated = when (val cur = latestObj) {
                            is PageObject.GeometricShapeObject -> cur.copy(rotationRadians = newAngle)
                            is PageObject.ImageObject -> cur.copy(rotationRadians = newAngle)
                            else -> cur
                        }
                        onUpdateObject(updated)
                    }
                )
            }

            if (
                isSelected &&
                (obj is PageObject.LatexObject ||
                    obj is PageObject.FormattedTextObject ||
                    obj is PageObject.GeometricShapeObject ||
                    obj is PageObject.ImageObject)
            ) {
                FloatingObjectActionPill(
                    obj = obj,
                    screenTopLeft = screenTopLeft,
                    heightPx = heightPx,
                    onUpdateObject = onUpdateObject,
                    onDeleteObject = { onDeleteObject(obj.id) },
                    onRestoreOriginalStrokes = { onRestoreOriginalStrokes(obj) },
                    onDeselect = { onSelectObject(null) },
                    onRequestStepByStep = onRequestStepByStep
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatingObjectActionPill(
    obj: PageObject,
    screenTopLeft: hu.bdev.stylusnote.core.math.ScreenPoint,
    heightPx: Float,
    onUpdateObject: (PageObject) -> Unit,
    onDeleteObject: () -> Unit,
    onRestoreOriginalStrokes: () -> Unit,
    onDeselect: () -> Unit,
    onRequestStepByStep: (PageObject.LatexObject) -> Unit
) {
    val canRestoreOriginal =
        (obj is PageObject.LatexObject && obj.originalStrokes.isNotEmpty()) ||
            (obj is PageObject.FormattedTextObject && obj.originalStrokes.isNotEmpty())
    Surface(
        modifier = Modifier.offset {
            IntOffset(
                x = screenTopLeft.xPx.roundToInt().coerceAtLeast(8),
                y = (screenTopLeft.yPx + heightPx + 8f).roundToInt().coerceAtLeast(8)
            )
        },
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (canRestoreOriginal) {
                TextButton(onClick = onRestoreOriginalStrokes) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Eredeti")
                }
            }

            if (obj is PageObject.LatexObject) {
                TextButton(onClick = { onRequestStepByStep(obj) }) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Levezetés")
                }
            }

            if (obj is PageObject.GeometricShapeObject) {
                TextButton(
                    onClick = { onUpdateObject(obj.copy(hasFill = !obj.hasFill)) }
                ) {
                    Text(if (obj.hasFill) "Kitöltés: Be" else "Kitöltés: Ki")
                }
            }

            if (
                obj is PageObject.LatexObject ||
                obj is PageObject.FormattedTextObject ||
                obj is PageObject.GeometricShapeObject
            ) {
                val currentColor = when (obj) {
                    is PageObject.LatexObject -> obj.canonicalColorArgb
                    is PageObject.FormattedTextObject -> obj.canonicalColorArgb
                    is PageObject.GeometricShapeObject -> obj.canonicalStrokeColorArgb
                    else -> BrushConfig.DEFAULT_INK_COLOR_ARGB
                }
                LATEX_COLOR_SWATCHES.forEach { swatchArgb ->
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(swatchArgb))
                            .border(
                                width = if (currentColor == swatchArgb) 2.dp else 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable {
                                when (obj) {
                                    is PageObject.LatexObject -> onUpdateObject(obj.copy(canonicalColorArgb = swatchArgb))
                                    is PageObject.FormattedTextObject -> onUpdateObject(obj.copy(canonicalColorArgb = swatchArgb))
                                    is PageObject.GeometricShapeObject -> onUpdateObject(
                                        obj.copy(
                                            canonicalStrokeColorArgb = swatchArgb,
                                            canonicalFillColorArgb = (swatchArgb and 0x00FFFFFF) or 0x28000000
                                        )
                                    )
                                    else -> Unit
                                }
                            }
                    )
                }
            }

            IconButton(
                onClick = {
                    when (obj) {
                        is PageObject.LatexObject -> {
                            val cx = (obj.bounds.leftPt + obj.bounds.rightPt) * 0.5f
                            val cy = (obj.bounds.topPt + obj.bounds.bottomPt) * 0.5f
                            onUpdateObject(obj.transform(AffineTransform2D.scalingAround(1.15f, 1.15f, cx, cy)))
                        }
                        is PageObject.FormattedTextObject -> {
                            onUpdateObject(obj.copy(fontSizePt = (obj.fontSizePt + 2f).coerceAtMost(72f)))
                        }
                        is PageObject.GeometricShapeObject,
                        is PageObject.ImageObject -> {
                            val cx = obj.bounds.centerXPt
                            val cy = obj.bounds.centerYPt
                            onUpdateObject(obj.transform(AffineTransform2D.scalingAround(1.15f, 1.15f, cx, cy)))
                        }
                        else -> Unit
                    }
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nagyítás", modifier = Modifier.size(16.dp))
            }

            IconButton(
                onClick = {
                    when (obj) {
                        is PageObject.LatexObject -> {
                            val cx = (obj.bounds.leftPt + obj.bounds.rightPt) * 0.5f
                            val cy = (obj.bounds.topPt + obj.bounds.bottomPt) * 0.5f
                            onUpdateObject(obj.transform(AffineTransform2D.scalingAround(0.87f, 0.87f, cx, cy)))
                        }
                        is PageObject.FormattedTextObject -> {
                            onUpdateObject(obj.copy(fontSizePt = (obj.fontSizePt - 2f).coerceAtLeast(10f)))
                        }
                        is PageObject.GeometricShapeObject,
                        is PageObject.ImageObject -> {
                            val cx = obj.bounds.centerXPt
                            val cy = obj.bounds.centerYPt
                            onUpdateObject(obj.transform(AffineTransform2D.scalingAround(0.87f, 0.87f, cx, cy)))
                        }
                        else -> Unit
                    }
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Kicsinyítés", modifier = Modifier.size(16.dp))
            }

            if (obj is PageObject.GeometricShapeObject || obj is PageObject.ImageObject) {
                IconButton(
                    onClick = {
                        val stepRad = (15.0 * PI / 180.0).toFloat()
                        val updated = when (obj) {
                            is PageObject.GeometricShapeObject -> obj.copy(rotationRadians = obj.rotationRadians + stepRad)
                            is PageObject.ImageObject -> obj.copy(rotationRadians = obj.rotationRadians + stepRad)
                            else -> obj
                        }
                        onUpdateObject(updated)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Forgatás +15°", modifier = Modifier.size(16.dp))
                }
            }

            IconButton(onClick = onDeleteObject, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Törlés",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(onClick = onDeselect, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Kijelölés levétele",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
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
    onRequestStepByStep: () -> Unit
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
            .graphicsLayer {
                if (abs(latexObject.rotationRadians) > 1e-4f) {
                    rotationZ = (latexObject.rotationRadians * 180.0 / PI).toFloat()
                }
            }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.2.dp, borderColor, RoundedCornerShape(12.dp))
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
                if (isSelected) {
                    IconButton(onClick = onToggleCollapse, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (card.isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = null,
                            tint = fgColor
                        )
                    }
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

private val LATEX_COLOR_SWATCHES = listOf(
    BrushConfig.DEFAULT_INK_COLOR_ARGB,
    0xFF1D4ED8.toInt(),
    0xFF047857.toInt(),
    0xFFB91C1C.toInt()
)

@Composable
private fun FormattedTextOverlayNode(
    textObject: PageObject.FormattedTextObject,
    scale: Float,
    isOledMode: Boolean,
    isSelected: Boolean
) {
    val effectiveColorArgb = remember(textObject.canonicalColorArgb, isOledMode) {
        if (isOledMode) {
            ColorLuminanceInverter.invertArgbLuminance(textObject.canonicalColorArgb)
        } else {
            textObject.canonicalColorArgb
        }
    }
    val density = LocalDensity.current
    val scaledFontSp = with(density) {
        (textObject.fontSizePt * scale).coerceAtLeast(8f).toSp()
    }

    Box(
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
            .graphicsLayer {
                if (abs(textObject.rotationRadians) > 1e-4f) {
                    rotationZ = (textObject.rotationRadians * 180.0 / PI).toFloat()
                }
            },
        contentAlignment = Alignment.TopStart
    ) {
        Text(
            text = textObject.text,
            color = Color(effectiveColorArgb),
            fontFamily = HandwritingFontFamily,
            fontSize = scaledFontSp,
            lineHeight = scaledFontSp * 1.2f
        )
    }
}

@Composable
private fun GeometricShapeOverlayNode(
    shapeObject: PageObject.GeometricShapeObject,
    scale: Float,
    isOledMode: Boolean,
    isSelected: Boolean
) {
    val effectiveStrokeArgb = remember(shapeObject.canonicalStrokeColorArgb, isOledMode) {
        if (isOledMode) {
            ColorLuminanceInverter.invertArgbLuminance(shapeObject.canonicalStrokeColorArgb)
        } else {
            shapeObject.canonicalStrokeColorArgb
        }
    }
    val effectiveFillArgb = remember(shapeObject.canonicalFillColorArgb, isOledMode, effectiveStrokeArgb) {
        if (isOledMode) {
            (effectiveStrokeArgb and 0x00FFFFFF) or 0x2E000000
        } else {
            shapeObject.canonicalFillColorArgb
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = (shapeObject.strokeWidthPt * scale).coerceIn(1.2f, 24f)
            GeometricShapeRenderer.drawShape(
                canvas = drawContext.canvas.nativeCanvas,
                shapeType = shapeObject.shapeType,
                bounds = android.graphics.RectF(0f, 0f, size.width, size.height),
                strokeWidthPx = strokePx,
                strokeColorArgb = effectiveStrokeArgb,
                hasFill = shapeObject.hasFill,
                fillColorArgb = effectiveFillArgb,
                rotationRadians = shapeObject.rotationRadians
            )
            if (isSelected) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val angleDeg = (shapeObject.rotationRadians * 180.0 / PI).toFloat()
                rotate(degrees = angleDeg, pivot = Offset(cx, cy)) {
                    drawRect(
                        color = Color(0xFF1A56DB),
                        style = Stroke(
                            width = 1.6.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(10f, 8f),
                                0f
                            )
                        )
                    )
                }
            }
        }
    }
}

private object PageImageBitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).toInt().coerceAtLeast(1)
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

@Composable
private fun ImageOverlayNode(
    imageObject: PageObject.ImageObject,
    isSelected: Boolean
) {
    val context = LocalContext.current
    val cacheKey = "${context.filesDir.absolutePath}::${imageObject.imageAssetRelativePath}"
    val loadedBitmap by produceState<ImageBitmap?>(
        initialValue = PageImageBitmapCache.get(cacheKey),
        key1 = cacheKey
    ) {
        if (value != null) return@produceState
        val decoded = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, imageObject.imageAssetRelativePath)
            if (!file.isFile) return@withContext null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sampleSize = 1
            val maxEdge = maxOf(opts.outWidth, opts.outHeight)
            while (maxEdge / sampleSize > 1600) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)?.asImageBitmap()
        }
        if (decoded != null) {
            PageImageBitmapCache.put(cacheKey, decoded)
        }
        value = decoded
    }

    val angleDeg = (imageObject.rotationRadians * 180.0 / PI).toFloat()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = angleDeg }
    ) {
        val bmp = loadedBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Beszúrt kép",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Kép betöltése...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isSelected) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color(0xFF1A56DB),
                    style = Stroke(
                        width = 1.6.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(10f, 8f),
                            0f
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun RotatableBoxInteractiveHandles(
    objectId: String,
    bounds: DocRect,
    rotationRadians: Float,
    transformMatrix: CanvasTransformMatrix,
    widthPx: Float,
    heightPx: Float,
    overlayCoordsProvider: () -> LayoutCoordinates?,
    onResizeBounds: (DocRect) -> Unit,
    onRotateRadians: (Float) -> Unit
) {
    val latestBounds by rememberUpdatedState(bounds)
    val latestRotation by rememberUpdatedState(rotationRadians)
    val density = LocalDensity.current
    val handleBoxPx = with(density) { 28.dp.toPx() }
    val halfHandlePx = handleBoxPx * 0.5f
    val stemGapPx = with(density) { 28.dp.toPx() }

    val centerScreen = transformMatrix.docToScreen(
        bounds.centerXPt,
        bounds.centerYPt
    )
    val c = cos(rotationRadians)
    val s = sin(rotationRadians)

    fun rotatedOffset(lx: Float, ly: Float): Offset = Offset(
        x = centerScreen.xPx + lx * c - ly * s,
        y = centerScreen.yPx + lx * s + ly * c
    )

    val halfW = widthPx * 0.5f
    val halfH = heightPx * 0.5f

    // 4 corner resize handles
    val corners = listOf(
        -1f to -1f,
        +1f to -1f,
        +1f to +1f,
        -1f to +1f
    )
    corners.forEach { (sx, sy) ->
        val cornerCenter = rotatedOffset(sx * halfW, sy * halfH)
        var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (cornerCenter.x - halfHandlePx).roundToInt(),
                        y = (cornerCenter.y - halfHandlePx).roundToInt()
                    )
                }
                .size(28.dp)
                .onGloballyPositioned { handleCoords = it }
                .pointerInput(objectId, sx, sy) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val oCoords = overlayCoordsProvider() ?: return@detectDragGestures
                        val hCoords = handleCoords ?: return@detectDragGestures
                        val overlayPt = oCoords.localPositionOf(hCoords, change.position)
                        val curBounds = latestBounds
                        val rot = latestRotation
                        val cosR = cos(rot)
                        val sinR = sin(rot)
                        // Pin opposite corner in document space so resizing feels anchored
                        val oppLocalX = -sx * curBounds.widthPt * 0.5f
                        val oppLocalY = -sy * curBounds.heightPt * 0.5f
                        val oppDocX = curBounds.centerXPt + oppLocalX * cosR - oppLocalY * sinR
                        val oppDocY = curBounds.centerYPt + oppLocalX * sinR + oppLocalY * cosR

                        val fingerDoc = transformMatrix.screenToDoc(overlayPt.x, overlayPt.y)
                        val vx = fingerDoc.xPt - oppDocX
                        val vy = fingerDoc.yPt - oppDocY
                        val newW = ((vx * cosR + vy * sinR) * sx).coerceAtLeast(24f)
                        val newH = ((-vx * sinR + vy * cosR) * sy).coerceAtLeast(24f)
                        val cLocalX = sx * newW * 0.5f
                        val cLocalY = sy * newH * 0.5f
                        val newCx = oppDocX + cLocalX * cosR - cLocalY * sinR
                        val newCy = oppDocY + cLocalX * sinR + cLocalY * cosR

                        onResizeBounds(
                            DocRect(
                                leftPt = newCx - newW * 0.5f,
                                topPt = newCy - newH * 0.5f,
                                rightPt = newCx + newW * 0.5f,
                                bottomPt = newCy + newH * 0.5f
                            )
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0xFF1A56DB), CircleShape)
            )
        }
    }

    // Top-center free-rotation knob + connecting stem
    val topMid = rotatedOffset(0f, -halfH)
    val rotKnobCenter = rotatedOffset(0f, -halfH - stemGapPx)
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(
            color = Color(0xFF1A56DB),
            start = topMid,
            end = rotKnobCenter,
            strokeWidth = 1.6.dp.toPx()
        )
    }
    var rotHandleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (rotKnobCenter.x - halfHandlePx).roundToInt(),
                    y = (rotKnobCenter.y - halfHandlePx).roundToInt()
                )
            }
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.8.dp, Color(0xFF1A56DB), CircleShape)
            .onGloballyPositioned { rotHandleCoords = it }
            .pointerInput(objectId) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val oCoords = overlayCoordsProvider() ?: return@detectDragGestures
                    val hCoords = rotHandleCoords ?: return@detectDragGestures
                    val overlayPt = oCoords.localPositionOf(hCoords, change.position)
                    val curBounds = latestBounds
                    val curCenter = transformMatrix.docToScreen(
                        curBounds.centerXPt,
                        curBounds.centerYPt
                    )
                    val angle = atan2(
                        overlayPt.y - curCenter.yPx,
                        overlayPt.x - curCenter.xPx
                    ) + (PI.toFloat() * 0.5f)
                    onRotateRadians(angle)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.RotateRight,
            contentDescription = "Objektum forgatása",
            tint = Color(0xFF1A56DB),
            modifier = Modifier.size(16.dp)
        )
    }
}


