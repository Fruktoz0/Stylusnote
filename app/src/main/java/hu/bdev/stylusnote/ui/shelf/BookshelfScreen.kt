package hu.bdev.stylusnote.ui.shelf

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeDrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import hu.bdev.stylusnote.core.ai.GeminiApiSupport
import hu.bdev.stylusnote.core.ai.GeminiClient
import hu.bdev.stylusnote.core.ai.GeminiModelInfo
import hu.bdev.stylusnote.core.ai.InNotebookSearchCoordinator
import hu.bdev.stylusnote.core.ai.ModelCatalog
import hu.bdev.stylusnote.core.ai.NotebookSearchMatch
import hu.bdev.stylusnote.core.ai.SearchExecutionResult
import hu.bdev.stylusnote.core.ai.SearchIndexState
import hu.bdev.stylusnote.core.ai.SearchTextIndexer
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.ContinuousPageLayout
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.OledStrokeRenderCache
import hu.bdev.stylusnote.core.math.PageLayoutSlot
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.core.math.ShapeRecognizer
import hu.bdev.stylusnote.data.export.CloudSyncScheduler
import hu.bdev.stylusnote.data.export.ExportablePdfPage
import hu.bdev.stylusnote.data.export.PdfExportManager
import hu.bdev.stylusnote.data.export.PdfTileRenderer
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
import hu.bdev.stylusnote.data.local.SyncTombstoneEntity
import hu.bdev.stylusnote.data.local.TagEntity
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.GeometricShapeType
import hu.bdev.stylusnote.domain.model.NotebookCover
import hu.bdev.stylusnote.domain.model.OcrLanguageTag
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PageSizePreset
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.DryInkRasterCache
import hu.bdev.stylusnote.ui.canvas.HandwritingToLatexConverter
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.LassoAutoFormatResult
import hu.bdev.stylusnote.ui.canvas.LeftPageDrawerOverlay
import hu.bdev.stylusnote.ui.canvas.LeftPagePullTab
import hu.bdev.stylusnote.ui.canvas.LiveObjectInteractionController
import hu.bdev.stylusnote.ui.canvas.MultiPageSessionManager
import hu.bdev.stylusnote.ui.canvas.NotebookPageRuntime
import hu.bdev.stylusnote.ui.canvas.PageObjectsOverlayLayer
import hu.bdev.stylusnote.ui.canvas.PageThumbnailRenderer
import hu.bdev.stylusnote.ui.canvas.PageThumbnailSourceSnapshot
import hu.bdev.stylusnote.ui.canvas.StylusDrawingSurface
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import hu.bdev.stylusnote.ui.canvas.resolveNextActivePageAfterDelete
import hu.bdev.stylusnote.ui.theme.StylusIcons
import hu.bdev.stylusnote.ui.components.AiCopilotScope
import hu.bdev.stylusnote.ui.components.AiCopilotSheet
import hu.bdev.stylusnote.ui.components.AiFormattingMode
import hu.bdev.stylusnote.ui.components.BrushPresetStore
import hu.bdev.stylusnote.ui.components.BrushSettingsPopover
import hu.bdev.stylusnote.ui.components.ConsentDialog
import hu.bdev.stylusnote.ui.components.FloatingToolDock
import hu.bdev.stylusnote.ui.components.InNotebookSearchToolbarSection
import hu.bdev.stylusnote.ui.components.PaperSettingsDialog
import hu.bdev.stylusnote.ui.components.SearchHighlightCanvasOverlay
import hu.bdev.stylusnote.ui.components.ShapePickerPopover
import hu.bdev.stylusnote.ui.components.ToolDockPreferencesStore
import hu.bdev.stylusnote.ui.components.ToolDockPrefs
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class OpenedPageTarget(
    val notebookId: String,
    val pageId: String
)

enum class FixedPageScrollNavigationAction {
    NONE,
    PREVIOUS_PAGE,
    NEXT_PAGE,
    CREATE_NEXT_PAGE
}

/**
 * Scale-aware vertical scroll threshold for FIXED_PAGE multi-page navigation.
 * Uses the visible document slice beyond top/bottom page boundaries together with
 * viewport center position so pinch-zooming out does not flip pages, while vertical
 * scrolling between sheets responds cleanly across 100%-250% zoom.
 */
internal fun resolveFixedPageScrollNavigation(
    visibleRect: DocRect,
    centerDocY: Float,
    pageHeightPt: Float,
    currentPageIndex: Int,
    totalPages: Int
): FixedPageScrollNavigationAction {
    val visibleHeightPt = visibleRect.bottomPt - visibleRect.topPt
    if (visibleHeightPt <= 0f || pageHeightPt <= 0f) {
        return FixedPageScrollNavigationAction.NONE
    }
    val switchRevealThresholdPt = maxOf(96f, visibleHeightPt * 0.28f)
    val createThresholdPt = maxOf(switchRevealThresholdPt * 1.35f, 180f)
    val pastBottomPt = visibleRect.bottomPt - pageHeightPt
    val pastTopPt = -visibleRect.topPt

    if (pastBottomPt >= switchRevealThresholdPt && centerDocY > pageHeightPt * 0.60f) {
        if (currentPageIndex >= 0 && currentPageIndex + 1 < totalPages) {
            return FixedPageScrollNavigationAction.NEXT_PAGE
        }
        if (currentPageIndex >= 0 && pastBottomPt >= createThresholdPt && centerDocY > pageHeightPt * 0.72f) {
            return FixedPageScrollNavigationAction.CREATE_NEXT_PAGE
        }
    } else if (
        currentPageIndex > 0 &&
        pastTopPt >= switchRevealThresholdPt &&
        centerDocY < pageHeightPt * 0.40f
    ) {
        return FixedPageScrollNavigationAction.PREVIOUS_PAGE
    }
    return FixedPageScrollNavigationAction.NONE
}

private sealed interface ShelfSidebarSelection {
    data object All : ShelfSidebarSelection
    data object Uncategorized : ShelfSidebarSelection
    data object Bookmarks : ShelfSidebarSelection
    data class Category(val folderId: String) : ShelfSidebarSelection
}

private enum class ShelfTypeFilter(val label: String) {
    ALL("MIND"),
    NOTES("JEGYZET"),
    PDF("PDF")
}

private const val DROP_KEY_UNCATEGORIZED = "__UNCATEGORIZED__"

/**
 * Full Bookshelf workspace using StarNote's 2-pane layout: left sidebar with search and
 * drag-drop categories, and right grid with dashed creation tile and Moleskine notebook cards.
 */
