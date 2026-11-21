package hu.bdev.stylusnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette

/** User-selected dimensions & visual style produced by [TablePickerPopover]. */
data class TableCreationConfig(
    val rows: Int = 4,
    val columns: Int = 3,
    val borderStrokeWidthPt: Float = PageObject.TableObject.DEFAULT_BORDER_WIDTH_PT,
    val canonicalBorderColorArgb: Int = PageObject.TableObject.DEFAULT_BORDER_COLOR_ARGB,
    val hasHeaderRow: Boolean = true
)

private val TABLE_BORDER_COLOR_SWATCHES: List<Int> = listOf(
    0xFF334155.toInt(), // Slate
    0xFF2563EB.toInt(), // Blue
    0xFF0D9488.toInt(), // Teal
    0xFFD97706.toInt(), // Amber
    0xFF7C3AED.toInt()  // Violet
)

private val TABLE_BORDER_WIDTH_PRESETS: List<Pair<String, Float>> = listOf(
    "Vékony" to 0.8f,
    "Normál" to 1.2f,
    "Vastag" to 2.0f
)

/**
 * Floating table configuration dialog (`N × M` quick grid + fine steppers up to `20 × 12`,
 * header row highlight toggle, border thickness, and color). Supports dismissal via both `ESC`
 * and backdrop click before transitioning to [hu.bdev.stylusnote.ui.canvas.PendingTableDraftOverlay].
 */
@Composable
fun TablePickerPopover(
    initialConfig: TableCreationConfig = TableCreationConfig(),
    onConfirm: (TableCreationConfig) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStylusSurfacePalette.current
    val focusRequester = remember { FocusRequester() }

    var rows by remember {
        mutableIntStateOf(
            initialConfig.rows.coerceIn(PageObject.TableObject.MIN_ROWS, PageObject.TableObject.MAX_ROWS)
        )
    }
    var columns by remember {
        mutableIntStateOf(
            initialConfig.columns.coerceIn(PageObject.TableObject.MIN_COLS, PageObject.TableObject.MAX_COLS)
        )
    }
    var borderStrokeWidthPt by remember { mutableFloatStateOf(initialConfig.borderStrokeWidthPt) }
    var borderColorArgb by remember { mutableIntStateOf(initialConfig.canonicalBorderColorArgb) }
    var hasHeaderRow by remember { mutableStateOf(initialConfig.hasHeaderRow) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .testTag("table_picker_backdrop")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 14.dp,
            border = BorderStroke(1.dp, palette.pageBorder),
            modifier = Modifier
                .widthIn(min = 320.dp, max = 380.dp)
                .padding(16.dp)
                .testTag("table_picker_card")
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Táblázat beszúrása",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$rows × $columns táblázat (lehelyezés előtt méretezhető)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("table_dimension_summary")
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Bezárás",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Quick 6 × 6 grid selector
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (r in 1..6) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in 1..6) {
                                val selected = r <= rows && c <= columns
                                val isHeaderPreview = hasHeaderRow && rows >= 2 && r == 1 && selected
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            when {
                                                isHeaderPreview -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                                else -> palette.groupedCardBackground
                                            }
                                        )
                                        .border(
                                            width = if (selected) 1.3.dp else 0.8.dp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                            shape = RoundedCornerShape(5.dp)
                                        )
                                        .testTag("table_grid_cell_${r}_${c}")
                                        .clickable {
                                            rows = r
                                            columns = c
                                        }
                                )
                            }
                        }
                    }
                }

                // Exact steppers (up to 20 × 12)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DimensionStepper(
                        label = "Sorok",
                        value = rows,
                        min = PageObject.TableObject.MIN_ROWS,
                        max = PageObject.TableObject.MAX_ROWS,
                        decTag = "table_rows_dec",
                        valueTag = "table_rows_value",
                        incTag = "table_rows_inc",
                        onChange = { rows = it }
                    )
                    DimensionStepper(
                        label = "Oszlopok",
                        value = columns,
                        min = PageObject.TableObject.MIN_COLS,
                        max = PageObject.TableObject.MAX_COLS,
                        decTag = "table_cols_dec",
                        valueTag = "table_cols_value",
                        incTag = "table_cols_inc",
                        onChange = { columns = it }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Fejléc sor kiemelése",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = hasHeaderRow,
                        onCheckedChange = { hasHeaderRow = it },
                        modifier = Modifier.testTag("table_header_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for ((label, widthPt) in TABLE_BORDER_WIDTH_PRESETS) {
                        val isSelected = kotlin.math.abs(borderStrokeWidthPt - widthPt) < 0.25f
                        FilterChip(
                            selected = isSelected,
                            onClick = { borderStrokeWidthPt = widthPt },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (swatch in TABLE_BORDER_COLOR_SWATCHES) {
                            val active = borderColorArgb == swatch
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch))
                                    .border(
                                        width = if (active) 2.2.dp else 1.dp,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.White.copy(alpha = 0.65f)
                                        },
                                        shape = CircleShape
                                    )
                                    .clickable { borderColorArgb = swatch }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("table_picker_cancel")
                    ) {
                        Text("Mégse")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                TableCreationConfig(
                                    rows = rows,
                                    columns = columns,
                                    borderStrokeWidthPt = borderStrokeWidthPt,
                                    canonicalBorderColorArgb = borderColorArgb,
                                    hasHeaderRow = hasHeaderRow
                                )
                            )
                        },
                        modifier = Modifier.testTag("table_picker_confirm")
                    ) {
                        Text("Tovább a lehelyezéshez")
                    }
                }
            }
        }
    }
}

@Composable
private fun DimensionStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    decTag: String,
    valueTag: String,
    incTag: String,
    onChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (value > min) onChange(value - 1) },
                enabled = value > min,
                modifier = Modifier
                    .size(30.dp)
                    .testTag(decTag)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "$label csökkentése", modifier = Modifier.size(16.dp))
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .testTag(valueTag)
            )
            IconButton(
                onClick = { if (value < max) onChange(value + 1) },
                enabled = value < max,
                modifier = Modifier
                    .size(30.dp)
                    .testTag(incTag)
            ) {
                Icon(Icons.Default.Add, contentDescription = "$label növelése", modifier = Modifier.size(16.dp))
            }
        }
    }
}
