package hu.bdev.stylusnote.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import hu.bdev.stylusnote.core.hardware.PressureCurve
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.ui.theme.LocalStylusSurfacePalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val Context.brushPresetsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "stylusnote_brush_presets"
)

/**
 * Persistent store for the 6 favorite brush presets backed by Jetpack DataStore Preferences
 * (Section 5.4). Uses compact field-delimited encoding (no JSON dependency required).
 */
class BrushPresetStore(private val context: Context) {

    val presetsFlow: Flow<List<BrushConfig>> = context.brushPresetsDataStore.data.map { prefs ->
        List(PRESET_COUNT) { index ->
            val raw = prefs[presetKey(index)]
            if (raw != null) decodePreset(raw) ?: defaultPresets[index] else defaultPresets[index]
        }
    }

    suspend fun savePreset(slotIndex: Int, config: BrushConfig) {
        val safeIndex = slotIndex.coerceIn(0, PRESET_COUNT - 1)
        context.brushPresetsDataStore.edit { prefs ->
            prefs[presetKey(safeIndex)] = encodePreset(config)
        }
    }

    companion object {
        const val PRESET_COUNT: Int = 6

        private fun presetKey(index: Int) = stringPreferencesKey("brush_preset_$index")

        val defaultPresets: List<BrushConfig> = listOf(
            BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 2.0f,
                colorArgb = 0xFF14171C.toInt(),
                opacity = 1.0f,
                stabilizationPercent = 0f
            ),
            BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 1.0f,
                colorArgb = 0xFF1A56DB.toInt(),
                opacity = 1.0f,
                stabilizationPercent = 15f
            ),
            BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 4.0f,
                colorArgb = 0xFF334155.toInt(),
                opacity = 1.0f,
                stabilizationPercent = 25f
            ),
            BrushConfig(
                toolType = BrushToolType.HIGHLIGHTER,
                sizePt = 16.0f,
                colorArgb = 0xFFFFEB3B.toInt(),
                opacity = BrushConfig.HIGHLIGHTER_OPACITY,
                stabilizationPercent = 40f
            ),
            BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 2.0f,
                colorArgb = 0xFFD92D20.toInt(),
                opacity = 1.0f,
                stabilizationPercent = 30f
            ),
            BrushConfig(
                toolType = BrushToolType.HIGHLIGHTER,
                sizePt = 16.0f,
                colorArgb = 0xFF34D399.toInt(),
                opacity = BrushConfig.HIGHLIGHTER_OPACITY,
                stabilizationPercent = 40f
            )
        )

        fun encodePreset(config: BrushConfig): String = listOf(
            config.toolType.name,
            config.clampedSizePt,
            config.colorArgb,
            config.opacity,
            config.clampedStabilizationPercent,
            config.pressureCurve.name,
            config.holdToSnapEnabled,
            config.eraserRadiusDp
        ).joinToString("|")

        fun decodePreset(raw: String): BrushConfig? {
            val parts = raw.split("|")
            if (parts.size < 7) return null
            val toolType = runCatching { BrushToolType.valueOf(parts[0]) }.getOrNull() ?: return null
            val sizePt = parts[1].toFloatOrNull() ?: return null
            val colorArgb = parts[2].toIntOrNull() ?: return null
            val opacity = parts[3].toFloatOrNull() ?: return null
            val stab = parts[4].toFloatOrNull() ?: return null
            val curve = runCatching { PressureCurve.valueOf(parts[5]) }.getOrDefault(PressureCurve.LINEAR)
            val snap = parts[6].toBooleanStrictOrNull() ?: true
            val eraserDp = parts.getOrNull(7)?.toFloatOrNull() ?: BrushConfig.DEFAULT_ERASER_RADIUS_DP
            return BrushConfig(
                toolType = toolType,
                sizePt = sizePt,
                colorArgb = colorArgb,
                opacity = opacity,
                stabilizationPercent = stab,
                pressureCurve = curve,
                holdToSnapEnabled = snap,
                eraserRadiusDp = eraserDp
            )
        }
    }
}

