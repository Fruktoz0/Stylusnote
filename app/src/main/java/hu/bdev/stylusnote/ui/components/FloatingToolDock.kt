package hu.bdev.stylusnote.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import hu.bdev.stylusnote.ui.theme.StylusIcons
import kotlin.math.roundToInt

/**
 * Globally positioned, freely draggable floating tool dock shared across all notebooks.
 *
 * Persists normalized viewport coordinates, collapsed state, and horizontal/vertical orientation
 * in [ToolDockPreferencesStore], enforces non-overlap against [avoidTopLeftRectPx], shows Pen +
 * Eraser + Undo when collapsed, and opens [BrushSettingsPopover] on long-press of any tool.
 */
@Composable
fun FloatingToolDock(
    brushConfig: BrushConfig,
    lastDrawingTool: BrushToolType,
    lastEraserTool: BrushToolType,
    dockPrefs: ToolDockPrefs,
    avoidTopLeftRectPx: Rect,
    viewportSize: IntSize,
    canUndo: Boolean,
    canRedo: Boolean,
    isDarkTheme: Boolean,
    showDebugOverlay: Boolean,
    isFullScreen: Boolean = false,
    onUpdateDockPrefs: (ToolDockPrefs) -> Unit,
    onSelectTool: (BrushToolType) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPaperSettings: () -> Unit = {},
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitPage: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onToggleFullScreen: () -> Unit = {},
    onOpenShapes: () -> Unit = {},
    onTriggerNotebookOcr: () -> Unit = {},
    isOcrRunning: Boolean = false,
    onDockBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dockSizePx by remember { mutableStateOf(IntSize.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var dragDeltaX by remember { mutableFloatStateOf(0f) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    val marginPx = 12f
    val maxAvailX = (viewportSize.width - dockSizePx.width - marginPx * 2f).coerceAtLeast(1f)
    val maxAvailY = (viewportSize.height - dockSizePx.height - marginPx * 2f).coerceAtLeast(1f)

    val desiredOriginX = marginPx + dockPrefs.xFraction * maxAvailX + if (isDragging) dragDeltaX else 0f
    val desiredOriginY = marginPx + dockPrefs.yFraction * maxAvailY + if (isDragging) dragDeltaY else 0f

    val resolvedOrigin = clampDockOriginPx(
        desiredLeftPx = desiredOriginX,
        desiredTopPx = desiredOriginY,
        dockWidthPx = dockSizePx.width,
        dockHeightPx = dockSizePx.height,
        viewportWidthPx = viewportSize.width,
        viewportHeightPx = viewportSize.height,
        avoidRectPx = avoidTopLeftRectPx,
        marginPx = marginPx
    )

    val surfacePalette = LocalStylusSurfacePalette.current
    val isVertical = dockPrefs.isVertical

    val dragHandleModifier = Modifier.pointerInput(viewportSize, dockSizePx, dockPrefs, avoidTopLeftRectPx) {
        detectDragGestures(
            onDragStart = {
                isDragging = true
                dragDeltaX = 0f
                dragDeltaY = 0f
            },
            onDrag = { change, dragAmount ->
                change.consume()
                dragDeltaX += dragAmount.x
                dragDeltaY += dragAmount.y
            },
            onDragCancel = {
                isDragging = false
                dragDeltaX = 0f
                dragDeltaY = 0f
            },
            onDragEnd = {
                val droppedOrigin = clampDockOriginPx(
                    desiredLeftPx = marginPx + dockPrefs.xFraction * maxAvailX + dragDeltaX,
                    desiredTopPx = marginPx + dockPrefs.yFraction * maxAvailY + dragDeltaY,
                    dockWidthPx = dockSizePx.width,
                    dockHeightPx = dockSizePx.height,
                    viewportWidthPx = viewportSize.width,
                    viewportHeightPx = viewportSize.height,
                    avoidRectPx = avoidTopLeftRectPx,
                    marginPx = marginPx
                )
                val nextXFraction = ((droppedOrigin.x - marginPx) / maxAvailX).coerceIn(0f, 1f)
                val nextYFraction = ((droppedOrigin.y - marginPx) / maxAvailY).coerceIn(0f, 1f)
                isDragging = false
                dragDeltaX = 0f
                dragDeltaY = 0f
                onUpdateDockPrefs(
                    dockPrefs.copy(
                        xFraction = nextXFraction,
                        yFraction = nextYFraction
                    )
                )
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .offset { IntOffset(resolvedOrigin.x, resolvedOrigin.y) }
                .onSizeChanged { dockSizePx = it }
                .onGloballyPositioned { coords -> onDockBoundsChanged(coords.boundsInParent()) }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .animateContentSize(),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 5.dp,
            shadowElevation = if (isDragging) 14.dp else 6.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            if (dockPrefs.isCollapsed) {
                CollapsedDockContent(
                    isVertical = isVertical,
                    brushConfig = brushConfig,
                    lastDrawingTool = lastDrawingTool,
                    lastEraserTool = lastEraserTool,
                    canUndo = canUndo,
                    dragHandleModifier = dragHandleModifier,
                    onSelectTool = onSelectTool,
                    onOpenToolSettings = { tool ->
                        if (brushConfig.toolType != tool) {
                            onSelectTool(tool)
                        }
                        onOpenSettings()
                    },
                    onUndo = onUndo,
                    onExpand = {
                        onUpdateDockPrefs(dockPrefs.copy(isCollapsed = false))
                    }
                )
            } else if (isVertical) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    DockDragHandle(modifier = dragHandleModifier)
                    ToolButtonsGroup(
                        brushConfig = brushConfig,
                        isVertical = true,
                        onSelectTool = onSelectTool,
                        onOpenSettings = onOpenSettings
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier
                            .width(28.dp)
                            .padding(vertical = 2.dp)
                    )
                    ActionButtonsGroup(
                        canUndo = canUndo,
                        canRedo = canRedo,
                        isDarkTheme = isDarkTheme,
                        showDebugOverlay = showDebugOverlay,
                        isFullScreen = isFullScreen,
                        isOcrRunning = isOcrRunning,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onFitPage = onFitPage,
                        onOpenShapes = onOpenShapes,
                        onTriggerNotebookOcr = onTriggerNotebookOcr,
                        onOpenPaperSettings = onOpenPaperSettings,
                        onToggleTheme = onToggleTheme,
                        onToggleDebugOverlay = onToggleDebugOverlay,
                        onToggleFullScreen = onToggleFullScreen
                    )
                    IconButton(
                        onClick = { onUpdateDockPrefs(dockPrefs.copy(isVertical = false)) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = "Vízszintes elrendezés",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { onUpdateDockPrefs(dockPrefs.copy(isCollapsed = true)) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.UnfoldLess,
                            contentDescription = "Eszköztár összecsukása",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    DockDragHandle(modifier = dragHandleModifier)
                    ToolButtonsGroup(
                        brushConfig = brushConfig,
                        isVertical = false,
                        onSelectTool = onSelectTool,
                        onOpenSettings = onOpenSettings
                    )
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier
                            .height(26.dp)
                            .padding(horizontal = 2.dp)
                    )
                    ActionButtonsGroup(
                        canUndo = canUndo,
                        canRedo = canRedo,
                        isDarkTheme = isDarkTheme,
                        showDebugOverlay = showDebugOverlay,
                        isFullScreen = isFullScreen,
                        isOcrRunning = isOcrRunning,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onFitPage = onFitPage,
                        onOpenShapes = onOpenShapes,
                        onTriggerNotebookOcr = onTriggerNotebookOcr,
                        onOpenPaperSettings = onOpenPaperSettings,
                        onToggleTheme = onToggleTheme,
                        onToggleDebugOverlay = onToggleDebugOverlay,
                        onToggleFullScreen = onToggleFullScreen
                    )
                    IconButton(
                        onClick = { onUpdateDockPrefs(dockPrefs.copy(isVertical = true)) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapVert,
                            contentDescription = "Függőleges elrendezés",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { onUpdateDockPrefs(dockPrefs.copy(isCollapsed = true)) },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(surfacePalette.groupedCardBackground)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.UnfoldLess,
                            contentDescription = "Eszköztár összecsukása",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedDockContent(
    isVertical: Boolean,
    brushConfig: BrushConfig,
    lastDrawingTool: BrushToolType,
    lastEraserTool: BrushToolType,
    canUndo: Boolean,
    dragHandleModifier: Modifier,
    onSelectTool: (BrushToolType) -> Unit,
    onOpenToolSettings: (BrushToolType) -> Unit,
    onUndo: () -> Unit,
    onExpand: () -> Unit
) {
    val activePenTool = when {
        brushConfig.toolType == BrushToolType.HIGHLIGHTER -> BrushToolType.HIGHLIGHTER
        !brushConfig.toolType.isEraser && brushConfig.toolType != BrushToolType.LASSO -> BrushToolType.FOUNTAIN_PEN
        lastDrawingTool == BrushToolType.HIGHLIGHTER -> BrushToolType.HIGHLIGHTER
        else -> BrushToolType.FOUNTAIN_PEN
    }
    val activeEraser = if (brushConfig.toolType.isEraser) {
        brushConfig.toolType
    } else if (lastEraserTool.isEraser) {
        lastEraserTool
    } else {
        BrushToolType.PRECISION_ERASER
    }

    val isPenSelected = brushConfig.toolType == activePenTool
    val isEraserSelected = brushConfig.toolType.isEraser
    val accentColor = Color(brushConfig.effectiveColorArgb())

    val content: @Composable () -> Unit = {
        DockDragHandle(modifier = dragHandleModifier)
        DockToolIconButton(
            icon = iconForTool(activePenTool),
            contentDescription = activePenTool.displayName,
            selected = isPenSelected,
            accentDotColor = accentColor,
            onClick = { onSelectTool(activePenTool) },
            onLongClick = { onOpenToolSettings(activePenTool) }
        )
        DockToolIconButton(
            icon = iconForTool(activeEraser),
            contentDescription = activeEraser.displayName,
            selected = isEraserSelected,
            accentDotColor = null,
            selectedTintOverride = LocalStylusSurfacePalette.current.eraserAccent,
            onClick = { onSelectTool(activeEraser) },
            onLongClick = { onOpenToolSettings(activeEraser) }
        )
        IconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Visszavonás",
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = onExpand,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(LocalStylusSurfacePalette.current.activePillBackground)
        ) {
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = "Eszköztár kinyitása",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (isVertical) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            content()
        }
    } else {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun DockDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.DragIndicator,
            contentDescription = "Eszköztár mozgatása",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun iconForTool(toolType: BrushToolType): ImageVector = when (toolType) {
    BrushToolType.FOUNTAIN_PEN -> StylusIcons.FountainPen
    BrushToolType.BALLPOINT_PEN -> StylusIcons.BallpointPen
    BrushToolType.PENCIL -> StylusIcons.Pencil
    BrushToolType.HIGHLIGHTER -> StylusIcons.Highlighter
    BrushToolType.VECTOR_ERASER -> StylusIcons.VectorEraser
    BrushToolType.PRECISION_ERASER -> StylusIcons.PrecisionEraser
    BrushToolType.LASSO -> StylusIcons.Lasso
}

@Composable
private fun ToolButtonsGroup(
    brushConfig: BrushConfig,
    isVertical: Boolean,
    onSelectTool: (BrushToolType) -> Unit,
    onOpenSettings: () -> Unit
) {
    val activeTool = brushConfig.toolType
    val activeInkColor = Color(brushConfig.effectiveColorArgb())
    val surfacePalette = LocalStylusSurfacePalette.current

    val toolOrder = listOf(
        BrushToolType.FOUNTAIN_PEN,
        BrushToolType.HIGHLIGHTER,
        BrushToolType.VECTOR_ERASER,
        BrushToolType.PRECISION_ERASER,
        BrushToolType.LASSO
    )

    for (tool in toolOrder) {
        val selected = activeTool == tool
        val dotColor = if (selected && !tool.isEraser && tool != BrushToolType.LASSO) {
            activeInkColor
        } else {
            null
        }
        val eraserTint = if (tool.isEraser && selected) surfacePalette.eraserAccent else null
        DockToolIconButton(
            icon = iconForTool(tool),
            contentDescription = tool.displayName,
            selected = selected,
            accentDotColor = dotColor,
            selectedTintOverride = eraserTint,
            onClick = { onSelectTool(tool) },
            onLongClick = {
                if (!selected) {
                    onSelectTool(tool)
                }
                onOpenSettings()
            }
        )
    }

    // Compact brush swatch + thickness indicator pill opening quick Brush Settings.
    Surface(
        onClick = onOpenSettings,
        shape = RoundedCornerShape(14.dp),
        color = surfacePalette.groupedCardBackground,
        modifier = Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = RoundedCornerShape(14.dp)
        )
    ) {
        if (isVertical) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(activeInkColor)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
                )
                Text(
                    text = if (brushConfig.toolType.isEraser) {
                        "${brushConfig.eraserRadiusDp.roundToInt()}dp"
                    } else {
                        "${"%.0f".format(brushConfig.clampedSizePt)}pt"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(activeInkColor)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
                )
                Text(
                    text = if (brushConfig.toolType.isEraser) {
                        "${brushConfig.eraserRadiusDp.roundToInt()} dp"
                    } else {
                        "${"%.1f".format(brushConfig.clampedSizePt)} pt"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = "Ecsetbeállítások",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionButtonsGroup(
    canUndo: Boolean,
    canRedo: Boolean,
    isDarkTheme: Boolean,
    showDebugOverlay: Boolean,
    isFullScreen: Boolean,
    isOcrRunning: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitPage: () -> Unit,
    onOpenShapes: () -> Unit,
    onTriggerNotebookOcr: () -> Unit,
    onOpenPaperSettings: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleDebugOverlay: () -> Unit,
    onToggleFullScreen: () -> Unit
) {
    IconButton(
        onClick = onUndo,
        enabled = canUndo,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Visszavonás",
            modifier = Modifier.size(20.dp)
        )
    }
    IconButton(
        onClick = onRedo,
        enabled = canRedo,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Újra",
            modifier = Modifier.size(20.dp)
        )
    }
    DockToolIconButton(
        icon = StylusIcons.ShapesTool,
        contentDescription = "Alakzatok beszúrása",
        selected = false,
        onClick = onOpenShapes,
        onLongClick = onOpenShapes
    )
    DockToolIconButton(
        icon = StylusIcons.OcrText,
        contentDescription = "Füzet OCR felismerés",
        selected = isOcrRunning,
        onClick = { if (!isOcrRunning) onTriggerNotebookOcr() },
        onLongClick = { if (!isOcrRunning) onTriggerNotebookOcr() }
    )
    IconButton(
        onClick = onFitPage,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.FitScreen,
            contentDescription = "Oldal illesztése",
            modifier = Modifier.size(20.dp)
        )
    }
    IconButton(
        onClick = onOpenPaperSettings,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = "Papír sablon és vászonmód",
            modifier = Modifier.size(20.dp)
        )
    }
    IconButton(
        onClick = onToggleTheme,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = "Téma váltása",
            modifier = Modifier.size(20.dp)
        )
    }
    DockToolIconButton(
        icon = if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
        contentDescription = if (isFullScreen) "Kilépés a teljes képernyőből" else "Teljes képernyő",
        selected = isFullScreen,
        onClick = onToggleFullScreen,
        onLongClick = onToggleFullScreen
    )
    DockToolIconButton(
        icon = Icons.Filled.BugReport,
        contentDescription = "FPS Overlay",
        selected = showDebugOverlay,
        onClick = onToggleDebugOverlay,
        onLongClick = onToggleDebugOverlay
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    accentDotColor: Color? = null,
    selectedTintOverride: Color? = null
) {
    val surfacePalette = LocalStylusSurfacePalette.current
    val shape = RoundedCornerShape(12.dp)
    val bgColor = when {
        selected && selectedTintOverride != null -> selectedTintOverride.copy(alpha = 0.16f)
        selected -> surfacePalette.activePillBackground
        else -> Color.Transparent
    }
    val fgColor = when {
        selected && selectedTintOverride != null -> selectedTintOverride
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fgColor,
            modifier = Modifier.size(19.dp)
        )
        if (accentDotColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accentDotColor)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}
