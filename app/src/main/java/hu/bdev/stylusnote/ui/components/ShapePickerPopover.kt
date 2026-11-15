package hu.bdev.stylusnote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.bdev.stylusnote.core.math.GeometricShapeRenderer
import hu.bdev.stylusnote.domain.model.GeometricShapeType
import java.util.Locale
import kotlin.math.roundToInt

private val SHAPE_STROKE_SWATCHES = listOf(
    0xFF111827.toInt(),
    0xFF991B1B.toInt(),
    0xFF1E40AF.toInt(),
    0xFF166534.toInt(),
    0xFFD97706.toInt()
)

/**
 * Anchored Shape Picker popover modeled on the reference StarNote shapes studio:
 * - Page 0: 4x6 grid of 24 geometric polygons & 3D dashed-back-edge solids
 * - Page 1: Essential 2D shapes, lines, arrows & coordinate axes
 * - Controls: Stroke thickness slider, 5 stroke color swatches, and Shape fill switch
 * Closes via ESC key press and backdrop click (UX Rule 11).
 */
@Composable
fun ShapePickerPopover(
    initialStrokeWidthPt: Float,
    initialStrokeColorArgb: Int,
    initialHasFill: Boolean,
    anchorBoundsPx: Rect = Rect.Zero,
    viewportSize: IntSize = IntSize.Zero,
    onInsertShape: (
        shapeType: GeometricShapeType,
        strokeWidthPt: Float,
        strokeColorArgb: Int,
        hasFill: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCatalogPage by remember { mutableIntStateOf(0) }
    var strokeWidthPt by remember { mutableStateOf(initialStrokeWidthPt.coerceIn(0.6f, 8.0f)) }
    var strokeColorArgb by remember { mutableIntStateOf(initialStrokeColorArgb) }
    var hasFill by remember { mutableStateOf(initialHasFill) }
    var popoverSizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Escape) {
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
        ) {
            val offsetPx = computeAnchoredPopoverOffsetPx(
                anchorBoundsPx = anchorBoundsPx,
                popoverSizePx = popoverSizePx,
                viewportSize = viewportSize,
                marginPx = with(density) { 12.dp.roundToPx() },
                gapPx = with(density) { 10.dp.roundToPx() }
            )
            Surface(
                modifier = Modifier
                    .offset { offsetPx }
                    .onSizeChanged { popoverSizePx = it }
                    .width(340.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Alakzat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    val shapesForPage = remember(selectedCatalogPage) {
                        GeometricShapeType.forCatalogPage(selectedCatalogPage)
                    }
                    val previewStrokeColor = MaterialTheme.colorScheme.onSurface.toArgb()
                    val previewFillColor = (previewStrokeColor and 0x00FFFFFF) or 0x22000000

                    // 4x6 grid supporting horizontal swipe between Geometry 3D/Polygons & Basic 2D Shapes
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                var totalDrag = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { totalDrag = 0f },
                                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                                    onDragEnd = {
                                        if (totalDrag < -48f && selectedCatalogPage < 1) {
                                            selectedCatalogPage = 1
                                        } else if (totalDrag > 48f && selectedCatalogPage > 0) {
                                            selectedCatalogPage = 0
                                        }
                                    }
                                )
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val rows = shapesForPage.chunked(6)
                            for (row in rows) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (slotIndex in 0 until 6) {
                                        val shapeType = row.getOrNull(slotIndex)
                                        if (shapeType == null) {
                                            Spacer(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                                    .clickable {
                                                        onInsertShape(
                                                            shapeType,
                                                            strokeWidthPt,
                                                            strokeColorArgb,
                                                            hasFill
                                                        )
                                                        onDismiss()
                                                    }
                                                    .padding(6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Canvas(modifier = Modifier.fillMaxSize()) {
                                                    val strokePx = with(density) { 1.35.dp.toPx() }
                                                    GeometricShapeRenderer.drawShape(
                                                        canvas = drawContext.canvas.nativeCanvas,
                                                        shapeType = shapeType,
                                                        bounds = android.graphics.RectF(0f, 0f, size.width, size.height),
                                                        strokeWidthPx = strokePx,
                                                        strokeColorArgb = previewStrokeColor,
                                                        hasFill = hasFill,
                                                        fillColorArgb = previewFillColor
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Page indicator dots (- •)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(0, 1).forEach { pageIdx ->
                            val isActive = selectedCatalogPage == pageIdx
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                    .width(if (isActive) 18.dp else 8.dp)
                                    .height(5.dp)
                                    .clickable { selectedCatalogPage = pageIdx }
                            )
                        }
                    }

                    // Stroke thickness slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Vonalvastagság",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f mm", strokeWidthPt * 0.35f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Slider(
                                value = strokeWidthPt,
                                onValueChange = { strokeWidthPt = (it * 10f).roundToInt() / 10f },
                                valueRange = 0.6f..8.0f,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }

                    // Stroke color swatches
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Vonalszín",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SHAPE_STROKE_SWATCHES.forEach { swatchArgb ->
                                    val selected = strokeColorArgb == swatchArgb
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Color(swatchArgb))
                                            .then(
                                                if (selected) {
                                                    Modifier.border(
                                                        width = 2.5.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = CircleShape
                                                    )
                                                } else {
                                                    Modifier.border(
                                                        width = 1.dp,
                                                        color = MaterialTheme.colorScheme.outlineVariant,
                                                        shape = CircleShape
                                                    )
                                                }
                                            )
                                            .clickable { strokeColorArgb = swatchArgb },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) {
                                            IconCheckWhite()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Shape fill toggle
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Alakzat kitöltése",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Switch(
                                checked = hasFill,
                                onCheckedChange = { hasFill = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconCheckWhite() {
    androidx.compose.material3.Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(16.dp)
    )
}
