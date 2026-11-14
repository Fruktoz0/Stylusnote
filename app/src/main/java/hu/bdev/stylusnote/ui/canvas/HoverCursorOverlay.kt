package hu.bdev.stylusnote.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import hu.bdev.stylusnote.core.hardware.HoverCursorState
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix

/**
 * 1 px outline hover preview rendered directly in the Compose draw phase (Section 4).
 *
 * Reads [hoverTick] and [hoverStateProvider] solely inside the `Canvas` draw block so
 * 240 Hz `ACTION_HOVER_MOVE` events never trigger Compose recomposition.
 */
@Composable
fun HoverCursorOverlay(
    hoverStateProvider: () -> HoverCursorState,
    hoverTick: State<Long>,
    transformMatrix: CanvasTransformMatrix,
    brushSizePt: Float,
    brushColor: Color,
    isEraserMode: Boolean,
    eraserRadiusPx: Float = 24f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // Subscribe only the draw phase to hover movement ticks.
        hoverTick.value
        val state = hoverStateProvider()
        if (!state.isVisible) return@Canvas

        val screenCenter = transformMatrix.docToScreen(state.docXPt, state.docYPt)
        val eraserActive = isEraserMode || state.isEraserTool
        val radiusPx = if (eraserActive) {
            eraserRadiusPx.coerceAtLeast(6f)
        } else {
            (transformMatrix.docSizeToScreenPx(brushSizePt) * 0.5f).coerceAtLeast(2f)
        }

        val ringColor = if (eraserActive) {
            Color(0xFFEF4444).copy(alpha = 0.85f)
        } else {
            brushColor.copy(alpha = 0.85f)
        }

        drawCircle(
            color = ringColor,
            radius = radiusPx,
            center = Offset(screenCenter.xPx, screenCenter.yPx),
            style = Stroke(width = 1f)
        )
    }
}

