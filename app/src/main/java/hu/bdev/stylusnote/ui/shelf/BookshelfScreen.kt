package hu.bdev.stylusnote.ui.shelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hu.bdev.stylusnote.core.ai.AiConsentState
import hu.bdev.stylusnote.core.ai.AiConsentStore
import hu.bdev.stylusnote.core.ai.BackgroundOcrWorker
import hu.bdev.stylusnote.core.ai.GeminiClient
import hu.bdev.stylusnote.core.ai.GeminiModelInfo
import hu.bdev.stylusnote.core.ai.ModelCatalog
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.OledStrokeRenderCache
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.data.export.ExportablePdfPage
import hu.bdev.stylusnote.data.export.PdfExportManager
import hu.bdev.stylusnote.data.local.AppDatabase
import hu.bdev.stylusnote.data.local.BookmarkEntity
import hu.bdev.stylusnote.data.local.ChapterEntity
import hu.bdev.stylusnote.data.local.FolderEntity
import hu.bdev.stylusnote.data.local.NotebookEntity
import hu.bdev.stylusnote.data.local.NotebookTagCrossRef
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.PageSearchHit
import hu.bdev.stylusnote.data.local.StrokeBinarySerializer
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.data.local.TagEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.NotebookCover
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.DryInkRasterCache
import hu.bdev.stylusnote.ui.canvas.FunctionPlotterDialog
import hu.bdev.stylusnote.ui.canvas.HandwritingToLatexConverter
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.LaserTrailStateHolder
import hu.bdev.stylusnote.ui.canvas.LatexConversionResult
import hu.bdev.stylusnote.ui.canvas.PageObjectsOverlayLayer
import hu.bdev.stylusnote.ui.canvas.StylusDrawingSurface
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import hu.bdev.stylusnote.ui.components.AiCopilotSheet
import hu.bdev.stylusnote.ui.components.BrushPresetStore
import hu.bdev.stylusnote.ui.components.BrushSettingsPopover
import hu.bdev.stylusnote.ui.components.ConsentDialog
import hu.bdev.stylusnote.ui.components.DockEdge
import hu.bdev.stylusnote.ui.components.FloatingToolDock
import hu.bdev.stylusnote.ui.components.PaperSettingsDialog
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class OpenedPageTarget(
    val notebookId: String,
    val pageId: String
)

private enum class BookshelfTab(val label: String, val icon: ImageVector) {
    FOLDERS("Mappák", Icons.Filled.Folder),
    FAVORITES("Kedvencek", Icons.Filled.Star),
    BOOKMARKS("Könyvjelzők", Icons.Filled.Bookmark),
    TAGS("Címkék", Icons.Filled.Label)
}

private val TAG_PALETTE = listOf(
    0xFF1E88E5.toInt(),
    0xFF43A047.toInt(),
    0xFFE53935.toInt(),
    0xFFFB8C00.toInt(),
    0xFF8E24AA.toInt(),
    0xFF00897B.toInt()
)

