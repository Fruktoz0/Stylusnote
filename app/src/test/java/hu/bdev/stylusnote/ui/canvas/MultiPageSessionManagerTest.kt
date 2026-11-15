package hu.bdev.stylusnote.ui.canvas

import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.CanvasAction
import hu.bdev.stylusnote.domain.model.PageObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

class MultiPageSessionManagerTest {

    private fun dummyObjectAction(objectId: String): CanvasAction.AddedObject =
        CanvasAction.AddedObject(
            PageObject.LatexObject(
                id = objectId,
                zOrder = 2_000_000_001L,
                bounds = DocRect(10f, 10f, 100f, 60f),
                latexSource = "x^2 + 1"
            )
        )

    @Test
    fun slidingWindowMaintainsThreeWarmPagesAndAsynchronouslyEvictsFarPages() = runBlocking {
        val closedPages = Collections.synchronizedList(ArrayList<String>())
        val trimmedPages = Collections.synchronizedList(ArrayList<String>())
        val hydratedPages = Collections.synchronizedList(ArrayList<String>())

        val pool = SlidingWindowPagePool(
            scope = this,
            factory = { pageId -> pageId to Any() },
            hydrator = { pageId, _ ->
                delay(5)
                hydratedPages.add(pageId)
            },
            heavyCacheTrimmer = { (pageId, _) -> trimmedPages.add(pageId) },
            closer = { pageId, _ ->
                delay(5)
                closedPages.add(pageId)
            }
        )

        val ninePages = (0 until 9).map { "page-$it" }

        // Start at page 0 -> should load {page-0, page-1}.
        pool.updateWindow(ninePages, newActiveIndex = 0)
        delay(80)
        assertEquals(setOf("page-0", "page-1"), pool.readyPageIdsSnapshot())
        assertTrue(closedPages.isEmpty())

        // Scroll through page 2 -> keeps {0, 1, 2, 3} warm, trims distance-2 page-0 heavy cache.
        pool.updateWindow(ninePages, newActiveIndex = 2)
        delay(80)
        assertTrue(pool.isReady("page-2"))
        assertTrue(pool.isReady("page-3"))
        assertTrue("distance-2 warm page-0 should have heavy cache trimmed", trimmedPages.contains("page-0"))

        // Scroll to page 4 -> loads {page-3, page-4, page-5}, trims page-2, asynchronously evicts {0, 1} (> 2).
        pool.updateWindow(ninePages, newActiveIndex = 4)
        delay(80)
        assertTrue(pool.isReady("page-4"))
        assertTrue(pool.isReady("page-3"))
        assertTrue(pool.isReady("page-5"))
        assertFalse("page-0 must be evicted beyond radius 2", pool.activePageIdsSnapshot().contains("page-0"))
        assertFalse("page-1 must be evicted beyond radius 2", pool.activePageIdsSnapshot().contains("page-1"))
        assertTrue("distance-2 page-2 stays in hysteresis without blocking", pool.isReady("page-2"))
        assertTrue("page-2 heavy cache trimmed at distance 2", trimmedPages.contains("page-2"))
        assertTrue("evicted pages must close on IO", closedPages.containsAll(listOf("page-0", "page-1")))

        // Jump all the way to page 8 -> {page-6..page-8} max window; earlier {2,3,4,5} close asynchronously.
        pool.updateWindow(ninePages, newActiveIndex = 8)
        delay(80)
        assertTrue(pool.isReady("page-8"))
        assertTrue(pool.isReady("page-7"))
        assertFalse(pool.activePageIdsSnapshot().contains("page-4"))
        assertTrue(closedPages.containsAll(listOf("page-2", "page-3", "page-4", "page-5")))

        pool.closeAll()
        assertTrue(pool.activePageIdsSnapshot().isEmpty())
    }

    @Test
    fun undoCoordinatorPreservesChronologicalOrderAcrossMultiplePages() {
        val coordinator = NotebookUndoCoordinator()

        coordinator.record("page-0", dummyObjectAction("obj-1"))
        coordinator.record("page-1", dummyObjectAction("obj-2"))
        coordinator.record("page-0", dummyObjectAction("obj-3"))

        assertTrue(coordinator.canUndo)
        assertFalse(coordinator.canRedo)
        assertEquals(3, coordinator.undoDepth)

        // Strict LIFO across pages: page-0(obj-3) -> page-1(obj-2) -> page-0(obj-1)
        val firstUndo = coordinator.popUndo()
        assertEquals("page-0", firstUndo?.pageId)
        assertEquals("obj-3", (firstUndo?.action as CanvasAction.AddedObject).pageObject.id)

        val secondUndo = coordinator.popUndo()
        assertEquals("page-1", secondUndo?.pageId)
        assertEquals("obj-2", (secondUndo?.action as CanvasAction.AddedObject).pageObject.id)

        // Re-applying redo replays chronologically forward: page-1(obj-2) first!
        val firstRedo = coordinator.popRedo()
        assertNotNull(firstRedo)
        assertEquals("page-1", firstRedo?.pageId)

        // Recording a fresh mutation discards stale redo tail.
        coordinator.record("page-2", dummyObjectAction("obj-4"))
        assertFalse(coordinator.canRedo)
        assertEquals(3, coordinator.undoDepth)

        coordinator.removePageEntries("page-2")
        assertEquals(2, coordinator.undoDepth)
        assertEquals("page-1", coordinator.popUndo()?.pageId)
        assertEquals("page-0", coordinator.popUndo()?.pageId)
        assertNull(coordinator.popUndo())
    }

