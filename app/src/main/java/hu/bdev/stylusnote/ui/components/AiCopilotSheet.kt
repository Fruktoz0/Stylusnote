package hu.bdev.stylusnote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.bdev.stylusnote.core.ai.AiConsentState
import hu.bdev.stylusnote.core.ai.GeminiClient
import hu.bdev.stylusnote.core.ai.GeminiJsonCodec
import hu.bdev.stylusnote.core.ai.UserInitiatedAiTrigger
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.ui.canvas.HandwritingToLatexConverter
import hu.bdev.stylusnote.ui.theme.StylusIcons
import kotlinx.coroutines.launch

data class ExamFlashcard(
    val front: String,
    val back: String,
    val topicTag: String = ""
)

data class ExamQuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String = ""
)

data class ExamStudyPack(
    val title: String,
    val flashcards: List<ExamFlashcard>,
    val questions: List<ExamQuizQuestion>
)

data class MagicCircleExplanation(
    val title: String,
    val summaryMarkdown: String,
    val examples: List<String>
)

/**
 * Pure-Kotlin JSON parsers for Gemini Copilot structured outputs (`ExamStudyPack` &
 * `MagicCircleExplanation`) using [GeminiJsonCodec] so JVM unit tests run without `org.json`.
 */
object AiCopilotParsers {

    fun parseExamPackJson(rawOrFencedJson: String): ExamStudyPack {
        val cleaned = GeminiClient.stripMarkdownCodeFences(rawOrFencedJson)
        val root = GeminiJsonCodec.parseObject(cleaned)
            ?: throw IllegalArgumentException("Érvénytelen JSON vizsgacsomag érkezett a Geminitől.")

        val title = root["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Vizsgafelkészítő csomag"

        val flashcards = (root["flashcards"] as? List<*>)
            ?.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val front = obj["front"]?.toString()?.trim().orEmpty()
                val back = obj["back"]?.toString()?.trim().orEmpty()
                if (front.isEmpty() || back.isEmpty()) return@mapNotNull null
                val tag = obj["topicTag"]?.toString()?.trim().orEmpty()
                ExamFlashcard(front = front, back = back, topicTag = tag)
            }
            .orEmpty()

        val questions = (root["questions"] as? List<*>)
            ?.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val question = obj["question"]?.toString()?.trim().orEmpty()
                val options = (obj["options"] as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .orEmpty()
                if (question.isEmpty() || options.size < 2) return@mapNotNull null
                val rawIdx = (obj["correctIndex"] as? Number)?.toInt() ?: 0
                val safeIdx = rawIdx.coerceIn(0, options.lastIndex)
                val explanation = obj["explanation"]?.toString()?.trim().orEmpty()
                ExamQuizQuestion(
                    question = question,
                    options = options,
                    correctIndex = safeIdx,
                    explanation = explanation
                )
            }
            .orEmpty()

        return ExamStudyPack(title = title, flashcards = flashcards, questions = questions)
    }

