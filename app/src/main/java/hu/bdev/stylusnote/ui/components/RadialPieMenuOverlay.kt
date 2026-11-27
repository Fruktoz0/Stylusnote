package hu.bdev.stylusnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.ui.theme.StylusIcons
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-Kotlin radial sector hit-tester centered on the cursor-anchored Pie Menu.
 * Returns `0 until sliceCount` clockwise starting from 12 o'clock, or `null` when the cursor
 * rests inside the inner cancel deadzone (`< innerDeadzonePx`).
 */
internal fun resolvePieMenuSliceIndex(
    dxPx: Float,
    dyPx: Float,
    innerDeadzonePx: Float = 36f,
    outerMaxRadiusPx: Float = 260f,
    sliceCount: Int = 8
): Int? {
    val dist = hypot(dxPx, dyPx)
    if (!dist.isFinite() || dist < innerDeadzonePx || dist > outerMaxRadiusPx || sliceCount <= 0) {
        return null
    }
    // Shift -PI/2 (12 o'clock) to 0 radians clockwise.
    val rawAngle = atan2(dyPx.toDouble(), dxPx.toDouble()) + PI / 2.0 + (PI / sliceCount.toDouble())
    val twoPi = 2.0 * PI
    val wrapped = ((rawAngle % twoPi) + twoPi) % twoPi
    return (wrapped / (twoPi / sliceCount.toDouble())).toInt().coerceIn(0, sliceCount - 1)
}

/**
 * Clamps the Pie Menu center pixel coordinate so the entire radial ring stays inside the viewport.
 */
internal fun computeClampedPieCenterPx(
    anchorXPx: Float,
    anchorYPx: Float,
    outerRadiusPx: Float,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    marginPx: Float = 16f
): Offset {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) {
        return Offset(anchorXPx, anchorYPx)
    }
    val minX = (outerRadiusPx + marginPx).coerceAtMost(viewportWidthPx * 0.5f)
    val maxX = (viewportWidthPx - outerRadiusPx - marginPx).coerceAtLeast(minX)
    val minY = (outerRadiusPx + marginPx).coerceAtMost(viewportHeightPx * 0.5f)
    val maxY = (viewportHeightPx - outerRadiusPx - marginPx).coerceAtLeast(minY)
    return Offset(
        x = anchorXPx.coerceIn(minX, maxX),
        y = anchorYPx.coerceIn(minY, maxY)
    )
}

data class PieMenuSliceSpec(
    val index: Int,
    val label: String,
    val icon: ImageVector? = null,
    val colorDotArgb: Int? = null,
    val isActive: Boolean = false
)

/**
 * Cursor-anchored 8-slice radial Pie Menu for eyes-free, muscle-memory switching between Pen,
 * Highlighter, Lasso, Eraser, stroke width cycle, and quick ink colors under the stylus tip.
 * Supports flick-and-release, stylus/finger tap, ESC key, and outside backdrop dismissal (Rule 11).
 */