private val QUICK_COLOR_PALETTE = listOf(
    0xFF14171C.toInt(),
    0xFF1A56DB.toInt(),
    0xFF047857.toInt(),
    0xFFDC2626.toInt(),
    0xFFD97706.toInt(),
    0xFF7C3AED.toInt(),
    0xFF0891B2.toInt(),
    0xFFFACC15.toInt()
)

private val QUICK_SIZE_PRESETS_PT = listOf(1.0f, 2.0f, 4.0f, 8.0f)
private val QUICK_HIGHLIGHTER_SIZE_PRESETS_PT = listOf(8.0f, 12.0f, 16.0f, 24.0f)
private val QUICK_ERASER_RADII_DP = listOf(12f, 20f, 32f, 44f)

/**
 * Computes an anchored viewport offset placing the popover adjacent to [anchorBoundsPx]
 * while keeping the entire popup inside [viewportSize].
 */
fun computeAnchoredPopoverOffsetPx(
    anchorBoundsPx: Rect,
    popoverSizePx: IntSize,
    viewportSize: IntSize,
    marginPx: Int = 16,
    gapPx: Int = 12
): IntOffset {
    if (viewportSize.width <= 0 || viewportSize.height <= 0 || popoverSizePx.width <= 0 || popoverSizePx.height <= 0) {
        return IntOffset(marginPx, marginPx)
    }
    val maxX = (viewportSize.width - popoverSizePx.width - marginPx).coerceAtLeast(marginPx)
    val maxY = (viewportSize.height - popoverSizePx.height - marginPx).coerceAtLeast(marginPx)
    if (anchorBoundsPx.width <= 0f || anchorBoundsPx.height <= 0f) {
        return IntOffset(maxX, ((viewportSize.height - popoverSizePx.height) / 2).coerceIn(marginPx, maxY))
    }

    val isHorizontalDock = anchorBoundsPx.width >= anchorBoundsPx.height
    return if (isHorizontalDock) {
        val centeredX = (anchorBoundsPx.center.x - popoverSizePx.width / 2f).roundToInt()
        val resolvedX = centeredX.coerceIn(marginPx, maxX)
        val spaceBelow = viewportSize.height - anchorBoundsPx.bottom - marginPx - gapPx
        val spaceAbove = anchorBoundsPx.top - marginPx - gapPx
        val rawY = if (spaceBelow >= popoverSizePx.height || spaceBelow >= spaceAbove) {
            (anchorBoundsPx.bottom + gapPx).roundToInt()
        } else {
            (anchorBoundsPx.top - gapPx - popoverSizePx.height).roundToInt()
        }
        IntOffset(resolvedX, rawY.coerceIn(marginPx, maxY))
    } else {
        val spaceLeft = anchorBoundsPx.left - marginPx - gapPx
        val spaceRight = viewportSize.width - anchorBoundsPx.right - marginPx - gapPx
        val rawX = if (spaceLeft >= spaceRight) {
            (anchorBoundsPx.left - gapPx - popoverSizePx.width).roundToInt()
        } else {
            (anchorBoundsPx.right + gapPx).roundToInt()
        }
        val rawY = anchorBoundsPx.top.roundToInt()
        IntOffset(rawX.coerceIn(marginPx, maxX), rawY.coerceIn(marginPx, maxY))
    }
}

internal fun resolveBrushPopoverExpandsRightward(
    anchorBoundsPx: Rect,
    viewportSize: IntSize
): Boolean {
    if (anchorBoundsPx.width <= 0f || viewportSize.width <= 0) return false
    val isHorizontalDock = anchorBoundsPx.width >= anchorBoundsPx.height
    return if (isHorizontalDock) {
        anchorBoundsPx.center.x <= viewportSize.width * 0.5f
    } else {
        val spaceLeft = anchorBoundsPx.left
        val spaceRight = viewportSize.width - anchorBoundsPx.right
        spaceRight > spaceLeft
    }
}

private fun Modifier.progressiveStudioReveal(fraction: Float): Modifier = this
    .clipToBounds()
    .graphicsLayer { alpha = fraction }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val revealedHeight = (placeable.height * fraction).roundToInt()
        layout(placeable.width, revealedHeight) {
            placeable.placeRelative(0, 0)
        }
    }