    @Test
    fun sessionManagerRehydratesEvictedPagesForUndoRedoAndCapsPerPageTileBudget() = runBlocking {
        val tempRoot = Files.createTempDirectory("mp_session_unit_").toFile()
        try {
            val pages = (0 until 9).map { idx ->
                PageEntity(
                    id = "pg-$idx",
                    notebookId = "nb-multi",
                    pageIndexInNotebook = idx,
                    createdAtEpochMs = 1000L + idx,
                    updatedAtEpochMs = 1000L + idx
                )
            }
            val session = MultiPageSessionManager(
                filesDir = tempRoot,
                activeBrushConfigProvider = { BrushConfig() },
                onLocalMutationCommitted = {},
                onPageReadyCallback = { _, _ -> },
                totalTileBudgetBytes = 192L * 1024L * 1024L,
                ioDispatcher = Dispatchers.IO
            )
            assertTrue(session.perPageTileBudgetBytes * 3L <= 192L * 1024L * 1024L)

            val layout = session.updatePagesAndFocus(pages, activePageIndex = 0)
            assertEquals(9, layout.pageCount)
            delay(120)

            val runtime0 = session.getReadyRuntime("pg-0")!!
            val obj0 = PageObject.LatexObject(
                id = "obj-pg0",
                zOrder = 2_000_000_001L,
                bounds = DocRect(20f, 30f, 120f, 70f),
                latexSource = "a^2 + b^2 = c^2"
            )
            runtime0.canvasStateHolder.addPageObject(obj0)
            runtime0.strokeJournal.appendAddObject(obj0)

            // Move to page 4 so page 0 evicts beyond radius 2, then add an object on page 4.
            session.updateActiveFocus(4)
            delay(120)
            assertNull(session.getRuntime("pg-0"))
            val runtime4 = session.getReadyRuntime("pg-4")!!
            val obj4 = PageObject.LatexObject(
                id = "obj-pg4",
                zOrder = 2_000_000_001L,
                bounds = DocRect(40f, 50f, 150f, 90f),
                latexSource = "E = mc^2"
            )
            runtime4.canvasStateHolder.addPageObject(obj4)
            runtime4.strokeJournal.appendAddObject(obj4)

            // Jump to page 8 so page 4 also evicts asynchronously beyond radius 2.
            session.updateActiveFocus(8)
            delay(150)
            assertNull(session.getRuntime("pg-4"))
            assertNotNull(session.getReadyRuntime("pg-8"))

            // Undo #1 -> must rehydrate pg-4 and remove obj-pg4.
            val undo4 = session.applyUndo()
            assertNotNull(undo4)
            assertEquals("pg-4", undo4?.pageId)
            assertTrue(undo4?.didRehydratePage == true)
            assertNull(session.getReadyRuntime("pg-4")?.canvasStateHolder?.getPageObject("obj-pg4"))

            // Undo #2 -> must rehydrate pg-0 and remove obj-pg0.
            val undo0 = session.applyUndo()
            assertNotNull(undo0)
            assertEquals("pg-0", undo0?.pageId)
            assertTrue(undo0?.didRehydratePage == true)
            assertNull(session.getReadyRuntime("pg-0")?.canvasStateHolder?.getPageObject("obj-pg0"))

            // Redo -> restores obj-pg0 on pg-0 cleanly.
            val redo0 = session.applyRedo()
            assertEquals("pg-0", redo0?.pageId)
            assertNotNull(session.getReadyRuntime("pg-0")?.canvasStateHolder?.getPageObject("obj-pg0"))

            session.flushAllJournals()
            session.disposeSuspending()

            // Verify disk recovery matches exact Undo/Redo outcome!
            val verifyPg0 = StrokeJournal(pageDirectory = tempRoot.resolve("pages/pg-0"))
            val rec0 = verifyPg0.openAndRecover()
            assertEquals(listOf("obj-pg0"), rec0.objects.map { it.id })
            verifyPg0.closeBlocking(compactBeforeClose = false)

            val verifyPg4 = StrokeJournal(pageDirectory = tempRoot.resolve("pages/pg-4"))
            val rec4 = verifyPg4.openAndRecover()
            assertTrue(rec4.objects.isEmpty())
            verifyPg4.closeBlocking(compactBeforeClose = false)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