/**
 * Full Bookshelf workspace supporting unlimited nested folders, colored tags, bookmarks,
 * Cover Studio customization, FTS4 accent-insensitive OCR search, and launching single-page or
 * side-by-side Split-Screen editors (Section 8).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookshelfScreen(
    database: AppDatabase,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSinglePage: (OpenedPageTarget) -> Unit,
    onOpenSplitScreen: (OpenedPageTarget, OpenedPageTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val allFolders by database.folderDao().observeAllFolders().collectAsState(initial = emptyList())
    val allNotebooks by database.notebookDao().observeAllNotebooks().collectAsState(initial = emptyList())
    val allTags by database.tagDao().observeAllTags().collectAsState(initial = emptyList())
    val tagLinks by database.tagDao().observeAllNotebookTagLinks().collectAsState(initial = emptyList())
    val allBookmarks by database.bookmarkDao().observeAllBookmarks().collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(BookshelfTab.FOLDERS) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var selectedTagId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var ocrHits by remember { mutableStateOf<List<PageSearchHit>>(emptyList()) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewTagDialog by remember { mutableStateOf(false) }
    var editingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var creatingNewNotebook by remember { mutableStateOf(false) }
    var inspectingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var splitPrimaryPending by remember { mutableStateOf<OpenedPageTarget?>(null) }

    LaunchedEffect(searchQuery) {
        ocrHits = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            database.pageTextSearchDao().searchAccentInsensitive(searchQuery)
        }
    }

    val folderById = remember(allFolders) { allFolders.associateBy { it.id } }
    val breadcrumbs = remember(currentFolderId, folderById) {
        val trail = ArrayList<FolderEntity>()
        var cursor = currentFolderId
        var guard = 0
        while (cursor != null && guard++ < 64) {
            val folder = folderById[cursor] ?: break
            trail.add(folder)
            cursor = folder.parentId
        }
        trail.reversed()
    }

    val childFolders = remember(allFolders, currentFolderId) {
        allFolders.filter { it.parentId == currentFolderId }
    }

    val tagsByNotebookId = remember(tagLinks, allTags) {
        val tagMap = allTags.associateBy { it.id }
        tagLinks.groupBy { it.notebookId }
            .mapValues { (_, refs) -> refs.mapNotNull { tagMap[it.tagId] } }
    }

    val visibleNotebooks = remember(
        activeTab,
        currentFolderId,
        selectedTagId,
        searchQuery,
        allNotebooks,
        tagsByNotebookId
    ) {
        val q = searchQuery.trim()
        val base = if (q.isNotEmpty()) {
            allNotebooks.filter { it.title.contains(q, ignoreCase = true) }
        } else {
            when (activeTab) {
                BookshelfTab.FOLDERS -> allNotebooks.filter { it.folderId == currentFolderId }
                BookshelfTab.FAVORITES -> allNotebooks.filter { it.isFavorite }
                BookshelfTab.TAGS -> {
                    val filterTag = selectedTagId
                    if (filterTag == null) allNotebooks
                    else allNotebooks.filter { nb ->
                        tagsByNotebookId[nb.id]?.any { it.id == filterTag } == true
                    }
                }
                BookshelfTab.BOOKMARKS -> allNotebooks
            }
        }
        base
    }

    fun openDefaultPageForNotebook(nb: NotebookEntity) {
        scope.launch {
            val pages = withContext(Dispatchers.IO) {
                var existingId = nb.lastOpenedPageId
                if (existingId != null && database.pageDao().getPageById(existingId) != null) {
                    return@withContext existingId
                }
                val firstPage = PageEntity(
                    id = UUID.randomUUID().toString(),
                    notebookId = nb.id,
                    pageIndexInNotebook = 0,
                    canvasMode = nb.defaultCanvasMode,
                    pageSizePreset = nb.defaultPageSizePreset,
                    templateType = nb.defaultTemplateType,
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                database.pageDao().upsertPage(firstPage)
                database.notebookDao().upsertNotebook(nb.copy(lastOpenedPageId = firstPage.id))
                firstPage.id
            }
            val target = OpenedPageTarget(notebookId = nb.id, pageId = pages)
            val pending = splitPrimaryPending
            if (pending != null) {
                splitPrimaryPending = null
                onOpenSplitScreen(pending, target)
            } else {
                onOpenSinglePage(target)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creatingNewNotebook = true },
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Új jegyzetfüzet", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Modern Bookshelf Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "StylusNote",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (splitPrimaryPending != null) {
                                "Split-Screen: válaszd ki a második oldalt"
                            } else {
                                "${allNotebooks.size} jegyzetfüzet • 144 Hz munkaterület"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (splitPrimaryPending != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (splitPrimaryPending != null) {
                        FilledTonalButton(
                            onClick = { splitPrimaryPending = null },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Split megszakítása")
                        }
                    }
                    FilledTonalButton(
                        onClick = { showNewFolderDialog = true },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Új mappa")
                    }
                    FilledTonalButton(
                        onClick = { showNewTagDialog = true },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.Label, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Új címke")
                    }
                    Surface(
                        onClick = onToggleTheme,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                contentDescription = "Téma váltása",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Pill Search Bar + Segmented Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Keresés címben vagy kézírott jegyzetekben...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Törlés")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(20.dp)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BookshelfTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            Surface(
                                onClick = { activeTab = tab },
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (activeTab == BookshelfTab.FOLDERS && searchQuery.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        onClick = { currentFolderId = null },
                        shape = RoundedCornerShape(12.dp),
                        color = if (currentFolderId == null) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(15.dp))
                            Text("Gyökér", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                    breadcrumbs.forEach { folder ->
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Surface(
                            onClick = { currentFolderId = folder.id },
                            shape = RoundedCornerShape(12.dp),
                            color = if (folder.id == currentFolderId) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        ) {
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (childFolders.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        childFolders.forEach { folder ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                tonalElevation = 2.dp,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable { currentFolderId = folder.id }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(folder.accentColorArgb).copy(alpha = 0.16f),
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.Folder,
                                                contentDescription = null,
                                                tint = Color(folder.accentColorArgb),
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        folder.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                database.folderDao().deleteFolderById(folder.id)
                                            }
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Mappa törlése",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (activeTab == BookshelfTab.TAGS && searchQuery.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTagId == null,
                        onClick = { selectedTagId = null },
                        label = { Text("Összes") }
                    )
                    allTags.forEach { tag ->
                        FilterChip(
                            selected = selectedTagId == tag.id,
                            onClick = {
                                selectedTagId = if (selectedTagId == tag.id) null else tag.id
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.colorArgb))
                                )
                            },
                            label = { Text(tag.name) }
                        )
                    }
                }
            }

            if (searchQuery.isNotBlank() && ocrHits.isNotEmpty()) {
                Text(
                    text = "Kézírás OCR találatok (${ocrHits.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(ocrHits, key = { "${it.notebookId}:${it.pageId}" }) { hit ->
                        val nbTitle = allNotebooks.firstOrNull { it.id == hit.notebookId }?.title ?: "Füzet"
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val target = OpenedPageTarget(hit.notebookId, hit.pageId)
                                    val pending = splitPrimaryPending
                                    if (pending != null) {
                                        splitPrimaryPending = null
                                        onOpenSplitScreen(pending, target)
                                    } else {
                                        onOpenSinglePage(target)
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(nbTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = hit.rawText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (activeTab == BookshelfTab.BOOKMARKS && searchQuery.isBlank()) {
                if (allBookmarks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Még nincsenek könyvjelzővel jelölt oldalak.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(allBookmarks, key = { it.id }) { bm ->
                            val nbTitle = allNotebooks.firstOrNull { it.id == bm.notebookId }?.title ?: "Füzet"
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val target = OpenedPageTarget(bm.notebookId, bm.pageId)
                                        val pending = splitPrimaryPending
                                        if (pending != null) {
                                            splitPrimaryPending = null
                                            onOpenSplitScreen(pending, target)
                                        } else {
                                            onOpenSinglePage(target)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Filled.Bookmark,
                                            contentDescription = null,
                                            tint = Color(bm.colorArgb)
                                        )
                                        Column {
                                            Text(bm.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(nbTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                database.bookmarkDao().deleteBookmarkById(bm.id)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Könyvjelző törlése")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 196.dp),
                    contentPadding = PaddingValues(bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visibleNotebooks, key = { it.id }) { notebook ->
                        val attachedTags = tagsByNotebookId[notebook.id].orEmpty()
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 2.dp,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(20.dp)
                                )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NotebookCoverPreviewCard(
                                    title = notebook.title,
                                    cover = notebook.cover,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openDefaultPageForNotebook(notebook) }
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Surface(
                                        onClick = { inspectingNotebook = notebook },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            text = "Oldalak",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    database.notebookDao().upsertNotebook(
                                                        notebook.copy(isFavorite = !notebook.isFavorite)
                                                    )
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (notebook.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                                contentDescription = "Kedvenc",
                                                tint = if (notebook.isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { editingNotebook = notebook },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = "Borító szerkesztése",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                                if (attachedTags.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        attachedTags.take(3).forEach { t ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(t.colorArgb).copy(alpha = 0.22f))
                                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = t.name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        NewNameModalDialog(
            title = "Új mappa létrehozása",
            label = "Mappa neve",
            onConfirm = { name ->
                val now = System.currentTimeMillis()
                scope.launch(Dispatchers.IO) {
                    database.folderDao().upsertFolder(
                        FolderEntity(
                            id = UUID.randomUUID().toString(),
                            parentId = currentFolderId,
                            name = name,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                }
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    if (showNewTagDialog) {
        NewNameModalDialog(
            title = "Új címke létrehozása",
            label = "Címke neve",
            onConfirm = { name ->
                val color = TAG_PALETTE[allTags.size % TAG_PALETTE.size]
                scope.launch(Dispatchers.IO) {
                    database.tagDao().upsertTag(
                        TagEntity(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            colorArgb = color
                        )
                    )
                }
                showNewTagDialog = false
            },
            onDismiss = { showNewTagDialog = false }
        )
    }

    if (creatingNewNotebook) {
        CoverDesignerDialog(
            initialTitle = "Új jegyzetfüzet",
            initialCover = NotebookCover(),
            initialCanvasMode = CanvasMode.FIXED_PAGE,
            initialPageSizePreset = PageSizePreset.A4_PORTRAIT,
            initialTemplateType = PaperTemplateType.GRID_5MM,
            initialOcrLanguage = OcrLanguageTag.HUNGARIAN,
            confirmLabel = "Létrehozás",
            onConfirm = { draft ->
                val now = System.currentTimeMillis()
                val nbId = UUID.randomUUID().toString()
                val firstPageId = UUID.randomUUID().toString()
                scope.launch(Dispatchers.IO) {
                    database.notebookDao().upsertNotebook(
                        NotebookEntity(
                            id = nbId,
                            folderId = currentFolderId,
                            title = draft.title,
                            cover = draft.cover,
                            defaultCanvasMode = draft.canvasMode,
                            defaultPageSizePreset = draft.pageSizePreset,
                            defaultTemplateType = draft.templateType,
                            ocrLanguage = draft.ocrLanguage,
                            lastOpenedPageId = firstPageId,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                    database.pageDao().upsertPage(
                        PageEntity(
                            id = firstPageId,
                            notebookId = nbId,
                            pageIndexInNotebook = 0,
                            canvasMode = draft.canvasMode,
                            pageSizePreset = draft.pageSizePreset,
                            templateType = draft.templateType,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                }
                creatingNewNotebook = false
            },
            onDismiss = { creatingNewNotebook = false }
        )
    }

    editingNotebook?.let { targetNb ->
        CoverDesignerDialog(
            initialTitle = targetNb.title,
            initialCover = targetNb.cover,
            initialCanvasMode = targetNb.defaultCanvasMode,
            initialPageSizePreset = targetNb.defaultPageSizePreset,
            initialTemplateType = targetNb.defaultTemplateType,
            initialOcrLanguage = targetNb.ocrLanguage,
            confirmLabel = "Mentés",
            onConfirm = { draft ->
                scope.launch(Dispatchers.IO) {
                    database.notebookDao().upsertNotebook(
                        targetNb.copy(
                            title = draft.title,
                            cover = draft.cover,
                            defaultCanvasMode = draft.canvasMode,
                            defaultPageSizePreset = draft.pageSizePreset,
                            defaultTemplateType = draft.templateType,
                            ocrLanguage = draft.ocrLanguage,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
                editingNotebook = null
            },
            onDismiss = { editingNotebook = null }
        )
    }

    inspectingNotebook?.let { nb ->
        NotebookDetailsDialog(
            database = database,
            notebook = nb,
            allTags = allTags,
            attachedTags = tagsByNotebookId[nb.id].orEmpty(),
            onOpenPage = { pageId ->
                val target = OpenedPageTarget(nb.id, pageId)
                inspectingNotebook = null
                val pending = splitPrimaryPending
                if (pending != null) {
                    splitPrimaryPending = null
                    onOpenSplitScreen(pending, target)
                } else {
                    onOpenSinglePage(target)
                }
            },
            onPickForSplitScreen = { pageId ->
                splitPrimaryPending = OpenedPageTarget(nb.id, pageId)
                inspectingNotebook = null
            },
            onDeleteNotebook = {
                scope.launch(Dispatchers.IO) {
                    database.notebookDao().deleteNotebookById(nb.id)
                }
                inspectingNotebook = null
            },
            onDismiss = { inspectingNotebook = null }
        )
    }
}

@Composable
private fun NewNameModalDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    ModalBackdropBox(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp)
                .widthIn(min = 300.dp, max = 420.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Mégse") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = text.isNotBlank(),
                        onClick = { onConfirm(text.trim()) }
                    ) {
                        Text("Létrehozás")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotebookDetailsDialog(
    database: AppDatabase,
    notebook: NotebookEntity,
    allTags: List<TagEntity>,
    attachedTags: List<TagEntity>,
    onOpenPage: (String) -> Unit,
    onPickForSplitScreen: (String) -> Unit,
    onDeleteNotebook: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pages by database.pageDao().observePagesForNotebook(notebook.id).collectAsState(initial = emptyList())
    val chapters by database.chapterDao().observeChapters(notebook.id).collectAsState(initial = emptyList())
    val bookmarks by database.bookmarkDao().observeBookmarksForNotebook(notebook.id).collectAsState(initial = emptyList())
    val bookmarkedPageIds = remember(bookmarks) { bookmarks.map { it.pageId }.toSet() }
    val attachedTagIds = remember(attachedTags) { attachedTags.map { it.id }.toSet() }

    ModalBackdropBox(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp)
                .widthIn(min = 360.dp, max = 640.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(notebook.title, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDeleteNotebook) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Jegyzetfüzet törlése",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (allTags.isNotEmpty()) {
                    Text("Címkék", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allTags.forEach { tag ->
                            val attached = tag.id in attachedTagIds
                            FilterChip(
                                selected = attached,
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        if (attached) {
                                            database.tagDao().detachTagFromNotebook(notebook.id, tag.id)
                                        } else {
                                            database.tagDao().attachTagToNotebook(
                                                NotebookTagCrossRef(notebook.id, tag.id)
                                            )
                                        }
                                    }
                                },
                                label = { Text(tag.name) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Oldalak (${pages.size}) • Fejezetek (${chapters.size})", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    database.chapterDao().upsertChapter(
                                        ChapterEntity(
                                            id = UUID.randomUUID().toString(),
                                            notebookId = notebook.id,
                                            title = "${chapters.size + 1}. fejezet",
                                            sortOrder = chapters.size,
                                            createdAtEpochMs = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        ) {
                            Text("+ Fejezet")
                        }
                        Button(
                            onClick = {
                                val now = System.currentTimeMillis()
                                scope.launch(Dispatchers.IO) {
                                    database.pageDao().upsertPage(
                                        PageEntity(
                                            id = UUID.randomUUID().toString(),
                                            notebookId = notebook.id,
                                            chapterId = chapters.lastOrNull()?.id,
                                            pageIndexInNotebook = pages.size,
                                            canvasMode = notebook.defaultCanvasMode,
                                            pageSizePreset = notebook.defaultPageSizePreset,
                                            templateType = notebook.defaultTemplateType,
                                            createdAtEpochMs = now,
                                            updatedAtEpochMs = now
                                        )
                                    )
                                }
                            }
                        ) {
                            Text("+ Oldal")
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pages, key = { it.id }) { page ->
                        val isBookmarked = page.id in bookmarkedPageIds
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPage(page.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${page.pageIndexInNotebook + 1}. oldal",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "${page.canvasMode.displayName} • ${page.templateType.displayName}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                if (isBookmarked) {
                                                    bookmarks.firstOrNull { it.pageId == page.id }?.let { existing ->
                                                        database.bookmarkDao().deleteBookmarkById(existing.id)
                                                    }
                                                } else {
                                                    database.bookmarkDao().upsertBookmark(
                                                        BookmarkEntity(
                                                            id = UUID.randomUUID().toString(),
                                                            notebookId = notebook.id,
                                                            pageId = page.id,
                                                            label = "${notebook.title} – ${page.pageIndexInNotebook + 1}. oldal",
                                                            createdAtEpochMs = System.currentTimeMillis()
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                            contentDescription = "Könyvjelző"
                                        )
                                    }
                                    IconButton(onClick = { onPickForSplitScreen(page.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Splitscreen,
                                            contentDescription = "Split-Screen társoldal választása"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Bezárás") }
                }
            }
        }
    }
}

/**
 * Hosts either a single active notebook page or two side-by-side notebook pages in Split-Screen
 * mode with an adjustable divider and strictly independent [CanvasStateHolder] and
 * [StrokeJournal] instances per pane (Section 8).
 */