private const val POPOVER_COMPACT_WIDTH_DP = 312f
private const val POPOVER_FULL_WIDTH_DP = 472f

/**
 * Dynamically resizable StarNote side-tab popover anchored near [anchorBoundsPx].
 *
 * Opens compact (`312 dp`) with quick sizes and colors and expands continuously under the user's
 * finger up to `472 dp` (gradually revealing the full studio: HSV wheel/HEX, opacity, 1-Euro
 * stabilization, presets, pressure curves, Hold-to-Snap) with the corner handle positioned on the
 * outer bottom corner matching the opening direction. Closes via ESC key and backdrop tap (Rule 11).
 */
@Composable
fun BrushSettingsPopover(
    currentConfig: BrushConfig,
    presets: List<BrushConfig>,
    anchorBoundsPx: Rect = Rect.Zero,
    viewportSize: IntSize = IntSize.Zero,
    onConfigChange: (BrushConfig) -> Unit,
    onSavePresetSlot: (Int, BrushConfig) -> Unit,
    onApplyPreset: (BrushConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val surfacePalette = LocalStylusSurfacePalette.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val widthAnimatable = remember { Animatable(POPOVER_COMPACT_WIDTH_DP) }
    var dragStartExpanded by remember { mutableStateOf(false) }
    var popoverSizePx by remember { mutableStateOf(IntSize.Zero) }
    val currentWidthDp = widthAnimatable.value.coerceIn(POPOVER_COMPACT_WIDTH_DP, POPOVER_FULL_WIDTH_DP)
    val expansionFraction = (
        (currentWidthDp - POPOVER_COMPACT_WIDTH_DP) /
            (POPOVER_FULL_WIDTH_DP - POPOVER_COMPACT_WIDTH_DP)
        ).coerceIn(0f, 1f)
    val isExpandedFullStudio = expansionFraction >= 0.5f
    val showFullSections = expansionFraction > 0.08f
    val stage2RevealFraction = ((expansionFraction - 0.08f) / 0.68f).coerceIn(0f, 1f)
    val hasAnchor = anchorBoundsPx.width > 0f && viewportSize.width > 0 && viewportSize.height > 0
    val expandsRightward = remember(anchorBoundsPx, viewportSize, hasAnchor) {
        if (hasAnchor) resolveBrushPopoverExpandsRightward(anchorBoundsPx, viewportSize) else false
    }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val gapPx = with(density) { 10.dp.roundToPx() }
    val resolvedPopoverOffset = if (hasAnchor && popoverSizePx.width > 0 && popoverSizePx.height > 0) {
        computeAnchoredPopoverOffsetPx(
            anchorBoundsPx = anchorBoundsPx,
            popoverSizePx = popoverSizePx,
            viewportSize = viewportSize,
            marginPx = marginPx,
            gapPx = gapPx
        )
    } else {
        IntOffset(marginPx, marginPx)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val hsv = remember(currentConfig.colorArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(currentConfig.colorArgb, it) }
    }
    var hue by remember(currentConfig.colorArgb) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(currentConfig.colorArgb) { mutableFloatStateOf(hsv[1]) }
    var value by remember(currentConfig.colorArgb) { mutableFloatStateOf(hsv[2]) }

    var hexText by remember(currentConfig.colorArgb) {
        mutableStateOf(formatRgbHex(currentConfig.colorArgb))
    }

    LaunchedEffect(currentConfig.colorArgb) {
        val next = FloatArray(3).also { AndroidColor.colorToHSV(currentConfig.colorArgb, it) }
        hue = next[0]
        saturation = next[1]
        value = next[2]
        val formatted = formatRgbHex(currentConfig.colorArgb)
        if (!hexText.equals(formatted, ignoreCase = true)) {
            hexText = formatted
        }
    }

    fun emitHsvColor(nextH: Float, nextS: Float, nextV: Float) {
        val argb = AndroidColor.HSVToColor(
            0xFF,
            floatArrayOf(nextH.coerceIn(0f, 360f), nextS.coerceIn(0f, 1f), nextV.coerceIn(0f, 1f))
        )
        hexText = formatRgbHex(argb)
        onConfigChange(currentConfig.copy(colorArgb = argb))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.24f))
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
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
            contentAlignment = if (hasAnchor) Alignment.TopStart else Alignment.CenterEnd
        ) {
            Surface(
                modifier = Modifier
                    .then(
                        if (hasAnchor) {
                            Modifier.offset { resolvedPopoverOffset }
                        } else {
                            Modifier.padding(end = 14.dp, top = 16.dp, bottom = 16.dp)
                        }
                    )
                    .onSizeChanged { popoverSizePx = it }
                    .width(currentWidthDp.dp)
                    .heightIn(max = 680.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = surfacePalette.activePillBackground,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Tune,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = currentConfig.toolType.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isExpandedFullStudio) "Teljes stúdió" else "Gyors beállítások",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        val nextTarget = if (isExpandedFullStudio) {
                                            POPOVER_COMPACT_WIDTH_DP
                                        } else {
                                            POPOVER_FULL_WIDTH_DP
                                        }
                                        coroutineScope.launch {
                                            widthAnimatable.animateTo(
                                                nextTarget,
                                                spring(dampingRatio = 0.85f, stiffness = 420f)
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = surfacePalette.groupedCardBackground
                                ) {
                                    Text(
                                        text = if (isExpandedFullStudio) "Kevesebb" else "Teljes",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                                Surface(
                                    onClick = onDismiss,
                                    shape = CircleShape,
                                    color = surfacePalette.groupedCardBackground,
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Bezárás",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Compact Live Stroke Preview
                        LiveBrushPreviewCard(config = currentConfig)

                        if (currentConfig.toolType.isEraser) {
                            StudioSectionCard {
                                SettingSliderHeader(
                                    title = "Radír sugár",
                                    valueBadge = "${currentConfig.eraserRadiusDp.roundToInt()} dp"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    QUICK_ERASER_RADII_DP.forEach { radiusPreset ->
                                        val selected = kotlin.math.abs(currentConfig.eraserRadiusDp - radiusPreset) < 2f
                                        Surface(
                                            onClick = { onConfigChange(currentConfig.copy(eraserRadiusDp = radiusPreset)) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) {
                                                surfacePalette.activePillBackground
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                            border = if (selected) {
                                                BorderStroke(1.25.dp, MaterialTheme.colorScheme.primary)
                                            } else null,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "${radiusPreset.toInt()}dp",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                                Slider(
                                    value = currentConfig.eraserRadiusDp,
                                    onValueChange = { next ->
                                        onConfigChange(currentConfig.copy(eraserRadiusDp = next))
                                    },
                                    valueRange = 8f..48f
                                )
                            }
                        } else if (currentConfig.toolType == BrushToolType.LASSO) {
                            StudioSectionCard {
                                Text(
                                    text = "Lasszó kijelölő",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Karikázd be a kézírást vagy ábrát a mozgatáshoz, másoláshoz vagy azonnali ✨ AI formázáshoz.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // Stage 1 Essential 1: Quick Thickness Pills + Slider
                            StudioSectionCard {
                                SettingSliderHeader(
                                    title = "Vastagság",
                                    valueBadge = "${"%.1f".format(currentConfig.clampedSizePt)} pt"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val sizePresets = if (currentConfig.toolType.isHighlighter) {
                                        QUICK_HIGHLIGHTER_SIZE_PRESETS_PT
                                    } else {
                                        QUICK_SIZE_PRESETS_PT
                                    }
                                    sizePresets.forEach { presetPt ->
                                        val isSelected = kotlin.math.abs(currentConfig.clampedSizePt - presetPt) < 0.25f
                                        Surface(
                                            onClick = { onConfigChange(currentConfig.copy(sizePt = presetPt)) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) {
                                                surfacePalette.activePillBackground
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                            border = if (isSelected) {
                                                BorderStroke(1.25.dp, MaterialTheme.colorScheme.primary)
                                            } else null,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size((presetPt * 0.65f + 3f).coerceIn(4f, 12f).dp)
                                                        .clip(CircleShape)
                                                        .background(Color(currentConfig.effectiveColorArgb()))
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (presetPt % 1f == 0f) "${presetPt.toInt()}" else "$presetPt",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                                Slider(
                                    value = currentConfig.clampedSizePt,
                                    onValueChange = { nextSize ->
                                        onConfigChange(currentConfig.copy(sizePt = nextSize))
                                    },
                                    valueRange = BrushConfig.MIN_BRUSH_SIZE_PT..BrushConfig.MAX_BRUSH_SIZE_PT
                                )
                            }

                            // Stage 1 Essential 2: Quick Colors (+ Stage 2 HSV Wheel & HEX when expanded)
                            StudioSectionCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Palette,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Színek",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface
                                    ) {
                                        Text(
                                            text = formatRgbHex(currentConfig.colorArgb),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    QUICK_COLOR_PALETTE.forEach { argb ->
                                        val selected = (currentConfig.colorArgb and 0x00FFFFFF) == (argb and 0x00FFFFFF)
                                        val c = Color(argb)
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .border(
                                                    width = if (selected) 2.5.dp else 1.dp,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.outlineVariant
                                                    },
                                                    shape = CircleShape
                                                )
                                                .clickable { onConfigChange(currentConfig.copy(colorArgb = argb)) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = if (luminanceOf(c) > 0.55f) Color.Black else Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (showFullSections) {
                                    Column(
                                        modifier = Modifier.progressiveStudioReveal(stage2RevealFraction),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            HsvColorWheel(
                                                hue = hue,
                                                saturation = saturation,
                                                value = value,
                                                onHueSaturationChange = { nextH, nextS ->
                                                    hue = nextH
                                                    saturation = nextS
                                                    emitHsvColor(nextH, nextS, value)
                                                },
                                                modifier = Modifier.size(128.dp)
                                            )

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                SettingSliderHeader(
                                                    title = "Fényerő",
                                                    valueBadge = "${(value * 100f).roundToInt()}%"
                                                )
                                                Slider(
                                                    value = value,
                                                    onValueChange = { nextV ->
                                                        value = nextV
                                                        emitHsvColor(hue, saturation, nextV)
                                                    },
                                                    valueRange = 0.05f..1.0f
                                                )
                                                OutlinedTextField(
                                                    value = hexText,
                                                    onValueChange = { candidate ->
                                                        hexText = candidate
                                                        parseHexColor(candidate)?.let { parsedArgb ->
                                                            onConfigChange(currentConfig.copy(colorArgb = parsedArgb))
                                                        }
                                                    },
                                                    label = { Text("HEX") },
                                                    singleLine = true,
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Stage 2 Only: Opacity + 1-Euro Stabilization + Presets + Pressure Curve + Hold-to-Snap
                            if (showFullSections) {
                                Column(
                                    modifier = Modifier.progressiveStudioReveal(stage2RevealFraction),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                StudioSectionCard {
                                    if (currentConfig.toolType == BrushToolType.HIGHLIGHTER) {
                                        SettingSliderHeader(
                                            title = "Átlátszóság",
                                            valueBadge = "35% fix"
                                        )
                                        Text(
                                            text = "Önátfedés-mentes kiemelő mód aktív.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        SettingSliderHeader(
                                            title = "Átlátszóság",
                                            valueBadge = "${(currentConfig.effectiveOpacity() * 100f).roundToInt()}%"
                                        )
                                        Slider(
                                            value = currentConfig.opacity.coerceIn(BrushConfig.MIN_OPACITY, 1f),
                                            onValueChange = { nextOpacity ->
                                                onConfigChange(currentConfig.copy(opacity = nextOpacity))
                                            },
                                            valueRange = BrushConfig.MIN_OPACITY..1.0f
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                    val stabInt = currentConfig.clampedStabilizationPercent.roundToInt()
                                    SettingSliderHeader(
                                        title = "1€ Stabilizálás",
                                        valueBadge = if (stabInt == 0) "0% (min. késleltetés)" else "$stabInt%"
                                    )
                                    Slider(
                                        value = currentConfig.clampedStabilizationPercent,
                                        onValueChange = { nextStab ->
                                            onConfigChange(currentConfig.copy(stabilizationPercent = nextStab))
                                        },
                                        valueRange = 0f..100f
                                    )
                                }

                                StudioSectionCard {
                                    Text(
                                        text = "Mentett kedvenc ecsetek",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        presets.forEachIndexed { index, preset ->
                                            val swatchColor = Color(preset.effectiveColorArgb())
                                            val isMatching = preset.toolType == currentConfig.toolType &&
                                                preset.colorArgb == currentConfig.colorArgb &&
                                                kotlin.math.abs(preset.sizePt - currentConfig.sizePt) < 0.25f

                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .border(
                                                        width = if (isMatching) 2.dp else 1.dp,
                                                        color = if (isMatching) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                        },
                                                        shape = RoundedCornerShape(12.dp)
                                                    ),
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (isMatching) {
                                                    surfacePalette.activePillBackground
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                }
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .clickable { onApplyPreset(preset) }
                                                        .padding(vertical = 6.dp, horizontal = 3.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(22.dp)
                                                            .clip(CircleShape)
                                                            .background(swatchColor),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size((preset.clampedSizePt * 0.4f).coerceIn(3f, 10f).dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                    if (luminanceOf(swatchColor) > 0.55f) {
                                                                        Color.Black.copy(alpha = 0.7f)
                                                                    } else {
                                                                        Color.White.copy(alpha = 0.9f)
                                                                    }
                                                                )
                                                        )
                                                    }
                                                    Text(
                                                        text = "${"%.1f".format(preset.clampedSizePt)}p",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    IconButton(
                                                        onClick = { onSavePresetSlot(index, currentConfig) },
                                                        modifier = Modifier.size(20.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Bookmark,
                                                            contentDescription = "Mentés ${index + 1}. helyre",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                StudioSectionCard {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Gesture,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Nyomásgörbe",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        PressureCurve.entries.forEach { curve ->
                                            val selected = currentConfig.pressureCurve == curve
                                            Surface(
                                                onClick = {
                                                    onConfigChange(currentConfig.copy(pressureCurve = curve))
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (selected) {
                                                    surfacePalette.activePillBackground
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .border(
                                                        width = if (selected) 1.5.dp else 1.dp,
                                                        color = if (selected) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                        },
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    val curveColor = if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                    Canvas(modifier = Modifier.size(width = 34.dp, height = 18.dp)) {
                                                        val p = Path()
                                                        val steps = 16
                                                        for (i in 0..steps) {
                                                            val t = i / steps.toFloat()
                                                            val yNorm = curve.apply(t)
                                                            val x = t * size.width
                                                            val y = (1f - yNorm) * size.height
                                                            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                                                        }
                                                        drawPath(
                                                            path = p,
                                                            color = curveColor,
                                                            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                                                        )
                                                    }
                                                    Text(
                                                        text = curve.displayName,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = curveColor
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Hold-to-Snap alakzatok",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "Vonás végén 300 ms tartással egyenes, kör vagy nyíl",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = currentConfig.holdToSnapEnabled,
                                            onCheckedChange = { enabled ->
                                                onConfigChange(currentConfig.copy(holdToSnapEnabled = enabled))
                                            }
                                        )
                                    }
                                }
                                }
                            }
                        }
                    }

                    // Dynamic outer-bottom corner grip positioned according to opening direction.
                    val gripStrokeColor = if (isExpandedFullStudio) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    }
                    Box(
                        modifier = Modifier
                            .align(if (expandsRightward) Alignment.BottomEnd else Alignment.BottomStart)
                            .padding(
                                start = if (expandsRightward) 0.dp else 6.dp,
                                end = if (expandsRightward) 6.dp else 0.dp,
                                bottom = 6.dp
                            )
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(expandsRightward) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragStartExpanded =
                                            widthAnimatable.value >= (POPOVER_COMPACT_WIDTH_DP + POPOVER_FULL_WIDTH_DP) * 0.5f
                                        coroutineScope.launch { widthAnimatable.stop() }
                                    },
                                    onDragCancel = {
                                        val frac = ((widthAnimatable.value - POPOVER_COMPACT_WIDTH_DP) /
                                            (POPOVER_FULL_WIDTH_DP - POPOVER_COMPACT_WIDTH_DP)).coerceIn(0f, 1f)
                                        val targetDp = if (frac >= 0.5f) POPOVER_FULL_WIDTH_DP else POPOVER_COMPACT_WIDTH_DP
                                        coroutineScope.launch {
                                            widthAnimatable.animateTo(
                                                targetDp,
                                                spring(dampingRatio = 0.85f, stiffness = 420f)
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        val frac = ((widthAnimatable.value - POPOVER_COMPACT_WIDTH_DP) /
                                            (POPOVER_FULL_WIDTH_DP - POPOVER_COMPACT_WIDTH_DP)).coerceIn(0f, 1f)
                                        val targetDp = if (dragStartExpanded) {
                                            if (frac <= 0.72f) POPOVER_COMPACT_WIDTH_DP else POPOVER_FULL_WIDTH_DP
                                        } else {
                                            if (frac >= 0.28f) POPOVER_FULL_WIDTH_DP else POPOVER_COMPACT_WIDTH_DP
                                        }
                                        coroutineScope.launch {
                                            widthAnimatable.animateTo(
                                                targetDp,
                                                spring(dampingRatio = 0.85f, stiffness = 420f)
                                            )
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val outwardPx = if (expandsRightward) dragAmount.x else -dragAmount.x
                                        val deltaDp = with(density) { outwardPx.toDp().value }
                                        val nextDp = (widthAnimatable.value + deltaDp)
                                            .coerceIn(POPOVER_COMPACT_WIDTH_DP, POPOVER_FULL_WIDTH_DP)
                                        coroutineScope.launch { widthAnimatable.snapTo(nextDp) }
                                    }
                                )
                            }
                            .clickable(
                                onClickLabel = if (isExpandedFullStudio) "Kompakt nézet" else "Teljes funkciók"
                            ) {
                                val nextTarget = if (isExpandedFullStudio) {
                                    POPOVER_COMPACT_WIDTH_DP
                                } else {
                                    POPOVER_FULL_WIDTH_DP
                                }
                                coroutineScope.launch {
                                    widthAnimatable.animateTo(
                                        nextTarget,
                                        spring(dampingRatio = 0.85f, stiffness = 420f)
                                    )
                                }
                            }
                            .padding(4.dp),
                        contentAlignment = if (expandsRightward) Alignment.BottomEnd else Alignment.BottomStart
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokePx = 1.8.dp.toPx()
                            val w = size.width
                            val h = size.height
                            if (expandsRightward) {
                                // Bottom-right corner grip lines
                                drawLine(
                                    color = gripStrokeColor,
                                    start = Offset(w * 0.10f, h * 0.90f),
                                    end = Offset(w * 0.90f, h * 0.10f),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = gripStrokeColor,
                                    start = Offset(w * 0.48f, h * 0.90f),
                                    end = Offset(w * 0.90f, h * 0.48f),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                            } else {
                                // Bottom-left corner grip lines
                                drawLine(
                                    color = gripStrokeColor,
                                    start = Offset(w * 0.10f, h * 0.10f),
                                    end = Offset(w * 0.90f, h * 0.90f),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = gripStrokeColor,
                                    start = Offset(w * 0.10f, h * 0.48f),
                                    end = Offset(w * 0.52f, h * 0.90f),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioSectionCard(
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingSliderHeader(
    title: String,
    valueBadge: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = valueBadge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun LiveBrushPreviewCard(config: BrushConfig) {
    val previewInkColor = Color(config.effectiveColorArgb()).copy(alpha = config.effectiveOpacity())
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Subtle dotted paper grid behind the preview stroke
            val stepPx = 14.dp.toPx()
            var y = stepPx * 0.6f
            while (y < size.height) {
                var x = stepPx * 0.6f
                while (x < size.width) {
                    drawCircle(color = gridColor, radius = 1.1f, center = Offset(x, y))
                    x += stepPx
                }
                y += stepPx
            }

            if (config.toolType.isEraser) {
                drawCircle(
                    color = Color(0xFF94A3B8).copy(alpha = 0.25f),
                    radius = config.eraserRadiusDp.dp.toPx().coerceAtMost(size.height * 0.42f),
                    center = Offset(size.width * 0.5f, size.height * 0.5f)
                )
                drawCircle(
                    color = Color(0xFF64748B),
                    radius = config.eraserRadiusDp.dp.toPx().coerceAtMost(size.height * 0.42f),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    style = Stroke(width = 2.dp.toPx())
                )
            } else {
                val padX = 24.dp.toPx()
                val usableW = (size.width - padX * 2f).coerceAtLeast(40f)
                val centerY = size.height * 0.5f
                val ampY = size.height * 0.24f
                val segments = 32
                val baseWidthPx = (config.clampedSizePt * 1.35f).dp.toPx().coerceIn(2f, size.height * 0.52f)

                var prevX = padX
                var prevY = centerY
                for (i in 1..segments) {
                    val t = i / segments.toFloat()
                    val x = padX + usableW * t
                    val curY = centerY - sin(t * 2f * PI.toFloat()) * ampY
                    val simulatedPressure = (0.28f + 0.72f * sin(t * PI.toFloat())).coerceIn(0.15f, 1f)
                    val widthFactor = if (config.toolType == BrushToolType.HIGHLIGHTER) {
                        1f
                    } else {
                        0.45f + 0.65f * config.pressureCurve.apply(simulatedPressure)
                    }
                    drawLine(
                        color = previewInkColor,
                        start = Offset(prevX, prevY),
                        end = Offset(x, curY),
                        strokeWidth = baseWidthPx * widthFactor,
                        cap = if (config.toolType == BrushToolType.HIGHLIGHTER) {
                            StrokeCap.Square
                        } else {
                            StrokeCap.Round
                        }
                    )
                    prevX = x
                    prevY = curY
                }
            }
        }
    }
}

@Composable
private fun HsvColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onHueSaturationChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    fun updateFromOffset(offset: Offset, width: Float, height: Float) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = kotlin.math.min(cx, cy).coerceAtLeast(1f)
        val dx = offset.x - cx
        val dy = offset.y - cy
        val dist = hypot(dx, dy).coerceIn(0f, radius)
        val nextSat = (dist / radius).coerceIn(0f, 1f)
        var angleDeg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
        if (angleDeg < 0f) angleDeg += 360f
        onHueSaturationChange(angleDeg, nextSat)
    }

    val sweepColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red
        )
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    updateFromOffset(offset, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    updateFromOffset(change.position, size.width.toFloat(), size.height.toFloat())
                }
            }
    ) {
        val radius = size.minDimension * 0.5f
        val center = Offset(size.width * 0.5f, size.height * 0.5f)

        drawCircle(
            brush = Brush.sweepGradient(colors = sweepColors, center = center),
            radius = radius,
            center = center
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
        if (value < 0.999f) {
            drawCircle(
                color = Color.Black.copy(alpha = (1f - value).coerceIn(0f, 0.92f)),
                radius = radius,
                center = center
            )
        }
        drawCircle(
            color = Color.Black.copy(alpha = 0.18f),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5f)
        )

        val rad = (hue * PI / 180.0).toFloat()
        val indicatorRadius = radius * saturation.coerceIn(0f, 1f)
        val knob = Offset(
            x = center.x + cos(rad) * indicatorRadius,
            y = center.y + sin(rad) * indicatorRadius
        )
        drawCircle(color = Color.White, radius = 9f, center = knob)
        drawCircle(color = Color.Black, radius = 9f, center = knob, style = Stroke(width = 2f))
    }
}

private fun formatRgbHex(argb: Int): String =
    String.format("#%06X", argb and 0x00FFFFFF)

private fun parseHexColor(text: String): Int? {
    val clean = text.trim().removePrefix("#")
    if (clean.length != 6) return null
    val rgb = clean.toIntOrNull(16) ?: return null
    return (0xFF shl 24) or rgb
}

private fun luminanceOf(color: Color): Float =
    0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

