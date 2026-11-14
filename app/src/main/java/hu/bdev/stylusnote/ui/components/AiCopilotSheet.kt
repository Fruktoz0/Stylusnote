package hu.bdev.stylusnote.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

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

/**
 * Full-featured Hybrid AI Copilot sheet supporting Handwriting -> Markdown (+ MD/PDF export),
 * Magic Circle / Lasso -> LaTeX, and Exam Mode (Flashcards + Quiz) with ESC & backdrop dismissal
 * (Section 7 & UX Rule 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCopilotSheet(
    notebookTitle: String,
    consentState: AiConsentState,
    currentPageStrokes: List<PersistedStrokeRecord>,
    notebookOcrTexts: List<String>,
    onOpenConsentSettings: () -> Unit,
    onConvertStrokesToLatex: suspend () -> String,
    onInsertDerivationCard: suspend (title: String, bodyMarkdown: String) -> Unit,
    onExportPagePdf: () -> Unit,
    geminiClient: GeminiClient,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var isRunning by remember { mutableStateOf(false) }
    var statusErrorHu by remember { mutableStateOf<String?>(null) }

    var generatedMarkdown by remember { mutableStateOf("") }
    var magicExplanation by remember { mutableStateOf<MagicCircleExplanation?>(null) }
    var examPack by remember { mutableStateOf<ExamStudyPack?>(null) }

    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null && generatedMarkdown.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(generatedMarkdown.toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    }
                }.onFailure { err ->
                    statusErrorHu = err.message ?: "Markdown mentési hiba."
                }
            }
        }
    }

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
                .background(Color.Black.copy(alpha = 0.50f))
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
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 6.dp,
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
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
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "AI Copilot",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = notebookTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row {
                            IconButton(onClick = onOpenConsentSettings) {
                                Icon(Icons.Default.Settings, contentDescription = "AI adatvédelmi és kulcs beállítások")
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Bezárás")
                            }
                        }
                    }

                    if (!consentState.isReadyForUserInitiatedCall) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Az AI funkciók alapból kikapcsoltak. Bekapcsoláshoz fogadd el az adatvédelmi tájékoztatót és adj meg egy BYOK API-kulcsot.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = onOpenConsentSettings) {
                                    Text("Beállítás")
                                }
                            }
                        }
                    }

                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Jegyzet → MD") },
                            icon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Magyarázat & LaTeX") },
                            icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Vizsgamód") },
                            icon = { Icon(Icons.Default.Quiz, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }

                    when (selectedTab) {
                        0 -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    enabled = !isRunning && consentState.isReadyForUserInitiatedCall,
                                    onClick = {
                                        scope.launch {
                                            isRunning = true
                                            statusErrorHu = null
                                            AiCopilotController.convertPagesToStructuredMarkdown(
                                                pageStrokeBatches = listOf(currentPageStrokes),
                                                notebookTitle = notebookTitle,
                                                geminiClient = geminiClient
                                            ).onSuccess { md ->
                                                generatedMarkdown = md
                                            }.onFailure { err ->
                                                statusErrorHu = err.message ?: "Sikertelen Markdown konverzió."
                                            }
                                            isRunning = false
                                        }
                                    }
                                ) {
                                    Text("Oldal átalakítása Markdownba")
                                }
                                if (generatedMarkdown.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = { exportMarkdownLauncher.launch("${notebookTitle.ifBlank { "jegyzet" }}.md") }
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Export MD")
                                    }
                                    OutlinedButton(onClick = onExportPagePdf) {
                                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Export PDF")
                                    }
                                }
                            }
                            if (generatedMarkdown.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = generatedMarkdown,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }

                        1 -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !isRunning && consentState.isReadyForUserInitiatedCall,
                                    onClick = {
                                        scope.launch {
                                            isRunning = true
                                            statusErrorHu = null
                                            AiCopilotController.explainWithMagicCircle(
                                                circledStrokes = currentPageStrokes,
                                                geminiClient = geminiClient
                                            ).onSuccess { exp ->
                                                magicExplanation = exp
                                            }.onFailure { err ->
                                                statusErrorHu = err.message ?: "Sikertelen magyarázat kérés."
                                            }
                                            isRunning = false
                                        }
                                    }
                                ) {
                                    Text("Magic Circle: Magyarázd el")
                                }
                                OutlinedButton(
                                    enabled = !isRunning && consentState.isReadyForUserInitiatedCall,
                                    onClick = {
                                        scope.launch {
                                            isRunning = true
                                            statusErrorHu = null
                                            runCatching { onConvertStrokesToLatex() }
                                                .onSuccess { msg -> statusErrorHu = msg }
                                                .onFailure { err -> statusErrorHu = err.message }
                                            isRunning = false
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Functions, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Képlet → Vektoros LaTeX")
                                }
                            }

                            magicExplanation?.let { exp ->
                                MagicCircleCardContent(
                                    explanation = exp,
                                    onInsertToPage = {
                                        scope.launch {
                                            val body = buildString {
                                                appendLine(exp.summaryMarkdown)
                                                if (exp.examples.isNotEmpty()) {
                                                    appendLine()
                                                    appendLine("Példák:")
                                                    exp.examples.forEach { appendLine("• $it") }
                                                }
                                            }.trim()
                                            onInsertDerivationCard(exp.title, body)
                                            onDismiss()
                                        }
                                    }
                                )
                            }
                        }

                        2 -> {
                            Button(
                                enabled = !isRunning && consentState.isReadyForUserInitiatedCall,
                                onClick = {
                                    scope.launch {
                                        isRunning = true
                                        statusErrorHu = null
                                        AiCopilotController.generateExamStudyPack(
                                            notebookTitle = notebookTitle,
                                            ocrTexts = notebookOcrTexts,
                                            pageStrokeBatches = listOf(currentPageStrokes),
                                            geminiClient = geminiClient
                                        ).onSuccess { pack ->
                                            examPack = pack
                                        }.onFailure { err ->
                                            statusErrorHu = err.message ?: "Sikertelen vizsgagenerálás."
                                        }
                                        isRunning = false
                                    }
                                }
                            ) {
                                Text("Flashcardok és kvíz generálása")
                            }

                            examPack?.let { pack ->
                                ExamPackInteractiveView(pack = pack)
                            }
                        }
                    }

                    if (isRunning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Gemini feldolgozás folyamatban...", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    statusErrorHu?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MagicCircleCardContent(
    explanation: MagicCircleExplanation,
    onInsertToPage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = explanation.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = explanation.summaryMarkdown, style = MaterialTheme.typography.bodyMedium)
            if (explanation.examples.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Példák:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                explanation.examples.forEach { ex ->
                    Text(text = "• $ex", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onInsertToPage) {
                    Text("Beszúrás kártyaként az oldalra")
                }
            }
        }
    }
}

@Composable
private fun ExamPackInteractiveView(pack: ExamStudyPack) {
    val flippedCards = remember(pack) { mutableStateMapOf<Int, Boolean>() }
    val selectedOptions = remember(pack) { mutableStateMapOf<Int, Int>() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = pack.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (pack.flashcards.isNotEmpty()) {
            Text("Tanulókártyák (koppints a megfordításhoz):", style = MaterialTheme.typography.labelLarge)
            pack.flashcards.forEachIndexed { idx, card ->
                val isFlipped = flippedCards[idx] == true
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { flippedCards[idx] = !isFlipped }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (card.topicTag.isNotBlank()) {
                            Text(
                                text = card.topicTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = if (isFlipped) card.back else card.front,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isFlipped) FontWeight.Normal else FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (pack.questions.isNotEmpty()) {
            Text("Feleletválasztós teszt:", style = MaterialTheme.typography.labelLarge)
            pack.questions.forEachIndexed { qIdx, q ->
                val chosen = selectedOptions[qIdx]
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${qIdx + 1}. ${q.question}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        q.options.forEachIndexed { optIdx, optText ->
                            val isSelected = chosen == optIdx
                            val isCorrect = optIdx == q.correctIndex
                            val badgeColor = when {
                                chosen == null -> Color.Transparent
                                isCorrect -> Color(0xFF2E7D32).copy(alpha = 0.16f)
                                isSelected -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                                else -> Color.Transparent
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(badgeColor, RoundedCornerShape(8.dp))
                                    .clickable { selectedOptions[qIdx] = optIdx }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${'A' + optIdx}) $optText",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (chosen != null && isCorrect) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        if (chosen != null && q.explanation.isNotBlank()) {
                            Text(
                                text = q.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

