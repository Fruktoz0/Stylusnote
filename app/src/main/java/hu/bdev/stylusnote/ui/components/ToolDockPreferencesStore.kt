package hu.bdev.stylusnote.ui.components

import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val Context.toolDockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "stylusnote_tool_dock_prefs"
)

/**
 * App-wide persisted state of [FloatingToolDock] shared uniformly across every notebook:
 * normalized viewport position, collapsed 3-button mode vs full bar, vertical orientation,
 * left pull-tab Y fraction, and per-tool independent brush/eraser configurations.
 */
data class ToolDockPrefs(
    val xFraction: Float = 0.62f,
    val yFraction: Float = 0.02f,
    val isCollapsed: Boolean = false,
    val isVertical: Boolean = false,
    val leftTabYFraction: Float = 0.5f,
    val perToolConfigsRaw: String = ""
)

class ToolDockPreferencesStore(private val context: Context) {

    val prefsFlow: Flow<ToolDockPrefs> = context.toolDockDataStore.data.map { prefs ->
        ToolDockPrefs(
            xFraction = (prefs[KEY_X_FRACTION] ?: 0.62f).coerceIn(0f, 1f),
            yFraction = (prefs[KEY_Y_FRACTION] ?: 0.02f).coerceIn(0f, 1f),
            isCollapsed = prefs[KEY_IS_COLLAPSED] ?: false,
            isVertical = prefs[KEY_IS_VERTICAL] ?: false,
            leftTabYFraction = (prefs[KEY_LEFT_TAB_Y_FRACTION] ?: 0.5f).coerceIn(0.08f, 0.92f),
            perToolConfigsRaw = prefs[KEY_PER_TOOL_CONFIGS] ?: ""
        )
    }

    suspend fun savePrefs(next: ToolDockPrefs) {
        context.toolDockDataStore.edit { prefs ->
            prefs[KEY_X_FRACTION] = next.xFraction.coerceIn(0f, 1f)
            prefs[KEY_Y_FRACTION] = next.yFraction.coerceIn(0f, 1f)
            prefs[KEY_IS_COLLAPSED] = next.isCollapsed
            prefs[KEY_IS_VERTICAL] = next.isVertical
            prefs[KEY_LEFT_TAB_Y_FRACTION] = next.leftTabYFraction.coerceIn(0.08f, 0.92f)
            if (next.perToolConfigsRaw.isNotEmpty()) {
                prefs[KEY_PER_TOOL_CONFIGS] = next.perToolConfigsRaw
            }
        }
    }

    suspend fun saveLeftTabYFraction(fraction: Float) {
        context.toolDockDataStore.edit { prefs ->
            prefs[KEY_LEFT_TAB_Y_FRACTION] = fraction.coerceIn(0.08f, 0.92f)
        }
    }

    suspend fun savePerToolConfigs(map: Map<BrushToolType, BrushConfig>) {
        val encoded = encodePerToolConfigs(map)
        context.toolDockDataStore.edit { prefs ->
            prefs[KEY_PER_TOOL_CONFIGS] = encoded
        }
    }

    companion object {
        private val KEY_X_FRACTION = floatPreferencesKey("dock_x_fraction")
        private val KEY_Y_FRACTION = floatPreferencesKey("dock_y_fraction")
        private val KEY_IS_COLLAPSED = booleanPreferencesKey("dock_is_collapsed")
        private val KEY_IS_VERTICAL = booleanPreferencesKey("dock_is_vertical")
        private val KEY_LEFT_TAB_Y_FRACTION = floatPreferencesKey("left_tab_y_fraction")
        private val KEY_PER_TOOL_CONFIGS = stringPreferencesKey("per_tool_configs_v1")

        fun defaultToolConfig(toolType: BrushToolType): BrushConfig = when (toolType) {
            BrushToolType.FOUNTAIN_PEN -> BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 2.0f,
                colorArgb = BrushConfig.DEFAULT_INK_COLOR_ARGB,
                opacity = 1.0f,
                stabilizationPercent = BrushConfig.DEFAULT_STABILIZATION_PERCENT
            )
            BrushToolType.HIGHLIGHTER -> BrushConfig(
                toolType = BrushToolType.HIGHLIGHTER,
                sizePt = 16.0f,
                colorArgb = BrushConfig.DEFAULT_HIGHLIGHTER_COLOR_ARGB,
                opacity = BrushConfig.HIGHLIGHTER_OPACITY,
                stabilizationPercent = 40f
            )
            BrushToolType.PRECISION_ERASER -> BrushConfig(
                toolType = BrushToolType.PRECISION_ERASER,
                eraserRadiusDp = 18f
            )
            BrushToolType.VECTOR_ERASER -> BrushConfig(
                toolType = BrushToolType.VECTOR_ERASER,
                eraserRadiusDp = 24f
            )
            BrushToolType.LASSO -> BrushConfig(toolType = BrushToolType.LASSO)
            BrushToolType.BALLPOINT_PEN,
            BrushToolType.PENCIL -> BrushConfig(
                toolType = BrushToolType.FOUNTAIN_PEN,
                sizePt = 2.0f
            )
        }