@Composable
fun BookshelfScreen(
    database: AppDatabase,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSinglePage: (OpenedPageTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val surfacePalette = LocalStylusSurfacePalette.current
    val dateFormatter = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }

    val allFolders by database.folderDao().observeAllFolders().collectAsState(initial = emptyList())
    val allNotebooks by database.notebookDao().observeAllNotebooks().collectAsState(initial = emptyList())
    val allTags by database.tagDao().observeAllTags().collectAsState(initial = emptyList())
    val tagLinks by database.tagDao().observeAllNotebookTagLinks().collectAsState(initial = emptyList())
    val allBookmarks by database.bookmarkDao().observeAllBookmarks().collectAsState(initial = emptyList())
    val pdfNotebookIdList by database.pageDao().observePdfNotebookIds().collectAsState(initial = emptyList())
    val pdfNotebookIds = remember(pdfNotebookIdList) { pdfNotebookIdList.toSet() }

    var sidebarSelection by remember { mutableStateOf<ShelfSidebarSelection>(ShelfSidebarSelection.All) }
    var typeFilter by remember { mutableStateOf(ShelfTypeFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var ocrHits by remember { mutableStateOf<List<PageSearchHit>>(emptyList()) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var editingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var creatingNewNotebook by remember { mutableStateOf(false) }
    var inspectingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var moveTargetNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var showPdfImportDialog by remember { mutableStateOf(false) }
    var pdfImportTargetNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var notebookLmExportTarget by remember { mutableStateOf<NotebookEntity?>(null) }
    var bookPdfExportTarget by remember { mutableStateOf<NotebookEntity?>(null) }
    var showCloudBackupDialog by remember { mutableStateOf(false) }
    var shelfNoticeHu by remember { mutableStateOf<String?>(null) }

    val bookExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val nb = bookPdfExportTarget
        bookPdfExportTarget = null
        if (uri != null && nb != null) {
            scope.launch(Dispatchers.IO) {
                val res = runCatching {
                    val pages = database.pageDao().observePagesForNotebook(nb.id).first()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        exportNotebookPagesToPdfStream(
                            context = context,
                            database = database,
                            notebook = nb,
                            pagesToExport = pages,
                            outputStream = out
                        )
                    } ?: error("A célfájl nem írható.")
                }
                withContext(Dispatchers.Main) {
                    shelfNoticeHu = res.fold(
                        onSuccess = { "PDF sikeresen exportálva: ${nb.title}" },
                        onFailure = { err -> err.message ?: "Sikertelen PDF export." }
                    )
                }
            }
        }
    }

    // Drag-and-drop state for moving notebooks onto sidebar categories or Uncategorized.
    var rootWindowPos by remember { mutableStateOf(Offset.Zero) }
    val dropTargetBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var dragPointerWindowOffset by remember { mutableStateOf(Offset.Zero) }

    val hoveredDropKey = if (draggingNotebook != null) {
        dropTargetBounds.entries.firstOrNull { (_, bounds) ->
            bounds.contains(dragPointerWindowOffset)
        }?.key
    } else {
        null
    }

    LaunchedEffect(searchQuery) {
        ocrHits = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            database.pageTextSearchDao().searchAccentInsensitive(searchQuery)
        }
    }

    val folderById = remember(allFolders) { allFolders.associateBy { it.id } }
    val tagsByNotebookId = remember(tagLinks, allTags) {
        val tagMap = allTags.associateBy { it.id }
        tagLinks.groupBy { it.notebookId }
            .mapValues { (_, refs) -> refs.mapNotNull { tagMap[it.tagId] } }
    }

    val uncategorizedCount = remember(allNotebooks) {
        allNotebooks.count { it.folderId == null }
    }
    val folderCounts = remember(allNotebooks) {
        allNotebooks.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
    }

    val visibleNotebooks = remember(
        sidebarSelection,
        typeFilter,
        searchQuery,
        allNotebooks,
        pdfNotebookIds
    ) {
        val q = searchQuery.trim()
        val scopedBySidebar = if (q.isNotEmpty()) {
            allNotebooks.filter { it.title.contains(q, ignoreCase = true) }
        } else {
            when (val sel = sidebarSelection) {
                ShelfSidebarSelection.All -> allNotebooks
                ShelfSidebarSelection.Uncategorized -> allNotebooks.filter { it.folderId == null }
                ShelfSidebarSelection.Bookmarks -> allNotebooks
                is ShelfSidebarSelection.Category -> allNotebooks.filter { it.folderId == sel.folderId }
            }
        }
        when (typeFilter) {
            ShelfTypeFilter.ALL -> scopedBySidebar
            ShelfTypeFilter.NOTES -> scopedBySidebar.filter { it.id !in pdfNotebookIds }
            ShelfTypeFilter.PDF -> scopedBySidebar.filter { it.id in pdfNotebookIds }
        }
    }

    val activeSectionTitle = when (val sel = sidebarSelection) {
        ShelfSidebarSelection.All -> "Minden jegyzet"
        ShelfSidebarSelection.Uncategorized -> "Kategorizálatlan"
        ShelfSidebarSelection.Bookmarks -> "Könyvjelzők"
        is ShelfSidebarSelection.Category -> folderById[sel.folderId]?.name ?: "Kategória"
    }

    fun openDefaultPageForNotebook(nb: NotebookEntity) {
        scope.launch {
            val pages = withContext(Dispatchers.IO) {
                val existingId = nb.lastOpenedPageId
                if (existingId != null && database.pageDao().getPageById(existingId) != null) {
                    return@withContext existingId
                }
                val fallbackExisting = database.pageDao().getPagesForNotebook(nb.id).firstOrNull()
                if (fallbackExisting != null) {
                    database.notebookDao().upsertNotebook(nb.copy(lastOpenedPageId = fallbackExisting.id))
                    return@withContext fallbackExisting.id
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
            onOpenSinglePage(OpenedPageTarget(notebookId = nb.id, pageId = pages))
        }
    }

    fun moveNotebookToFolder(nb: NotebookEntity, targetFolderId: String?) {
        if (nb.folderId == targetFolderId) return
        scope.launch(Dispatchers.IO) {
            database.notebookDao().upsertNotebook(
                nb.copy(
                    folderId = targetFolderId,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { rootWindowPos = it.positionInWindow() }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left StarNote-style Sidebar
            Column(
                modifier = Modifier
                    .width(256.dp)
                    .fillMaxHeight()
                    .background(surfacePalette.sidebarBackground)
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = "StylusNote",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CloudSyncStatusBadgeButton(
                            onOpenSettingsDialog = { showCloudBackupDialog = true },
                            size = 34.dp,
                            containerColor = MaterialTheme.colorScheme.surface,
                            onQuickSyncTriggered = { shelfNoticeHu = it }
                        )
                        Surface(
                            onClick = onToggleTheme,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                    contentDescription = "Téma váltása",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Search Pill at Top of Left Sidebar (StarNote layout)
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Keresés...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Törlés",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                SidebarNavItem(
                    label = "Minden jegyzet",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    count = allNotebooks.size,
                    selected = sidebarSelection == ShelfSidebarSelection.All,
                    isDropHovered = false,
                    onClick = { sidebarSelection = ShelfSidebarSelection.All }
                )

                SidebarNavItem(
                    label = "Kategorizálatlan",
                    icon = Icons.Filled.Inbox,
                    count = uncategorizedCount,
                    selected = sidebarSelection == ShelfSidebarSelection.Uncategorized,
                    isDropHovered = hoveredDropKey == DROP_KEY_UNCATEGORIZED,
                    onClick = { sidebarSelection = ShelfSidebarSelection.Uncategorized },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        dropTargetBounds[DROP_KEY_UNCATEGORIZED] = coords.boundsInWindow()
                    }
                )

                SidebarNavItem(
                    label = "Könyvjelzők",
                    icon = Icons.Filled.Bookmark,
                    count = allBookmarks.size,
                    selected = sidebarSelection == ShelfSidebarSelection.Bookmarks,
                    isDropHovered = false,
                    onClick = { sidebarSelection = ShelfSidebarSelection.Bookmarks }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Kategóriák",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { showNewFolderDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Új kategória",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (allFolders.isEmpty()) {
                        item(key = "empty_categories_hint") {
                            Text(
                                text = "Nyomj a + gombra kategória létrehozásához, majd húzd rá a füzeteket.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    } else {
                        items(allFolders, key = { it.id }) { folder ->
                            DisposableEffect(folder.id) {
                                onDispose { dropTargetBounds.remove(folder.id) }
                            }
                            val isSelected = (sidebarSelection as? ShelfSidebarSelection.Category)?.folderId == folder.id
                            val isHovered = hoveredDropKey == folder.id
                            val count = folderCounts[folder.id] ?: 0
                            SidebarNavItem(
                                label = folder.name,
                                icon = Icons.Filled.Folder,
                                iconTint = Color(folder.accentColorArgb),
                                count = count,
                                selected = isSelected,
                                isDropHovered = isHovered,
                                onClick = { sidebarSelection = ShelfSidebarSelection.Category(folder.id) },
                                onDelete = {
                                    if (isSelected) {
                                        sidebarSelection = ShelfSidebarSelection.All
                                    }
                                    scope.launch(Dispatchers.IO) {
                                        // Unassign notebooks before deleting because NotebookEntity.folderId cascades on delete.
                                        allNotebooks
                                            .filter { it.folderId == folder.id }
                                            .forEach { childNb ->
                                                database.notebookDao().upsertNotebook(
                                                    childNb.copy(
                                                        folderId = null,
                                                        updatedAtEpochMs = System.currentTimeMillis()
                                                    )
                                                )
                                            }
                                        database.deleteWithTombstone(SyncTombstoneEntity.TYPE_FOLDER, folder.id)
                                        CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                                    }
                                },
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    dropTargetBounds[folder.id] = coords.boundsInWindow()
                                }
                            )
                        }
                    }
                }
            }

            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            // Right Main Workspace Area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) activeSectionTitle else "Keresési találatok",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        val countLabel = if (sidebarSelection == ShelfSidebarSelection.Bookmarks && searchQuery.isBlank()) {
                            "${allBookmarks.size} könyvjelző"
                        } else {
                            "${visibleNotebooks.size} elem"
                        }
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // StarNote-style Segmented Pill Tabs: MIND | JEGYZET | PDF
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = surfacePalette.groupedCardBackground
                        ) {
                            Row(
                                modifier = Modifier.padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ShelfTypeFilter.entries.forEach { filter ->
                                    val selected = typeFilter == filter
                                    Surface(
                                        onClick = { typeFilter = filter },
                                        shape = RoundedCornerShape(9.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.surface
                                        } else {
                                            Color.Transparent
                                        },
                                        shadowElevation = if (selected) 2.dp else 0.dp
                                    ) {
                                        Text(
                                            text = filter.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }

                        FilledTonalButton(
                            onClick = {
                                pdfImportTargetNotebook = null
                                showPdfImportDialog = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PictureAsPdf,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PDF import",
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                shelfNoticeHu?.let { notice ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = surfacePalette.activePillBackground,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { shelfNoticeHu = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Bezárás",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
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
                            .height(132.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(ocrHits, key = { "${it.notebookId}:${it.pageId}" }) { hit ->
                            val nbTitle = allNotebooks.firstOrNull { it.id == hit.notebookId }?.title ?: "Füzet"
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = surfacePalette.groupedCardBackground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenSinglePage(OpenedPageTarget(hit.notebookId, hit.pageId))
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(nbTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = hit.rawText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (sidebarSelection == ShelfSidebarSelection.Bookmarks && searchQuery.isBlank()) {
                    if (allBookmarks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Még nincsenek könyvjelzővel jelölt oldalak.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(allBookmarks, key = { it.id }) { bm ->
                                val nbTitle = allNotebooks.firstOrNull { it.id == bm.notebookId }?.title ?: "Füzet"
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = surfacePalette.groupedCardBackground,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenSinglePage(OpenedPageTarget(bm.notebookId, bm.pageId))
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
                                                    database.deleteWithTombstone(SyncTombstoneEntity.TYPE_BOOKMARK, bm.id)
                                                    CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
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
                    val showCreateTile = typeFilter != ShelfTypeFilter.PDF && searchQuery.isBlank()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 176.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (showCreateTile) {
                            item(key = "create_new_notebook_tile") {
                                CreateNotebookDashedTile(
                                    onClick = { creatingNewNotebook = true }
                                )
                            }
                        }

                        items(visibleNotebooks, key = { it.id }) { notebook ->
                            val isPdfBook = notebook.id in pdfNotebookIds
                            val isBeingDragged = draggingNotebook?.id == notebook.id
                            var coverWindowTopLeft by remember { mutableStateOf(Offset.Zero) }
                            var menuExpanded by remember(notebook.id) { mutableStateOf(false) }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isBeingDragged) 0.28f else 1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned { coverWindowTopLeft = it.positionInWindow() }
                                        .pointerInput(notebook.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { localOffset ->
                                                    draggingNotebook = notebook
                                                    dragPointerWindowOffset = coverWindowTopLeft + localOffset
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragPointerWindowOffset += dragAmount
                                                },
                                                onDragEnd = {
                                                    val droppedKey = hoveredDropKey
                                                    val dragged = draggingNotebook
                                                    if (dragged != null && droppedKey != null) {
                                                        val targetFolderId = if (droppedKey == DROP_KEY_UNCATEGORIZED) {
                                                            null
                                                        } else {
                                                            droppedKey
                                                        }
                                                        moveNotebookToFolder(dragged, targetFolderId)
                                                    }
                                                    draggingNotebook = null
                                                },
                                                onDragCancel = { draggingNotebook = null }
                                            )
                                        }
                                ) {
                                    NotebookCoverPreviewCard(
                                        title = notebook.title,
                                        cover = notebook.cover,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { openDefaultPageForNotebook(notebook) }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = notebook.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val dateText = remember(notebook.updatedAtEpochMs) {
                                            dateFormatter.format(Date(notebook.updatedAtEpochMs))
                                        }
                                        Text(
                                            text = if (isPdfBook) "PDF • $dateText" else dateText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }

                                    Box {
                                        IconButton(
                                            onClick = { menuExpanded = true },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.MoreHoriz,
                                                contentDescription = "Füzet műveletek",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Borító és beállítások") },
                                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    editingNotebook = notebook
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Oldalak és címkék") },
                                                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    inspectingNotebook = notebook
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Áthelyezés kategóriába...") },
                                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    moveTargetNotebook = notebook
                                                }
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("Feltöltés NotebookLM-be...") },
                                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    notebookLmExportTarget = notebook
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("PDF importálása ebbe a füzetbe...") },
                                                leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    pdfImportTargetNotebook = notebook
                                                    showPdfImportDialog = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("PDF exportálása...") },
                                                leadingIcon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    bookPdfExportTarget = notebook
                                                    val safeName = notebook.title
                                                        .ifBlank { "jegyzet" }
                                                        .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
                                                    bookExportLauncher.launch("$safeName.pdf")
                                                }
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "Törlés",
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Filled.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    scope.launch(Dispatchers.IO) {
                                                        database.deleteWithTombstone(SyncTombstoneEntity.TYPE_NOTEBOOK, notebook.id)
                                                        CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                                                    }
                                                }
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

        // Floating dragged notebook ghost preview crossing from grid to sidebar.
        draggingNotebook?.let { dragged ->
            val targetLabel = when (hoveredDropKey) {
                null -> null
                DROP_KEY_UNCATEGORIZED -> "Kategorizálatlan"
                else -> folderById[hoveredDropKey]?.name
            }
            Column(
                modifier = Modifier
                    .zIndex(30f)
                    .offset {
                        IntOffset(
                            x = (dragPointerWindowOffset.x - rootWindowPos.x - 72f).roundToInt(),
                            y = (dragPointerWindowOffset.y - rootWindowPos.y - 96f).roundToInt()
                        )
                    }
                    .width(132.dp)
                    .graphicsLayer {
                        scaleX = 0.96f
                        scaleY = 0.96f
                        alpha = 0.94f
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NotebookCoverPreviewCard(
                    title = dragged.title,
                    cover = dragged.cover,
                    modifier = Modifier.fillMaxWidth()
                )
                if (targetLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = targetLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        NewNameModalDialog(
            title = "Új kategória létrehozása",
            label = "Kategória neve",
            onConfirm = { name ->
                val now = System.currentTimeMillis()
                val accent = FOLDER_PALETTE[allFolders.size % FOLDER_PALETTE.size]
                val newFolderId = UUID.randomUUID().toString()
                scope.launch(Dispatchers.IO) {
                    database.folderDao().upsertFolder(
                        FolderEntity(
                            id = newFolderId,
                            parentId = null,
                            name = name,
                            accentColorArgb = accent,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                    CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                }
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    moveTargetNotebook?.let { targetNb ->
        MoveNotebookCategoryDialog(
            notebook = targetNb,
            folders = allFolders,
            onSelectFolder = { targetFolderId ->
                moveNotebookToFolder(targetNb, targetFolderId)
                moveTargetNotebook = null
            },
            onDismiss = { moveTargetNotebook = null }
        )
    }

    if (creatingNewNotebook) {
        val defaultCategoryId = (sidebarSelection as? ShelfSidebarSelection.Category)?.folderId
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
                            folderId = defaultCategoryId,
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
                    CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
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
                    CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
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
                onOpenSinglePage(target)
            },
            onDeleteNotebook = {
                scope.launch(Dispatchers.IO) {
                    database.deleteWithTombstone(SyncTombstoneEntity.TYPE_NOTEBOOK, nb.id)
                    CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                }
                inspectingNotebook = null
            },
            onDismiss = { inspectingNotebook = null }
        )
    }

    if (showPdfImportDialog) {
        val activeCatId = (sidebarSelection as? ShelfSidebarSelection.Category)?.folderId
        SelectivePdfImportDialog(
            database = database,
            defaultFolderId = activeCatId,
            targetNotebook = pdfImportTargetNotebook,
            onImported = { notebookId, firstPageId ->
                showPdfImportDialog = false
                pdfImportTargetNotebook = null
                onOpenSinglePage(OpenedPageTarget(notebookId = notebookId, pageId = firstPageId))
            },
            onDismiss = {
                showPdfImportDialog = false
                pdfImportTargetNotebook = null
            }
        )
    }

    notebookLmExportTarget?.let { targetNb ->
        NotebookLmExportDialog(
            notebook = targetNb,
            database = database,
            onDismiss = { notebookLmExportTarget = null }
        )
    }

    if (showCloudBackupDialog) {
        CloudBackupDialog(
            database = database,
            onDismiss = { showCloudBackupDialog = false },
            onBackupNotice = { shelfNoticeHu = it }
        )
    }
}

private val FOLDER_PALETTE = listOf(
    0xFF2B689C.toInt(),
    0xFF2F8066.toInt(),
    0xFFD97706.toInt(),
    0xFF7C3AED.toInt(),
    0xFF0F766E.toInt(),
    0xFFC2410C.toInt()
)

@Composable
private fun SidebarNavItem(
    label: String,
    icon: ImageVector,
    count: Int,
    selected: Boolean,
    isDropHovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    onDelete: (() -> Unit)? = null
) {
    val surfacePalette = LocalStylusSurfacePalette.current
    val bgColor = when {
        isDropHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        selected -> surfacePalette.activePillBackground
        else -> Color.Transparent
    }
    val borderStroke = if (isDropHovered) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = borderStroke,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || isDropHovered) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Kategória törlése",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateNotebookDashedTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dashColor = MaterialTheme.colorScheme.outlineVariant
    val containerColor = LocalStylusSurfacePalette.current.groupedCardBackground.copy(alpha = 0.45f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.74f)
                .clip(RoundedCornerShape(14.dp))
                .background(containerColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 1.6.dp.toPx()
                val radius = 14.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(radius, radius),
                    style = ComposeDrawStroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    )
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Létrehozás",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Text(
                    text = "Létrehozás",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(34.dp))
    }
}

@Composable
private fun MoveNotebookCategoryDialog(
    notebook: NotebookEntity,
    folders: List<FolderEntity>,
    onSelectFolder: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBackdropBox(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp)
                .widthIn(min = 320.dp, max = 440.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Áthelyezés: ${notebook.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(
                    onClick = { onSelectFolder(null) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (notebook.folderId == null) {
                        LocalStylusSurfacePalette.current.activePillBackground
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Kategorizálatlan", modifier = Modifier.weight(1f))
                        if (notebook.folderId == null) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(folders, key = { it.id }) { folder ->
                        val isCurrent = notebook.folderId == folder.id
                        Surface(
                            onClick = { onSelectFolder(folder.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) {
                                LocalStylusSurfacePalette.current.activePillBackground
                            } else {
                                Color.Transparent
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = Color(folder.accentColorArgb),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(folder.name, modifier = Modifier.weight(1f))
                                if (isCurrent) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Mégse") }
                }
            }
        }
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
    onDeleteNotebook: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
                                            database.deleteWithTombstone(
                                                SyncTombstoneEntity.TYPE_TAG_LINK,
                                                SyncTombstoneEntity.tagLinkId(notebook.id, tag.id)
                                            )
                                        } else {
                                            database.tagDao().attachTagToNotebook(
                                                NotebookTagCrossRef(notebook.id, tag.id)
                                            )
                                        }
                                        CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
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
                                                        database.deleteWithTombstone(
                                                            SyncTombstoneEntity.TYPE_BOOKMARK,
                                                            existing.id
                                                        )
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
                                                CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                            contentDescription = "Könyvjelző"
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

private class ActivePageSearchBridge(
    val pageId: String,
    val liveStrokesProvider: () -> List<PersistedStrokeRecord>,
    val liveObjectsProvider: () -> List<PageObject>,
    val livePageTitleProvider: () -> String?,
    val switchPageAction: (String) -> Unit
)

internal data class NotebookSearchUiState(
    val isOpen: Boolean = false,
    val query: String = "",
    val activeMatchIndex: Int = 0,
    val matchesCount: Int = 0,
    val indexState: SearchIndexState = SearchIndexState.IDLE,
    val canRunAiDeepIndex: Boolean = false,
    val activeMatchForCurrentPage: NotebookSearchMatch? = null,
    val navigateTriggerTick: Long = 0L,
    val onOpen: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onPreviousMatch: () -> Unit = {},
    val onNextMatch: () -> Unit = {},
    val onTriggerAiDeepIndex: () -> Unit = {},
    val onClose: () -> Unit = {}
)

/**
 * Single-pane 144 Hz notebook workspace hosting [CanvasStateHolder], [DryInkRasterCache],
 * [StrokeJournal], AI Copilot, and tool overlays.
 */
@Composable
fun NotebookWorkspaceScreen(
    database: AppDatabase,
    target: OpenedPageTarget,
    penButtonToggleTick: Long,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSelectPageTarget: (OpenedPageTarget) -> Unit,
    onExitToBookshelf: () -> Unit,
    showDebugOverlay: Boolean = false,
    onToggleDebugOverlay: () -> Unit = {},
    benchmarkStrokeCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val workspaceScope = rememberCoroutineScope()
    val toolDockPrefsStore = remember(context) { ToolDockPreferencesStore(context.applicationContext) }
    val dockPrefs by toolDockPrefsStore.prefsFlow.collectAsState(initial = ToolDockPrefs())
    var perToolConfigs by remember(target.notebookId) {
        mutableStateOf(ToolDockPreferencesStore.decodePerToolConfigs(dockPrefs.perToolConfigsRaw))
    }
    var hasHydratedPerToolConfigs by remember(target.notebookId) { mutableStateOf(false) }
    var brushConfig by remember(target.notebookId) {
        mutableStateOf(
            ToolDockPreferencesStore.defaultToolConfig(BrushToolType.FOUNTAIN_PEN)
        )
    }
    LaunchedEffect(dockPrefs.perToolConfigsRaw, target.notebookId) {
        if (!hasHydratedPerToolConfigs && dockPrefs.perToolConfigsRaw.isNotEmpty()) {
            val decoded = ToolDockPreferencesStore.decodePerToolConfigs(dockPrefs.perToolConfigsRaw)
            perToolConfigs = decoded
            decoded[brushConfig.toolType]?.let { restored ->
                brushConfig = restored.copy(toolType = brushConfig.toolType)
            }
            hasHydratedPerToolConfigs = true
        }
    }
    var lastDrawingTool by remember(target.notebookId) { mutableStateOf(BrushToolType.FOUNTAIN_PEN) }
    var lastEraserTool by remember(target.notebookId) { mutableStateOf(BrushToolType.PRECISION_ERASER) }
    var shapeStrokeWidthPt by remember(target.notebookId) { mutableStateOf(1.6f) }
    var shapeStrokeColorArgb by remember(target.notebookId) { mutableIntStateOf(0xFF111827.toInt()) }
    var shapeHasFill by remember(target.notebookId) { mutableStateOf(false) }
    var isFullScreenCanvas by remember(target.notebookId) { mutableStateOf(false) }
    var showLeftPageDrawer by remember(target.notebookId) { mutableStateOf(false) }

    val aiConsentStore = remember(context) { AiConsentStore(context.applicationContext) }
    val androidAppHeaders = remember(context) {
        GeminiApiSupport.extractAndroidAppHeaders(context.applicationContext)
    }
    val modelCatalog = remember(androidAppHeaders) {
        ModelCatalog(extraHeaders = androidAppHeaders)
    }
    val geminiClient = remember(aiConsentStore, modelCatalog, androidAppHeaders) {
        GeminiClient(
            consentStore = aiConsentStore,
            modelCatalog = modelCatalog,
            extraHeaders = androidAppHeaders
        )
    }
    val consentState by aiConsentStore.stateFlow.collectAsState(initial = AiConsentState())
    var showConsentDialog by remember(target.notebookId) { mutableStateOf(false) }
    var discoveredFlashModels by remember { mutableStateOf<List<GeminiModelInfo>>(emptyList()) }
    val notebookPages by database.pageDao()
        .observePagesForNotebook(target.notebookId)
        .collectAsState(initial = emptyList())
    var notebookOcrLanguage by remember(target.notebookId) { mutableStateOf(OcrLanguageTag.HUNGARIAN) }
    var notebookDefaultCanvasMode by remember(target.notebookId) { mutableStateOf<CanvasMode?>(null) }
    LaunchedEffect(target.notebookId) {
        val nb = withContext(Dispatchers.IO) { database.notebookDao().getNotebookById(target.notebookId) }
        if (nb != null) {
            notebookOcrLanguage = nb.ocrLanguage
            notebookDefaultCanvasMode = nb.defaultCanvasMode
        }
    }

    var isSearchOpen by remember(target.notebookId) { mutableStateOf(false) }
    var searchQuery by remember(target.notebookId) { mutableStateOf("") }
    var lastExecutedQuery by remember(target.notebookId) { mutableStateOf("") }
    var searchMatches by remember(target.notebookId) { mutableStateOf<List<NotebookSearchMatch>>(emptyList()) }
    var activeMatchIndex by remember(target.notebookId) { mutableIntStateOf(0) }
    var searchIndexState by remember(target.notebookId) { mutableStateOf(SearchIndexState.IDLE) }
    var searchNavigateTick by remember(target.notebookId) { mutableLongStateOf(0L) }
    var activePageRevisionTick by remember(target.notebookId) { mutableLongStateOf(0L) }
    var activePageBridge by remember(target.notebookId) { mutableStateOf<ActivePageSearchBridge?>(null) }

    val searchCoordinator = remember(database, context, geminiClient) {
        InNotebookSearchCoordinator(
            database = database,
            filesDir = context.applicationContext.filesDir,
            aiTextBoxExtractor = { strokes, lang ->
                HandwritingToLatexConverter.recognizeHandwritingBoxesForSearch(
                    strokes = strokes,
                    ocrLanguage = lang,
                    geminiClient = geminiClient
                )
            }
        )
    }

    var workspaceBannerHu by remember(target.notebookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(workspaceBannerHu) {
        if (workspaceBannerHu != null) {
            kotlinx.coroutines.delay(2200L)
            workspaceBannerHu = null
        }
    }

    var shownSearchWarningHu by remember(target.notebookId) { mutableStateOf<String?>(null) }

    fun applySearchExecutionResult(result: SearchExecutionResult, queryChanged: Boolean) {
        searchMatches = result.matches
        searchIndexState = result.state
        if (result.matches.isEmpty()) {
            activeMatchIndex = 0
        } else if (queryChanged) {
            val samePageIdx = result.matches.indexOfFirst { it.pageId == target.pageId }
            activeMatchIndex = if (samePageIdx >= 0) samePageIdx else 0
            if (samePageIdx >= 0) {
                searchNavigateTick++
            }
        } else {
            activeMatchIndex = activeMatchIndex.coerceIn(0, result.matches.lastIndex)
        }
        val notice = result.statusNoticeHu
        if (!notice.isNullOrBlank() && notice != shownSearchWarningHu) {
            shownSearchWarningHu = notice
            workspaceBannerHu = notice
        } else if (notice == null) {
            shownSearchWarningHu = null
        }
    }

    fun stepSearchMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        val nextIdx = (activeMatchIndex + delta).mod(searchMatches.size)
        activeMatchIndex = nextIdx
        searchNavigateTick++
        val nextMatch = searchMatches[nextIdx]
        if (nextMatch.pageId != target.pageId) {
            activePageBridge?.switchPageAction?.invoke(nextMatch.pageId)
        }
    }

    fun closeInNotebookSearch() {
        isSearchOpen = false
        searchQuery = ""
        lastExecutedQuery = ""
        searchMatches = emptyList()
        activeMatchIndex = 0
        searchIndexState = SearchIndexState.IDLE
    }

    LaunchedEffect(
        isSearchOpen,
        searchQuery,
        target.pageId,
        activePageRevisionTick,
        notebookPages,
        notebookOcrLanguage,
        benchmarkStrokeCount
    ) {
        if (!isSearchOpen || benchmarkStrokeCount > 0) return@LaunchedEffect
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            searchMatches = emptyList()
            activeMatchIndex = 0
            lastExecutedQuery = ""
            searchIndexState = SearchIndexState.IDLE
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(140L)
        val bridge = activePageBridge?.takeIf { it.pageId == target.pageId }
        val result = searchCoordinator.prepareAndSearch(
            notebookId = target.notebookId,
            ocrLanguage = notebookOcrLanguage,
            pages = notebookPages,
            currentPageId = target.pageId,
            liveStrokes = bridge?.liveStrokesProvider?.invoke().orEmpty(),
            liveObjects = bridge?.liveObjectsProvider?.invoke().orEmpty(),
            livePageTitle = bridge?.livePageTitleProvider?.invoke()
                ?: notebookPages.firstOrNull { it.id == target.pageId }?.title,
            query = trimmed,
            onStateChanged = { nextState, notice ->
                searchIndexState = nextState
                if (nextState == SearchIndexState.DOWNLOADING_MODEL && notice != null) {
                    workspaceBannerHu = notice
                }
            }
        )
        val queryChanged = trimmed != lastExecutedQuery
        lastExecutedQuery = trimmed
        applySearchExecutionResult(result, queryChanged = queryChanged)
    }

    fun triggerAiDeepSearch() {
        if (!consentState.isReadyForUserInitiatedCall) {
            showConsentDialog = true
            return
        }
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) return
        workspaceScope.launch {
            val bridge = activePageBridge?.takeIf { it.pageId == target.pageId }
            val result = searchCoordinator.runManualAiDeepIndex(
                notebookId = target.notebookId,
                ocrLanguage = notebookOcrLanguage,
                pages = notebookPages,
                currentPageId = target.pageId,
                liveStrokes = bridge?.liveStrokesProvider?.invoke().orEmpty(),
                liveObjects = bridge?.liveObjectsProvider?.invoke().orEmpty(),
                livePageTitle = bridge?.livePageTitleProvider?.invoke()
                    ?: notebookPages.firstOrNull { it.id == target.pageId }?.title,
                query = trimmed,
                onStateChanged = { nextState, _ -> searchIndexState = nextState }
            )
            lastExecutedQuery = trimmed
            applySearchExecutionResult(result, queryChanged = true)
            workspaceBannerHu = result.statusNoticeHu ?: if (result.matches.isEmpty()) {
                "AI mélyindexelés kész, nincs találat."
            } else {
                "AI mélyindexelés: ${result.matches.size} találat."
            }
        }
    }

    val activeSearchMatch = searchMatches.getOrNull(activeMatchIndex)
    val searchUiState = NotebookSearchUiState(
        isOpen = isSearchOpen,
        query = searchQuery,
        activeMatchIndex = activeMatchIndex,
        matchesCount = searchMatches.size,
        indexState = searchIndexState,
        canRunAiDeepIndex = consentState.isReadyForUserInitiatedCall,
        activeMatchForCurrentPage = activeSearchMatch?.takeIf { it.pageId == target.pageId },
        navigateTriggerTick = searchNavigateTick,
        onOpen = { isSearchOpen = true },
        onQueryChange = { searchQuery = it },
        onPreviousMatch = { stepSearchMatch(-1) },
        onNextMatch = { stepSearchMatch(+1) },
        onTriggerAiDeepIndex = ::triggerAiDeepSearch,
        onClose = ::closeInNotebookSearch
    )

    fun updateActiveBrushConfig(next: BrushConfig) {
        brushConfig = next
        val updatedMap = perToolConfigs.toMutableMap()
        updatedMap[next.toolType] = next
        perToolConfigs = updatedMap
        hasHydratedPerToolConfigs = true
        workspaceScope.launch {
            toolDockPrefsStore.savePerToolConfigs(updatedMap)
        }
    }

    fun selectTool(nextTool: BrushToolType) {
        val updatedMap = perToolConfigs.toMutableMap()
        updatedMap[brushConfig.toolType] = brushConfig
        val restored = (updatedMap[nextTool] ?: ToolDockPreferencesStore.defaultToolConfig(nextTool))
            .copy(toolType = nextTool)
        perToolConfigs = updatedMap
        if (nextTool == BrushToolType.FOUNTAIN_PEN || nextTool == BrushToolType.HIGHLIGHTER) {
            lastDrawingTool = nextTool
        } else if (nextTool.isEraser) {
            lastEraserTool = nextTool
        }
        brushConfig = restored
        hasHydratedPerToolConfigs = true
        workspaceScope.launch {
            toolDockPrefsStore.savePerToolConfigs(updatedMap)
        }
    }

    LaunchedEffect(penButtonToggleTick) {
        if (penButtonToggleTick > 0L) {
            val next = if (brushConfig.toolType.isEraser) lastDrawingTool else lastEraserTool
            selectTool(next)
        }
    }

    val activePageCanvasMode = notebookPages.firstOrNull { it.id == target.pageId }?.canvasMode
        ?: notebookDefaultCanvasMode
        ?: CanvasMode.FIXED_PAGE
    val isContinuousMultiPage = benchmarkStrokeCount == 0 && activePageCanvasMode == CanvasMode.FIXED_PAGE
    val paneKey: Any = if (isContinuousMultiPage) {
        "continuous_${target.notebookId}"
    } else {
        "single_${target.notebookId}_${target.pageId}"
    }

    Box(modifier = modifier.fillMaxSize()) {
        key(paneKey) {
            NotebookPagePane(
                database = database,
                target = target,
                isContinuousMultiPage = isContinuousMultiPage,
                cacheBudgetBytes = DryInkRasterCache.DEFAULT_MAX_CACHE_BYTES,
                isDarkTheme = isDarkTheme,
                showDebugOverlay = showDebugOverlay,
                benchmarkStrokeCount = benchmarkStrokeCount,
                brushConfig = brushConfig,
                lastDrawingTool = lastDrawingTool,
                lastEraserTool = lastEraserTool,
                toolDockPrefsStore = toolDockPrefsStore,
                dockPrefs = dockPrefs,
                shapeStrokeWidthPt = shapeStrokeWidthPt,
                shapeStrokeColorArgb = shapeStrokeColorArgb,
                shapeHasFill = shapeHasFill,
                isFullScreenCanvas = isFullScreenCanvas,
                showLeftPageDrawer = showLeftPageDrawer,
                notebookPages = notebookPages,
                notebookOcrLanguage = notebookOcrLanguage,
                geminiClient = geminiClient,
                consentState = consentState,
                searchUiState = searchUiState,
                onOpenConsentDialog = { showConsentDialog = true },
                onNotebookOcrLanguageLoaded = { loadedLang -> notebookOcrLanguage = loadedLang },
                onPublishSearchBridge = { bridge -> activePageBridge = bridge },
                onPageContentRevisionChanged = { activePageRevisionTick++ },
                onClearSearchBridge = { disposedPageId ->
                    if (activePageBridge?.pageId == disposedPageId) {
                        activePageBridge = null
                    }
                },
                onUpdateBrushConfig = ::updateActiveBrushConfig,
                onSelectTool = ::selectTool,
                onTogglePenEraser = {
                    val next = if (brushConfig.toolType.isEraser) lastDrawingTool else lastEraserTool
                    selectTool(next)
                },
                onUpdateShapeDefaults = { w, c, fill ->
                    shapeStrokeWidthPt = w
                    shapeStrokeColorArgb = c
                    shapeHasFill = fill
                },
                onToggleFullScreen = { isFullScreenCanvas = !isFullScreenCanvas },
                onShowLeftPageDrawerChange = { showLeftPageDrawer = it },
                onShowWorkspaceBanner = { workspaceBannerHu = it },
                onToggleTheme = onToggleTheme,
                onToggleDebugOverlay = onToggleDebugOverlay,
                onSelectPageTarget = onSelectPageTarget,
                onBackToShelf = onExitToBookshelf,
                modifier = Modifier.fillMaxSize()
            )
        }

        workspaceBannerHu?.let { msg ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 10.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
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
                    try {
                        val key = typedKey ?: aiConsentStore.readDecryptedApiKey()
                            ?: throw IllegalStateException("Előbb adj meg egy API-kulcsot.")
                        val refreshed = modelCatalog.refreshCatalog(key)
                        discoveredFlashModels = refreshed.availableFlashModels
                        Result.success(refreshed.availableFlashModels)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                }
            )
        }
    }
}

private data class ActivePageBinding(
    val pageId: String,
    val pageIndex: Int,
    val originXPt: Float,
    val originYPt: Float,
    val canvasStateHolder: CanvasStateHolder,
    val strokeJournal: StrokeJournal,
    val dryInkRasterCache: DryInkRasterCache,
    val oledStrokeCache: OledStrokeRenderCache,
    val isReadOnly: Boolean
)

@Composable
private fun NotebookPagePane(
    database: AppDatabase,
    target: OpenedPageTarget,
    isContinuousMultiPage: Boolean,
    cacheBudgetBytes: Long,
    isDarkTheme: Boolean,
    showDebugOverlay: Boolean,
    benchmarkStrokeCount: Int,
    brushConfig: BrushConfig,
    lastDrawingTool: BrushToolType,
    lastEraserTool: BrushToolType,
    toolDockPrefsStore: ToolDockPreferencesStore,
    dockPrefs: ToolDockPrefs,
    shapeStrokeWidthPt: Float,
    shapeStrokeColorArgb: Int,
    shapeHasFill: Boolean,
    isFullScreenCanvas: Boolean,
    showLeftPageDrawer: Boolean,
    notebookPages: List<PageEntity>,
    notebookOcrLanguage: OcrLanguageTag,
    geminiClient: GeminiClient,
    consentState: AiConsentState,
    searchUiState: NotebookSearchUiState,
    onOpenConsentDialog: () -> Unit,
    onNotebookOcrLanguageLoaded: (OcrLanguageTag) -> Unit,
    onPublishSearchBridge: (ActivePageSearchBridge) -> Unit,
    onPageContentRevisionChanged: () -> Unit,
    onClearSearchBridge: (String) -> Unit,
    onUpdateBrushConfig: (BrushConfig) -> Unit,
    onSelectTool: (BrushToolType) -> Unit,
    onTogglePenEraser: () -> Unit,
    onUpdateShapeDefaults: (Float, Int, Boolean) -> Unit,
    onToggleFullScreen: () -> Unit,
    onShowLeftPageDrawerChange: (Boolean) -> Unit,
    onShowWorkspaceBanner: (String) -> Unit,
    onToggleTheme: () -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onSelectPageTarget: (OpenedPageTarget) -> Unit,
    onBackToShelf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val sessionKey: Any = if (isContinuousMultiPage) target.notebookId else target
    val historyMutex = remember(sessionKey) { Mutex() }
    val latestTargetPageId by rememberUpdatedState(target.pageId)

    val transformMatrix = remember(sessionKey) { CanvasTransformMatrix() }
    val canvasStateHolder = remember(sessionKey) { CanvasStateHolder() }
    val dryInkRasterCache = remember(sessionKey, cacheBudgetBytes) {
        DryInkRasterCache(maxCacheBytes = cacheBudgetBytes)
    }
    val oledStrokeCache = remember(sessionKey) { OledStrokeRenderCache() }
    val liveObjectController = remember(sessionKey) { LiveObjectInteractionController() }
    val presetStore = remember(context) { BrushPresetStore(context.applicationContext) }
    val presets by presetStore.presetsFlow.collectAsState(initial = BrushPresetStore.defaultPresets)

    var paperConfig by remember(sessionKey) { mutableStateOf(PagePaperConfig()) }
    var dockBoundsPx by remember { mutableStateOf(Rect.Zero) }
    var showBrushSettings by remember(sessionKey) { mutableStateOf(false) }
    var showShapePicker by remember(sessionKey) { mutableStateOf(false) }
    var showPaperSettings by remember(sessionKey) { mutableStateOf(false) }
    var showAiCopilot by remember(sessionKey) { mutableStateOf(false) }
    var showCloudBackupDialog by remember(sessionKey) { mutableStateOf(false) }
    var selectedObjectId by remember(sessionKey) { mutableStateOf<String?>(null) }
    var selectedObjectPageId by remember(sessionKey) { mutableStateOf<String?>(null) }
    var copilotNoticeBannerHu by remember(sessionKey) { mutableStateOf<String?>(null) }

    var notebookTitle by remember(sessionKey) { mutableStateOf("Jegyzetfüzet") }
    var pageNumber by remember(sessionKey) { mutableIntStateOf(1) }
    var currentPageEntity by remember(sessionKey) { mutableStateOf<PageEntity?>(null) }
    var isCreatingPage by remember { mutableStateOf(false) }
    var isDeletingPage by remember { mutableStateOf(false) }
    var topToolbarBoundsPx by remember { mutableStateOf(Rect.Zero) }
    var observedFullScreen by remember { mutableStateOf(isFullScreenCanvas) }
    var isReadOnlyPage by remember(sessionKey) { mutableStateOf(false) }
    var hasHydratedPageContent by remember(sessionKey) { mutableStateOf(false) }

    val pdfTileRenderer = remember(context) { PdfTileRenderer(context.filesDir) }
    val pdfExportManager = remember(context, pdfTileRenderer) {
        PdfExportManager(context.applicationContext, pdfTileRenderer)
    }
    DisposableEffect(pdfTileRenderer) {
        onDispose { pdfTileRenderer.close() }
    }
    val pageThumbnailRenderer = remember(context, target.notebookId, pdfTileRenderer) {
        PageThumbnailRenderer(context.applicationContext, pdfTileRenderer)
    }

    val palette = LocalStylusSurfacePalette.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var currentZoomPercent by remember(sessionKey) { mutableIntStateOf(100) }
    var externalDrawTick by remember { mutableLongStateOf(0L) }
    var historyRevisionTick by remember { mutableLongStateOf(0L) }
    var hydrationRevisionTick by remember { mutableLongStateOf(0L) }
    var viewportTransformTick by remember { mutableLongStateOf(0L) }
    var scrollToPageRequestId by remember(sessionKey) { mutableStateOf<String?>(null) }
    var scrollToPageTargetLocalRect by remember(sessionKey) { mutableStateOf<DocRect?>(null) }
    var scrollToPageRequestTick by remember(sessionKey) { mutableLongStateOf(0L) }
    var pendingScrollAfterLayoutPageId by remember(sessionKey) { mutableStateOf<String?>(null) }

    val latestBrushConfig by rememberUpdatedState(brushConfig)
    val dirtyContinuousPageIds = remember(sessionKey) { Collections.synchronizedSet(LinkedHashSet<String>()) }
    val continuousSessionManager = remember(sessionKey, isContinuousMultiPage, cacheBudgetBytes) {
        if (isContinuousMultiPage) {
            MultiPageSessionManager(
                filesDir = context.applicationContext.filesDir,
                activeBrushConfigProvider = { latestBrushConfig },
                onLocalMutationCommitted = { pageId ->
                    dirtyContinuousPageIds.add(pageId)
                    CloudSyncScheduler.notifyLocalMutation(context)
                },
                onPageReadyCallback = { _, _ ->
                    mainHandler.post {
                        hydrationRevisionTick++
                        historyRevisionTick++
                        externalDrawTick++
                    }
                },
                totalTileBudgetBytes = cacheBudgetBytes
            )
        } else {
            null
        }
    }

    val effectiveContinuousPages: List<PageEntity> = remember(
        isContinuousMultiPage,
        notebookPages,
        currentPageEntity,
        target.pageId
    ) {
        if (!isContinuousMultiPage) {
            emptyList()
        } else if (notebookPages.isNotEmpty()) {
            notebookPages.sortedBy { it.pageIndexInNotebook }
        } else {
            listOfNotNull(currentPageEntity)
        }
    }
    val continuousLayout: ContinuousPageLayout? = remember(isContinuousMultiPage, effectiveContinuousPages) {
        if (isContinuousMultiPage && effectiveContinuousPages.isNotEmpty()) {
            ContinuousPageLayout.build(effectiveContinuousPages)
        } else {
            null
        }
    }
    LaunchedEffect(continuousSessionManager, effectiveContinuousPages, target.pageId) {
        val manager = continuousSessionManager ?: return@LaunchedEffect
        if (effectiveContinuousPages.isEmpty()) return@LaunchedEffect
        val activeIdx = effectiveContinuousPages
            .indexOfFirst { it.id == target.pageId }
            .let { if (it >= 0) it else 0 }
        manager.updatePagesAndFocus(effectiveContinuousPages, activeIdx)
        val pendingPageId = pendingScrollAfterLayoutPageId
        if (pendingPageId != null && manager.currentLayout.slotForPageId(pendingPageId) != null) {
            pendingScrollAfterLayoutPageId = null
            scrollToPageRequestId = pendingPageId
            scrollToPageTargetLocalRect = null
            scrollToPageRequestTick++
        }
    }

    val activePageMatch = searchUiState.activeMatchForCurrentPage?.takeIf { it.pageId == target.pageId }
    val latestActivePageMatch by rememberUpdatedState(activePageMatch)
    fun bumpOverlayTransformTickIfVisible() {
        val hasAnyObjects = if (continuousSessionManager != null) {
            continuousSessionManager.readyPageIdsSnapshot().any { id ->
                continuousSessionManager.getReadyRuntime(id)?.canvasStateHolder?.hasPageObjects == true
            }
        } else {
            canvasStateHolder.hasPageObjects
        }
        if (hasAnyObjects || latestActivePageMatch != null) {
            viewportTransformTick++
        }
    }

    val strokeJournal = remember(sessionKey, isContinuousMultiPage) {
        if (isContinuousMultiPage) {
            null
        } else {
            StrokeJournal(
                pageDirectory = File(context.filesDir, "pages/${target.pageId}"),
                activeBrushConfigProvider = { latestBrushConfig },
                shapeMetaProvider = { strokeId -> canvasStateHolder.getShapeMeta(strokeId) },
                onMutationCommitted = {
                    CloudSyncScheduler.notifyLocalMutation(context)
                }
            )
        }
    }

    fun resolveBindingForPage(pageId: String): ActivePageBinding? {
        if (continuousSessionManager != null && continuousLayout != null) {
            val slot = continuousLayout.slotForPageId(pageId) ?: return null
            val runtime = continuousSessionManager.getReadyRuntime(pageId) ?: return null
            return ActivePageBinding(
                pageId = pageId,
                pageIndex = slot.pageIndex,
                originXPt = slot.leftPt,
                originYPt = slot.topPt,
                canvasStateHolder = runtime.canvasStateHolder,
                strokeJournal = runtime.strokeJournal,
                dryInkRasterCache = runtime.dryInkRasterCache,
                oledStrokeCache = runtime.oledStrokeCache,
                isReadOnly = runtime.isReadOnly
            )
        }
        val singleJournal = strokeJournal ?: return null
        return ActivePageBinding(
            pageId = target.pageId,
            pageIndex = currentPageEntity?.pageIndexInNotebook ?: 0,
            originXPt = 0f,
            originYPt = 0f,
            canvasStateHolder = canvasStateHolder,
            strokeJournal = singleJournal,
            dryInkRasterCache = dryInkRasterCache,
            oledStrokeCache = oledStrokeCache,
            isReadOnly = isReadOnlyPage
        )
    }

    fun resolveViewportTargetBinding(): ActivePageBinding? {
        if (continuousSessionManager != null && continuousLayout != null) {
            val wsCenter = if (viewportSize.width > 0 && viewportSize.height > 0) {
                transformMatrix.screenToDoc(viewportSize.width * 0.5f, viewportSize.height * 0.5f)
            } else {
                null
            }
            val slot = if (wsCenter != null) {
                continuousLayout.findSlotAtWorkspacePoint(wsCenter.xPt, wsCenter.yPt)
                    ?: continuousLayout.findNearestSlotForViewportCenter(wsCenter.yPt)
            } else {
                null
            } ?: continuousLayout.slotForPageId(latestTargetPageId)
                ?: continuousLayout.slots.firstOrNull()
                ?: return null
            return resolveBindingForPage(slot.pageId)
        }
        return resolveBindingForPage(target.pageId)
    }

    fun syncZoomPercentFromMatrix() {
        val nextPct = transformMatrix.relativeZoomPercent
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
        if (continuousSessionManager != null) {
            for (readyId in continuousSessionManager.readyPageIdsSnapshot()) {
                continuousSessionManager.getReadyRuntime(readyId)
                    ?.dryInkRasterCache
                    ?.onGestureSettled(transformMatrix.scale)
            }
        } else {
            dryInkRasterCache.onGestureSettled(transformMatrix.scale)
        }
        syncZoomPercentFromMatrix()
        bumpOverlayTransformTickIfVisible()
        externalDrawTick++
    }

    fun fitPageToViewportNow(overridePaddingPx: Float? = null) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return
        val paddingPx = overridePaddingPx ?: if (isFullScreenCanvas) 0f else 36f
        val layout = continuousLayout
        val manager = continuousSessionManager
        if (manager != null && layout != null) {
            val slot = layout.slotForPageId(latestTargetPageId) ?: layout.slots.firstOrNull()
            if (slot != null) {
                transformMatrix.fitPageToViewport(
                    pageWidthPt = slot.widthPt,
                    pageHeightPt = slot.heightPt,
                    viewportWidthPx = viewportSize.width.toFloat(),
                    viewportHeightPx = viewportSize.height.toFloat(),
                    paddingPx = paddingPx
                )
                val fitScale = transformMatrix.scale
                val (panX, panY) = layout.computeCameraPanForSlot(
                    slot = slot,
                    scale = fitScale,
                    viewportWidthPx = viewportSize.width.toFloat(),
                    viewportHeightPx = viewportSize.height.toFloat(),
                    verticalPaddingPx = paddingPx
                )
                transformMatrix.setTransform(scale = fitScale, panXPx = panX, panYPx = panY)
                manager.getReadyRuntime(slot.pageId)?.dryInkRasterCache?.onGestureSettled(fitScale)
            }
        } else {
            transformMatrix.fitPageToViewport(
                pageWidthPt = paperConfig.pageDimensions.widthPt,
                pageHeightPt = paperConfig.pageDimensions.heightPt,
                viewportWidthPx = viewportSize.width.toFloat(),
                viewportHeightPx = viewportSize.height.toFloat(),
                paddingPx = paddingPx
            )
            dryInkRasterCache.onGestureSettled(transformMatrix.scale)
        }
        syncZoomPercentFromMatrix()
        bumpOverlayTransformTickIfVisible()
        externalDrawTick++
    }

    fun viewportCenterWorkspacePoint(): DocPoint = if (viewportSize.width > 0 && viewportSize.height > 0) {
        transformMatrix.screenToDoc(
            viewportSize.width * 0.5f,
            viewportSize.height * 0.5f
        )
    } else {
        DocPoint(
            paperConfig.pageDimensions.widthPt * 0.5f,
            paperConfig.pageDimensions.heightPt * 0.5f
        )
    }

    val insertImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { pickedUri ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        val binding = resolveViewportTargetBinding() ?: return@rememberLauncherForActivityResult
        if (binding.isReadOnly) return@rememberLauncherForActivityResult
        val centerWs = viewportCenterWorkspacePoint()
        val centerLocal = DocPoint(
            xPt = centerWs.xPt - binding.originXPt,
            yPt = centerWs.yPt - binding.originYPt
        )
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importPickedImageAsPageAsset(
                        context = context,
                        sourceUri = pickedUri,
                        pageId = binding.pageId
                    )
                }
            }.onSuccess { imported ->
                val maxDocSpan = 260f
                val longestPx = maxOf(imported.widthPx, imported.heightPx).toFloat().coerceAtLeast(1f)
                val docW = (imported.widthPx / longestPx * maxDocSpan).coerceAtLeast(48f)
                val docH = (imported.heightPx / longestPx * maxDocSpan).coerceAtLeast(48f)
                val imgObj = PageObject.ImageObject(
                    id = UUID.randomUUID().toString(),
                    zOrder = binding.canvasStateHolder.allocateObjectZOrder(),
                    bounds = DocRect(
                        leftPt = centerLocal.xPt - docW * 0.5f,
                        topPt = centerLocal.yPt - docH * 0.5f,
                        rightPt = centerLocal.xPt + docW * 0.5f,
                        bottomPt = centerLocal.yPt + docH * 0.5f
                    ),
                    imageAssetRelativePath = imported.relativePath,
                    rotationRadians = 0f
                )
                binding.canvasStateHolder.addPageObject(imgObj)
                binding.strokeJournal.appendAddObject(imgObj)
                pageThumbnailRenderer.invalidatePage(binding.pageId)
                selectedObjectId = imgObj.id
                selectedObjectPageId = binding.pageId
                externalDrawTick++
                historyRevisionTick++
                viewportTransformTick++
            }.onFailure { err ->
                copilotNoticeBannerHu = "Kép beszúrása sikertelen: ${err.message ?: "ismeretlen hiba"}"
            }
        }
    }

    LaunchedEffect(isFullScreenCanvas) {
        if (isFullScreenCanvas == observedFullScreen) {
            return@LaunchedEffect
        }
        observedFullScreen = isFullScreenCanvas
        if (isFullScreenCanvas) {
            topToolbarBoundsPx = Rect.Zero
        }
        fitPageToViewportNow(overridePaddingPx = if (isFullScreenCanvas) 0f else 36f)
    }

    fun persistedStrokesFrom(holder: CanvasStateHolder): List<PersistedStrokeRecord> =
        holder.allStrokesSorted().map { entry ->
            StrokeBinarySerializer.fromIndexedDryStroke(
                entry = entry,
                brushConfig = brushConfig,
                shapeMeta = holder.getShapeMeta(entry.id)
            )
        }

    suspend fun formatInMemoryTarget(
        holder: CanvasStateHolder,
        journal: StrokeJournal,
        rasterCache: DryInkRasterCache,
        onlyUnformattedOcr: Boolean,
        formatText: Boolean
    ): hu.bdev.stylusnote.ui.canvas.PageFormatSummary {
        val strokes = persistedStrokesFrom(holder)
        val res = if (onlyUnformattedOcr) {
            HandwritingToLatexConverter.formatUnformattedStrokesInPage(
                pageStrokes = strokes,
                existingObjects = holder.allPageObjects(),
                ocrLanguage = notebookOcrLanguage,
                geminiClient = geminiClient,
                canvasStateHolder = holder,
                journalSink = journal
            )
        } else {
            HandwritingToLatexConverter.formatPageStrokesStructured(
                pageStrokes = strokes,
                formatText = formatText,
                geminiClient = geminiClient,
                canvasStateHolder = holder,
                journalSink = journal,
                context = context,
                ocrLanguage = notebookOcrLanguage
            )
        }
        val summary = res.getOrThrow()
        rasterCache.clearAll()
        externalDrawTick++
        historyRevisionTick++
        return summary
    }

    suspend fun executeAiFormattingAcrossPages(
        targets: List<PageEntity>,
        onlyUnformattedOcr: Boolean,
        formatText: Boolean
    ): hu.bdev.stylusnote.ui.canvas.PageFormatSummary {
        continuousSessionManager?.flushAllJournals()
        var totalTexts = 0
        var totalFormulas = 0
        var totalShapes = 0
        var lastModel: String? = null

        for (pageEntity in targets) {
            val warmRuntime = continuousSessionManager?.getReadyRuntime(pageEntity.id)
            val singleJournal = if (continuousSessionManager == null && pageEntity.id == target.pageId) {
                strokeJournal
            } else {
                null
            }
            val summary = when {
                warmRuntime != null -> {
                    dirtyContinuousPageIds.add(pageEntity.id)
                    formatInMemoryTarget(
                        holder = warmRuntime.canvasStateHolder,
                        journal = warmRuntime.strokeJournal,
                        rasterCache = warmRuntime.dryInkRasterCache,
                        onlyUnformattedOcr = onlyUnformattedOcr,
                        formatText = formatText
                    )
                }
                singleJournal != null -> {
                    formatInMemoryTarget(
                        holder = canvasStateHolder,
                        journal = singleJournal,
                        rasterCache = dryInkRasterCache,
                        onlyUnformattedOcr = onlyUnformattedOcr,
                        formatText = formatText
                    )
                }
                else -> {
                    val pageDir = File(context.filesDir, "pages/${pageEntity.id}")
                    val bgJournal = StrokeJournal(pageDirectory = pageDir)
                    withContext(Dispatchers.IO) {
                        val rec = bgJournal.openAndRecover()
                        try {
                            if (rec.isReadOnly || rec.strokes.isEmpty()) {
                                hu.bdev.stylusnote.ui.canvas.PageFormatSummary(0, 0, 0)
                            } else {
                                val maxZ = rec.objects.maxOfOrNull { it.zOrder } ?: 1_000_000L
                                val res = if (onlyUnformattedOcr) {
                                    HandwritingToLatexConverter.formatUnformattedStrokesInPage(
                                        pageStrokes = rec.strokes,
                                        existingObjects = rec.objects,
                                        ocrLanguage = notebookOcrLanguage,
                                        geminiClient = geminiClient,
                                        canvasStateHolder = null,
                                        journalSink = bgJournal,
                                        startingZOrder = maxZ
                                    )
                                } else {
                                    HandwritingToLatexConverter.formatPageStrokesStructured(
                                        pageStrokes = rec.strokes,
                                        formatText = formatText,
                                        geminiClient = geminiClient,
                                        canvasStateHolder = null,
                                        journalSink = bgJournal,
                                        context = context,
                                        startingZOrder = maxZ,
                                        ocrLanguage = notebookOcrLanguage
                                    )
                                }
                                res.getOrThrow()
                            }
                        } finally {
                            bgJournal.close(compactBeforeClose = !rec.isReadOnly)
                        }
                    }
                }
            }
            totalTexts += summary.formattedTextCount
            totalFormulas += summary.formattedFormulaCount
            totalShapes += summary.formattedShapeCount
            summary.modelIdUsed?.takeIf { it.isNotBlank() }?.let { lastModel = it }
        }

        return hu.bdev.stylusnote.ui.canvas.PageFormatSummary(
            formattedTextCount = totalTexts,
            formattedFormulaCount = totalFormulas,
            formattedShapeCount = totalShapes,
            modelIdUsed = lastModel
        )
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val binding = resolveBindingForPage(latestTargetPageId)
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    val activeHolder = binding?.canvasStateHolder ?: canvasStateHolder
                    val activePage = currentPageEntity ?: notebookPages.firstOrNull { it.id == latestTargetPageId }
                    val activeConfig = activePage?.pageSizePreset?.toPagePaperConfig(
                        canvasMode = activePage.canvasMode,
                        templateType = activePage.templateType
                    ) ?: paperConfig
                    val liveStrokes = activeHolder.allStrokesSorted().associate { it.id to it.stroke }
                    val exportPage = ExportablePdfPage(
                        pageNumberOneBased = pageNumber,
                        paperConfig = activeConfig,
                        strokes = persistedStrokesFrom(activeHolder),
                        objects = activeHolder.allPageObjects(),
                        backgroundPdfAssetId = activePage?.pdfAssetId,
                        backgroundPdfPageIndex = activePage?.pdfPageIndex,
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

    LaunchedEffect(sessionKey, benchmarkStrokeCount) {
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
        val loadedOrCreatedPage = withContext(Dispatchers.IO) {
            val existing = database.pageDao().getPageById(target.pageId)
            if (existing != null) {
                existing
            } else {
                val now = System.currentTimeMillis()
                val created = PageEntity(
                    id = target.pageId,
                    notebookId = target.notebookId,
                    pageIndexInNotebook = 0,
                    canvasMode = nb?.defaultCanvasMode ?: CanvasMode.FIXED_PAGE,
                    pageSizePreset = nb?.defaultPageSizePreset ?: PageSizePreset.A4_PORTRAIT,
                    templateType = nb?.defaultTemplateType ?: PaperTemplateType.GRID_5MM,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
                database.pageDao().upsertPage(created)
                created
            }
        }
        if (nb != null) {
            notebookTitle = nb.title
            onNotebookOcrLanguageLoaded(nb.ocrLanguage)
        }
        currentPageEntity = loadedOrCreatedPage
        pageNumber = loadedOrCreatedPage.pageIndexInNotebook + 1
        paperConfig = loadedOrCreatedPage.pageSizePreset.toPagePaperConfig(
            canvasMode = loadedOrCreatedPage.canvasMode,
            templateType = loadedOrCreatedPage.templateType
        )

        val singleJournal = strokeJournal
        if (continuousSessionManager != null) {
            val allPages = withContext(Dispatchers.IO) {
                database.pageDao().getPagesForNotebook(target.notebookId)
            }.ifEmpty { listOf(loadedOrCreatedPage) }
            val activeIdx = allPages.indexOfFirst { it.id == target.pageId }.let { if (it >= 0) it else 0 }
            continuousSessionManager.updatePagesAndFocus(allPages, activeIdx)
            externalDrawTick++
            historyRevisionTick++
            hasHydratedPageContent = true
        } else if (singleJournal != null) {
            val recovery = singleJournal.openAndRecover()
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
            hasHydratedPageContent = true
        }
    }

    LaunchedEffect(isContinuousMultiPage, target.pageId, notebookPages) {
        if (!isContinuousMultiPage) return@LaunchedEffect
        val activePage = notebookPages.firstOrNull { it.id == target.pageId } ?: return@LaunchedEffect
        currentPageEntity = activePage
        pageNumber = activePage.pageIndexInNotebook + 1
        paperConfig = activePage.pageSizePreset.toPagePaperConfig(
            canvasMode = activePage.canvasMode,
            templateType = activePage.templateType
        )
    }

    LaunchedEffect(searchUiState.isOpen, continuousSessionManager) {
        if (searchUiState.isOpen && continuousSessionManager != null) {
            continuousSessionManager.flushAllJournals()
        }
    }

    LaunchedEffect(target.pageId, historyRevisionTick) {
        if (hasHydratedPageContent && historyRevisionTick > 0L) {
            onPageContentRevisionChanged()
        }
    }

    LaunchedEffect(
        target.pageId,
        activePageMatch?.highlightDocRect,
        activePageMatch?.matchedSnippet,
        searchUiState.navigateTriggerTick,
        hasHydratedPageContent,
        viewportSize,
        continuousLayout
    ) {
        val targetRect = activePageMatch?.highlightDocRect ?: return@LaunchedEffect
        if (!hasHydratedPageContent || viewportSize.width <= 0 || viewportSize.height <= 0) {
            return@LaunchedEffect
        }
        val matchPageId = activePageMatch.pageId
        val slot = continuousLayout?.slotForPageId(matchPageId)
        val wsTargetRect = slot?.localRectToWorkspaceRect(targetRect) ?: targetRect
        if (continuousSessionManager != null && slot != null) {
            continuousSessionManager.updateActiveFocus(slot.pageIndex)
        }
        val panned = transformMatrix.centerOnDocRectIfNeeded(
            targetRect = wsTargetRect,
            viewportWidthPx = viewportSize.width.toFloat(),
            viewportHeightPx = viewportSize.height.toFloat(),
            forceCenter = false
        )
        if (panned) {
            if (continuousSessionManager != null) {
                for (readyId in continuousSessionManager.readyPageIdsSnapshot()) {
                    continuousSessionManager.getReadyRuntime(readyId)
                        ?.dryInkRasterCache
                        ?.onGestureSettled(transformMatrix.scale)
                }
            } else {
                dryInkRasterCache.onGestureSettled(transformMatrix.scale)
            }
            syncZoomPercentFromMatrix()
            viewportTransformTick++
            externalDrawTick++
        }
    }

    var switchedWithinWorkspace by remember(sessionKey) { mutableStateOf(false) }
    var deletedActivePageId by remember(sessionKey) { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, sessionKey, benchmarkStrokeCount) {
        if (benchmarkStrokeCount > 0) {
            return@DisposableEffect onDispose {}
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                val manager = continuousSessionManager
                if (manager != null) {
                    val snapshotDirty = synchronized(dirtyContinuousPageIds) {
                        dirtyContinuousPageIds.toList().also { dirtyContinuousPageIds.clear() }
                    }
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        manager.flushAllJournals()
                        val now = System.currentTimeMillis()
                        for (dirtyId in snapshotDirty) {
                            database.pageDao().touchPageTimestamp(dirtyId, now)
                            BackgroundOcrWorker.enqueueForPage(context, dirtyId)
                        }
                        if (snapshotDirty.isNotEmpty()) {
                            CloudSyncScheduler.onNotebookExit(
                                context = context,
                                database = database,
                                editedPageId = snapshotDirty.last()
                            )
                        }
                    }
                } else {
                    val journal = strokeJournal ?: return@LifecycleEventObserver
                    val hadLocalEdits = journal.consumeLocalMutationsFlag()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        journal.flushAndSync()
                        BackgroundOcrWorker.enqueueForPage(context, target.pageId)
                        if (hadLocalEdits) {
                            CloudSyncScheduler.onNotebookExit(
                                context = context,
                                database = database,
                                editedPageId = target.pageId
                            )
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val manager = continuousSessionManager
            if (manager != null) {
                val snapshotDirty = synchronized(dirtyContinuousPageIds) {
                    dirtyContinuousPageIds.toList().also { dirtyContinuousPageIds.clear() }
                }
                CoroutineScope(SupervisorJob() + Dispatchers.IO + NonCancellable).launch {
                    manager.flushAllJournals()
                    val now = System.currentTimeMillis()
                    for (dirtyId in snapshotDirty) {
                        database.pageDao().touchPageTimestamp(dirtyId, now)
                        BackgroundOcrWorker.enqueueForPage(context, dirtyId)
                    }
                    manager.disposeSuspending()
                    CloudSyncScheduler.onNotebookExit(
                        context = context,
                        database = if (snapshotDirty.isNotEmpty()) database else null,
                        editedPageId = snapshotDirty.lastOrNull()
                    )
                }
            } else {
                val journal = strokeJournal ?: return@onDispose
                val hadLocalEdits = journal.consumeLocalMutationsFlag()
                val closingPageId = target.pageId
                if (deletedActivePageId == closingPageId) {
                    return@onDispose
                }
                val shouldNotifyExit = !switchedWithinWorkspace
                CoroutineScope(SupervisorJob() + Dispatchers.IO + NonCancellable).launch {
                    journal.close(compactBeforeClose = !journal.isReadOnly)
                    BackgroundOcrWorker.enqueueForPage(context, closingPageId)
                    if (shouldNotifyExit) {
                        CloudSyncScheduler.onNotebookExit(
                            context = context,
                            database = if (hadLocalEdits) database else null,
                            editedPageId = if (hadLocalEdits) closingPageId else null
                        )
                    }
                }
            }
        }
    }

    historyRevisionTick
    hydrationRevisionTick
    val canUndo = continuousSessionManager?.undoCoordinator?.canUndo ?: canvasStateHolder.canUndo
    val canRedo = continuousSessionManager?.undoCoordinator?.canRedo ?: canvasStateHolder.canRedo

    fun switchToPage(nextPageId: String, targetLocalRect: DocRect? = null) {
        if (continuousSessionManager != null) {
            val slot = continuousLayout?.slotForPageId(nextPageId)
            if (slot != null) {
                continuousSessionManager.updateActiveFocus(slot.pageIndex)
                scrollToPageRequestId = nextPageId
                scrollToPageTargetLocalRect = targetLocalRect
                scrollToPageRequestTick++
            } else {
                pendingScrollAfterLayoutPageId = nextPageId
            }
            if (nextPageId != latestTargetPageId) {
                coroutineScope.launch(Dispatchers.IO) {
                    database.notebookDao().getNotebookById(target.notebookId)?.let { nb ->
                        if (nb.lastOpenedPageId != nextPageId) {
                            database.notebookDao().upsertNotebook(nb.copy(lastOpenedPageId = nextPageId))
                        }
                    }
                }
                CloudSyncScheduler.onPageTransition(context)
                onSelectPageTarget(OpenedPageTarget(target.notebookId, nextPageId))
            }
            return
        }
        val singleJournal = strokeJournal ?: return
        if (nextPageId == target.pageId) return
        switchedWithinWorkspace = true
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val hadLocalEdits = singleJournal.consumeLocalMutationsFlag()
                singleJournal.flushAndSync()
                val now = System.currentTimeMillis()
                if (hadLocalEdits) {
                    database.pageDao().touchPageTimestamp(target.pageId, now)
                }
                database.notebookDao().getNotebookById(target.notebookId)?.let { nb ->
                    database.notebookDao().upsertNotebook(nb.copy(lastOpenedPageId = nextPageId))
                }
            }
            CloudSyncScheduler.onPageTransition(context)
            onSelectPageTarget(OpenedPageTarget(target.notebookId, nextPageId))
        }
    }

    val bridgeReady = if (isContinuousMultiPage) {
        hasHydratedPageContent && (continuousSessionManager?.getReadyRuntime(target.pageId) != null)
    } else {
        hasHydratedPageContent
    }
    DisposableEffect(sessionKey, target.pageId, bridgeReady) {
        if (bridgeReady) {
            val activeHolder = continuousSessionManager?.getReadyRuntime(target.pageId)?.canvasStateHolder
                ?: canvasStateHolder
            onPublishSearchBridge(
                ActivePageSearchBridge(
                    pageId = target.pageId,
                    liveStrokesProvider = {
                        SearchTextIndexer.buildSearchablePersistedRecords(activeHolder.allStrokesSorted())
                    },
                    liveObjectsProvider = { activeHolder.allPageObjects() },
                    livePageTitleProvider = {
                        notebookPages.firstOrNull { it.id == target.pageId }?.title
                            ?: currentPageEntity?.title
                    },
                    switchPageAction = { nextId -> switchToPage(nextId) }
                )
            )
        }
        onDispose {
            onClearSearchBridge(target.pageId)
        }
    }

    fun createAndOpenNextPage() {
        if (isCreatingPage) return
        isCreatingPage = true
        coroutineScope.launch {
            try {
                val createdPair = withContext(Dispatchers.IO) {
                    if (continuousSessionManager != null) {
                        continuousSessionManager.flushAllJournals()
                    } else {
                        strokeJournal?.flushAndSync()
                    }
                    val now = System.currentTimeMillis()
                    val nb = database.notebookDao().getNotebookById(target.notebookId)
                        ?: return@withContext null
                    var existingPages = database.pageDao().getPagesForNotebook(target.notebookId)
                    if (existingPages.none { it.id == target.pageId }) {
                        val fallbackPreset = currentPageEntity?.pageSizePreset
                            ?: nb.defaultPageSizePreset
                        database.pageDao().upsertPage(
                            PageEntity(
                                id = target.pageId,
                                notebookId = target.notebookId,
                                pageIndexInNotebook = 0,
                                canvasMode = paperConfig.canvasMode,
                                pageSizePreset = fallbackPreset,
                                templateType = paperConfig.templateType,
                                createdAtEpochMs = now,
                                updatedAtEpochMs = now
                            )
                        )
                        existingPages = database.pageDao().getPagesForNotebook(target.notebookId)
                    }
                    val createdId = UUID.randomUUID().toString()
                    val nextIndex = (existingPages.maxOfOrNull { it.pageIndexInNotebook } ?: -1) + 1
                    val resolvedPreset = currentPageEntity?.pageSizePreset
                        ?: nb.defaultPageSizePreset
                    database.pageDao().upsertPage(
                        PageEntity(
                            id = createdId,
                            notebookId = target.notebookId,
                            pageIndexInNotebook = nextIndex,
                            canvasMode = CanvasMode.FIXED_PAGE,
                            pageSizePreset = resolvedPreset,
                            templateType = paperConfig.templateType,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                    database.notebookDao().upsertNotebook(
                        nb.copy(lastOpenedPageId = createdId, updatedAtEpochMs = now)
                    )
                    createdId to (nextIndex + 1)
                }
                if (createdPair == null) {
                    copilotNoticeBannerHu = "A jegyzetfüzet nem található az adatbázisban."
                } else {
                    val (newPageId, newPageNum) = createdPair
                    switchedWithinWorkspace = true
                    CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                    CloudSyncScheduler.onPageTransition(context)
                    onShowLeftPageDrawerChange(false)
                    onShowWorkspaceBanner("${newPageNum}. oldal létrehozva")
                    if (continuousSessionManager != null) {
                        val immediateSlot = continuousLayout?.slotForPageId(newPageId)
                        if (immediateSlot != null) {
                            continuousSessionManager.updateActiveFocus(immediateSlot.pageIndex)
                            scrollToPageRequestId = newPageId
                            scrollToPageTargetLocalRect = null
                            scrollToPageRequestTick++
                        } else {
                            pendingScrollAfterLayoutPageId = newPageId
                        }
                    }
                    onSelectPageTarget(OpenedPageTarget(target.notebookId, newPageId))
                }
            } finally {
                isCreatingPage = false
            }
        }
    }

    fun deletePageSafely(pageIdToDelete: String) {
        if (isDeletingPage) return
        val ordered = notebookPages.sortedBy { it.pageIndexInNotebook }
        if (ordered.size <= 1) return
        val deletingCurrent = (pageIdToDelete == latestTargetPageId)
        val nextActive = resolveNextActivePageAfterDelete(
            orderedPages = ordered,
            deletedPageId = pageIdToDelete,
            currentActivePageId = latestTargetPageId
        ) ?: return

        isDeletingPage = true
        dirtyContinuousPageIds.remove(pageIdToDelete)
        pageThumbnailRenderer.invalidatePage(pageIdToDelete)
        continuousSessionManager?.notifyPageDeleted(pageIdToDelete)
        if (selectedObjectPageId == pageIdToDelete) {
            selectedObjectId = null
            selectedObjectPageId = null
        }
        if (deletingCurrent && continuousSessionManager == null) {
            deletedActivePageId = pageIdToDelete
            switchedWithinWorkspace = true
        }

        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (deletingCurrent && continuousSessionManager == null) {
                        strokeJournal?.close(compactBeforeClose = false)
                    }
                    val now = System.currentTimeMillis()
                    val nb = database.notebookDao().getNotebookById(target.notebookId)
                    if (nb != null && nb.lastOpenedPageId == pageIdToDelete) {
                        database.notebookDao().upsertNotebook(nb.copy(lastOpenedPageId = nextActive.id))
                    }
                    database.deleteWithTombstone(SyncTombstoneEntity.TYPE_PAGE, pageIdToDelete)
                    val remaining = database.pageDao().getPagesForNotebook(target.notebookId)
                    if (remaining.isNotEmpty()) {
                        database.pageDao().reorderPagesInNotebook(
                            notebookId = target.notebookId,
                            orderedPageIds = remaining.sortedBy { it.pageIndexInNotebook }.map { it.id },
                            updatedAtMs = now
                        )
                    }
                    val pageDir = File(context.filesDir, "pages/$pageIdToDelete")
                    if (pageDir.exists()) {
                        pageDir.deleteRecursively()
                    }
                }
                CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                if (deletingCurrent) {
                    CloudSyncScheduler.onPageTransition(context)
                    onSelectPageTarget(OpenedPageTarget(target.notebookId, nextActive.id))
                }
                externalDrawTick++
                historyRevisionTick++
                onShowWorkspaceBanner("Oldal törölve")
            } finally {
                isDeletingPage = false
            }
        }
    }

    val activePageIdx = notebookPages.indexOfFirst { it.id == target.pageId }
    val currentIdxOneBased = if (activePageIdx >= 0) activePageIdx + 1 else pageNumber
    val totalPageCount = notebookPages.size.coerceAtLeast(currentIdxOneBased)

    var lassoPreviewPageId by remember(sessionKey) { mutableStateOf<String?>(null) }
    var lassoPreviewObjectIds by remember(sessionKey) { mutableStateOf<Set<String>>(emptySet()) }
    var lassoPreviewDeltaXPt by remember(sessionKey) { mutableFloatStateOf(0f) }
    var lassoPreviewDeltaYPt by remember(sessionKey) { mutableFloatStateOf(0f) }
    var lassoPreviewRotationRad by remember(sessionKey) { mutableFloatStateOf(0f) }
    var lassoPreviewRotationCenter by remember(sessionKey) { mutableStateOf<DocPoint?>(null) }

    val fallbackJournalForSingleSurface = strokeJournal ?: remember(sessionKey) {
        StrokeJournal(pageDirectory = File(context.filesDir, "pages/${target.pageId}"))
    }

    Box(modifier = modifier.fillMaxSize()) {
        StylusDrawingSurface(
            transformMatrix = transformMatrix,
            paperConfig = paperConfig,
            brushConfig = brushConfig,
            lastEraserTool = lastEraserTool,
            dryStrokeStore = canvasStateHolder,
            dryInkRasterCache = dryInkRasterCache,
            oledStrokeCache = oledStrokeCache,
            journalCommitSink = fallbackJournalForSingleSurface,
            onTogglePenEraser = onTogglePenEraser,
            onCanvasStateMutated = {
                pageThumbnailRenderer.invalidatePage(latestTargetPageId)
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
            onViewportTransformChanged = {
                syncZoomPercentFromMatrix()
                bumpOverlayTransformTickIfVisible()
            },
            onLiveViewportTransformChanged = {
                bumpOverlayTransformTickIfVisible()
            },
            onCanvasTouchStart = {
                if (selectedObjectId != null) {
                    selectedObjectId = null
                    selectedObjectPageId = null
                }
            },
            onNavigationGestureEnd = {
                if (!isContinuousMultiPage &&
                    paperConfig.canvasMode == CanvasMode.FIXED_PAGE &&
                    benchmarkStrokeCount == 0 &&
                    viewportSize.width > 0 &&
                    viewportSize.height > 0
                ) {
                    val visibleRect = transformMatrix.screenRectToDocRect(
                        leftPx = 0f,
                        topPx = 0f,
                        rightPx = viewportSize.width.toFloat(),
                        bottomPx = viewportSize.height.toFloat()
                    )
                    val centerDocY = (visibleRect.topPt + visibleRect.bottomPt) * 0.5f
                    val idx = notebookPages.indexOfFirst { it.id == target.pageId }
                    when (
                        resolveFixedPageScrollNavigation(
                            visibleRect = visibleRect,
                            centerDocY = centerDocY,
                            pageHeightPt = paperConfig.pageDimensions.heightPt,
                            currentPageIndex = idx,
                            totalPages = notebookPages.size
                        )
                    ) {
                        FixedPageScrollNavigationAction.PREVIOUS_PAGE -> {
                            if (idx > 0) switchToPage(notebookPages[idx - 1].id)
                        }
                        FixedPageScrollNavigationAction.NEXT_PAGE -> {
                            if (idx >= 0 && idx + 1 < notebookPages.size) {
                                switchToPage(notebookPages[idx + 1].id)
                            }
                        }
                        FixedPageScrollNavigationAction.CREATE_NEXT_PAGE -> createAndOpenNextPage()
                        FixedPageScrollNavigationAction.NONE -> Unit
                    }
                }
            },
            currentPageIndexOneBased = currentIdxOneBased,
            totalPages = totalPageCount,
            initialPagePaddingPx = if (isFullScreenCanvas) 0f else 36f,
            onAiAutoFormatLassoSelection = { selectedIds, onFormatted ->
                val binding = resolveBindingForPage(target.pageId)
                if (selectedIds.isEmpty() || binding == null || binding.isReadOnly) {
                    onFormatted(false)
                } else {
                    coroutineScope.launch {
                        val selectedRecords = selectedIds.mapNotNull { id ->
                            binding.canvasStateHolder.getStroke(id)?.let { entry ->
                                val strokeColor = entry.stroke.brush.colorIntArgb
                                StrokeBinarySerializer.fromIndexedDryStroke(
                                    entry = entry,
                                    brushConfig = brushConfig.copy(colorArgb = strokeColor),
                                    shapeMeta = binding.canvasStateHolder.getShapeMeta(id)
                                )
                            }
                        }
                        if (selectedRecords.isEmpty()) {
                            onFormatted(false)
                            return@launch
                        }
                        val isLocalSingleShape = selectedRecords.size == 1 &&
                            ShapeRecognizer.recognize(
                                samples = selectedRecords.first().samples,
                                requireHoldAtEnd = false,
                                minConfidence = 0.74f
                            ) != null
                        if (!isLocalSingleShape && !consentState.isReadyForUserInitiatedCall) {
                            onOpenConsentDialog()
                            onFormatted(false)
                            return@launch
                        }
                        copilotNoticeBannerHu = "AI formázás folyamatban..."
                        when (
                            val res = HandwritingToLatexConverter.autoFormatLassoSelection(
                                selectedStrokes = selectedRecords,
                                geminiClient = geminiClient,
                                canvasStateHolder = binding.canvasStateHolder,
                                context = context,
                                journalSink = binding.strokeJournal
                            )
                        ) {
                            is LassoAutoFormatResult.Success -> {
                                selectedObjectId = null
                                selectedObjectPageId = null
                                historyRevisionTick++
                                copilotNoticeBannerHu = res.noticeHu
                                onFormatted(true)
                            }
                            is LassoAutoFormatResult.Failure -> {
                                copilotNoticeBannerHu = res.messageHu
                                onFormatted(false)
                            }
                        }
                    }
                }
            },
            onLassoSelectedObject = { objId ->
                selectedObjectId = objId
                selectedObjectPageId = target.pageId
            },
            onLassoObjectPreviewChanged = { ids, dx, dy, rot, center ->
                lassoPreviewPageId = target.pageId
                lassoPreviewObjectIds = ids
                lassoPreviewDeltaXPt = dx
                lassoPreviewDeltaYPt = dy
                lassoPreviewRotationRad = rot
                lassoPreviewRotationCenter = center
            },
            multiPageSessionManager = continuousSessionManager,
            multiPageLayout = continuousLayout,
            initialPageId = target.pageId,
            hydrationVersionTick = hydrationRevisionTick,
            pdfTileRenderer = pdfTileRenderer,
            singlePagePdfAssetId = currentPageEntity?.pdfAssetId,
            singlePagePdfPageIndex = currentPageEntity?.pdfPageIndex ?: 0,
            liveObjectInteractionController = liveObjectController,
            scrollToPageRequestId = scrollToPageRequestId,
            scrollToPageTargetLocalRect = scrollToPageTargetLocalRect,
            scrollToPageRequestTick = scrollToPageRequestTick,
            onActiveSlotChanged = { slot ->
                if (continuousSessionManager != null) {
                    continuousSessionManager.updateActiveFocus(slot.pageIndex)
                    if (slot.pageId != latestTargetPageId) {
                        coroutineScope.launch(Dispatchers.IO) {
                            database.notebookDao().getNotebookById(target.notebookId)?.let { nb ->
                                if (nb.lastOpenedPageId != slot.pageId) {
                                    database.notebookDao().upsertNotebook(nb.copy(lastOpenedPageId = slot.pageId))
                                }
                            }
                        }
                        onSelectPageTarget(OpenedPageTarget(target.notebookId, slot.pageId))
                    }
                }
            },
            onPageMutated = { mutatedPageId ->
                pageThumbnailRenderer.invalidatePage(mutatedPageId)
                dirtyContinuousPageIds.add(mutatedPageId)
                historyRevisionTick++
                if (benchmarkStrokeCount == 0) {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.pageDao().markOcrSyncState(
                            pageId = mutatedPageId,
                            needsSync = true,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                }
            },
            onLassoSelectedObjectInPage = { pageId, objId ->
                selectedObjectId = objId
                selectedObjectPageId = pageId ?: target.pageId
            },
            onAiAutoFormatLassoSelectionInPage = { pageId, selectedIds, onFormatted ->
                val resolvedPageId = pageId ?: latestTargetPageId
                val binding = resolveBindingForPage(resolvedPageId)
                if (selectedIds.isEmpty() || binding == null || binding.isReadOnly) {
                    onFormatted(false)
                } else {
                    coroutineScope.launch {
                        val selectedRecords = selectedIds.mapNotNull { id ->
                            binding.canvasStateHolder.getStroke(id)?.let { entry ->
                                val strokeColor = entry.stroke.brush.colorIntArgb
                                StrokeBinarySerializer.fromIndexedDryStroke(
                                    entry = entry,
                                    brushConfig = brushConfig.copy(colorArgb = strokeColor),
                                    shapeMeta = binding.canvasStateHolder.getShapeMeta(id)
                                )
                            }
                        }
                        if (selectedRecords.isEmpty()) {
                            onFormatted(false)
                            return@launch
                        }
                        val isLocalSingleShape = selectedRecords.size == 1 &&
                            ShapeRecognizer.recognize(
                                samples = selectedRecords.first().samples,
                                requireHoldAtEnd = false,
                                minConfidence = 0.74f
                            ) != null
                        if (!isLocalSingleShape && !consentState.isReadyForUserInitiatedCall) {
                            onOpenConsentDialog()
                            onFormatted(false)
                            return@launch
                        }
                        copilotNoticeBannerHu = "AI formázás folyamatban..."
                        when (
                            val res = HandwritingToLatexConverter.autoFormatLassoSelection(
                                selectedStrokes = selectedRecords,
                                geminiClient = geminiClient,
                                canvasStateHolder = binding.canvasStateHolder,
                                context = context,
                                journalSink = binding.strokeJournal
                            )
                        ) {
                            is LassoAutoFormatResult.Success -> {
                                dirtyContinuousPageIds.add(resolvedPageId)
                                selectedObjectId = null
                                selectedObjectPageId = null
                                historyRevisionTick++
                                copilotNoticeBannerHu = res.noticeHu
                                onFormatted(true)
                            }
                            is LassoAutoFormatResult.Failure -> {
                                copilotNoticeBannerHu = res.messageHu
                                onFormatted(false)
                            }
                        }
                    }
                }
            },
            onLassoObjectPreviewChangedInPage = { pageId, ids, dx, dy, rot, center ->
                lassoPreviewPageId = pageId
                lassoPreviewObjectIds = ids
                lassoPreviewDeltaXPt = dx
                lassoPreviewDeltaYPt = dy
                lassoPreviewRotationRad = rot
                lassoPreviewRotationCenter = center
            },
            externalDrawVersionTick = externalDrawTick,
            modifier = Modifier.fillMaxSize()
        )

        historyRevisionTick
        hydrationRevisionTick
        if (continuousSessionManager != null && continuousLayout != null) {
            for (slot in continuousLayout.slots) {
                val runtime = continuousSessionManager.getReadyRuntime(slot.pageId) ?: continue
                val slotObjects = runtime.canvasStateHolder.allPageObjects()
                if (slotObjects.isEmpty()) continue
                key(slot.pageId) {
                    val slotBinding = ActivePageBinding(
                        pageId = slot.pageId,
                        pageIndex = slot.pageIndex,
                        originXPt = slot.leftPt,
                        originYPt = slot.topPt,
                        canvasStateHolder = runtime.canvasStateHolder,
                        strokeJournal = runtime.strokeJournal,
                        dryInkRasterCache = runtime.dryInkRasterCache,
                        oledStrokeCache = runtime.oledStrokeCache,
                        isReadOnly = runtime.isReadOnly
                    )
                    BoundPageObjectsOverlay(
                        binding = slotBinding,
                        pageObjects = slotObjects,
                        transformMatrix = transformMatrix,
                        isOledMode = isDarkTheme,
                        selectedObjectId = if (selectedObjectPageId == slot.pageId) selectedObjectId else null,
                        onSelectObject = { objId ->
                            selectedObjectId = objId
                            selectedObjectPageId = if (objId != null) slot.pageId else null
                        },
                        consentState = consentState,
                        onOpenConsentDialog = onOpenConsentDialog,
                        geminiClient = geminiClient,
                        coroutineScope = coroutineScope,
                        onStepByStepFailure = { copilotNoticeBannerHu = it },
                        onMutated = {
                            pageThumbnailRenderer.invalidatePage(slot.pageId)
                            dirtyContinuousPageIds.add(slot.pageId)
                            externalDrawTick++
                            historyRevisionTick++
                        },
                        liveObjectInteractionController = liveObjectController,
                        lassoPreviewObjectIds = if (lassoPreviewPageId == slot.pageId) {
                            lassoPreviewObjectIds
                        } else {
                            emptySet()
                        },
                        lassoPreviewDeltaXPt = lassoPreviewDeltaXPt,
                        lassoPreviewDeltaYPt = lassoPreviewDeltaYPt,
                        lassoPreviewRotationRadians = lassoPreviewRotationRad,
                        lassoPreviewRotationCenter = lassoPreviewRotationCenter,
                        viewportTransformTick = viewportTransformTick
                    )
                }
            }
        } else {
            val singleBinding = resolveBindingForPage(target.pageId)
            val livePageObjects = canvasStateHolder.allPageObjects()
            if (singleBinding != null && livePageObjects.isNotEmpty()) {
                BoundPageObjectsOverlay(
                    binding = singleBinding,
                    pageObjects = livePageObjects,
                    transformMatrix = transformMatrix,
                    isOledMode = isDarkTheme,
                    selectedObjectId = selectedObjectId,
                    onSelectObject = { objId ->
                        selectedObjectId = objId
                        selectedObjectPageId = if (objId != null) target.pageId else null
                    },
                    consentState = consentState,
                    onOpenConsentDialog = onOpenConsentDialog,
                    geminiClient = geminiClient,
                    coroutineScope = coroutineScope,
                    onStepByStepFailure = { copilotNoticeBannerHu = it },
                    onMutated = {
                        pageThumbnailRenderer.invalidatePage(target.pageId)
                        externalDrawTick++
                        historyRevisionTick++
                    },
                    liveObjectInteractionController = liveObjectController,
                    lassoPreviewObjectIds = lassoPreviewObjectIds,
                    lassoPreviewDeltaXPt = lassoPreviewDeltaXPt,
                    lassoPreviewDeltaYPt = lassoPreviewDeltaYPt,
                    lassoPreviewRotationRadians = lassoPreviewRotationRad,
                    lassoPreviewRotationCenter = lassoPreviewRotationCenter,
                    viewportTransformTick = viewportTransformTick
                )
            }
        }

        val highlightRect = activePageMatch?.highlightDocRect
        if (highlightRect != null) {
            viewportTransformTick
            val matchSlot = continuousLayout?.slotForPageId(activePageMatch.pageId)
            val wsHighlightRect = matchSlot?.localRectToWorkspaceRect(highlightRect) ?: highlightRect
            SearchHighlightCanvasOverlay(
                highlightDocRect = wsHighlightRect,
                transformSnapshot = transformMatrix.snapshot(),
                isOledMode = palette.isOledDeepBlack,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("in_notebook_search_highlight")
            )
        }

        if (!isFullScreenCanvas) {
            CanvasTopToolbar(
                notebookTitle = notebookTitle,
                zoomPercentProvider = { currentZoomPercent },
                searchUiState = searchUiState,
                onBackToShelf = onBackToShelf,
                onOpenCloudBackupDialog = { showCloudBackupDialog = true },
                onCloudQuickSyncTriggered = { msg -> onShowWorkspaceBanner(msg) },
                onZoomOut = { applyStepZoom(0.8f) },
                onResetZoom = { fitPageToViewportNow() },
                onZoomIn = { applyStepZoom(1.25f) },
                onOpenAiCopilot = { showAiCopilot = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(0.dp)
                    .onGloballyPositioned { coords ->
                        val next = coords.boundsInParent()
                        if (rectsDifferOver1Px(topToolbarBoundsPx, next)) {
                            topToolbarBoundsPx = next
                        }
                    }
            )
        }

        if (benchmarkStrokeCount == 0 && paperConfig.canvasMode == CanvasMode.FIXED_PAGE) {
            LeftPagePullTab(
                currentPageIndexOneBased = currentIdxOneBased,
                totalPages = totalPageCount,
                yFraction = dockPrefs.leftTabYFraction,
                viewportHeightPx = viewportSize.height,
                onUpdateYFraction = { nextY ->
                    coroutineScope.launch { toolDockPrefsStore.saveLeftTabYFraction(nextY) }
                },
                onOpenDrawer = { onShowLeftPageDrawerChange(true) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 0.dp)
            )

            if (!isFullScreenCanvas) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(end = 14.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (activePageIdx > 0) {
                                    switchToPage(notebookPages[activePageIdx - 1].id)
                                }
                            },
                            enabled = activePageIdx > 0,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Előző oldal",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        val activePageEntity = notebookPages.firstOrNull { it.id == target.pageId }
                            ?: currentPageEntity
                        val customPageTitle = activePageEntity?.title?.trim()?.takeIf { it.isNotEmpty() }
                        val stepperLabel = if (customPageTitle != null) {
                            "$customPageTitle • $currentIdxOneBased/$totalPageCount"
                        } else {
                            "$currentIdxOneBased / $totalPageCount"
                        }
                        Text(
                            text = stepperLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clickable { onShowLeftPageDrawerChange(true) }
                                .widthIn(max = 180.dp)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                if (activePageIdx >= 0 && activePageIdx + 1 < notebookPages.size) {
                                    switchToPage(notebookPages[activePageIdx + 1].id)
                                }
                            },
                            enabled = activePageIdx >= 0 && activePageIdx + 1 < notebookPages.size,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Következő oldal",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { createAndOpenNextPage() },
                            enabled = !isCreatingPage,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Új oldal hozzáadása",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

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

        val activePageIsReadOnly = if (isContinuousMultiPage) {
            continuousSessionManager?.getReadyRuntime(target.pageId)?.isReadOnly == true
        } else {
            isReadOnlyPage
        }
        if (activePageIsReadOnly) {
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

        fun applyHistoryTransition(isUndo: Boolean) {
            val manager = continuousSessionManager
            if (manager != null) {
                coroutineScope.launch {
                    historyMutex.withLock {
                        val res = if (isUndo) {
                            manager.applyUndo()
                        } else {
                            manager.applyRedo()
                        } ?: return@withLock
                        val runtime = manager.getReadyRuntime(res.pageId)
                        if (selectedObjectId != null &&
                            selectedObjectPageId == res.pageId &&
                            runtime?.canvasStateHolder?.getPageObject(selectedObjectId!!) == null
                        ) {
                            selectedObjectId = null
                            selectedObjectPageId = null
                        }
                        if (!res.isReadOnly) {
                            dirtyContinuousPageIds.add(res.pageId)
                            coroutineScope.launch(Dispatchers.IO) {
                                database.pageDao().markOcrSyncState(
                                    pageId = res.pageId,
                                    needsSync = true,
                                    updatedAtMs = System.currentTimeMillis()
                                )
                                CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                            }
                        }
                        if (res.didRehydratePage || res.pageId != latestTargetPageId) {
                            val slot = continuousLayout?.slotForPageId(res.pageId)
                            if (slot != null) {
                                manager.updateActiveFocus(slot.pageIndex)
                            }
                            scrollToPageRequestId = res.pageId
                            scrollToPageTargetLocalRect = res.affectedBoundsLocal
                            scrollToPageRequestTick++
                            onSelectPageTarget(OpenedPageTarget(target.notebookId, res.pageId))
                        }
                        pageThumbnailRenderer.invalidatePage(res.pageId)
                        externalDrawTick++
                        historyRevisionTick++
                    }
                }
                return
            }

            val singleJournal = strokeJournal ?: return
            val outcome = if (isUndo) {
                canvasStateHolder.undoWithAction()
            } else {
                canvasStateHolder.redoWithAction()
            }
            val action = outcome.action ?: return
            outcome.affectedBounds?.let { dryInkRasterCache.invalidateDocRect(it) }
            if (selectedObjectId != null && canvasStateHolder.getPageObject(selectedObjectId!!) == null) {
                selectedObjectId = null
                selectedObjectPageId = null
            }
            if (!isReadOnlyPage) {
                if (isUndo) {
                    singleJournal.enqueueCanvasActionInverse(action)
                } else {
                    singleJournal.enqueueCanvasActionForward(action)
                }
                coroutineScope.launch(Dispatchers.IO) {
                    database.pageDao().markOcrSyncState(
                        pageId = target.pageId,
                        needsSync = true,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                }
            }
            pageThumbnailRenderer.invalidatePage(target.pageId)
            externalDrawTick++
            historyRevisionTick++
        }

        FloatingToolDock(
            brushConfig = brushConfig,
            lastDrawingTool = lastDrawingTool,
            lastEraserTool = lastEraserTool,
            dockPrefs = dockPrefs,
            avoidTopLeftRectPx = topToolbarBoundsPx,
            viewportSize = viewportSize,
            canUndo = canUndo,
            canRedo = canRedo,
            isDarkTheme = isDarkTheme,
            showDebugOverlay = showDebugOverlay,
            isFullScreen = isFullScreenCanvas,
            onUpdateDockPrefs = { next ->
                coroutineScope.launch { toolDockPrefsStore.savePrefs(next) }
            },
            onSelectTool = onSelectTool,
            onOpenSettings = { showBrushSettings = true },
            onOpenPaperSettings = { showPaperSettings = true },
            onOpenShapes = { showShapePicker = true },
            onInsertImage = { insertImageLauncher.launch("image/*") },
            onUndo = { applyHistoryTransition(isUndo = true) },
            onRedo = { applyHistoryTransition(isUndo = false) },
            onFitPage = { fitPageToViewportNow() },
            onToggleTheme = onToggleTheme,
            onToggleDebugOverlay = onToggleDebugOverlay,
            onToggleFullScreen = onToggleFullScreen,
            onDockBoundsChanged = { next ->
                if (rectsDifferOver1Px(dockBoundsPx, next)) {
                    dockBoundsPx = next
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showBrushSettings) {
            BrushSettingsPopover(
                currentConfig = brushConfig,
                presets = presets,
                anchorBoundsPx = dockBoundsPx,
                viewportSize = viewportSize,
                onConfigChange = onUpdateBrushConfig,
                onSavePresetSlot = { slot, cfg ->
                    coroutineScope.launch { presetStore.savePreset(slot, cfg) }
                },
                onApplyPreset = onUpdateBrushConfig,
                onDismiss = { showBrushSettings = false }
            )
        }

        if (showShapePicker) {
            ShapePickerPopover(
                initialStrokeWidthPt = shapeStrokeWidthPt,
                initialStrokeColorArgb = shapeStrokeColorArgb,
                initialHasFill = shapeHasFill,
                anchorBoundsPx = dockBoundsPx,
                viewportSize = viewportSize,
                onInsertShape = { shapeType, strokeWidthPt, strokeColorArgb, hasFill ->
                    onUpdateShapeDefaults(strokeWidthPt, strokeColorArgb, hasFill)
                    val binding = resolveViewportTargetBinding()
                    if (binding != null && !binding.isReadOnly) {
                        val centerWs = viewportCenterWorkspacePoint()
                        val centerDoc = DocPoint(
                            xPt = centerWs.xPt - binding.originXPt,
                            yPt = centerWs.yPt - binding.originYPt
                        )
                        val defaultHalfW = if (
                            shapeType == GeometricShapeType.LINE ||
                            shapeType == GeometricShapeType.ARROW ||
                            shapeType == GeometricShapeType.DOUBLE_ARROW
                        ) 95f else 68f
                        val defaultHalfH = if (
                            shapeType == GeometricShapeType.LINE ||
                            shapeType == GeometricShapeType.ARROW ||
                            shapeType == GeometricShapeType.DOUBLE_ARROW
                        ) 24f else 68f
                        val shapeObj = PageObject.GeometricShapeObject(
                            id = UUID.randomUUID().toString(),
                            zOrder = binding.canvasStateHolder.allocateObjectZOrder(),
                            bounds = DocRect(
                                leftPt = centerDoc.xPt - defaultHalfW,
                                topPt = centerDoc.yPt - defaultHalfH,
                                rightPt = centerDoc.xPt + defaultHalfW,
                                bottomPt = centerDoc.yPt + defaultHalfH
                            ),
                            shapeType = shapeType,
                            strokeWidthPt = strokeWidthPt,
                            canonicalStrokeColorArgb = strokeColorArgb,
                            hasFill = hasFill,
                            canonicalFillColorArgb = (strokeColorArgb and 0x00FFFFFF) or 0x28000000
                        )
                        binding.canvasStateHolder.addPageObject(shapeObj)
                        coroutineScope.launch { binding.strokeJournal.appendAddObject(shapeObj) }
                        dirtyContinuousPageIds.add(binding.pageId)
                        selectedObjectId = shapeObj.id
                        selectedObjectPageId = binding.pageId
                        historyRevisionTick++
                    }
                },
                onDismiss = { showShapePicker = false }
            )
        }

        if (showLeftPageDrawer && paperConfig.canvasMode == CanvasMode.FIXED_PAGE) {
            LeftPageDrawerOverlay(
                pages = notebookPages,
                activePageId = target.pageId,
                onSelectPage = { nextPageId -> switchToPage(nextPageId) },
                onAddNewPage = { createAndOpenNextPage() },
                onReorderPages = { orderedIds ->
                    coroutineScope.launch(Dispatchers.IO) {
                        database.pageDao().reorderPagesInNotebook(
                            notebookId = target.notebookId,
                            orderedPageIds = orderedIds,
                            updatedAtMs = System.currentTimeMillis()
                        )
                        CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                    }
                },
                onRenamePage = { pageId, newTitle ->
                    val sanitized = newTitle?.trim()?.takeIf { it.isNotEmpty() }
                    coroutineScope.launch(Dispatchers.IO) {
                        database.pageDao().renamePage(
                            pageId = pageId,
                            title = sanitized,
                            updatedAtMs = System.currentTimeMillis()
                        )
                        CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                    }
                },
                onDeletePage = { pageId -> deletePageSafely(pageId) },
                thumbnailRenderer = pageThumbnailRenderer,
                pageThumbnailVersionProvider = { pageId ->
                    if (pageId == latestTargetPageId) historyRevisionTick else pageThumbnailRenderer.versionFor(pageId)
                },
                livePageSnapshotProvider = { pageId ->
                    val holder = if (continuousSessionManager != null) {
                        continuousSessionManager.getReadyRuntime(pageId)?.canvasStateHolder
                    } else if (pageId == latestTargetPageId) {
                        canvasStateHolder
                    } else {
                        null
                    }
                    holder?.let {
                        PageThumbnailSourceSnapshot(
                            strokes = persistedStrokesFrom(it),
                            objects = it.allPageObjects(),
                            liveInkStrokesById = it.allStrokesSorted().associate { entry -> entry.id to entry.stroke }
                        )
                    }
                },
                onDismiss = { onShowLeftPageDrawerChange(false) }
            )
        }

        if (showPaperSettings) {
            PaperSettingsDialog(
                currentConfig = paperConfig,
                onConfigChange = { updated ->
                    paperConfig = updated
                    pageThumbnailRenderer.invalidatePage(target.pageId)
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
                            CloudSyncScheduler.notifyDatabaseMetadataChanged(context)
                        }
                    }
                },
                onDismiss = { showPaperSettings = false }
            )
        }

        if (showAiCopilot) {
            AiCopilotSheet(
                notebookTitle = notebookTitle,
                pageNumber = pageNumber,
                totalPageCount = notebookPages.size.coerceAtLeast(1),
                consentState = consentState,
                onOpenConsentSettings = onOpenConsentDialog,
                onFormatWithAi = { aiScope, mode ->
                    runCatching {
                        val formatText = (mode == AiFormattingMode.FULL_HANDWRITING_AND_FIGURES)
                        val targets = when (aiScope) {
                            AiCopilotScope.CURRENT_PAGE -> listOfNotNull(
                                currentPageEntity ?: notebookPages.firstOrNull { it.id == target.pageId }
                            )
                            AiCopilotScope.ENTIRE_WORKBOOK ->
                                withContext(Dispatchers.IO) {
                                    database.pageDao().getPagesForNotebook(target.notebookId)
                                }.ifEmpty { notebookPages }.ifEmpty { listOfNotNull(currentPageEntity) }
                        }
                        val summary = executeAiFormattingAcrossPages(
                            targets = targets,
                            onlyUnformattedOcr = false,
                            formatText = formatText
                        )
                        val total = summary.formattedTextCount + summary.formattedFormulaCount + summary.formattedShapeCount
                        if (total == 0) {
                            "Nem található formázható elem a kiválasztott hatókörben."
                        } else {
                            "Kész (${targets.size} oldal): ${summary.formattedTextCount} szöveg, ${summary.formattedFormulaCount} képlet, ${summary.formattedShapeCount} alakzat formázva."
                        }
                    }
                },
                onDismiss = { showAiCopilot = false }
            )
        }

        if (showCloudBackupDialog) {
            CloudBackupDialog(
                database = database,
                onDismiss = { showCloudBackupDialog = false },
                onBackupNotice = { msg -> onShowWorkspaceBanner(msg) }
            )
        }
    }
}

@Composable
private fun BoundPageObjectsOverlay(
    binding: ActivePageBinding,
    pageObjects: List<PageObject>,
    transformMatrix: CanvasTransformMatrix,
    isOledMode: Boolean,
    selectedObjectId: String?,
    onSelectObject: (String?) -> Unit,
    consentState: AiConsentState,
    onOpenConsentDialog: () -> Unit,
    geminiClient: GeminiClient,
    coroutineScope: CoroutineScope,
    onStepByStepFailure: (String) -> Unit,
    onMutated: () -> Unit,
    liveObjectInteractionController: LiveObjectInteractionController,
    lassoPreviewObjectIds: Set<String>,
    lassoPreviewDeltaXPt: Float,
    lassoPreviewDeltaYPt: Float,
    lassoPreviewRotationRadians: Float,
    lassoPreviewRotationCenter: DocPoint?,
    viewportTransformTick: Long
) {
    PageObjectsOverlayLayer(
        pageObjects = pageObjects,
        transformMatrix = transformMatrix,
        isOledMode = isOledMode,
        selectedObjectId = selectedObjectId,
        onSelectObject = onSelectObject,
        pageId = binding.pageId,
        liveObjectInteractionController = liveObjectInteractionController,
        onCommitObjectInteraction = { beforeObj, afterObj, attachedAffine ->
            if (!binding.isReadOnly) {
                val res = binding.canvasStateHolder.commitObjectInteractionWithAttachedStrokes(
                    beforeObject = beforeObj,
                    afterObject = afterObj,
                    attachedTransform = attachedAffine
                )
                if (res.action != null) {
                    for (id in res.movedStrokeIds) {
                        binding.oledStrokeCache.invalidate(id)
                    }
                    res.affectedBounds?.let { binding.dryInkRasterCache.invalidateDocRect(it) }
                    onMutated()
                    coroutineScope.launch { binding.strokeJournal.onCanvasActionApplied(res.action) }
                }
            }
        },
        onUpdateObject = { updatedObj ->
            if (!binding.isReadOnly) {
                binding.canvasStateHolder.updatePageObject(updatedObj)
                onMutated()
                coroutineScope.launch { binding.strokeJournal.appendUpdateObject(updatedObj) }
            }
        },
        onDeleteObject = { objId ->
            if (!binding.isReadOnly) {
                val res = binding.canvasStateHolder.removePageObjectWithAttachedStrokes(objId)
                if (res.action != null) {
                    for (id in res.removedStrokeIds) {
                        binding.oledStrokeCache.invalidate(id)
                    }
                    res.affectedBounds?.let { binding.dryInkRasterCache.invalidateDocRect(it) }
                    if (selectedObjectId == objId) onSelectObject(null)
                    onMutated()
                    coroutineScope.launch { binding.strokeJournal.onCanvasActionApplied(res.action) }
                }
            }
        },
        onRestoreOriginalStrokes = { targetObj ->
            if (!binding.isReadOnly) {
                val sourceRecords = when (targetObj) {
                    is PageObject.LatexObject -> targetObj.originalStrokes
                    is PageObject.FormattedTextObject -> targetObj.originalStrokes
                    else -> emptyList()
                }
                if (sourceRecords.isNotEmpty()) {
                    val pairs = sourceRecords.map { rec ->
                        val (entry, _) = StrokeBinarySerializer.toIndexedDryStroke(rec)
                        rec to entry
                    }
                    val metaMap = sourceRecords.mapNotNull { rec ->
                        rec.shapeMeta?.let { meta -> rec.id to meta }
                    }.toMap()
                    val (dirtyBounds, insertedEntries) = binding.canvasStateHolder.restoreObjectToOriginalStrokes(
                        objectId = targetObj.id,
                        restoredEntries = pairs.map { it.second },
                        shapeMetas = metaMap
                    )
                    if (insertedEntries.isNotEmpty()) {
                        val insertedById = insertedEntries.associateBy { it.id }
                        val journalRecords = pairs.map { (rec, _) ->
                            val finalEntry = insertedById[rec.id]
                            if (finalEntry != null) rec.copy(zOrder = finalEntry.zIndex) else rec
                        }
                        dirtyBounds?.let { binding.dryInkRasterCache.invalidateDocRect(it) }
                        if (selectedObjectId == targetObj.id) onSelectObject(null)
                        onMutated()
                        coroutineScope.launch {
                            binding.strokeJournal.appendRemoveObject(targetObj.id)
                            binding.strokeJournal.appendAddStrokes(journalRecords)
                        }
                    }
                }
            }
        },
        onRequestStepByStep = { latexObj ->
            if (!consentState.isReadyForUserInitiatedCall) {
                onOpenConsentDialog()
            } else if (!binding.isReadOnly) {
                coroutineScope.launch {
                    HandwritingToLatexConverter.generateStepByStepDerivationCard(
                        latexObject = latexObj,
                        geminiClient = geminiClient,
                        canvasStateHolder = binding.canvasStateHolder,
                        journalSink = binding.strokeJournal
                    ).onSuccess {
                        onMutated()
                    }.onFailure { err ->
                        onStepByStepFailure(err.message ?: "Sikertelen levezetés.")
                    }
                }
            }
        },
        lassoPreviewObjectIds = lassoPreviewObjectIds,
        lassoPreviewDeltaXPt = lassoPreviewDeltaXPt,
        lassoPreviewDeltaYPt = lassoPreviewDeltaYPt,
        lassoPreviewRotationRadians = lassoPreviewRotationRadians,
        lassoPreviewRotationCenter = lassoPreviewRotationCenter,
        viewportTransformTick = viewportTransformTick,
        pageOriginXPt = binding.originXPt,
        pageOriginYPt = binding.originYPt,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
internal fun CanvasTopToolbar(
    notebookTitle: String,
    zoomPercentProvider: () -> Int,
    searchUiState: NotebookSearchUiState = NotebookSearchUiState(),
    onBackToShelf: () -> Unit,
    onOpenCloudBackupDialog: () -> Unit,
    onCloudQuickSyncTriggered: (String) -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onZoomIn: () -> Unit,
    onOpenAiCopilot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 0.dp, bottomEnd = 10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
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
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Vissza a polcra",
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                text = notebookTitle.ifBlank { "Jegyzet" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp)
            )

            CloudSyncStatusBadgeButton(
                onOpenSettingsDialog = onOpenCloudBackupDialog,
                size = 30.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                onQuickSyncTriggered = onCloudQuickSyncTriggered
            )

            VerticalDivider(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(18.dp)
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
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Kicsinyítés",
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    ZoomPercentLabel(
                        zoomPercentProvider = zoomPercentProvider,
                        onClick = onResetZoom
                    )

                    IconButton(
                        onClick = onZoomIn,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Nagyítás",
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            VerticalDivider(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(18.dp)
            )

            Surface(
                onClick = onOpenAiCopilot,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = StylusIcons.StarFourPoint,
                        contentDescription = "AI funkciók",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "AI",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            InNotebookSearchToolbarSection(
                isOpen = searchUiState.isOpen,
                query = searchUiState.query,
                activeMatchIndex = searchUiState.activeMatchIndex,
                matchesCount = searchUiState.matchesCount,
                indexState = searchUiState.indexState,
                canRunAiDeepIndex = searchUiState.canRunAiDeepIndex,
                onOpen = searchUiState.onOpen,
                onQueryChange = searchUiState.onQueryChange,
                onPreviousMatch = searchUiState.onPreviousMatch,
                onNextMatch = searchUiState.onNextMatch,
                onTriggerAiDeepIndex = searchUiState.onTriggerAiDeepIndex,
                onClose = searchUiState.onClose
            )
        }
    }
}

@Composable
private fun ZoomPercentLabel(
    zoomPercentProvider: () -> Int,
    onClick: () -> Unit
) {
    // Isolate snapshot reads and fix container width so live % updates never reflow the toolbar.
    Box(
        modifier = Modifier
            .width(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${zoomPercentProvider()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun rectsDifferOver1Px(a: Rect, b: Rect): Boolean =
    kotlin.math.abs(a.left - b.left) > 1f ||
        kotlin.math.abs(a.top - b.top) > 1f ||
        kotlin.math.abs(a.right - b.right) > 1f ||
        kotlin.math.abs(a.bottom - b.bottom) > 1f

private data class ImportedImageAsset(
    val relativePath: String,
    val widthPx: Int,
    val heightPx: Int
)

private fun importPickedImageAsPageAsset(
    context: android.content.Context,
    sourceUri: android.net.Uri,
    pageId: String,
    maxEdgePx: Int = 1600
): ImportedImageAsset {
    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, sourceUri)
    val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val srcW = info.size.width.coerceAtLeast(1)
        val srcH = info.size.height.coerceAtLeast(1)
        val longest = maxOf(srcW, srcH)
        if (longest > maxEdgePx) {
            val scale = maxEdgePx.toFloat() / longest.toFloat()
            decoder.setTargetSize(
                (srcW * scale).roundToInt().coerceAtLeast(1),
                (srcH * scale).roundToInt().coerceAtLeast(1)
            )
        }
    }
    try {
        val assetId = UUID.randomUUID().toString()
        val relPath = "pages/$pageId/images/$assetId.png"
        val outFile = File(context.filesDir, relPath)
        outFile.parentFile?.mkdirs()
        java.io.FileOutputStream(outFile).use { fos ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
        }
        return ImportedImageAsset(
            relativePath = relPath,
            widthPx = bitmap.width.coerceAtLeast(1),
            heightPx = bitmap.height.coerceAtLeast(1)
        )
    } finally {
        bitmap.recycle()
    }
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


