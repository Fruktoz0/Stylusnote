package hu.bdev.stylusnote.ui.components

import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val Context.toolDockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "stylusnote_tool_dock_prefs"
)

/**
 * App-wide persisted state of [FloatingToolDock] shared uniformly across every notebook:
 * normalized viewport position, collapsed 3-button mode vs full bar, and vertical orientation.
 */
data class ToolDockPrefs(
    val xFraction: Float = 0.62f,
    val yFraction: Float = 0.02f,
    val isCollapsed: Boolean = false,
    val isVertical: Boolean = false
)

class ToolDockPreferencesStore(private val context: Context) {

    val prefsFlow: Flow<ToolDockPrefs> = context.toolDockDataStore.data.map { prefs ->
        ToolDockPrefs(
            xFraction = (prefs[KEY_X_FRACTION] ?: 0.62f).coerceIn(0f, 1f),
            yFraction = (prefs[KEY_Y_FRACTION] ?: 0.02f).coerceIn(0f, 1f),
            isCollapsed = prefs[KEY_IS_COLLAPSED] ?: false,
            isVertical = prefs[KEY_IS_VERTICAL] ?: false
        )
    }

    suspend fun savePrefs(next: ToolDockPrefs) {
        context.toolDockDataStore.edit { prefs ->
            prefs[KEY_X_FRACTION] = next.xFraction.coerceIn(0f, 1f)
            prefs[KEY_Y_FRACTION] = next.yFraction.coerceIn(0f, 1f)
            prefs[KEY_IS_COLLAPSED] = next.isCollapsed
            prefs[KEY_IS_VERTICAL] = next.isVertical
        }
    }

    private companion object {
        val KEY_X_FRACTION = floatPreferencesKey("dock_x_fraction")
        val KEY_Y_FRACTION = floatPreferencesKey("dock_y_fraction")
        val KEY_IS_COLLAPSED = booleanPreferencesKey("dock_is_collapsed")
        val KEY_IS_VERTICAL = booleanPreferencesKey("dock_is_vertical")
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
