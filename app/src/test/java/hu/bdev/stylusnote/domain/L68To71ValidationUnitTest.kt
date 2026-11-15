package hu.bdev.stylusnote.domain

import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.export.PdfTileRenderer
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.domain.model.ObjectAttachmentGeometry
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.ui.canvas.PageThumbnailRenderer
import hu.bdev.stylusnote.ui.canvas.resolveNextActivePageAfterDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class L68To71ValidationUnitTest {

    @Test
    fun imageAttachmentGeometryDetectsPointsInsideRotatedRectAndRespectsMargin() {
        val unrotated = PageObject.ImageObject(
            id = "img-flat",
            zOrder = 10L,
            bounds = DocRect(100f, 100f, 300f, 200f),
            imageAssetRelativePath = "pages/p1/images/flat.png",
            rotationRadians = 0f
        )
        assertTrue(
            ObjectAttachmentGeometry.isStrokeAttachedToImage(
                points = listOf(DocPoint(150f, 150f), DocPoint(250f, 160f)),
                image = unrotated
            )
        )
        // Within DEFAULT_ATTACHMENT_MARGIN_PT (2 pt) on edge remains attached.
        assertTrue(
            ObjectAttachmentGeometry.isStrokeAttachedToImage(
                points = listOf(DocPoint(98.5f, 150f)),
                image = unrotated
            )
        )
        assertFalse(
            ObjectAttachmentGeometry.isStrokeAttachedToImage(
                points = listOf(DocPoint(50f, 50f), DocPoint(90f, 150f)),
                image = unrotated
            )
        )

        // Rotated 45 degrees around center (200, 150): long axis stretches farther diagonally,
        // while points inside the unrotated axis-aligned corner fall outside the rotated rectangle.
        val rotated = unrotated.copy(rotationRadians = (PI / 4.0).toFloat())
        // Center + (85, 85) is diagonal along the rotated 200pt width (half-width = 100 -> radial reach 100 > sqrt(85^2+85^2)? 85*sqrt(2)=120.2, try 60 along local X: (42.4, 42.4))
        assertTrue(
            ObjectAttachmentGeometry.containsPointInRotatedRect(
                xPt = 200f + 50f,
                yPt = 150f + 50f,
                bounds = rotated.bounds,
                rotationRadians = rotated.rotationRadians
            )
        )
        // Corner (295, 105) would be inside unrotated [100..300, 100..200], but outside after 45° rotation
        // because localY = (95*sin(-45) + (-45)*cos(-45)) ≈ -99 -> |99| > halfH (50 + 2).
        assertFalse(
            ObjectAttachmentGeometry.containsPointInRotatedRect(
                xPt = 295f,
                yPt = 105f,
                bounds = rotated.bounds,
                rotationRadians = rotated.rotationRadians
            )
        )
    }

    @Test
    fun findTopmostImageForStrokePicksHighestZOrderOverlappingImage() {
        val bottomImg = PageObject.ImageObject(
            id = "img-bottom",
            zOrder = 5L,
            bounds = DocRect(100f, 100f, 300f, 300f),
            imageAssetRelativePath = "pages/p1/images/bottom.png"
        )
        val topImg = PageObject.ImageObject(
            id = "img-top",
            zOrder = 25L,
            bounds = DocRect(150f, 150f, 260f, 260f),
            imageAssetRelativePath = "pages/p1/images/top.png"
        )
        val unrelatedLatex = PageObject.LatexObject(
            id = "latex-99",
            zOrder = 999L,
            bounds = DocRect(140f, 140f, 280f, 280f),
            latexSource = "x^2"
        )

        val picked = ObjectAttachmentGeometry.findTopmostImageForStroke(
            points = listOf(DocPoint(180f, 180f), DocPoint(210f, 210f)),
            objects = listOf(bottomImg, topImg, unrelatedLatex)
        )
        assertEquals("img-top", picked?.id)
    }

    @Test
    fun computeObjectInteractionAffineTracksMoveResizeAndRotationExactly() {
        val before = PageObject.ImageObject(
            id = "img-affine",
            zOrder = 10L,
            bounds = DocRect(100f, 100f, 200f, 160f), // center (150, 130), size 100 x 60
            imageAssetRelativePath = "pages/p1/images/a.png",
            rotationRadians = 0f
        )
        val after = before.copy(
            bounds = DocRect(250f, 220f, 450f, 340f), // center (350, 280), size 200 x 120 (2x scale)
            rotationRadians = (PI / 2.0).toFloat() // +90 deg
        )
        val affine = ObjectAttachmentGeometry.computeObjectInteractionAffine(before, after)

        // Center (150, 130) must map onto new center (350, 280).
        val mappedCenter = affine.transformPoint(150f, 130f)
        assertEquals(350f, mappedCenter.xPt, 0.05f)
        assertEquals(280f, mappedCenter.yPt, 0.05f)

        // Right-middle edge of before (200, 130) (+50 in X) scales 2x -> +100 and rotates +90° -> (0, +100) from new center.
        val mappedRightEdge = affine.transformPoint(200f, 130f)
        assertEquals(350f, mappedRightEdge.xPt, 0.05f)
        assertEquals(380f, mappedRightEdge.yPt, 0.05f)
    }

    @Test
    fun resolveNextActivePageAfterDeleteEnforcesLastPageGuardAndNeighborSelection() {
        val p1 = PageEntity(
            id = "p1",
            notebookId = "nb",
            pageIndexInNotebook = 0,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L
        )
        val p2 = p1.copy(id = "p2", pageIndexInNotebook = 1)
        val p3 = p1.copy(id = "p3", pageIndexInNotebook = 2)

        // Never delete the final remaining page.
        assertNull(resolveNextActivePageAfterDelete(listOf(p1), "p1", "p1"))
        // Ignore unknown page IDs.
        assertNull(resolveNextActivePageAfterDelete(listOf(p1, p2), "missing", "p1"))

        // Deleting active middle page advances to next neighbor.
        assertEquals("p3", resolveNextActivePageAfterDelete(listOf(p1, p2, p3), "p2", "p2")?.id)
        // Deleting active final page steps back to previous page.
        assertEquals("p2", resolveNextActivePageAfterDelete(listOf(p1, p2, p3), "p3", "p3")?.id)
        // Deleting inactive page keeps current active page unchanged.
        assertEquals("p3", resolveNextActivePageAfterDelete(listOf(p1, p2, p3), "p1", "p3")?.id)
    }

    @Test
    fun thumbnailDimensionCalculatorPreservesAspectRatioAndClampsSafely() {
        val (wPortrait, hPortrait) = PageThumbnailRenderer.computeThumbnailDimensionsPx(
            pageWidthPt = 595f,
            pageHeightPt = 842f,
            maxEdgePx = 280
        )
        assertEquals(280, hPortrait)
        assertEquals(198, wPortrait)

        val (wLandscape, hLandscape) = PageThumbnailRenderer.computeThumbnailDimensionsPx(
            pageWidthPt = 1600f,
            pageHeightPt = 1000f,
            maxEdgePx = 280
        )
        assertEquals(280, wLandscape)
        assertEquals(175, hLandscape)

        val (wMin, hMin) = PageThumbnailRenderer.computeThumbnailDimensionsPx(
            pageWidthPt = 0f,
            pageHeightPt = 0f,
            maxEdgePx = 10
        )
        assertTrue(wMin >= 16 && hMin >= 16)
    }

    @Test
    fun pdfAspectFitBaseSizeAndDetailTilingComputeAccurateDocumentGeometry() {
        // Fit a square 1000x1000 PDF onto A4 portrait (595x842): fits horizontally, centered vertically.
        val fit = PdfTileRenderer.computePdfAspectFitPlacement(
            pdfPageWidth = 1000,
            pdfPageHeight = 1000,
            docPageWidthPt = 595f,
            docPageHeightPt = 842f
        )
        assertEquals(0.595f, fit.scalePdfToDoc, 1e-4f)
        assertEquals(0f, fit.offsetXDocPt, 1e-3f)
        assertEquals((842f - 595f) * 0.5f, fit.offsetYDocPt, 1e-2f)
        assertTrue(abs(fit.fittedWidthDocPt - 595f) < 0.1f)
        assertTrue(abs(fit.fittedHeightDocPt - 595f) < 0.1f)

        val (baseW, baseH) = PdfTileRenderer.computeBaseBitmapSizePx(595f, 842f, maxEdgePx = 1440)
        assertEquals(1440, baseH)
        assertEquals(1018, baseW)

        // At low zoom (~1.0f <= baseScale * 1.35f), no extra detail tiles should be scheduled.
        assertTrue(
            PdfTileRenderer.computeVisibleDetailTiles(
                docPageWidthPt = 595f,
                docPageHeightPt = 842f,
                visibleLocalDocRect = DocRect(0f, 0f, 595f, 842f),
                currentScale = 1.0f
            ).isEmpty()
        )

        // When deeply zoomed in (scale = 3.0f), detail tiles cover the clipped visible region.
        val tiles = PdfTileRenderer.computeVisibleDetailTiles(
            docPageWidthPt = 595f,
            docPageHeightPt = 842f,
            visibleLocalDocRect = DocRect(10f, 20f, 400f, 450f),
            currentScale = 3.0f
        )
        assertNotNull(tiles)
        assertTrue("Expected multiple zoomed detail tiles, got ${tiles.size}", tiles.size >= 4)
        for (tile in tiles) {
            assertTrue(tile.cropDocRect.leftPt >= 0f)
            assertTrue(tile.cropDocRect.topPt >= 0f)
            assertTrue(tile.cropDocRect.rightPt <= 595f)
            assertTrue(tile.cropDocRect.bottomPt <= 842f)
            assertTrue(tile.outputWidthPx >= 64 && tile.outputHeightPx >= 64)
        }
    }
}
