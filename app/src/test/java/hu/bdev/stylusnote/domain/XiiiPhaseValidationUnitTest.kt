package hu.bdev.stylusnote.domain

import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.domain.model.CanvasAction
import hu.bdev.stylusnote.domain.model.ObjectAttachmentGeometry
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.NotebookUndoCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiiiPhaseValidationUnitTest {

    private fun latexAction(id: String): CanvasAction.AddedObject = CanvasAction.AddedObject(
        PageObject.LatexObject(
            id = id,
            zOrder = 2_000_000_001L,
            bounds = DocRect(10f, 10f, 120f, 60f),
            latexSource = "x + 1"
        )
    )

    @Test
    fun crossPageTransferOffsetConvertsSourceAndDestSlotOriginsAcrossDifferentWidths() {
        // Source A4 narrow slot centered at x=60, y=0; destination wider slot at x=0, y=866.
        // Dragged +120 pt down and -15 pt left in workspace coordinates.
        val delta = CanvasStateHolder.computeCrossPageTransferOffset(
            sourceOffsetXPt = 60f,
            sourceOffsetYPt = 0f,
            destOffsetXPt = 0f,
            destOffsetYPt = 866f,
            dragDeltaXPt = -15f,
            dragDeltaYPt = 120f
        )
        // Visual point at source-local (100, 800) -> ws (160, 800) + drag (-15, 120) = ws (145, 920)
        // -> dest-local (145, 54) == source-local + (45, -746)
        assertEquals(45f, delta.xPt, 0.01f)
        assertEquals(-746f, delta.yPt, 0.01f)
    }

    @Test
    fun clipboardPasteOffsetApplies18PtCascadeOnSamePageAndClampsViewportCenterOnOtherPage() {
        val selBounds = DocRect(leftPt = 100f, topPt = 200f, rightPt = 220f, bottomPt = 280f)

        val samePageDelta = CanvasStateHolder.computeClipboardPasteOffset(
            selectionBounds = selBounds,
            isSamePage = true,
            destPageWidthPt = 595f,
            destPageHeightPt = 842f
        )
        assertEquals(18f, samePageDelta.xPt, 0.01f)
        assertEquals(18f, samePageDelta.yPt, 0.01f)

        // Different page requesting center near bottom-right corner clamps cleanly within 10pt margin
        val otherPageDelta = CanvasStateHolder.computeClipboardPasteOffset(
            selectionBounds = selBounds,
            isSamePage = false,
            destPageWidthPt = 595f,
            destPageHeightPt = 842f,
            preferredCenterLocal = DocPoint(585f, 835f)
        )
        // Max left = 595 - 120 - 10 = 465 -> deltaX = 465 - 100 = 365
        // Max top = 842 - 80 - 10 = 752 -> deltaY = 752 - 200 = 552
        assertEquals(365f, otherPageDelta.xPt, 0.01f)
        assertEquals(552f, otherPageDelta.yPt, 0.01f)
    }

    @Test
    fun notebookUndoCoordinatorGroupsTransactionAndEvictsCompleteGroupsWhenOverBudget() {
        val coordinator = NotebookUndoCoordinator(minUndoSteps = 1, maxUndoSteps = 3, maxPointBudget = 1000)

        coordinator.withTransaction(transactionId = "tx-cross") {
            coordinator.record("pg-1", latexAction("o1"))
            coordinator.record("pg-2", latexAction("o2"))
        }
        coordinator.record("pg-3", latexAction("o3"))

        assertEquals(3, coordinator.undoDepth)

        // Undo standalone pg-3 first
        val undoSingle = coordinator.popUndoGroup()
        assertEquals(1, undoSingle.size)
        assertEquals("pg-3", undoSingle[0].pageId)

        // Undo tx-cross pops BOTH pg-2 and pg-1 atomically in reverse order
        val undoCross = coordinator.popUndoGroup()
        assertEquals(2, undoCross.size)
        assertEquals("pg-2", undoCross[0].pageId)
        assertEquals("pg-1", undoCross[1].pageId)

        // Redo tx-cross pops BOTH pg-1 then pg-2 in forward execution order
        val redoCross = coordinator.popRedoGroup()
        assertEquals(2, redoCross.size)
        assertEquals("pg-1", redoCross[0].pageId)
        assertEquals("pg-2", redoCross[1].pageId)

        // Adding 2 more entries pushes undoDepth to 4 (> maxUndoSteps = 3), which must evict the ENTIRE
        // oldest transaction ("tx-cross", both entries) rather than leaving an orphaned half-transaction.
        coordinator.record("pg-4", latexAction("o4"))
        coordinator.record("pg-5", latexAction("o5"))
        assertEquals(2, coordinator.undoDepth)
        assertEquals("pg-5", coordinator.popUndoGroup().single().pageId)
        assertEquals("pg-4", coordinator.popUndoGroup().single().pageId)
        assertTrue(coordinator.popUndoGroup().isEmpty())
    }

    @Test
    fun tableObjectIsRenderedUnderInkButExcludedFromAttachmentLassoAndSnapshot() {
        val holder = CanvasStateHolder()
        val table = PageObject.TableObject(
            id = "table-locked-1",
            zOrder = holder.allocateObjectZOrder(),
            bounds = DocRect(40f, 80f, 360f, 260f),
            rows = 4,
            columns = 3,
            hasHeaderRow = true
        )
        holder.addPageObject(table)

        assertTrue(ObjectAttachmentGeometry.isUnderInkRenderedObject(table))
        assertTrue(ObjectAttachmentGeometry.isUnderInkAttachableObject(table))
        assertTrue(holder.hasUnderInkObjects())

        // Stroke drawn inside a table cell attaches its attachedObjectId to the table
        val cellStroke = listOf(DocPoint(80f, 120f), DocPoint(150f, 130f))
        val attachTarget = ObjectAttachmentGeometry.findTopmostUnderInkObjectForStroke(cellStroke, listOf(table))
        assertEquals(table.id, attachTarget?.id)

        // Single-cell tight lasso stays cell-local and must NOT capture the entire table
        val singleCellLasso = listOf(
            DocPoint(65f, 95f),
            DocPoint(130f, 95f),
            DocPoint(130f, 120f),
            DocPoint(65f, 120f)
        )
        assertTrue(holder.selectPageObjectsByLasso(singleCellLasso).isEmpty())

        // Full-table lasso captures the TableObject and snapshotSelection includes it
        val fullLasso = listOf(
            DocPoint(10f, 10f),
            DocPoint(420f, 10f),
            DocPoint(420f, 320f),
            DocPoint(10f, 320f)
        )
        val lassoHits = holder.selectPageObjectsByLasso(fullLasso)
        assertEquals(listOf(table.id), lassoHits.map { it.id })

        val snap = holder.snapshotSelection("pg-1", emptyList(), listOf(table.id))
        assertFalse(snap.isEmpty)
        assertEquals(listOf(table.id), snap.pageObjects.map { it.id })
    }
}
