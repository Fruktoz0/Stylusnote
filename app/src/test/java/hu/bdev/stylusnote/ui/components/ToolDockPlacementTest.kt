package hu.bdev.stylusnote.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDockPlacementTest {

    @Test
    fun dockStartingOverTopLeftToolbarIsAutomaticallyDisplacedOutOfOverlap() {
        val toolbarBounds = Rect(left = 10f, top = 8f, right = 460f, bottom = 72f)
        val dockWidth = 520
        val dockHeight = 64

        val origin = clampDockOriginPx(
            desiredLeftPx = 24f,
            desiredTopPx = 12f,
            dockWidthPx = dockWidth,
            dockHeightPx = dockHeight,
            viewportWidthPx = 2560,
            viewportHeightPx = 1600,
            avoidRectPx = toolbarBounds
        )

        val resolvedRect = Rect(
            left = origin.x.toFloat(),
            top = origin.y.toFloat(),
            right = (origin.x + dockWidth).toFloat(),
            bottom = (origin.y + dockHeight).toFloat()
        )
        assertFalse(resolvedRect.overlaps(toolbarBounds))
        assertTrue(resolvedRect.left >= toolbarBounds.right || resolvedRect.top >= toolbarBounds.bottom)
    }

    @Test
    fun dockDropsBelowToolbarWhenViewportTooNarrowToPlaceRightOfToolbar() {
        val toolbarBounds = Rect(left = 10f, top = 8f, right = 420f, bottom = 72f)
        val dockWidth = 380
        val dockHeight = 60

        val origin = clampDockOriginPx(
            desiredLeftPx = 40f,
            desiredTopPx = 16f,
            dockWidthPx = dockWidth,
            dockHeightPx = dockHeight,
            viewportWidthPx = 640,
            viewportHeightPx = 960,
            avoidRectPx = toolbarBounds
        )

        val resolvedRect = Rect(
            left = origin.x.toFloat(),
            top = origin.y.toFloat(),
            right = (origin.x + dockWidth).toFloat(),
            bottom = (origin.y + dockHeight).toFloat()
        )
        assertFalse(resolvedRect.overlaps(toolbarBounds))
        assertTrue(origin.y >= 72)
    }

    @Test
    fun dockStaysWithinViewportMarginBounds() {
        val origin = clampDockOriginPx(
            desiredLeftPx = 4000f,
            desiredTopPx = -200f,
            dockWidthPx = 300,
            dockHeightPx = 64,
            viewportWidthPx = 1920,
            viewportHeightPx = 1200,
            avoidRectPx = Rect.Zero
        )
        assertTrue(origin.x <= 1920 - 300 - 10)
        assertTrue(origin.y >= 10)
    }

    @Test
    fun perToolConfigsPreserveIndependentSizesAndColorsAcrossToolSwitches() {
        val initial = ToolDockPreferencesStore.defaultPerToolConfigsMap().toMutableMap()
        initial[BrushToolType.FOUNTAIN_PEN] = BrushConfig(
            toolType = BrushToolType.FOUNTAIN_PEN,
            sizePt = 1.0f,
            colorArgb = 0xFF1A56DB.toInt()
        )
        initial[BrushToolType.HIGHLIGHTER] = BrushConfig(
            toolType = BrushToolType.HIGHLIGHTER,
            sizePt = 22.0f,
            colorArgb = 0xFF34D399.toInt(),
            opacity = BrushConfig.HIGHLIGHTER_OPACITY
        )
        initial[BrushToolType.PRECISION_ERASER] = BrushConfig(
            toolType = BrushToolType.PRECISION_ERASER,
            eraserRadiusDp = 32f
        )

        val encoded = ToolDockPreferencesStore.encodePerToolConfigs(initial)
        val restored = ToolDockPreferencesStore.decodePerToolConfigs(encoded)

        assertEquals(1.0f, restored[BrushToolType.FOUNTAIN_PEN]?.sizePt)
        assertEquals(0xFF1A56DB.toInt(), restored[BrushToolType.FOUNTAIN_PEN]?.colorArgb)
        assertEquals(22.0f, restored[BrushToolType.HIGHLIGHTER]?.sizePt)
        assertEquals(0xFF34D399.toInt(), restored[BrushToolType.HIGHLIGHTER]?.colorArgb)
        assertEquals(32f, restored[BrushToolType.PRECISION_ERASER]?.eraserRadiusDp)
    }

    @Test
    fun anchoredPopoverPlacesDirectlyBelowTopHorizontalDockAndStaysInViewport() {
        val dockBounds = Rect(left = 900f, top = 16f, right = 1700f, bottom = 76f)
        val popoverSize = IntSize(width = 310, height = 340)
        val viewport = IntSize(width = 2560, height = 1600)

        val offset = computeAnchoredPopoverOffsetPx(
            anchorBoundsPx = dockBounds,
            popoverSizePx = popoverSize,
            viewportSize = viewport,
            marginPx = 12,
            gapPx = 10
        )

        assertTrue(offset.y >= dockBounds.bottom.toInt())
        assertTrue(offset.x >= 12 && offset.x + popoverSize.width <= viewport.width - 12)
        assertTrue(offset.y + popoverSize.height <= viewport.height - 12)
    }
}