@Composable
fun RadialPieMenuOverlay(
    anchorScreenPx: Offset,
    viewportSize: IntSize,
    brushConfig: BrushConfig,
    isOledMode: Boolean,
    commitTick: Long,
    onSelectTool: (BrushToolType) -> Unit,
    onSelectInkColor: (Int) -> Unit,
    onCyclePenSize: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val outerRadiusDp = 128.dp
    val innerDeadzoneDp = 34.dp
    val outerRadiusPx = with(density) { outerRadiusDp.toPx() }
    val innerDeadzonePx = with(density) { innerDeadzoneDp.toPx() }

    val clampedCenter = remember(anchorScreenPx, viewportSize, outerRadiusPx) {
        computeClampedPieCenterPx(
            anchorXPx = anchorScreenPx.x,
            anchorYPx = anchorScreenPx.y,
            outerRadiusPx = outerRadiusPx,
            viewportWidthPx = viewportSize.width,
            viewportHeightPx = viewportSize.height
        )
    }

    val blackInkArgb = BrushConfig.DEFAULT_INK_COLOR_ARGB
    val blackDotArgb = if (isOledMode) 0xFFFFFFFF.toInt() else blackInkArgb
    val blueInkArgb = 0xFF1A56DB.toInt()
    val redInkArgb = 0xFFDC2626.toInt()

    val slices = remember(brushConfig, isOledMode) {
        listOf(
            PieMenuSliceSpec(
                index = 0,
                label = "Toll",
                icon = Icons.Filled.Create,
                isActive = brushConfig.toolType == BrushToolType.FOUNTAIN_PEN
            ),
            PieMenuSliceSpec(
                index = 1,
                label = "Kiemelő",
                icon = Icons.Filled.Brush,
                isActive = brushConfig.toolType == BrushToolType.HIGHLIGHTER
            ),
            PieMenuSliceSpec(
                index = 2,
                label = "Lasszó",
                icon = Icons.Filled.Gesture,
                isActive = brushConfig.toolType == BrushToolType.LASSO
            ),
            PieMenuSliceSpec(
                index = 3,
                label = "Vastagság",
                icon = Icons.Filled.LineWeight,
                isActive = false
            ),
            PieMenuSliceSpec(
                index = 4,
                label = "Radír",
                icon = StylusIcons.PrecisionEraser,
                isActive = brushConfig.toolType.isEraser
            ),
            PieMenuSliceSpec(
                index = 5,
                label = "Kék",
                colorDotArgb = blueInkArgb,
                isActive = brushConfig.colorArgb == blueInkArgb && brushConfig.toolType.isDrawingBrush
            ),
            PieMenuSliceSpec(
                index = 6,
                label = "Piros",
                colorDotArgb = redInkArgb,
                isActive = brushConfig.colorArgb == redInkArgb && brushConfig.toolType.isDrawingBrush
            ),
            PieMenuSliceSpec(
                index = 7,
                label = if (isOledMode) "Fehér" else "Fekete",
                colorDotArgb = blackDotArgb,
                isActive = brushConfig.colorArgb == blackInkArgb && brushConfig.toolType.isDrawingBrush
            )
        )
    }

    var highlightedSliceIndex by remember { mutableStateOf<Int?>(null) }
    var handledCommitTick by remember { mutableStateOf(commitTick) }
    val focusRequester = remember { FocusRequester() }

    fun executeSlice(index: Int) {
        when (index) {
            0 -> onSelectTool(BrushToolType.FOUNTAIN_PEN)
            1 -> onSelectTool(BrushToolType.HIGHLIGHTER)
            2 -> onSelectTool(BrushToolType.LASSO)
            3 -> onCyclePenSize()
            4 -> onSelectTool(BrushToolType.PRECISION_ERASER)
            5 -> onSelectInkColor(blueInkArgb)
            6 -> onSelectInkColor(redInkArgb)
            7 -> onSelectInkColor(blackInkArgb)
        }
        onDismiss()
    }

    LaunchedEffect(commitTick) {
        if (commitTick > handledCommitTick) {
            handledCommitTick = commitTick
            val targetSlice = highlightedSliceIndex
            if (targetSlice != null) {
                executeSlice(targetSlice)
            } else {
                onDismiss()
            }
        }
    }

    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .testTag("radial_pie_menu_backdrop")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        val ringDiameterDp = outerRadiusDp * 2
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (clampedCenter.x - outerRadiusPx).roundToInt(),
                        y = (clampedCenter.y - outerRadiusPx).roundToInt()
                    )
                }
                .size(ringDiameterDp)
                .pointerInput(outerRadiusPx, innerDeadzonePx) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pt = ev.changes.firstOrNull()?.position ?: continue
                            if (ev.type == PointerEventType.Move) {
                                highlightedSliceIndex = resolvePieMenuSliceIndex(
                                    dxPx = pt.x - outerRadiusPx,
                                    dyPx = pt.y - outerRadiusPx,
                                    innerDeadzonePx = innerDeadzonePx,
                                    outerMaxRadiusPx = outerRadiusPx * 1.35f,
                                    sliceCount = 8
                                )
                            }
                        }
                    }
                }
                .pointerInput(outerRadiusPx, innerDeadzonePx) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            change.consume()
                            highlightedSliceIndex = resolvePieMenuSliceIndex(
                                dxPx = change.position.x - outerRadiusPx,
                                dyPx = change.position.y - outerRadiusPx,
                                innerDeadzonePx = innerDeadzonePx,
                                outerMaxRadiusPx = outerRadiusPx * 1.35f,
                                sliceCount = 8
                            )
                        },
                        onDragEnd = {
                            val idx = highlightedSliceIndex
                            if (idx != null) {
                                executeSlice(idx)
                            }
                        }
                    )
                }
                .testTag("radial_pie_menu_ring")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension * 0.5f
                drawCircle(
                    color = surfaceColor,
                    radius = radius,
                    center = center
                )
                val sweep = 360f / 8f
                for (i in 0 until 8) {
                    val startAngle = -90f - sweep * 0.5f + i * sweep
                    val isHovered = highlightedSliceIndex == i
                    val isCurrent = slices[i].isActive
                    if (isHovered || isCurrent) {
                        drawArc(
                            color = if (isHovered) primaryContainerColor else primaryContainerColor.copy(alpha = 0.42f),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true,
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height)
                        )
                    }
                    val sepAngleRad = Math.toRadians(startAngle.toDouble())
                    drawLine(
                        color = borderColor.copy(alpha = 0.65f),
                        start = Offset(
                            center.x + innerDeadzonePx * cos(sepAngleRad).toFloat(),
                            center.y + innerDeadzonePx * sin(sepAngleRad).toFloat()
                        ),
                        end = Offset(
                            center.x + radius * cos(sepAngleRad).toFloat(),
                            center.y + radius * sin(sepAngleRad).toFloat()
                        ),
                        strokeWidth = 1.25.dp.toPx()
                    )
                }
                drawCircle(
                    color = primaryColor.copy(alpha = 0.55f),
                    radius = radius - 1.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.6.dp.toPx())
                )
            }

            // Slice labels/icons arranged around the middle radius
            val itemRadiusPx = (innerDeadzonePx + outerRadiusPx) * 0.53f
            slices.forEach { spec ->
                val midAngleRad = Math.toRadians((-90.0 + spec.index * 45.0))
                val itemCx = outerRadiusPx + itemRadiusPx * cos(midAngleRad).toFloat()
                val itemCy = outerRadiusPx + itemRadiusPx * sin(midAngleRad).toFloat()
                val itemBoxSizeDp = 52.dp
                val halfItemPx = with(density) { (itemBoxSizeDp * 0.5f).toPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (itemCx - halfItemPx).roundToInt(),
                                y = (itemCy - halfItemPx).roundToInt()
                            )
                        }
                        .size(itemBoxSizeDp)
                        .clickable { executeSlice(spec.index) }
                        .testTag("pie_slice_${spec.index}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        when {
                            spec.colorDotArgb != null -> Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color(spec.colorDotArgb), CircleShape)
                            )
                            spec.icon != null -> Icon(
                                imageVector = spec.icon,
                                contentDescription = spec.label,
                                tint = if (highlightedSliceIndex == spec.index || spec.isActive) {
                                    primaryColor
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = spec.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (highlightedSliceIndex == spec.index || spec.isActive) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Medium
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Inner deadzone cancel hub
            Surface(
                onClick = onDismiss,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.25.dp, borderColor),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(innerDeadzoneDp * 1.85f)
                    .testTag("radial_pie_menu_center_cancel")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Mégse",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
