package hu.bdev.stylusnote.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
                sizePt = 2.5f,
                colorArgb = 0xFF14171C.toInt(),
                opacity = 1.0f,
                stabilizationPercent = 25f
            ),
            BrushConfig(
                toolType = BrushToolType.BALLPOINT_PEN,
                sizePt = 1.8f,
                colorArgb = 0xFF1A56DB.toInt(),
                opacity = 1.0f,
                stabilizationPercent = 15f
            ),
            BrushConfig(
                toolType = BrushToolType.PENCIL,
                sizePt = 3.0f,
                colorArgb = 0xFF334155.toInt(),
                opacity = 0.85f,
                stabilizationPercent = 20f
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
                sizePt = 2.8f,
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

private val QUICK_SIZE_PRESETS_PT = listOf(1.5f, 2.5f, 5.0f, 12.0f)

/**
 * Floating brush & color configuration studio popover (Section 5.4).
 *
 * Includes a live variable-width stroke preview, 6 DataStore preset cards, quick color palette +
 * HSV wheel + HEX input, quick size dots + thickness slider (0.5–40 pt), opacity (10–100%),
 * 1€ stabilization (0–100%), graphical pressure curve cards, and Hold-to-Snap toggle.
 * Supports closing via both ESC key and backdrop click (UX Rule 11).
 */
@Composable
fun BrushSettingsPopover(
    currentConfig: BrushConfig,
    presets: List<BrushConfig>,
    onConfigChange: (BrushConfig) -> Unit,
    onSavePresetSlot: (Int, BrushConfig) -> Unit,
    onApplyPreset: (BrushConfig) -> Unit,
    onDismiss: () -> Unit
) {
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
            usePlatformDefaultWidth = false
        )
    ) {
        // Full-viewport backdrop ensures backdrop clicks & ESC dismiss cleanly outside the studio card.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
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
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 460.dp)
                    .padding(20.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(28.dp)
                    )
                    // Consume taps on the card so clicks inside do not bubble up to the backdrop.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = currentConfig.toolType.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (currentConfig.toolType.isEraser) {
                                        "Radír sugara és előnézet"
                                    } else {
                                        "Élő vonás-előnézet és színstúdió"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Surface(
                            onClick = onDismiss,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Bezárás",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Live Stroke Preview Canvas
                    LiveBrushPreviewCard(config = currentConfig)

                    // 6 Saved Preset Cards
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                            shape = RoundedCornerShape(14.dp)
                                        ),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isMatching) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .clickable { onApplyPreset(preset) }
                                            .padding(vertical = 7.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(swatchColor)
                                                .border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size((preset.clampedSizePt * 0.45f).coerceIn(3f, 12f).dp)
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
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        IconButton(
                                            onClick = { onSavePresetSlot(index, currentConfig) },
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Bookmark,
                                                contentDescription = "Mentés ${index + 1}. helyre",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (currentConfig.toolType.isEraser) {
                        StudioSectionCard {
                            SettingSliderHeader(
                                title = "Radír sugár",
                                valueBadge = "${currentConfig.eraserRadiusDp.roundToInt()} dp"
                            )
                            Slider(
                                value = currentConfig.eraserRadiusDp,
                                onValueChange = { next ->
                                    onConfigChange(currentConfig.copy(eraserRadiusDp = next))
                                },
                                valueRange = 8f..48f
                            )
                        }
                    } else {
                        // Thickness card with quick-size visual dots + slider
                        StudioSectionCard {
                            SettingSliderHeader(
                                title = "Vastagság",
                                valueBadge = "${"%.1f".format(currentConfig.clampedSizePt)} pt"
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QUICK_SIZE_PRESETS_PT.forEach { presetPt ->
                                    val isSelected = kotlin.math.abs(currentConfig.clampedSizePt - presetPt) < 0.25f
                                    Surface(
                                        onClick = { onConfigChange(currentConfig.copy(sizePt = presetPt)) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size((presetPt * 0.7f + 3f).coerceIn(4f, 13f).dp)
                                                    .clip(CircleShape)
                                                    .background(Color(currentConfig.effectiveColorArgb()))
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
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

                        // Color Palette + HSV Wheel + HEX Card
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
                                        text = "Színpaletta",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(currentConfig.effectiveColorArgb()))
                                        )
                                        Text(
                                            text = formatRgbHex(currentConfig.colorArgb),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            // 8 Curated Quick Color Swatches
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                QUICK_COLOR_PALETTE.forEach { argb ->
                                    val selected = (currentConfig.colorArgb and 0x00FFFFFF) == (argb and 0x00FFFFFF)
                                    val c = Color(argb)
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
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
                                                modifier = Modifier.size(15.dp)
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
                                    modifier = Modifier.size(132.dp)
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
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // Opacity & 1-Euro Stabilization Card
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

                            SettingSliderHeader(
                                title = "1€ Stabilizálás",
                                valueBadge = "${currentConfig.clampedStabilizationPercent.roundToInt()}%"
                            )
                            Slider(
                                value = currentConfig.clampedStabilizationPercent,
                                onValueChange = { nextStab ->
                                    onConfigChange(currentConfig.copy(stabilizationPercent = nextStab))
                                },
                                valueRange = 0f..100f
                            )
                        }

                        // Pressure Curve Cards + Hold-to-Snap Card
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
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
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
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            val curveColor = if (selected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                            Canvas(modifier = Modifier.size(width = 36.dp, height = 20.dp)) {
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

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kész", fontWeight = FontWeight.SemiBold)
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

