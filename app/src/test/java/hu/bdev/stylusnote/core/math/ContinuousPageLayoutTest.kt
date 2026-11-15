package hu.bdev.stylusnote.core.math

import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousPageLayoutTest {

    @Test
    fun homogeneousNineA4PagesStackAtZeroLeftWithExactGap() {
        val entries = (0 until 9).map { idx ->
            "page-$idx" to PagePaperConfig(
                canvasMode = CanvasMode.FIXED_PAGE,
                pageDimensions = PageDimensionsPt.A4_PORTRAIT,
                templateType = PaperTemplateType.RULED_8MM
            )
        }

        val layout = ContinuousPageLayout.buildFromConfigs(entries, gapPt = 24f)

        assertEquals(9, layout.pageCount)
        val a4W = PageDimensionsPt.A4_PORTRAIT.widthPt
        val a4H = PageDimensionsPt.A4_PORTRAIT.heightPt

        val slot0 = layout.slotAt(0)!!
        assertEquals(0f, slot0.leftPt, 0.001f)
        assertEquals(0f, slot0.topPt, 0.001f)
        assertEquals(a4W, slot0.rightPt, 0.001f)
        assertEquals(a4H, slot0.bottomPt, 0.001f)

        val slot8 = layout.slotAt(8)!!
        val expectedTop8 = 8 * (a4H + 24f)
        assertEquals(0f, slot8.leftPt, 0.001f)
        assertEquals(expectedTop8, slot8.topPt, 0.001f)
        assertEquals(expectedTop8 + a4H, slot8.bottomPt, 0.001f)
        assertEquals(slot8.bottomPt, layout.totalBoundsPt.bottomPt, 0.001f)
    }

    @Test
    fun mixedPageSizesHorizontallyCenterNarrowerSheets() {
        val layout = ContinuousPageLayout.buildFromConfigs(
            listOf(
                "p-b5" to PagePaperConfig(pageDimensions = PageDimensionsPt.B5_ISO_PORTRAIT),
                "p-wide" to PagePaperConfig(pageDimensions = PageDimensionsPt.LANDSCAPE_16_10),
                "p-a4" to PagePaperConfig(pageDimensions = PageDimensionsPt.A4_PORTRAIT)
            ),
            gapPt = 20f
        )

        val wideSlot = layout.slotForPageId("p-wide")!!
        val b5Slot = layout.slotForPageId("p-b5")!!
        val a4Slot = layout.slotForPageId("p-a4")!!

        assertEquals(0f, wideSlot.leftPt, 0.001f)
        assertEquals(PageDimensionsPt.LANDSCAPE_16_10.widthPt, wideSlot.rightPt, 0.001f)
        assertEquals(wideSlot.centerXPt, b5Slot.centerXPt, 0.001f)
        assertEquals(wideSlot.centerXPt, a4Slot.centerXPt, 0.001f)
        assertTrue(b5Slot.leftPt > 0f)
        assertTrue(a4Slot.leftPt > 0f)
    }

    @Test
    fun visibilityCullingAndNearestCenterSlotOperateAcrossNinePages() {
        val entries = (0 until 9).map { idx ->
            "page-$idx" to PagePaperConfig(pageDimensions = PageDimensionsPt.A4_PORTRAIT)
        }
        val layout = ContinuousPageLayout.buildFromConfigs(entries, gapPt = 24f)
        val slot3 = layout.slotAt(3)!!
        val slot4 = layout.slotAt(4)!!

        // Viewport spanning bottom of page 3 through top of page 4 must return exactly slots 3 & 4.
        val visible = layout.resolveVisibleSlots(
            DocRect(
                leftPt = -20f,
                topPt = slot3.bottomPt - 100f,
                rightPt = 620f,
                bottomPt = slot4.topPt + 120f
            )
        )
        assertEquals(listOf(3, 4), visible.map { it.pageIndex })

        val nearestPage4 = layout.findNearestSlotForViewportCenter(slot4.centerYPt)
        assertEquals("page-4", nearestPage4?.pageId)

        // Point inside gap closer to slot4 top than slot3 bottom resolves to slot4.
        val gapCloserTo4 = slot4.topPt - 6f
        assertEquals("page-4", layout.findNearestSlotForViewportCenter(gapCloserTo4)?.pageId)
    }

    @Test
    fun strokeHitTestSnapsAcrossInterPageGapAndConvertsCoordinatesBidirectionally() {
        val layout = ContinuousPageLayout.buildFromConfigs(
            listOf(
                "p0" to PagePaperConfig(pageDimensions = PageDimensionsPt.A4_PORTRAIT),
                "p1" to PagePaperConfig(pageDimensions = PageDimensionsPt.B5_ISO_PORTRAIT)
            ),
            gapPt = 24f
        )

        val p1 = layout.slotAt(1)!!
        val insideP1Workspace = p1.localToWorkspacePoint(localXPt = 120f, localYPt = 250f)
        val hitP1 = layout.findSlotAtWorkspacePoint(insideP1Workspace.xPt, insideP1Workspace.yPt)
        assertNotNull(hitP1)
        assertEquals("p1", hitP1?.pageId)

        val roundTrip = hitP1!!.workspaceToLocalPoint(insideP1Workspace.xPt, insideP1Workspace.yPt)
        assertEquals(120f, roundTrip.xPt, 0.001f)
        assertEquals(250f, roundTrip.yPt, 0.001f)

        // Tap inside gap 5 pt above p1 top must snap to p1 (even with 24 pt gap).
        val nearTopGapHit = layout.findSlotAtWorkspacePoint(
            workspaceXPt = p1.centerXPt,
            workspaceYPt = p1.topPt - 5f,
            maxSnapDistancePt = 20f
        )
        assertEquals("p1", nearTopGapHit?.pageId)

        // Tap far out in void (> snap margin) must return null.
        val voidHit = layout.findSlotAtWorkspacePoint(
            workspaceXPt = -150f,
            workspaceYPt = p1.centerYPt,
            maxSnapDistancePt = 20f
        )
        assertNull(voidHit)
    }

    @Test
    fun computeCameraPanCentersMixedSlotHorizontallyAndRespectsTopPadding() {
        val layout = ContinuousPageLayout.buildFromConfigs(
            listOf(
                "p-wide" to PagePaperConfig(pageDimensions = PageDimensionsPt.LANDSCAPE_16_10),
                "p-b5" to PagePaperConfig(pageDimensions = PageDimensionsPt.B5_ISO_PORTRAIT),
                "p-a4" to PagePaperConfig(pageDimensions = PageDimensionsPt.A4_PORTRAIT)
            ),
            gapPt = 24f
        )
        val b5Slot = layout.slotForPageId("p-b5")!!
        val scale = 2.0f
        val (panX, panY) = layout.computeCameraPanForSlot(
            slot = b5Slot,
            scale = scale,
            viewportWidthPx = 2400f,
            viewportHeightPx = 1400f,
            verticalPaddingPx = 40f
        )
        val screenCenterX = b5Slot.centerXPt * scale + panX
        val screenTopY = b5Slot.topPt * scale + panY
        assertEquals(1200f, screenCenterX, 0.01f)
        assertEquals(40f, screenTopY, 0.01f)

        val localRect = DocRect(50f, 100f, 200f, 160f)
        val wsRect = b5Slot.localRectToWorkspaceRect(localRect)
        assertEquals(b5Slot.leftPt + 50f, wsRect.leftPt, 0.001f)
        assertEquals(b5Slot.topPt + 100f, wsRect.topPt, 0.001f)
        assertEquals(b5Slot.leftPt + 200f, wsRect.rightPt, 0.001f)
        assertEquals(b5Slot.topPt + 160f, wsRect.bottomPt, 0.001f)
    }
}
