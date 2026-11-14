package hu.bdev.stylusnote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PaperTemplateType

private data class PageFormatOption(
    val label: String,
    val dimensions: PageDimensionsPt
)

private val PAGE_FORMAT_OPTIONS = listOf(
    PageFormatOption("A4 álló", PageDimensionsPt.A4_PORTRAIT),
    PageFormatOption("B5 álló", PageDimensionsPt.B5_ISO_PORTRAIT),
    PageFormatOption("16:10 fekvő", PageDimensionsPt.LANDSCAPE_16_10)
)

/**
 * Modal dialog for selecting [CanvasMode], fixed [PageDimensionsPt], and [PaperTemplateType].
 * Supports closing via ESC key and backdrop tap per UI guidelines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperSettingsDialog(
    currentConfig: PagePaperConfig,
    onConfigChange: (PagePaperConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
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
                .safeDrawingPadding()
                .padding(24.dp)
                .widthIn(min = 320.dp, max = 460.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Consume card clicks so only backdrop clicks dismiss the dialog.
                },
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Papír sablon és vászonmód",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "Vászon mód",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CanvasMode.entries.forEach { mode ->
                        FilterChip(
                            selected = currentConfig.canvasMode == mode,
                            onClick = {
                                onConfigChange(currentConfig.copy(canvasMode = mode))
                            },
                            label = { Text(mode.displayName) }
                        )
                    }
                }

                if (currentConfig.canvasMode == CanvasMode.FIXED_PAGE) {
                    Text(
                        text = "Oldalméret",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PAGE_FORMAT_OPTIONS.forEach { option ->
                            val isSelected = currentConfig.pageDimensions == option.dimensions
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    onConfigChange(currentConfig.copy(pageDimensions = option.dimensions))
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Vektoros papírsablon",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PaperTemplateType.entries.forEach { template ->
                        FilterChip(
                            selected = currentConfig.templateType == template,
                            onClick = {
                                onConfigChange(currentConfig.copy(templateType = template))
                            },
                            label = { Text(template.displayName) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Bezárás")
                    }
                }
            }
        }
    }
}