@Composable
fun SplitScreenCanvasWorkspace(
    database: AppDatabase,
    primaryTarget: OpenedPageTarget,
    secondaryTarget: OpenedPageTarget?,
    penButtonToggleTick: Long,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onExitToBookshelf: () -> Unit,
    onCloseSecondaryPane: () -> Unit,
    onSelectSecondaryFromBookshelf: () -> Unit,
    showDebugOverlay: Boolean = false,
    onToggleDebugOverlay: () -> Unit = {},
    benchmarkStrokeCount: Int = 0,
    modifier: Modifier = Modifier
) {
    var focusedPane by remember { mutableIntStateOf(0) }
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var containerWidthPx by remember { mutableFloatStateOf(1280f) }

    val cacheBudgetPerPane = if (secondaryTarget != null) {
        DryInkRasterCache.DEFAULT_MAX_CACHE_BYTES / 2L
    } else {
        DryInkRasterCache.DEFAULT_MAX_CACHE_BYTES
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0) containerWidthPx = size.width.toFloat()
            }
    ) {
        Box(
            modifier = Modifier
                .weight(if (secondaryTarget != null) splitFraction else 1f)
                .fillMaxHeight()
        ) {
            NotebookPagePane(
                database = database,
                target = primaryTarget,
                cacheBudgetBytes = cacheBudgetPerPane,
                penButtonToggleTick = if (focusedPane == 0 || secondaryTarget == null) penButtonToggleTick else 0L,
                isDarkTheme = isDarkTheme,
                showDebugOverlay = showDebugOverlay,
                benchmarkStrokeCount = benchmarkStrokeCount,
                onToggleTheme = onToggleTheme,
                onToggleDebugOverlay = onToggleDebugOverlay,
                onFocused = { focusedPane = 0 },
                onBackToShelf = onExitToBookshelf,
                onSplitAction = if (secondaryTarget == null) onSelectSecondaryFromBookshelf else onCloseSecondaryPane,
                isSplitActive = secondaryTarget != null,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (secondaryTarget != null) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaFraction = dragAmount / containerWidthPx.coerceAtLeast(1f)
                            splitFraction = (splitFraction + deltaFraction).coerceIn(0.28f, 0.72f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f - splitFraction)
                    .fillMaxHeight()
            ) {
                NotebookPagePane(
                    database = database,
                    target = secondaryTarget,
                    cacheBudgetBytes = cacheBudgetPerPane,
                    penButtonToggleTick = if (focusedPane == 1) penButtonToggleTick else 0L,
                    isDarkTheme = isDarkTheme,
                    showDebugOverlay = showDebugOverlay,
                    benchmarkStrokeCount = 0,
                    onToggleTheme = onToggleTheme,
                    onToggleDebugOverlay = onToggleDebugOverlay,
                    onFocused = { focusedPane = 1 },
                    onBackToShelf = onCloseSecondaryPane,
                    onSplitAction = onCloseSecondaryPane,
                    isSplitActive = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun NotebookPagePane(
    database: AppDatabase,
    target: OpenedPageTarget,
    cacheBudgetBytes: Long,
    penButtonToggleTick: Long,
    isDarkTheme: Boolean,
    showDebugOverlay: Boolean,
    benchmarkStrokeCount: Int,
    onToggleTheme: () -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onFocused: () -> Unit,
    onBackToShelf: () -> Unit,
    onSplitAction: () -> Unit,
    isSplitActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val transformMatrix = remember(target) { CanvasTransformMatrix() }
    val canvasStateHolder = remember(target) { CanvasStateHolder() }
    val dryInkRasterCache = remember(target, cacheBudgetBytes) {
        DryInkRasterCache(maxCacheBytes = cacheBudgetBytes)
    }
    val oledStrokeCache = remember(target) { OledStrokeRenderCache() }
    val laserStateHolder = remember(target) { LaserTrailStateHolder() }
    val presetStore = remember(context) { BrushPresetStore(context.applicationContext) }
    val presets by presetStore.presetsFlow.collectAsState(initial = BrushPresetStore.defaultPresets)

    var paperConfig by remember(target) { mutableStateOf(PagePaperConfig()) }
    var brushConfig by remember(target) { mutableStateOf(BrushConfig()) }
    var lastDrawingTool by remember(target) { mutableStateOf(BrushToolType.FOUNTAIN_PEN) }
    var lastEraserTool by remember(target) { mutableStateOf(BrushToolType.PRECISION_ERASER) }
    var dockEdge by remember(target) { mutableStateOf(DockEdge.TOP) }
    var showBrushSettings by remember(target) { mutableStateOf(false) }
    var showPaperSettings by remember(target) { mutableStateOf(false) }
    var showFunctionPlotter by remember(target) { mutableStateOf(false) }
    var plotterInitialExpr by remember(target) { mutableStateOf("sin(x)") }
    var showAiCopilot by remember(target) { mutableStateOf(false) }
    var showConsentDialog by remember(target) { mutableStateOf(false) }
    var selectedObjectId by remember(target) { mutableStateOf<String?>(null) }
    var discoveredFlashModels by remember { mutableStateOf<List<GeminiModelInfo>>(emptyList()) }
    var copilotNoticeBannerHu by remember(target) { mutableStateOf<String?>(null) }

    var notebookTitle by remember(target) { mutableStateOf("Jegyzetfüzet") }
    var pageNumber by remember(target) { mutableIntStateOf(1) }
    var isReadOnlyPage by remember(target) { mutableStateOf(false) }
    var notebookOcrTexts by remember(target) { mutableStateOf<List<String>>(emptyList()) }

    val aiConsentStore = remember(context) { AiConsentStore(context.applicationContext) }
    val modelCatalog = remember { ModelCatalog() }
    val geminiClient = remember(aiConsentStore, modelCatalog) {
        GeminiClient(consentStore = aiConsentStore, modelCatalog = modelCatalog)
    }
    val consentState by aiConsentStore.stateFlow.collectAsState(initial = AiConsentState())
    val pdfExportManager = remember(context) { PdfExportManager(context.applicationContext) }

    val palette = LocalStylusSurfacePalette.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var currentZoomPercent by remember(target) { mutableIntStateOf(100) }
    var externalDrawTick by remember { mutableLongStateOf(0L) }
    var historyRevisionTick by remember { mutableLongStateOf(0L) }

    val strokeJournal = remember(target) {
        StrokeJournal(
            pageDirectory = File(context.filesDir, "pages/${target.pageId}"),
            activeBrushConfigProvider = { brushConfig },
            shapeMetaProvider = { strokeId -> canvasStateHolder.getShapeMeta(strokeId) }
        )
    }

    fun syncZoomPercentFromMatrix() {
        val nextPct = (transformMatrix.scale * 100f).roundToInt()
        if (nextPct != currentZoomPercent) {
            currentZoomPercent = nextPct
        }
    }

    fun applyStepZoom(zoomFactor: Float) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return
        transformMatrix.zoomAroundScreenPoint(
            focalXPx = viewportSize.width * 0.5f,
            focalYPx = viewportSize.height * 0.5f,
            zoomFactor = zoomFactor
        )
        dryInkRasterCache.onGestureSettled(transformMatrix.scale)
        syncZoomPercentFromMatrix()
        externalDrawTick++
    }

    fun fitPageToViewportNow() {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return
        transformMatrix.fitPageToViewport(
            pageWidthPt = paperConfig.pageDimensions.widthPt,
            pageHeightPt = paperConfig.pageDimensions.heightPt,
            viewportWidthPx = viewportSize.width.toFloat(),
            viewportHeightPx = viewportSize.height.toFloat()
        )
        dryInkRasterCache.onGestureSettled(transformMatrix.scale)
        syncZoomPercentFromMatrix()
        externalDrawTick++
    }

    fun currentPersistedStrokes(): List<PersistedStrokeRecord> =
        canvasStateHolder.allStrokesSorted().map { entry ->
            StrokeBinarySerializer.fromIndexedDryStroke(
                entry = entry,
                brushConfig = brushConfig,
                shapeMeta = canvasStateHolder.getShapeMeta(entry.id)
            )
        }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    val liveStrokes = canvasStateHolder.allStrokesSorted().associate { it.id to it.stroke }
                    val exportPage = ExportablePdfPage(
                        pageNumberOneBased = pageNumber,
                        paperConfig = paperConfig,
                        strokes = currentPersistedStrokes(),
                        objects = canvasStateHolder.allPageObjects(),
                        liveInkStrokesById = liveStrokes
                    )
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        pdfExportManager.exportPagesToPdf(listOf(exportPage), out)
                    }
                }.onFailure { err ->
                    copilotNoticeBannerHu = err.message ?: "PDF export hiba."
                }
            }
        }
    }

    LaunchedEffect(target, benchmarkStrokeCount) {
        if (benchmarkStrokeCount > 0) {
            notebookTitle = "Benchmark ($benchmarkStrokeCount vonal)"
            pageNumber = 1
            paperConfig = PagePaperConfig()
            val synthetic = withContext(Dispatchers.Default) {
                generateSyntheticBenchmarkStrokes(benchmarkStrokeCount)
            }
            canvasStateHolder.restoreLoadedStrokes(synthetic, emptyMap())
            canvasStateHolder.restoreLoadedObjects(emptyList())
            dryInkRasterCache.clearAll()
            val settledViewport = snapshotFlow { viewportSize }.first { it.width > 0 && it.height > 0 }
            val isOled = palette.isOledDeepBlack
            withContext(Dispatchers.Default) {
                dryInkRasterCache.prewarmVisibleTiles(
                    viewportWidthPx = settledViewport.width,
                    viewportHeightPx = settledViewport.height,
                    transformMatrix = transformMatrix,
                    spatialIndex = canvasStateHolder.spatialIndex,
                    isOledMode = isOled,
                    pageWidthPt = paperConfig.pageDimensions.widthPt,
                    pageHeightPt = paperConfig.pageDimensions.heightPt,
                    paperConfig = paperConfig,
                    palette = palette,
                    strokeAdapter = { entry -> oledStrokeCache.adaptStroke(entry, isOled) }
                )
            }
            syncZoomPercentFromMatrix()
            externalDrawTick++
            historyRevisionTick++
            notebookTitle = "Benchmark ($benchmarkStrokeCount vonal) • Kész"
            return@LaunchedEffect
        }

        val nb = withContext(Dispatchers.IO) { database.notebookDao().getNotebookById(target.notebookId) }
        val page = withContext(Dispatchers.IO) { database.pageDao().getPageById(target.pageId) }
        val ocrTexts = withContext(Dispatchers.IO) {
            database.pageTextSearchDao().getRecognizedTextsForNotebook(target.notebookId)
        }
        notebookOcrTexts = ocrTexts
        if (nb != null) notebookTitle = nb.title
        if (page != null) {
            pageNumber = page.pageIndexInNotebook + 1
            paperConfig = page.pageSizePreset.toPagePaperConfig(
                canvasMode = page.canvasMode,
                templateType = page.templateType
            )
        }

        val recovery = strokeJournal.openAndRecover()
        isReadOnlyPage = recovery.isReadOnly
        val loadedStrokes = ArrayList<IndexedDryStroke>(recovery.strokes.size)
        val loadedMetas = LinkedHashMap<String, ShapeMeta>()
        for (record in recovery.strokes) {
            val (dry, _) = StrokeBinarySerializer.toIndexedDryStroke(record)
            loadedStrokes.add(dry)
            record.shapeMeta?.let { loadedMetas[record.id] = it }
        }
        canvasStateHolder.restoreLoadedStrokes(loadedStrokes, loadedMetas)
        canvasStateHolder.restoreLoadedObjects(recovery.objects)
        dryInkRasterCache.clearAll()
        externalDrawTick++
        historyRevisionTick++
    }

    DisposableEffect(lifecycleOwner, strokeJournal, target.pageId, benchmarkStrokeCount) {
        if (benchmarkStrokeCount > 0) {
            return@DisposableEffect onDispose {}
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                strokeJournal.flushAndSyncBlocking()
                BackgroundOcrWorker.enqueueForPage(context, target.pageId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            strokeJournal.closeBlocking(compactBeforeClose = !strokeJournal.isReadOnly)
            BackgroundOcrWorker.enqueueForPage(context, target.pageId)
        }
    }

    historyRevisionTick
    val canUndo = canvasStateHolder.canUndo
    val canRedo = canvasStateHolder.canRedo

    fun selectTool(nextTool: BrushToolType) {
        if (nextTool.isDrawingBrush && !nextTool.isLaser) {
            lastDrawingTool = nextTool
        } else if (nextTool.isEraser) {
            lastEraserTool = nextTool
        }
        brushConfig = when {
            nextTool == BrushToolType.HIGHLIGHTER && !brushConfig.toolType.isHighlighter ->
                brushConfig.copy(
                    toolType = BrushToolType.HIGHLIGHTER,
                    sizePt = brushConfig.sizePt.coerceAtLeast(14f),
                    colorArgb = BrushConfig.DEFAULT_HIGHLIGHTER_COLOR_ARGB,
                    opacity = BrushConfig.HIGHLIGHTER_OPACITY
                )
            nextTool == BrushToolType.LASER ->
                brushConfig.copy(
                    toolType = BrushToolType.LASER,
                    colorArgb = BrushConfig.DEFAULT_LASER_COLOR_ARGB,
                    sizePt = brushConfig.sizePt.coerceIn(3f, 8f)
                )
            else ->
                brushConfig.copy(
                    toolType = nextTool,
                    colorArgb = if (brushConfig.toolType == BrushToolType.HIGHLIGHTER ||
                        brushConfig.toolType == BrushToolType.LASER
                    ) {
                        BrushConfig.DEFAULT_INK_COLOR_ARGB
                    } else {
                        brushConfig.colorArgb
                    },
                    sizePt = if (brushConfig.toolType == BrushToolType.HIGHLIGHTER &&
                        brushConfig.sizePt >= 14f
                    ) {
                        BrushConfig.DEFAULT_BRUSH_SIZE_PT
                    } else {
                        brushConfig.sizePt
                    },
                    opacity = if (brushConfig.opacity == BrushConfig.HIGHLIGHTER_OPACITY) 1.0f else brushConfig.opacity
                )
        }
    }

    LaunchedEffect(penButtonToggleTick) {
        if (penButtonToggleTick > 0L) {
            val next = if (brushConfig.toolType.isEraser) lastDrawingTool else lastEraserTool
            selectTool(next)
        }
    }

    Box(
        modifier = modifier.pointerInput(target) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.pressed }) {
                        onFocused()
                    }
                }
            }
        }
    ) {
        StylusDrawingSurface(
            transformMatrix = transformMatrix,
            paperConfig = paperConfig,
            brushConfig = brushConfig,
            lastEraserTool = lastEraserTool,
            dryStrokeStore = canvasStateHolder,
            dryInkRasterCache = dryInkRasterCache,
            oledStrokeCache = oledStrokeCache,
            laserStateHolder = laserStateHolder,
            journalCommitSink = strokeJournal,
            onTogglePenEraser = {
                val next = if (brushConfig.toolType.isEraser) lastDrawingTool else lastEraserTool
                selectTool(next)
            },
            onCanvasStateMutated = {
                historyRevisionTick++
                if (benchmarkStrokeCount == 0) {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.pageDao().markOcrSyncState(
                            pageId = target.pageId,
                            needsSync = true,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                }
            },
            onViewportSizeChanged = { viewportSize = it },
            onViewportTransformChanged = { syncZoomPercentFromMatrix() },
            externalDrawVersionTick = externalDrawTick,
            modifier = Modifier.fillMaxSize()
        )

        historyRevisionTick
        val livePageObjects = canvasStateHolder.allPageObjects()
        if (livePageObjects.isNotEmpty()) {
            PageObjectsOverlayLayer(
                pageObjects = livePageObjects,
                transformMatrix = transformMatrix,
                isOledMode = isDarkTheme,
                selectedObjectId = selectedObjectId,
                onSelectObject = { selectedObjectId = it },
                onUpdateObject = { updatedObj ->
                    canvasStateHolder.updatePageObject(updatedObj)
                    historyRevisionTick++
                    coroutineScope.launch { strokeJournal.appendUpdateObject(updatedObj) }
                },
                onDeleteObject = { objId ->
                    canvasStateHolder.removePageObject(objId)
                    if (selectedObjectId == objId) selectedObjectId = null
                    historyRevisionTick++
                    coroutineScope.launch { strokeJournal.appendRemoveObject(objId) }
                },
                onRequestStepByStep = { latexObj ->
                    if (!consentState.isReadyForUserInitiatedCall) {
                        showConsentDialog = true
                    } else {
                        coroutineScope.launch {
                            HandwritingToLatexConverter.generateStepByStepDerivationCard(
                                latexObject = latexObj,
                                geminiClient = geminiClient,
                                canvasStateHolder = canvasStateHolder,
                                journalSink = strokeJournal
                            ).onSuccess {
                                historyRevisionTick++
                            }.onFailure { err ->
                                copilotNoticeBannerHu = err.message ?: "Sikertelen levezetés."
                            }
                        }
                    }
                },
                onPlotFromLatex = { latexObj ->
                    plotterInitialExpr = latexObj.latexSource
                    showFunctionPlotter = true
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        CanvasTopToolbar(
            notebookTitle = notebookTitle,
            pageNumber = pageNumber,
            zoomPercentProvider = { currentZoomPercent },
            isSplitActive = isSplitActive,
            onBackToShelf = onBackToShelf,
            onZoomOut = { applyStepZoom(0.8f) },
            onResetZoom = { fitPageToViewportNow() },
            onZoomIn = { applyStepZoom(1.25f) },
            onOpenFunctionPlotter = { showFunctionPlotter = true },
            onOpenAiCopilot = { showAiCopilot = true },
            onSplitAction = onSplitAction,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(12.dp)
        )

        copilotNoticeBannerHu?.let { notice ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .clickable { copilotNoticeBannerHu = null },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (isReadOnlyPage) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = "Újabb formátumverzió: csak olvasható mód (nem írható felül)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        FloatingToolDock(
            brushConfig = brushConfig,
            dockEdge = dockEdge,
            viewportSize = viewportSize,
            canUndo = canUndo,
            canRedo = canRedo,
            isDarkTheme = isDarkTheme,
            showDebugOverlay = showDebugOverlay,
            onDockEdgeChanged = { dockEdge = it },
            onSelectTool = { selectTool(it) },
            onOpenSettings = { showBrushSettings = true },
            onOpenPaperSettings = { showPaperSettings = true },
            onUndo = {
                canvasStateHolder.undo()?.let { dryInkRasterCache.invalidateDocRect(it) }
                externalDrawTick++
                historyRevisionTick++
            },
            onRedo = {
                canvasStateHolder.redo()?.let { dryInkRasterCache.invalidateDocRect(it) }
                externalDrawTick++
                historyRevisionTick++
            },
            onFitPage = { fitPageToViewportNow() },
            onToggleTheme = onToggleTheme,
            onToggleDebugOverlay = onToggleDebugOverlay,
            modifier = Modifier.fillMaxSize()
        )

        if (showBrushSettings) {
            BrushSettingsPopover(
                currentConfig = brushConfig,
                presets = presets,
                onConfigChange = { updated -> brushConfig = updated },
                onSavePresetSlot = { slot, cfg ->
                    coroutineScope.launch { presetStore.savePreset(slot, cfg) }
                },
                onApplyPreset = { preset -> brushConfig = preset },
                onDismiss = { showBrushSettings = false }
            )
        }

        if (showPaperSettings) {
            PaperSettingsDialog(
                currentConfig = paperConfig,
                onConfigChange = { updated ->
                    paperConfig = updated
                    externalDrawTick++
                    coroutineScope.launch(Dispatchers.IO) {
                        val existing = database.pageDao().getPageById(target.pageId)
                        if (existing != null) {
                            database.pageDao().upsertPage(
                                existing.copy(
                                    canvasMode = updated.canvasMode,
                                    templateType = updated.templateType,
                                    updatedAtEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                },
                onDismiss = { showPaperSettings = false }
            )
        }

        if (showFunctionPlotter) {
            FunctionPlotterDialog(
                initialExpression = plotterInitialExpr,
                onDismiss = { showFunctionPlotter = false },
                onInsertPlot = { expr, xMin, xMax, yMin, yMax ->
                    val plotObj = PageObject.FunctionPlotObject(
                        id = UUID.randomUUID().toString(),
                        zOrder = canvasStateHolder.allocateObjectZOrder(),
                        bounds = DocRect(90f, 120f, 390f, 340f),
                        expression = expr,
                        domainMinX = xMin,
                        domainMaxX = xMax,
                        rangeMinY = yMin,
                        rangeMaxY = yMax,
                        canonicalColorArgb = brushConfig.colorArgb
                    )
                    canvasStateHolder.addPageObject(plotObj)
                    selectedObjectId = plotObj.id
                    historyRevisionTick++
                    coroutineScope.launch { strokeJournal.appendAddObject(plotObj) }
                }
            )
        }

        if (showAiCopilot) {
            AiCopilotSheet(
                notebookTitle = notebookTitle,
                consentState = consentState,
                currentPageStrokes = currentPersistedStrokes(),
                notebookOcrTexts = notebookOcrTexts,
                onOpenConsentSettings = { showConsentDialog = true },
                onConvertStrokesToLatex = {
                    val result = HandwritingToLatexConverter.convertLassoToLatex(
                        selectedStrokes = currentPersistedStrokes(),
                        geminiClient = geminiClient,
                        canvasStateHolder = canvasStateHolder,
                        context = context,
                        journalSink = strokeJournal
                    )
                    when (result) {
                        is LatexConversionResult.Success -> {
                            dryInkRasterCache.clearAll()
                            externalDrawTick++
                            historyRevisionTick++
                            selectedObjectId = result.latexObject.id
                            result.modelSwitchedNoticeHu ?: "Képlet vektoros LaTeX objektummá alakítva (${result.modelIdUsed})."
                        }
                        is LatexConversionResult.InvalidLatexError -> result.messageHu
                    }
                },
                onInsertDerivationCard = { cardTitle, cardBody ->
                    val card = PageObject.DerivationCardObject(
                        id = UUID.randomUUID().toString(),
                        zOrder = canvasStateHolder.allocateObjectZOrder(),
                        bounds = DocRect(72f, 120f, 432f, 310f),
                        title = cardTitle,
                        stepsMarkdownLatex = cardBody
                    )
                    canvasStateHolder.addPageObject(card)
                    selectedObjectId = card.id
                    historyRevisionTick++
                    strokeJournal.appendAddObject(card)
                },
                onExportPagePdf = {
                    exportPdfLauncher.launch("${notebookTitle.ifBlank { "oldal" }}.pdf")
                },
                geminiClient = geminiClient,
                onDismiss = { showAiCopilot = false }
            )
        }

        if (showConsentDialog) {
            ConsentDialog(
                currentState = consentState,
                availableFlashModels = discoveredFlashModels,
                onDismiss = { showConsentDialog = false },
                onSaveSettings = { accepted, aiEnabled, newKey, modelOverride ->
                    if (newKey != null) {
                        aiConsentStore.saveApiKey(newKey)
                    }
                    if (accepted) {
                        aiConsentStore.recordConsentAccepted(enableAi = aiEnabled)
                    } else {
                        aiConsentStore.revokeConsentAndDisableAi(clearApiKey = false)
                    }
                    aiConsentStore.setModelOverride(modelOverride)
                },
                onClearApiKey = { aiConsentStore.clearApiKey() },
                onRevokeConsent = { aiConsentStore.revokeConsentAndDisableAi(clearApiKey = false) },
                onRefreshModels = { typedKey ->
                    runCatching {
                        val key = typedKey ?: aiConsentStore.readDecryptedApiKey()
                            ?: throw IllegalStateException("Előbb adj meg egy API-kulcsot.")
                        val refreshed = modelCatalog.refreshCatalog(key)
                        discoveredFlashModels = refreshed.availableFlashModels
                        refreshed.availableFlashModels
                    }
                }
            )
        }
    }
}

@Composable
private fun CanvasTopToolbar(
    notebookTitle: String,
    pageNumber: Int,
    zoomPercentProvider: () -> Int,
    isSplitActive: Boolean,
    onBackToShelf: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onZoomIn: () -> Unit,
    onOpenFunctionPlotter: () -> Unit,
    onOpenAiCopilot: () -> Unit,
    onSplitAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBackToShelf,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Vissza a polcra",
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = notebookTitle.ifBlank { "Jegyzet" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp)
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
            ) {
                Text(
                    text = "$pageNumber. o.",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            VerticalDivider(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(20.dp)
            )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    IconButton(
                        onClick = onZoomOut,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Kicsinyítés",
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    ZoomPercentLabel(
                        zoomPercentProvider = zoomPercentProvider,
                        onClick = onResetZoom
                    )

                    IconButton(
                        onClick = onZoomIn,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Nagyítás",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            VerticalDivider(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(20.dp)
            )

            IconButton(
                onClick = onOpenFunctionPlotter,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Functions,
                    contentDescription = "Függvényrajzoló",
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenAiCopilot,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Segéd",
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onSplitAction,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isSplitActive) Icons.Default.Close else Icons.Default.Splitscreen,
                    contentDescription = if (isSplitActive) "Osztott nézet bezárása" else "Osztott nézet",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ZoomPercentLabel(
    zoomPercentProvider: () -> Int,
    onClick: () -> Unit
) {
    // Isolate snapshot reads and keep minimum width so live % updates do not reflow the toolbar.
    Text(
        text = "${zoomPercentProvider()}%",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Volatile
private var cachedSyntheticBenchmarkStrokes: List<IndexedDryStroke>? = null

private fun generateSyntheticBenchmarkStrokes(count: Int): List<IndexedDryStroke> {
    if (count <= 0) return emptyList()
    cachedSyntheticBenchmarkStrokes?.let { cached ->
        if (cached.size == count) return cached
    }
    val brush = BrushConfig(
        toolType = BrushToolType.BALLPOINT_PEN,
        sizePt = 2.0f
    ).toInkBrush()
    val reusableInput = StrokeInput()
    val cols = 100
    val rows = ((count + cols - 1) / cols).coerceAtLeast(1)
    val minX = 36f
    val spanX = 520f
    val minY = 48f
    val spanY = 740f
    val result = ArrayList<IndexedDryStroke>(count)

    for (i in 0 until count) {
        val col = i % cols
        val row = i / cols
        val originX = minX + (col.toFloat() / cols) * spanX
        val originY = minY + (row.toFloat() / rows) * spanY
        val batch = MutableStrokeInputBatch()
        for (pt in 0 until 5) {
            val dx = pt * 0.85f
            val dy = if (pt % 2 == 0) 0f else 1.4f
            reusableInput.update(
                x = originX + dx,
                y = originY + dy,
                elapsedTimeMillis = pt * 6L,
                toolType = InputToolType.STYLUS,
                pressure = 0.55f,
                tiltRadians = 0.1f,
                orientationRadians = 0f
            )
            batch.add(reusableInput)
        }
        val stroke = Stroke(brush = brush, inputs = batch)
        result.add(
            IndexedDryStroke(
                id = "bench_$i",
                stroke = stroke,
                bounds = computeStrokeDocBounds(stroke),
                zIndex = CanvasStateHolder.INK_Z_ORDER_BASE + i
            )
        )
    }
    cachedSyntheticBenchmarkStrokes = result
    return result
}


