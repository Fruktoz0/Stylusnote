package hu.bdev.stylusnote.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Permitted docking edges along the viewport for [FloatingToolDock] (Section 5.4).
 */
enum class DockEdge {
    TOP,
    LEFT,
    RIGHT
}

/**
 * Draggable, edge-snapping floating tool dock supporting [DockEdge.TOP] (horizontal) as well as
 * [DockEdge.LEFT] and [DockEdge.RIGHT] (vertical) orientations.
 *
 * Exposes the 5 native brushes, Vector & Precision erasers, Lasso selection, brush settings trigger,
 * Undo/Redo controls, page fit, theme toggle, and performance overlay switch.
 */
@Composable
fun FloatingToolDock(
    brushConfig: BrushConfig,
    dockEdge: DockEdge,
    viewportSize: IntSize,
    canUndo: Boolean,
    canRedo: Boolean,
    isDarkTheme: Boolean,
    showDebugOverlay: Boolean,
    onDockEdgeChanged: (DockEdge) -> Unit,
    onSelectTool: (BrushToolType) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPaperSettings: () -> Unit = {},
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitPage: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleDebugOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isCollapsed by rememberSaveable { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dockCenterInRootX by remember { mutableFloatStateOf(0f) }
    var dockCenterInRootY by remember { mutableFloatStateOf(0f) }

    val baseAlignment = when (dockEdge) {
        DockEdge.TOP -> Alignment.TopCenter
        DockEdge.LEFT -> Alignment.CenterStart
        DockEdge.RIGHT -> Alignment.CenterEnd
    }

    val isVertical = dockEdge == DockEdge.LEFT || dockEdge == DockEdge.RIGHT

    Box(
        modifier = modifier
            .safeDrawingPadding()
            .padding(12.dp),
        contentAlignment = baseAlignment
    ) {
        Surface(
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .animateContentSize()
                .onGloballyPositioned { coords ->
                    if (!isDragging) {
                        val pos = coords.positionInRoot()
                        dockCenterInRootX = pos.x + coords.size.width * 0.5f
                        dockCenterInRootY = pos.y + coords.size.height * 0.5f
                    }
                }
                .then(
                    if (isDragging) {
                        Modifier.offset {
                            IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt())
                        }
                    } else {
                        Modifier
                    }
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(26.dp)
                ),
            shape = RoundedCornerShape(26.dp),
            tonalElevation = 6.dp,
            shadowElevation = if (isDragging) 14.dp else 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
        ) {
            val dragHandleModifier = Modifier.pointerInput(viewportSize) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                    },
                    onDragCancel = {
                        isDragging = false
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDragEnd = {
                        val nextEdge = resolveNearestDockEdge(
                            dropCenterX = dockCenterInRootX + dragOffsetX,
                            dropCenterY = dockCenterInRootY + dragOffsetY,
                            viewportSize = viewportSize,
                            fallback = dockEdge
                        )
                        isDragging = false
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        onDockEdgeChanged(nextEdge)
                    }
                )
            }

            if (isCollapsed) {
                CollapsedDockContent(
                    isVertical = isVertical,
                    brushConfig = brushConfig,
                    canUndo = canUndo,
                    dragHandleModifier = dragHandleModifier,
                    onOpenSettings = onOpenSettings,
                    onUndo = onUndo,
                    onExpand = { isCollapsed = false }
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
                        modifier = Modifier
                            .width(28.dp)
                            .padding(vertical = 2.dp)
                    )
                    ActionButtonsGroup(
                        canUndo = canUndo,
                        canRedo = canRedo,
                        isDarkTheme = isDarkTheme,
                        showDebugOverlay = showDebugOverlay,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onFitPage = onFitPage,
                        onOpenPaperSettings = onOpenPaperSettings,
                        onToggleTheme = onToggleTheme,
                        onToggleDebugOverlay = onToggleDebugOverlay
                    )
                    IconButton(
                        onClick = { isCollapsed = true },
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
                        modifier = Modifier
                            .height(26.dp)
                            .padding(horizontal = 2.dp)
                    )
                    ActionButtonsGroup(
                        canUndo = canUndo,
                        canRedo = canRedo,
                        isDarkTheme = isDarkTheme,
                        showDebugOverlay = showDebugOverlay,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onFitPage = onFitPage,
                        onOpenPaperSettings = onOpenPaperSettings,
                        onToggleTheme = onToggleTheme,
                        onToggleDebugOverlay = onToggleDebugOverlay
                    )
                    IconButton(
                        onClick = { isCollapsed = true },
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
            }
        }
    }
}

