package hu.bdev.stylusnote.ui.components

import androidx.compose.ui.geometry.Rect
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
}