        fun defaultPerToolConfigsMap(): Map<BrushToolType, BrushConfig> = linkedMapOf(
            BrushToolType.FOUNTAIN_PEN to defaultToolConfig(BrushToolType.FOUNTAIN_PEN),
            BrushToolType.HIGHLIGHTER to defaultToolConfig(BrushToolType.HIGHLIGHTER),
            BrushToolType.PRECISION_ERASER to defaultToolConfig(BrushToolType.PRECISION_ERASER),
            BrushToolType.VECTOR_ERASER to defaultToolConfig(BrushToolType.VECTOR_ERASER),
            BrushToolType.LASSO to defaultToolConfig(BrushToolType.LASSO)
        )

        fun encodePerToolConfigs(map: Map<BrushToolType, BrushConfig>): String {
            return map.values.joinToString(";") { cfg ->
                BrushPresetStore.encodePreset(cfg)
            }
        }

        fun decodePerToolConfigs(raw: String): Map<BrushToolType, BrushConfig> {
            val result = defaultPerToolConfigsMap().toMutableMap()
            if (raw.isBlank()) return result
            raw.split(";").forEach { token ->
                val decoded = BrushPresetStore.decodePreset(token)
                if (decoded != null) {
                    result[decoded.toolType] = decoded
                }
            }
            return result
        }
    }
}

/**
 * Clamps a floating dock origin inside the viewport bounds and automatically displaces it out of
 * [avoidRectPx] (e.g. the top-left [CanvasTopToolbar]) so opening a notebook never covers header
 * navigation or zoom controls.
 */
fun clampDockOriginPx(
    desiredLeftPx: Float,
    desiredTopPx: Float,
    dockWidthPx: Int,
    dockHeightPx: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    avoidRectPx: Rect,
    marginPx: Float = 10f
): IntOffset {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) {
        return IntOffset(marginPx.roundToInt(), marginPx.roundToInt())
    }
    val w = dockWidthPx.coerceAtLeast(1)
    val h = dockHeightPx.coerceAtLeast(1)
    val maxLeft = (viewportWidthPx - w - marginPx).coerceAtLeast(marginPx)
    val maxTop = (viewportHeightPx - h - marginPx).coerceAtLeast(marginPx)

    var x = desiredLeftPx.coerceIn(marginPx, maxLeft)
    var y = desiredTopPx.coerceIn(marginPx, maxTop)

    if (avoidRectPx.width > 0f && avoidRectPx.height > 0f) {
        val pad = 8f
        val paddedAvoid = Rect(
            left = avoidRectPx.left - pad,
            top = avoidRectPx.top - pad,
            right = avoidRectPx.right + pad,
            bottom = avoidRectPx.bottom + pad
        )
        val dockRect = Rect(x, y, x + w, y + h)
        if (dockRect.overlaps(paddedAvoid)) {
            val rightCandidateX = (avoidRectPx.right + pad).coerceIn(marginPx, maxLeft)
            val fitsRight = rightCandidateX >= avoidRectPx.right + pad - 1f
            if (fitsRight && !Rect(rightCandidateX, y, rightCandidateX + w, y + h).overlaps(paddedAvoid)) {
                x = rightCandidateX
            } else {
                val belowCandidateY = (avoidRectPx.bottom + pad).coerceIn(marginPx, maxTop)
                y = belowCandidateY
            }
        }
    }

    return IntOffset(x.roundToInt(), y.roundToInt())
}
