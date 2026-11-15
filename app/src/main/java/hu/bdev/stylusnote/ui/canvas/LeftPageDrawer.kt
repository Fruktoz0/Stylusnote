package hu.bdev.stylusnote.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import kotlin.math.roundToInt

/**
 * Compact left-edge pull handle shown when the active notebook uses [CanvasMode.FIXED_PAGE].
 * Freely draggable vertically along the left viewport border while preserving tap-to-open.
 */
@Composable
fun LeftPagePullTab(
    currentPageIndexOneBased: Int,
    totalPages: Int,
    yFraction: Float = 0.5f,
    viewportHeightPx: Int = 0,
    onUpdateYFraction: (Float) -> Unit = {},
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tabHeightPx by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    val edgeMarginPx = 64f
    val maxOffsetPx = (viewportHeightPx - tabHeightPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
    val availRangePx = (maxOffsetPx - edgeMarginPx).coerceAtLeast(1f)
    val desiredOffsetY = if (viewportHeightPx > 0) {
        (edgeMarginPx + yFraction.coerceIn(0f, 1f) * availRangePx + if (isDragging) dragDeltaY else 0f)
            .coerceIn(edgeMarginPx, maxOffsetPx)
    } else {
        edgeMarginPx
    }

    Surface(
        onClick = { if (!isDragging) onOpenDrawer() },
        modifier = modifier
            .then(
                if (viewportHeightPx > 0) {
                    Modifier.offset { IntOffset(0, desiredOffsetY.roundToInt()) }
                } else {
                    Modifier
                }
            )
            .onSizeChanged { tabHeightPx = it.height }
            .pointerInput(yFraction, viewportHeightPx, tabHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragDeltaY = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragDeltaY += dragAmount
                    },
                    onDragCancel = {
                        isDragging = false
                        dragDeltaY = 0f
                    },
                    onDragEnd = {
                        val droppedY = (edgeMarginPx + yFraction.coerceIn(0f, 1f) * availRangePx + dragDeltaY)
                            .coerceIn(edgeMarginPx, maxOffsetPx)
                        val nextFraction = ((droppedY - edgeMarginPx) / availRangePx).coerceIn(0.08f, 0.92f)
                        isDragging = false
                        dragDeltaY = 0f
                        onUpdateYFraction(nextFraction)
                    }
                )
            },
        shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = if (isDragging) 8.dp else 4.dp,
        shadowElevation = if (isDragging) 10.dp else 4.dp,
        border = BorderStroke(
            width = if (isDragging) 1.5.dp else 1.dp,
            color = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Oldalak panel megnyitása",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "$currentPageIndexOneBased/$totalPages",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Narrow left slide-out page switcher, long-press drag-and-drop page reorderer, and "+ Add page"
 * drawer for fixed-page notebooks. Closes cleanly via both ESC key press and scrim backdrop click.
 */
@Composable
fun LeftPageDrawerOverlay(
    pages: List<PageEntity>,
    activePageId: String,
    onSelectPage: (String) -> Unit,
    onAddNewPage: () -> Unit,
    onReorderPages: (List<String>) -> Unit = {},
    onRenamePage: (pageId: String, newTitle: String?) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val surfacePalette = LocalStylusSurfacePalette.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var localPages by remember(pages) { mutableStateOf(pages) }
    var previousPageCount by remember { mutableIntStateOf(pages.size) }
    var draggedPageId by remember { mutableStateOf<String?>(null) }
    var dragDeltaYPx by remember { mutableFloatStateOf(0f) }
    var cardHeightPx by remember { mutableIntStateOf(160) }
    var renamingPage by remember { mutableStateOf<PageEntity?>(null) }
    var renamingPageIndexOneBased by remember { mutableIntStateOf(1) }
    var renameDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(pages) {
        if (draggedPageId == null) {
            localPages = pages
        }
        if (pages.size > previousPageCount && pages.isNotEmpty()) {
            listState.animateScrollToItem(pages.lastIndex)
        }
        previousPageCount = pages.size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .focusRequester(focusRequester)
            .focusable()
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
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier
                .width(236.dp)
                .fillMaxHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
            color = surfacePalette.sidebarBackground,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Oldalak (${localPages.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (localPages.size > 1) {
                            Text(
                                text = "Húzd hosszan az átrendezéshez",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Bezárás",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(localPages, key = { _, p -> p.id }) { index, page ->
                        val isActive = page.id == activePageId
                        val isDragged = draggedPageId == page.id
                        val swapStepPx = (cardHeightPx.coerceAtLeast(100) + 18).toFloat()
                        Surface(
                            onClick = {
                                if (draggedPageId == null) {
                                    onSelectPage(page.id)
                                    onDismiss()
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDragged) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            } else if (isActive) {
                                surfacePalette.activePillBackground
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(
                                width = if (isDragged || isActive) 1.75.dp else 1.dp,
                                color = if (isDragged || isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            shadowElevation = if (isDragged) 10.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragged) 1f else 0f)
                                .onSizeChanged { if (it.height > 40) cardHeightPx = it.height }
                                .graphicsLayer {
                                    if (isDragged) {
                                        translationY = dragDeltaYPx
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                    }
                                }
                                .pointerInput(page.id, localPages.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedPageId = page.id
                                            dragDeltaYPx = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragDeltaYPx += dragAmount.y
                                            val currentIdx = localPages.indexOfFirst { it.id == page.id }
                                            if (currentIdx >= 0) {
                                                if (dragDeltaYPx > swapStepPx * 0.55f && currentIdx < localPages.lastIndex) {
                                                    val mutable = localPages.toMutableList()
                                                    val item = mutable.removeAt(currentIdx)
                                                    mutable.add(currentIdx + 1, item)
                                                    localPages = mutable
                                                    dragDeltaYPx -= swapStepPx
                                                } else if (dragDeltaYPx < -swapStepPx * 0.55f && currentIdx > 0) {
                                                    val mutable = localPages.toMutableList()
                                                    val item = mutable.removeAt(currentIdx)
                                                    mutable.add(currentIdx - 1, item)
                                                    localPages = mutable
                                                    dragDeltaYPx += swapStepPx
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            draggedPageId = null
                                            dragDeltaYPx = 0f
                                            localPages = pages
                                        },
                                        onDragEnd = {
                                            val finalOrder = localPages.map { it.id }
                                            draggedPageId = null
                                            dragDeltaYPx = 0f
                                            if (finalOrder != pages.map { it.id }) {
                                                onReorderPages(finalOrder)
                                            }
                                        }
                                    )
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val customTitle = page.title?.trim()?.takeIf { it.isNotEmpty() }
                                    val labelText = if (customTitle != null) {
                                        "${index + 1}. $customTitle"
                                    } else {
                                        "${index + 1}. oldal"
                                    }
                                    Text(
                                        text = labelText,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            renamingPage = page
                                            renamingPageIndexOneBased = index + 1
                                            renameDraft = page.title.orEmpty()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Oldal átnevezése",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.DragIndicator,
                                        contentDescription = "Sorrend mozgatása",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.72f)
                                        .aspectRatio(0.71f)
                                        .background(
                                            color = surfacePalette.pagePaperBackground,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            width = 0.75.dp,
                                            color = surfacePalette.pageBorder,
                                            shape = RoundedCornerShape(6.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (page.pdfAssetId != null) {
                                        Icon(
                                            imageVector = Icons.Filled.PictureAsPdf,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    } else {
                                        Text(
                                            text = page.templateType.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onAddNewPage,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Új oldal hozzáadása")
                }
            }
        }

        val pendingRename = renamingPage
        if (pendingRename != null) {
            val dialogFocusRequester = remember { FocusRequester() }
            LaunchedEffect(pendingRename.id) {
                dialogFocusRequester.requestFocus()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .focusRequester(dialogFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                            renamingPage = null
                            true
                        } else {
                            false
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { renamingPage = null }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .width(340.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "${renamingPageIndexOneBased}. oldal elnevezése",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = renameDraft,
                            onValueChange = { renameDraft = it.take(64) },
                            singleLine = true,
                            label = { Text("Oldal neve (üresen hagyva sorszám)") },
                            placeholder = { Text("${renamingPageIndexOneBased}. oldal") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { renamingPage = null }) {
                                Text("Mégse")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val cleaned = renameDraft.trim().takeIf { it.isNotEmpty() }
                                    onRenamePage(pendingRename.id, cleaned)
                                    localPages = localPages.map {
                                        if (it.id == pendingRename.id) it.copy(title = cleaned) else it
                                    }
                                    renamingPage = null
                                }
                            ) {
                                Text("Mentés")
                            }
                        }
                    }
                }
            }
        }
    }
}