    fun parseMagicCircleJson(rawOrFencedJson: String): MagicCircleExplanation {
        val cleaned = GeminiClient.stripMarkdownCodeFences(rawOrFencedJson)
        val root = GeminiJsonCodec.parseObject(cleaned)
            ?: throw IllegalArgumentException("Érvénytelen Magic Circle JSON válasz.")

        val title = root["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Magyarázat"
        val summary = root["summaryMarkdown"]?.toString()?.trim()
            ?: root["summary"]?.toString()?.trim()
            ?: ""
        val examples = (root["examples"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()

        return MagicCircleExplanation(
            title = title,
            summaryMarkdown = summary,
            examples = examples
        )
    }
}

/**
 * Coordinates explicit user-triggered AI Copilot actions (Section 7):
 * 1. Batched Handwriting Pages -> Structured Markdown
 * 2. Magic Circle ("Magyarázd el") Contextual Concept/Diagram Bubble
 * 3. Exam Mode (OCR text + rendered pages -> Flashcards & Quiz JSON)
 */
object AiCopilotController {

    suspend fun convertPagesToStructuredMarkdown(
        pageStrokeBatches: List<List<PersistedStrokeRecord>>,
        notebookTitle: String,
        geminiClient: GeminiClient
    ): Result<String> = runCatching {
        val pngImages = encodeLightModePageImages(pageStrokeBatches, maxPages = 6)
        require(pngImages.isNotEmpty()) {
            "Legalább egy nem üres oldal szükséges a Markdown összefoglalóhoz."
        }

        val response = geminiClient.generateContent(
            trigger = UserInitiatedAiTrigger.HANDWRITING_TO_MARKDOWN,
            promptText = "Készíts tiszta, jól strukturált magyar Markdown jegyzetet a '$notebookTitle' " +
                "füzet beküldött kézírásos oldalai alapján. Használj címsorokat (#, ##), listákat, " +
                "kiemeléseket és ha van matematikai képlet, írd LaTeX `$...$` blokkba.",
            inlinePngImages = pngImages,
            systemInstruction = "Szakértő egyetemi jegyzetszerkesztő vagy. Tömör, áttekinthető Markdown-t adj vissza."
        )
        GeminiClient.stripMarkdownCodeFences(response.text)
    }

    suspend fun explainWithMagicCircle(
        circledStrokes: List<PersistedStrokeRecord>,
        geminiClient: GeminiClient
    ): Result<MagicCircleExplanation> = runCatching {
        val pngImages = encodeLightModePageImages(listOf(circledStrokes), maxPages = 1)
        require(pngImages.isNotEmpty()) {
            "A bekarikázott terület nem tartalmaz feldolgozható vonásokat."
        }

        val response = geminiClient.generateContent(
            trigger = UserInitiatedAiTrigger.MAGIC_CIRCLE_EXPLAIN,
            promptText = "Magyarázd el közérthetően magyarul a képen bekarikázott fogalmat, képletet " +
                "vagy ábrát, adj 1-2 konkrét példát is. Kizárólag JSON objektumban válaszolj.",
            inlinePngImages = pngImages,
            responseMimeType = "application/json",
            responseSchemaJson = MAGIC_CIRCLE_SCHEMA,
            systemInstruction = "Egyértelmű, diákbarát magyar oktató vagy."
        )
        AiCopilotParsers.parseMagicCircleJson(response.text)
    }

    suspend fun generateExamStudyPack(
        notebookTitle: String,
        ocrTexts: List<String>,
        pageStrokeBatches: List<List<PersistedStrokeRecord>>,
        geminiClient: GeminiClient
    ): Result<ExamStudyPack> = runCatching {
        val pngImages = encodeLightModePageImages(pageStrokeBatches, maxPages = 4)
        val combinedOcr = ocrTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n---\n")
            .take(8_000)

        require(pngImages.isNotEmpty() || combinedOcr.isNotEmpty()) {
            "Nincs elég jegyzettartalom (OCR szöveg vagy rajzolt oldal) a vizsga generálásához."
        }

        val prompt = buildString {
            append("Készíts magyar nyelvű vizsgacsomagot a '$notebookTitle' anyagából ")
            append("4-6 tanulókártyával (flashcards) és 3-5 négyválaszos feleletválasztós kérdéssel (questions). ")
            if (combinedOcr.isNotEmpty()) {
                append("\nOffline OCR-szöveg a füzetből:\n")
                append(combinedOcr)
            }
        }

        val response = geminiClient.generateContent(
            trigger = UserInitiatedAiTrigger.EXAM_MODE_GENERATION,
            promptText = prompt,
            inlinePngImages = pngImages,
            responseMimeType = "application/json",
            responseSchemaJson = EXAM_PACK_SCHEMA,
            systemInstruction = "Precíz magyar vizsgáztató vagy. Mindig a megadott JSON sémát kövesd."
        )
        AiCopilotParsers.parseExamPackJson(response.text)
    }

    private fun encodeLightModePageImages(
        batches: List<List<PersistedStrokeRecord>>,
        maxPages: Int
    ): List<ByteArray> = batches
        .filter { it.isNotEmpty() }
        .take(maxPages)
        .map { strokes ->
            val bitmap = HandwritingToLatexConverter.renderStrokesToCanonicalLightBitmap(strokes)
            try {
                GeminiClient.prepareInlinePngBytes(bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    private const val MAGIC_CIRCLE_SCHEMA =
        """{"type":"object","properties":{"title":{"type":"string"},"summaryMarkdown":{"type":"string"},"examples":{"type":"array","items":{"type":"string"}}},"required":["title","summaryMarkdown","examples"]}"""

    private const val EXAM_PACK_SCHEMA =
        """{"type":"object","properties":{"title":{"type":"string"},"flashcards":{"type":"array","items":{"type":"object","properties":{"front":{"type":"string"},"back":{"type":"string"},"topicTag":{"type":"string"}},"required":["front","back"]}},"questions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}},"correctIndex":{"type":"integer"},"explanation":{"type":"string"}},"required":["question","options","correctIndex"]}}},"required":["title","flashcards","questions"]}"""
}

enum class AiCopilotScope(val labelHu: String) {
    CURRENT_PAGE("Csak az adott oldal"),
    ENTIRE_WORKBOOK("Teljes munkafüzet")
}

enum class AiFormattingMode(
    val titleHu: String,
    val descriptionHu: String
) {
    FULL_HANDWRITING_AND_FIGURES(
        titleHu = "1. Teljes tartalom formázása (kézírásos szöveg + ábrák + képletek)",
        descriptionHu = "A kijelölt hatókörben gépelt, emberi íráshoz hasonló stílusra alakítja a szöveget, és szépen formázza a vonalakat, ábrákat és képleteket."
    ),
    SHAPES_AND_FORMULAS_ONLY(
        titleHu = "2. Csak alakzatok és képletek formázása",
        descriptionHu = "A kézzel írt szöveg érintetlen marad; csak az ábrákat, vonalakat és matematikai képleteket javítja ki."
    )
}

/**
 * Focused StarNote AI Formatting modal preserving Privacy/BYOK Settings, scope selection
 * (current page vs entire workbook), and the 2 formatting modes with ESC & backdrop dismissal
 * (Rule 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCopilotSheet(
    notebookTitle: String,
    pageNumber: Int,
    totalPageCount: Int,
    consentState: AiConsentState,
    onOpenConsentSettings: () -> Unit,
    onFormatWithAi: suspend (scope: AiCopilotScope, mode: AiFormattingMode) -> Result<String>,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedScope by remember { mutableStateOf(AiCopilotScope.CURRENT_PAGE) }
    var isRunning by remember { mutableStateOf(false) }
    var statusMessageHu by remember { mutableStateOf<String?>(null) }
    var isErrorStatus by remember { mutableStateOf(false) }

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
                .background(Color.Black.copy(alpha = 0.45f))
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
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 18.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(22.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(
                                    imageVector = StylusIcons.StarFourPoint,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "AI Formázó",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "$notebookTitle • $pageNumber / ${totalPageCount.coerceAtLeast(1)}. oldal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row {
                            IconButton(onClick = onOpenConsentSettings) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "AI beállítások és API-kulcs"
                                )
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Bezárás")
                            }
                        }
                    }

                    // Settings & Privacy Status Card
                    Surface(
                        color = if (consentState.isReadyForUserInitiatedCall) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(16.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI adatvédelem és BYOK API-beállítások",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (consentState.isReadyForUserInitiatedCall) {
                                        "Aktív • Modell: ${consentState.selectedModelIdOverride ?: "automatikus Flash"}"
                                    } else {
                                        "Kikapcsolva: fogadd el a feltételeket és add meg az API-kulcsot."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedButton(
                                onClick = onOpenConsentSettings,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Beállítások")
                            }
                        }
                    }

                    // Scope Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Érintett hatókör",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AiCopilotScope.entries.forEach { scopeOption ->
                                val selected = selectedScope == scopeOption
                                FilterChip(
                                    selected = selected,
                                    enabled = !isRunning,
                                    onClick = { selectedScope = scopeOption },
                                    label = {
                                        val suffix = if (scopeOption == AiCopilotScope.CURRENT_PAGE) {
                                            " ($pageNumber. o.)"
                                        } else {
                                            " (${totalPageCount.coerceAtLeast(1)} oldal)"
                                        }
                                        Text(scopeOption.labelHu + suffix)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Two Formatting Actions
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AiFormattingMode.entries.forEach { mode ->
                            val enabled = !isRunning && consentState.isReadyForUserInitiatedCall
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(16.dp)
                                    )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (mode == AiFormattingMode.FULL_HANDWRITING_AND_FIGURES) {
                                                Icons.Default.Draw
                                            } else {
                                                Icons.Default.Category
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = mode.titleHu,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        text = mode.descriptionHu,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Button(
                                            enabled = enabled,
                                            shape = RoundedCornerShape(12.dp),
                                            onClick = {
                                                coroutineScope.launch {
                                                    isRunning = true
                                                    statusMessageHu = null
                                                    isErrorStatus = false
                                                    onFormatWithAi(selectedScope, mode)
                                                        .onSuccess { summary ->
                                                            statusMessageHu = summary
                                                            isErrorStatus = false
                                                        }
                                                        .onFailure { err ->
                                                            statusMessageHu = err.message ?: "Sikertelen AI formázás."
                                                            isErrorStatus = true
                                                        }
                                                    isRunning = false
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Futtatás")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isRunning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = "AI felismerés és formázás folyamatban (${selectedScope.labelHu})...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    statusMessageHu?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isErrorStatus) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isErrorStatus) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