@Composable
private fun CollapsedDockContent(
    isVertical: Boolean,
    brushConfig: BrushConfig,
    canUndo: Boolean,
    dragHandleModifier: Modifier,
    onOpenSettings: () -> Unit,
    onUndo: () -> Unit,
    onExpand: () -> Unit
) {
    val activeIcon = iconForTool(brushConfig.toolType)
    val accentColor = Color(brushConfig.effectiveColorArgb())

    val content: @Composable () -> Unit = {
        DockDragHandle(modifier = dragHandleModifier)
        DockToolIconButton(
            icon = activeIcon,
            contentDescription = brushConfig.toolType.displayName,
            selected = true,
            accentDotColor = if (brushConfig.toolType.isEraser || brushConfig.toolType == BrushToolType.LASSO) {
                null
            } else {
                accentColor
            },
            onClick = onOpenSettings
        )
        IconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Undo,
                contentDescription = "Visszavonás",
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = onExpand,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
        ) {
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = "Eszköztár kinyitása",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
            .size(30.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.DragIndicator,
            contentDescription = "Eszköztár áthelyezése",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun iconForTool(toolType: BrushToolType): ImageVector = when (toolType) {
    BrushToolType.FOUNTAIN_PEN -> Icons.Filled.Edit
    BrushToolType.BALLPOINT_PEN -> Icons.Filled.Draw
    BrushToolType.PENCIL -> Icons.Filled.Brush
    BrushToolType.HIGHLIGHTER -> Icons.Filled.BorderColor
    BrushToolType.LASER -> Icons.Filled.Flare
    BrushToolType.VECTOR_ERASER -> Icons.Filled.AutoFixHigh
    BrushToolType.PRECISION_ERASER -> Icons.Filled.ContentCut
    BrushToolType.LASSO -> Icons.Filled.SelectAll
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

    val toolOrder = listOf(
        BrushToolType.FOUNTAIN_PEN,
        BrushToolType.BALLPOINT_PEN,
        BrushToolType.PENCIL,
        BrushToolType.HIGHLIGHTER,
        BrushToolType.LASER,
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
        DockToolIconButton(
            icon = iconForTool(tool),
            contentDescription = tool.displayName,
            selected = selected,
            accentDotColor = dotColor,
            onClick = {
                if (selected) {
                    onOpenSettings()
                } else {
                    onSelectTool(tool)
                }
            }
        )
    }

    // Combined brush swatch + thickness pill opening the brush settings popover.
    Surface(
        onClick = onOpenSettings,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp)
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
                    text = "${"%.0f".format(brushConfig.clampedSizePt)}pt",
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
                    text = "${"%.1f".format(brushConfig.clampedSizePt)} pt",
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
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFitPage: () -> Unit,
    onOpenPaperSettings: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleDebugOverlay: () -> Unit
) {
    IconButton(
        onClick = onUndo,
        enabled = canUndo,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Undo,
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
            imageVector = Icons.Filled.Redo,
            contentDescription = "Újra",
            modifier = Modifier.size(20.dp)
        )
    }
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
        icon = Icons.Filled.BugReport,
        contentDescription = "FPS Overlay",
        selected = showDebugOverlay,
        onClick = onToggleDebugOverlay
    )
}

@Composable
private fun DockToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    accentDotColor: Color? = null
) {
    val shape = RoundedCornerShape(14.dp)
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val fgColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onClick),
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

private fun resolveNearestDockEdge(
    dropCenterX: Float,
    dropCenterY: Float,
    viewportSize: IntSize,
    fallback: DockEdge
): DockEdge {
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return fallback
    val w = viewportSize.width.toFloat()
    val distTop = abs(dropCenterY)
    val distLeft = abs(dropCenterX)
    val distRight = abs(w - dropCenterX)

    return when {
        distTop <= distLeft && distTop <= distRight -> DockEdge.TOP
        distLeft <= distRight -> DockEdge.LEFT
        else -> DockEdge.RIGHT
    }
}
