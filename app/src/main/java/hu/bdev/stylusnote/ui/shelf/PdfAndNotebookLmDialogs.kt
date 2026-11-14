package hu.bdev.stylusnote.ui.shelf

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import hu.bdev.stylusnote.data.export.ExportablePdfPage
import hu.bdev.stylusnote.data.export.PdfExportManager
import hu.bdev.stylusnote.data.export.PdfTileRenderer
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.NotebookCover
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.TreeSet
import java.util.UUID

/**
 * Parses a 1-based user page selection expression (e.g. `"1-3, 5, 8-10"`) into sorted unique
 * 0-based page indices within `0 until totalPages`. Invalid tokens or reversed ranges are ignored.
 */
fun parsePageSelectionSpec(spec: String, totalPages: Int): List<Int> {
    if (totalPages <= 0) return emptyList()
    val trimmed = spec.trim()
    if (trimmed.isEmpty()) return emptyList()

    val selected = TreeSet<Int>()
    val tokens = trimmed.split(',')
    for (rawToken in tokens) {
        val token = rawToken.trim()
        if (token.isEmpty()) continue
        if ('-' in token) {
            val parts = token.split('-')
            if (parts.size != 2) continue
            val startOneBased = parts[0].trim().toIntOrNull() ?: continue
            val endOneBased = parts[1].trim().toIntOrNull() ?: continue
            if (startOneBased > endOneBased) continue
            for (p in startOneBased..endOneBased) {
                if (p in 1..totalPages) {
                    selected.add(p - 1)
                }
            }
        } else {
            val singleOneBased = token.toIntOrNull() ?: continue
            if (singleOneBased in 1..totalPages) {
                selected.add(singleOneBased - 1)
            }
        }
    }
    return selected.toList()
}

private fun formatPageIndicesSummary(indicesZeroBased: List<Int>): String {
    if (indicesZeroBased.isEmpty()) return "Nincs érvényes oldal"
    val preview = indicesZeroBased.take(8).joinToString(", ") { (it + 1).toString() }
    return if (indicesZeroBased.size > 8) "$preview, …" else preview
}

private fun resolveDisplayFileName(context: Context, uri: Uri): String {
    val queriedName = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
    }
    val raw = queriedName ?: uri.lastPathSegment ?: "Importált PDF"
    return raw.substringAfterLast('/').ifBlank { "Importált PDF" }
}

/**
 * Exports either all pages or a specific subset of pages from [notebook] as a vector PDF into
 * [outputStream], loading each page's binary strokes and background PDF tiles safely.
 */
suspend fun exportNotebookPagesToPdfStream(
    context: Context,
    database: AppDatabase,
    notebook: NotebookEntity,
    pagesToExport: List<PageEntity>,
    outputStream: OutputStream
) = withContext(Dispatchers.IO) {
    require(pagesToExport.isNotEmpty()) { "Nincs exportálható oldal." }
    val pdfRenderer = PdfTileRenderer(context.filesDir)
    val exportManager = PdfExportManager(
        context = context.applicationContext,
        pdfTileRenderer = pdfRenderer
    )
    val exportablePages = pagesToExport.mapIndexed { idx, entity ->
        val pageDir = File(context.filesDir, "pages/${entity.id}")
        val journal = StrokeJournal(pageDirectory = pageDir)
        val recovery = try {
            journal.openAndRecover()
        } finally {
            // Read-only export must close its WAL channel immediately so editing does not collide.
            journal.closeBlocking(compactBeforeClose = false)
        }
        val paperConfig = entity.pageSizePreset.toPagePaperConfig(
            canvasMode = entity.canvasMode,
            templateType = entity.templateType
        )
        ExportablePdfPage(
            pageNumberOneBased = idx + 1,
            paperConfig = paperConfig,
            strokes = recovery.strokes,
            objects = recovery.objects,
            backgroundPdfAssetId = entity.pdfAssetId,
            backgroundPdfPageIndex = entity.pdfPageIndex
        )
    }
    exportManager.exportPagesToPdf(exportablePages, outputStream)
}

