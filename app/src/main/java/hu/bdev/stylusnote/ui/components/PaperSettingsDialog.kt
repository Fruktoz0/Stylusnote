package hu.bdev.stylusnote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette

private data class PageFormatOption(
    val label: String,
    val subtitle: String,
    val dimensions: PageDimensionsPt
)

private val PAGE_FORMAT_OPTIONS = listOf(
    PageFormatOption("A4 álló", "210 × 297 mm", PageDimensionsPt.A4_PORTRAIT),
    PageFormatOption("B5 álló", "176 × 250 mm", PageDimensionsPt.B5_ISO_PORTRAIT),
    PageFormatOption("16:10 fekvő", "Széles jegyzet", PageDimensionsPt.LANDSCAPE_16_10)
)

/**
 * Modern modal dialog for selecting [CanvasMode], fixed [PageDimensionsPt], and [PaperTemplateType]
 * with live miniature paper previews and clear active-state selection rings.
 * Supports closing via ESC key and backdrop click (UX Rule 11).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperSettingsDialog(
    currentConfig: PagePaperConfig,
    onConfigChange: (PagePaperConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalStylusSurfacePalette.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
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
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 560.dp)
                    .padding(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Vászon és papírsablon",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Válassz határmódot, lapméretet és rácsvonalazást",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Bezárás"
                            )
                        }
                    }

                    // 1. Canvas mode visual cards
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Vászonmód",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CanvasMode.entries.forEach { mode ->
                                val selected = currentConfig.canvasMode == mode
                                Surface(
                                    onClick = { onConfigChange(currentConfig.copy(canvasMode = mode)) },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) {
                                        palette.activePillBackground
                                    } else {
                                        palette.groupedCardBackground
                                    },
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(modifier = Modifier.padding(12.dp)) {
                                        if (selected) {
                                            ActiveCheckBadge(
                                                modifier = Modifier.align(Alignment.TopEnd)
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(end = 22.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (mode == CanvasMode.FIXED_PAGE) {
                                                    Icons.Filled.Description
                                                } else {
                                                    Icons.Filled.AllInclusive
                                                },
                                                contentDescription = null,
                                                tint = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = mode.displayName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    }
                                                )
                                                Text(
                                                    text = if (mode == CanvasMode.FIXED_PAGE) {
                                                        "Oldalak és sorszám"
                                                    } else {
                                                        "Végtelen rajzterület"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Fixed page dimensions
                    if (currentConfig.canvasMode == CanvasMode.FIXED_PAGE) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Lapméret",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                PAGE_FORMAT_OPTIONS.forEach { option ->
                                    val selected = currentConfig.pageDimensions == option.dimensions
                                    Surface(
                                        onClick = {
                                            onConfigChange(currentConfig.copy(pageDimensions = option.dimensions))
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) {
                                            palette.activePillBackground
                                        } else {
                                            palette.groupedCardBackground
                                        },
                                        border = BorderStroke(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            }
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                                            if (selected) {
                                                ActiveCheckBadge(modifier = Modifier.align(Alignment.TopEnd))
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = option.label,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    }
                                                )
                                                Text(
                                                    text = option.subtitle,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Paper template miniature visual cards
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Papírsablon",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = 4,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PaperTemplateType.entries.forEach { template ->
                                val selected = currentConfig.templateType == template
                                Surface(
                                    onClick = {
                                        onConfigChange(currentConfig.copy(templateType = template))
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) {
                                        palette.activePillBackground
                                    } else {
                                        palette.groupedCardBackground
                                    },
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    ),
                                    modifier = Modifier.width(114.dp)
                                ) {
                                    Box(modifier = Modifier.padding(10.dp)) {
                                        if (selected) {
                                            ActiveCheckBadge(modifier = Modifier.align(Alignment.TopEnd))
                                        }
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            TemplateMiniPreview(
                                                templateType = template,
                                                paperColor = palette.pagePaperBackground,
                                                borderColor = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    palette.pageBorder
                                                }
                                            )
                                            Text(
                                                text = template.displayName,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
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
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Kész", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveCheckBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Kiválasztva",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun TemplateMiniPreview(
    templateType: PaperTemplateType,
    paperColor: Color,
    borderColor: Color
) {
    val lineColor = Color(0xFFB6C3D4)
    val strongLineColor = Color(0xFF7D90AA)
    val accentRed = Color(0xFFE57373)

    Canvas(
        modifier = Modifier
            .width(48.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(paperColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
    ) {
        val w = size.width
        val h = size.height
        when (templateType) {
            PaperTemplateType.BLANK -> Unit
            PaperTemplateType.DOTTED_5MM -> {
                val cols = 4
                val rows = 5
                val dx = w / (cols + 1)
                val dy = h / (rows + 1)
                for (r in 1..rows) {
                    for (c in 1..cols) {
                        drawCircle(
                            color = strongLineColor,
                            radius = 1.6f,
                            center = Offset(c * dx, r * dy)
                        )
                    }
                }
            }
            PaperTemplateType.GRID_SIMPLE_5MM -> {
                val step = w / 6f
                var x = step
                while (x < w - 1f) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    x += step
                }
                var y = step
                while (y < h - 1f) {
                    drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    y += step
                }
            }
            PaperTemplateType.GRID_5MM -> {
                val step = w / 6f
                var xi = 1
                var x = step
                while (x < w - 1f) {
                    val isMajor = xi % 3 == 0
                    drawLine(
                        color = if (isMajor) strongLineColor else lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = if (isMajor) 1.5f else 0.9f
                    )
                    x += step
                    xi++
                }
                var yi = 1
                var y = step
                while (y < h - 1f) {
                    val isMajor = yi % 3 == 0
                    drawLine(
                        color = if (isMajor) strongLineColor else lineColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = if (isMajor) 1.5f else 0.9f
                    )
                    y += step
                    yi++
                }
            }
            PaperTemplateType.RULED_8MM -> {
                val marginX = w * 0.22f
                drawLine(accentRed, Offset(marginX, 0f), Offset(marginX, h), strokeWidth = 1.2f)
                val dy = h / 7f
                for (i in 2..6) {
                    drawLine(lineColor, Offset(0f, i * dy), Offset(w, i * dy), strokeWidth = 1f)
                }
            }
            PaperTemplateType.CORNELL -> {
                val topY = h * 0.22f
                val cueX = w * 0.3f
                val sumY = h * 0.8f
                drawLine(accentRed, Offset(0f, topY), Offset(w, topY), strokeWidth = 1.1f)
                drawLine(accentRed, Offset(cueX, topY), Offset(cueX, sumY), strokeWidth = 1.1f)
                drawLine(strongLineColor, Offset(0f, sumY), Offset(w, sumY), strokeWidth = 1.1f)
                val dy = (sumY - topY) / 5f
                for (i in 1..4) {
                    drawLine(lineColor, Offset(cueX + 2f, topY + i * dy), Offset(w, topY + i * dy), strokeWidth = 0.9f)
                }
            }
            PaperTemplateType.MUSIC_STAFF -> {
                for (staff in 0..1) {
                    val baseY = h * (0.24f + staff * 0.42f)
                    for (line in 0..4) {
                        val y = baseY + line * 3.5f
                        drawLine(strongLineColor, Offset(4f, y), Offset(w - 4f, y), strokeWidth = 0.9f)
                    }
                }
            }
        }
    }
}
