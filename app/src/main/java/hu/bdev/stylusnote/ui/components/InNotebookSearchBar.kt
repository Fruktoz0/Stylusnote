package hu.bdev.stylusnote.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import hu.bdev.stylusnote.core.ai.SearchIndexState
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.TransformSnapshot
import kotlin.math.max

/**
 * Compact in-notebook search pill & expandable inline search bar rendered inside `CanvasTopToolbar`
 * directly beside the `AI` button.
 */
@Composable
fun InNotebookSearchToolbarSection(
    isOpen: Boolean,
    query: String,
    activeMatchIndex: Int,
    matchesCount: Int,
    indexState: SearchIndexState,
    canRunAiDeepIndex: Boolean,
    onOpen: () -> Unit,
    onQueryChange: (String) -> Unit,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onTriggerAiDeepIndex: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) {
        Surface(
            onClick = onOpen,
            modifier = modifier.testTag("in_notebook_search_trigger"),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Keresés a jegyzetben",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Keresés",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    BackHandler(enabled = true) { onClose() }

    val isBusy = indexState == SearchIndexState.CHECKING_MODEL ||
        indexState == SearchIndexState.DOWNLOADING_MODEL ||
        indexState == SearchIndexState.INDEXING_HANDWRITING ||
        indexState == SearchIndexState.INDEXING_WITH_AI

    Surface(
        modifier = modifier
            .testTag("in_notebook_search_bar")
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyUp) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Box(
                modifier = Modifier
                    .widthIn(min = 116.dp, max = 190.dp)
                    .padding(horizontal = 3.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Keresés kézírásban…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { if (matchesCount > 0) onNextMatch() }
                    ),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .widthIn(min = 116.dp, max = 190.dp)
                        .testTag("in_notebook_search_input")
                )
            }

            if (isBusy) {
                CircularProgressIndicator(
                    strokeWidth = 1.8.dp,
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(13.dp)
                )
            }

            if (query.isNotBlank()) {
                val label = if (matchesCount == 0) "0" else "${activeMatchIndex + 1}/$matchesCount"
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (matchesCount > 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .testTag("in_notebook_search_counter")
                )
            }

            IconButton(
                onClick = onPreviousMatch,
                enabled = matchesCount > 0,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("in_notebook_search_prev")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Előző találat",
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onNextMatch,
                enabled = matchesCount > 0,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("in_notebook_search_next")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Következő találat",
                    modifier = Modifier.size(16.dp)
                )
            }

            if (canRunAiDeepIndex && query.isNotBlank()) {
                IconButton(
                    onClick = onTriggerAiDeepIndex,
                    enabled = indexState != SearchIndexState.INDEXING_WITH_AI,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("in_notebook_search_ai_deep")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI mélyindexelés nehéz kézíráshoz",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("in_notebook_search_close")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Keresés bezárása",
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * Non-intercepting canvas overlay highlighting [highlightDocRect] of the active in-notebook search
 * match in screen coordinates derived directly from [transformSnapshot].
 */
@Composable
fun SearchHighlightCanvasOverlay(
    highlightDocRect: DocRect?,
    transformSnapshot: TransformSnapshot,
    isOledMode: Boolean,
    modifier: Modifier = Modifier
) {
    if (highlightDocRect == null) return

    val fillColor = if (isOledMode) Color(0x4DFDE047) else Color(0x5CFACC15)
    val strokeColor = if (isOledMode) Color(0xFFFACC15) else Color(0xFFD97706)

    Canvas(modifier = modifier.fillMaxSize()) {
        val leftPx = highlightDocRect.leftPt * transformSnapshot.scale + transformSnapshot.panXPx
        val topPx = highlightDocRect.topPt * transformSnapshot.scale + transformSnapshot.panYPx
        val rawWidthPx = highlightDocRect.widthPt * transformSnapshot.scale
        val rawHeightPx = highlightDocRect.heightPt * transformSnapshot.scale
        val widthPx = max(rawWidthPx, 28f)
        val heightPx = max(rawHeightPx, 20f)
        val radiusPx = 6.dp.toPx()

        drawRoundRect(
            color = fillColor,
            topLeft = Offset(leftPx, topPx),
            size = Size(widthPx, heightPx),
            cornerRadius = CornerRadius(radiusPx, radiusPx)
        )
        drawRoundRect(
            color = strokeColor,
            topLeft = Offset(leftPx, topPx),
            size = Size(widthPx, heightPx),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