/**
 * StarNote-styled modal dialog for importing either a complete PDF or a user-specified subset of
 * pages into a new notebook or appended onto an existing [targetNotebook].
 */
@Composable
fun SelectivePdfImportDialog(
    database: AppDatabase,
    defaultFolderId: String?,
    targetNotebook: NotebookEntity? = null,
    onImported: (notebookId: String, firstPageId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val surfacePalette = LocalStylusSurfacePalette.current

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember { mutableStateOf("") }
    var detectedPageCount by remember { mutableIntStateOf(0) }
    var importAllPages by remember { mutableStateOf(true) }
    var pageRangeSpec by remember { mutableStateOf("1") }
    var customNotebookTitle by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            val fileName = resolveDisplayFileName(context, uri)
            pickedFileName = fileName
            if (customNotebookTitle.isBlank()) {
                customNotebookTitle = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
                    .ifBlank { "Importált PDF" }
            }
            errorMessage = null
        }
    }

    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        detectedPageCount = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer -> renderer.pageCount }
                } ?: 0
            }.getOrDefault(0)
        }
        if (detectedPageCount > 0) {
            pageRangeSpec = "1-$detectedPageCount"
        } else {
            errorMessage = "A kiválasztott PDF nem nyitható meg vagy nem tartalmaz oldalt."
        }
    }

    val resolvedIndices = remember(importAllPages, pageRangeSpec, detectedPageCount) {
        if (detectedPageCount <= 0) {
            emptyList()
        } else if (importAllPages) {
            (0 until detectedPageCount).toList()
        } else {
            parsePageSelectionSpec(pageRangeSpec, detectedPageCount)
        }
    }

    ModalBackdropBox(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp)
                .widthIn(min = 380.dp, max = 560.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = if (targetNotebook == null) {
                                    "PDF importálása új füzetként"
                                } else {
                                    "PDF oldalak importálása füzetbe"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (targetNotebook != null) {
                                Text(
                                    text = "Célfüzet: ${targetNotebook.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Bezárás")
                    }
                }

                // PDF File Selector Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = surfacePalette.groupedCardBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (pickedFileName.isBlank()) "Nincs PDF kiválasztva" else pickedFileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (detectedPageCount > 0) {
                                    "$detectedPageCount oldal érhető el"
                                } else {
                                    "Válassz egy PDF dokumentumot az eszközről"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            enabled = !isImporting,
                            onClick = { pickerLauncher.launch(arrayOf("application/pdf")) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (pickedUri == null) "Tallózás..." else "Másik PDF")
                        }
                    }
                }

                if (targetNotebook == null && pickedUri != null) {
                    OutlinedTextField(
                        value = customNotebookTitle,
                        onValueChange = { customNotebookTitle = it },
                        label = { Text("Új füzet neve") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (detectedPageCount > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Importálandó oldalak",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = { importAllPages = true },
                                shape = RoundedCornerShape(12.dp),
                                color = if (importAllPages) surfacePalette.activePillBackground else surfacePalette.groupedCardBackground,
                                border = if (importAllPages) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Teljes könyv ($detectedPageCount o.)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (importAllPages) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                            Surface(
                                onClick = { importAllPages = false },
                                shape = RoundedCornerShape(12.dp),
                                color = if (!importAllPages) surfacePalette.activePillBackground else surfacePalette.groupedCardBackground,
                                border = if (!importAllPages) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Kiválasztott oldalak",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (!importAllPages) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }

                        if (!importAllPages) {
                            OutlinedTextField(
                                value = pageRangeSpec,
                                onValueChange = { pageRangeSpec = it },
                                label = { Text("Oldalszámok / tartomány (1–$detectedPageCount)") },
                                placeholder = { Text("pl. 1-3, 5, 8-10") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text(
                            text = if (resolvedIndices.isEmpty()) {
                                "Adj meg legalább egy érvényes oldalszámot 1 és $detectedPageCount között."
                            } else {
                                "Importálva lesz (${resolvedIndices.size} oldal): ${formatPageIndicesSummary(resolvedIndices)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (resolvedIndices.isEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(enabled = !isImporting, onClick = onDismiss) {
                        Text("Mégse")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !isImporting && pickedUri != null && resolvedIndices.isNotEmpty() &&
                            (targetNotebook != null || customNotebookTitle.isNotBlank()),
                        onClick = {
                            val uri = pickedUri ?: return@Button
                            isImporting = true
                            errorMessage = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val renderer = PdfTileRenderer(context.filesDir)
                                        val imported = context.contentResolver.openInputStream(uri)?.use { stream ->
                                            renderer.importPdf(stream)
                                        } ?: error("A PDF adatfolyam nem olvasható.")

                                        val preset = PageSizePreset.entries.firstOrNull {
                                            it.dimensionsPt == imported.firstPageDimensionsPt
                                        } ?: PageSizePreset.A4_PORTRAIT
                                        val now = System.currentTimeMillis()

                                        val notebookId: String
                                        val startOrderIndex: Int
                                        if (targetNotebook != null) {
                                            notebookId = targetNotebook.id
                                            val existingPages = database.pageDao()
                                                .observePagesForNotebook(notebookId)
                                                .first()
                                            startOrderIndex = (existingPages.maxOfOrNull { it.pageIndexInNotebook } ?: -1) + 1
                                        } else {
                                            notebookId = UUID.randomUUID().toString()
                                            startOrderIndex = 0
                                        }

                                        var firstCreatedPageId = ""
                                        resolvedIndices.forEachIndexed { offset, pdfPageIdx ->
                                            val pageId = UUID.randomUUID().toString()
                                            if (offset == 0) firstCreatedPageId = pageId
                                            if (offset == 0 && targetNotebook == null) {
                                                database.notebookDao().upsertNotebook(
                                                    NotebookEntity(
                                                        id = notebookId,
                                                        folderId = defaultFolderId,
                                                        title = customNotebookTitle.trim().ifBlank { "Importált PDF" },
                                                        cover = NotebookCover(),
                                                        defaultCanvasMode = CanvasMode.FIXED_PAGE,
                                                        defaultPageSizePreset = preset,
                                                        defaultTemplateType = PaperTemplateType.BLANK,
                                                        ocrLanguage = OcrLanguageTag.HUNGARIAN,
                                                        lastOpenedPageId = pageId,
                                                        createdAtEpochMs = now,
                                                        updatedAtEpochMs = now
                                                    )
                                                )
                                            }
                                            database.pageDao().upsertPage(
                                                PageEntity(
                                                    id = pageId,
                                                    notebookId = notebookId,
                                                    pageIndexInNotebook = startOrderIndex + offset,
                                                    canvasMode = CanvasMode.FIXED_PAGE,
                                                    pageSizePreset = preset,
                                                    templateType = PaperTemplateType.BLANK,
                                                    pdfAssetId = imported.pdfAssetId,
                                                    pdfPageIndex = pdfPageIdx,
                                                    createdAtEpochMs = now,
                                                    updatedAtEpochMs = now
                                                )
                                            )
                                        }
                                        if (targetNotebook != null) {
                                            database.notebookDao().upsertNotebook(
                                                targetNotebook.copy(updatedAtEpochMs = now)
                                            )
                                        }
                                        notebookId to firstCreatedPageId
                                    }
                                }
                                isImporting = false
                                result.onSuccess { (nbId, pageId) ->
                                    onImported(nbId, pageId)
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Sikertelen PDF importálás."
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Importálás")
                    }
                }
            }
        }
    }
}

/**
 * Dialog for packaging either an entire notebook or one specific page as a shared vector PDF
 * (plus recognized OCR summary text when exporting the whole notebook) and launching Android's
 * system share sheet toward NotebookLM or another destination app.
 */
@Composable
fun NotebookLmExportDialog(
    notebook: NotebookEntity,
    database: AppDatabase,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val surfacePalette = LocalStylusSurfacePalette.current
    val pages by database.pageDao().observePagesForNotebook(notebook.id).collectAsState(initial = emptyList())

    var exportWholeBook by remember { mutableStateOf(true) }
    var selectedPageIndex by remember { mutableIntStateOf(0) }
    var isExporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBackdropBox(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp)
                .widthIn(min = 380.dp, max = 540.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Feltöltés NotebookLM-be",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = notebook.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Bezárás")
                    }
                }

                Text(
                    text = "A rendszer megosztólapon keresztül közvetlenül továbbítható a NotebookLM (vagy bármely AI/felhő alkalmazás) felé.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { exportWholeBook = true },
                        shape = RoundedCornerShape(12.dp),
                        color = if (exportWholeBook) surfacePalette.activePillBackground else surfacePalette.groupedCardBackground,
                        border = if (exportWholeBook) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Teljes füzet (${pages.size} o.)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (exportWholeBook) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                    Surface(
                        onClick = { exportWholeBook = false },
                        shape = RoundedCornerShape(12.dp),
                        color = if (!exportWholeBook) surfacePalette.activePillBackground else surfacePalette.groupedCardBackground,
                        border = if (!exportWholeBook) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Csak egy oldal",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (!exportWholeBook) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                if (!exportWholeBook && pages.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Válaszd ki a megosztandó oldalt:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(pages, key = { _, p -> p.id }) { idx, _ ->
                                val isSel = idx == selectedPageIndex.coerceIn(0, pages.lastIndex)
                                Surface(
                                    onClick = { selectedPageIndex = idx },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) MaterialTheme.colorScheme.primary else surfacePalette.groupedCardBackground
                                ) {
                                    Text(
                                        text = "${idx + 1}. oldal",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(enabled = !isExporting, onClick = onDismiss) {
                        Text("Mégse")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !isExporting && pages.isNotEmpty(),
                        onClick = {
                            isExporting = true
                            errorMessage = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val chosenPages = if (exportWholeBook) {
                                            pages
                                        } else {
                                            listOf(pages[selectedPageIndex.coerceIn(0, pages.lastIndex)])
                                        }
                                        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val safeBase = notebook.title
                                            .replace(Regex("[^\\p{L}\\p{Nd}_-]+"), "_")
                                            .trim('_')
                                            .ifBlank { "jegyzet" }
                                        val suffix = if (exportWholeBook) {
                                            "teljes"
                                        } else {
                                            "oldal_${selectedPageIndex.coerceIn(0, pages.lastIndex) + 1}"
                                        }
                                        val pdfFile = File(exportDir, "${safeBase}_${suffix}.pdf")
                                        pdfFile.outputStream().use { out ->
                                            exportNotebookPagesToPdfStream(
                                                context = context,
                                                database = database,
                                                notebook = notebook,
                                                pagesToExport = chosenPages,
                                                outputStream = out
                                            )
                                        }
                                        val ocrSummary = if (exportWholeBook) {
                                            database.pageTextSearchDao()
                                                .getRecognizedTextsForNotebook(notebook.id)
                                                .filter { it.isNotBlank() }
                                                .joinToString(separator = "\n\n")
                                        } else {
                                            ""
                                        }
                                        val contentUri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            pdfFile
                                        )
                                        contentUri to ocrSummary
                                    }
                                }
                                isExporting = false
                                res.onSuccess { (uri, ocrText) ->
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "StylusNote: ${notebook.title}")
                                        if (ocrText.isNotBlank()) {
                                            putExtra(Intent.EXTRA_TEXT, ocrText)
                                        }
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    val chooser = Intent.createChooser(
                                        sendIntent,
                                        "Megosztás NotebookLM-be"
                                    )
                                    context.startActivity(chooser)
                                    onDismiss()
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Sikertelen exportálás NotebookLM-be."
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Megosztás NotebookLM-be")
                    }
                }
            }
        }
    }
}
