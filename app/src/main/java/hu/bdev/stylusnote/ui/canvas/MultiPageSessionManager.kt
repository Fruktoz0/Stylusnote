package hu.bdev.stylusnote.ui.canvas

import hu.bdev.stylusnote.core.math.ContinuousPageLayout
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.OledStrokeRenderCache
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.data.local.PageFileFormat
import hu.bdev.stylusnote.data.local.StrokeBinarySerializer
import hu.bdev.stylusnote.data.local.StrokeJournal
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.CanvasAction
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max

/**
 * Chronological undo/redo entry bound to a specific [pageId] inside an open multi-page notebook.
 */
data class PageUndoEntry(
    val pageId: String,
    val action: CanvasAction
)

/**
 * Notebook-wide Undo/Redo history stack preserving strict chronological action ordering across
 * all pages in a multi-page continuous notebook (`P1 -> P2 -> Undo` reverts `P2` even if `P1` is centered).
 */
class NotebookUndoCoordinator(
    private val minUndoSteps: Int = CanvasStateHolder.DEFAULT_MIN_UNDO_STEPS,
    private val maxUndoSteps: Int = CanvasStateHolder.DEFAULT_MAX_UNDO_STEPS,
    private val maxPointBudget: Int = CanvasStateHolder.DEFAULT_MAX_POINT_BUDGET
) {
    private val lock = Any()
    private val undoStack = ArrayDeque<PageUndoEntry>()
    private val redoStack = ArrayDeque<PageUndoEntry>()
    private var totalUndoPointCost: Int = 0

    val canUndo: Boolean
        get() = synchronized(lock) { undoStack.isNotEmpty() }

    val canRedo: Boolean
        get() = synchronized(lock) { redoStack.isNotEmpty() }

    val undoDepth: Int
        get() = synchronized(lock) { undoStack.size }

    val redoDepth: Int
        get() = synchronized(lock) { redoStack.size }

    fun record(pageId: String, action: CanvasAction) {
        synchronized(lock) {
            redoStack.clear()
            undoStack.addLast(PageUndoEntry(pageId = pageId, action = action))
            totalUndoPointCost += action.estimatedPointCost
            enforceBudgetLocked()
        }
    }

    fun peekUndo(): PageUndoEntry? = synchronized(lock) { undoStack.lastOrNull() }

    fun peekRedo(): PageUndoEntry? = synchronized(lock) { redoStack.lastOrNull() }

    fun popUndo(): PageUndoEntry? = synchronized(lock) {
        val entry = undoStack.removeLastOrNull() ?: return null
        totalUndoPointCost = max(0, totalUndoPointCost - entry.action.estimatedPointCost)
        redoStack.addLast(entry)
        entry
    }

    fun popRedo(): PageUndoEntry? = synchronized(lock) {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        totalUndoPointCost += entry.action.estimatedPointCost
        enforceBudgetLocked()
        entry
    }

    fun removePageEntries(pageId: String) {
        synchronized(lock) {
            undoStack.removeAll { it.pageId == pageId }
            redoStack.removeAll { it.pageId == pageId }
            totalUndoPointCost = undoStack.sumOf { it.action.estimatedPointCost }
        }
    }

    fun clear() {
        synchronized(lock) {
            undoStack.clear()
            redoStack.clear()
            totalUndoPointCost = 0
        }
    }

    private fun enforceBudgetLocked() {
        while (undoStack.size > maxUndoSteps) {
            val dropped = undoStack.removeFirst()
            totalUndoPointCost = max(0, totalUndoPointCost - dropped.action.estimatedPointCost)
        }
        while (undoStack.size > minUndoSteps && totalUndoPointCost > maxPointBudget) {
            val dropped = undoStack.removeFirst()
            totalUndoPointCost = max(0, totalUndoPointCost - dropped.action.estimatedPointCost)
        }
    }
}

/**
 * Pure-Kotlin sliding-window lifecycle pool for page runtimes:
 * - Preloads `active ± prefetchRadius` (`active ± 1`) in priority order `[0, +1, -1]`
 * - Retains hysteresis buffer up to `evictRadius` (`±2`), while immediately trimming heavy caches
 *   beyond `trimHeavyCacheRadius` (`±1`)
 * - Asynchronously closes and evicts pages strictly beyond `|index - active| > evictRadius` on
 *   [ioDispatcher] without ever blocking the Main thread.
 */
class SlidingWindowPagePool<T : Any>(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
    private val evictRadius: Int = DEFAULT_EVICT_RADIUS,
    private val trimHeavyCacheRadius: Int = DEFAULT_TRIM_HEAVY_CACHE_RADIUS,
    private val factory: (pageId: String) -> T,
    private val hydrator: suspend (pageId: String, runtime: T) -> Unit,
    private val heavyCacheTrimmer: (T) -> Unit = {},
    private val closer: suspend (pageId: String, runtime: T) -> Unit,
    private val onRuntimeReady: (pageId: String, runtime: T) -> Unit = { _, _ -> }
) {
    private enum class SlotState { LOADING, READY }

    private data class PoolEntry<T>(
        val pageId: String,
        val runtime: T,
        var state: SlotState,
        var loadJob: Job? = null
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, PoolEntry<T>>()
    private val pendingCloseJobs = Collections.synchronizedSet(LinkedHashSet<Job>())
    private var orderedPageIds: List<String> = emptyList()
    private var activeIndex: Int = 0

    fun getRuntime(pageId: String): T? = synchronized(lock) { entries[pageId]?.runtime }

    fun getReadyRuntime(pageId: String): T? = synchronized(lock) {
        val entry = entries[pageId] ?: return null
        if (entry.state == SlotState.READY) entry.runtime else null
    }

    fun isReady(pageId: String): Boolean = synchronized(lock) {
        entries[pageId]?.state == SlotState.READY
    }

    fun activePageIdsSnapshot(): Set<String> = synchronized(lock) { entries.keys.toSet() }

    fun readyPageIdsSnapshot(): Set<String> = synchronized(lock) {
        entries.values.filter { it.state == SlotState.READY }.mapTo(LinkedHashSet()) { it.pageId }
    }

    /**
     * Updates the ordered page roster and focuses the sliding window on [newActiveIndex].
     * Evicted pages are closed asynchronously on [ioDispatcher].
     */
    fun updateWindow(pageIds: List<String>, newActiveIndex: Int) {
        if (pageIds.isEmpty()) {
            val removed = synchronized(lock) {
                orderedPageIds = emptyList()
                activeIndex = 0
                val all = entries.values.toList()
                entries.clear()
                all
            }
            removed.forEach { scheduleAsyncClose(it) }
            return
        }

        val clampedActive = newActiveIndex.coerceIn(0, pageIds.lastIndex)
        val indexByPageId = HashMap<String, Int>(pageIds.size * 2)
        pageIds.forEachIndexed { idx, id -> indexByPageId[id] = idx }

        val toEvict = ArrayList<PoolEntry<T>>()
        val toTrim = ArrayList<T>()
        val toLoad = ArrayList<Pair<String, PoolEntry<T>>>()

        synchronized(lock) {
            orderedPageIds = pageIds
            activeIndex = clampedActive

            val iter = entries.entries.iterator()
            while (iter.hasNext()) {
                val (id, entry) = iter.next()
                val idx = indexByPageId[id]
                if (idx == null || abs(idx - clampedActive) > evictRadius) {
                    iter.remove()
                    toEvict.add(entry)
                } else if (abs(idx - clampedActive) > trimHeavyCacheRadius && entry.state == SlotState.READY) {
                    toTrim.add(entry.runtime)
                }
            }

            // Queue active slot first, then alternate forward/backward neighbors (+1, -1, ...)
            // so the visible sheet is hydrated first during fast jumps.
            val targetIndices = ArrayList<Int>(1 + prefetchRadius * 2)
            targetIndices.add(clampedActive)
            for (offset in 1..prefetchRadius) {
                val forward = clampedActive + offset
                if (forward in pageIds.indices) targetIndices.add(forward)
                val backward = clampedActive - offset
                if (backward in pageIds.indices) targetIndices.add(backward)
            }

            for (targetIdx in targetIndices) {
                val targetId = pageIds[targetIdx]
                if (!entries.containsKey(targetId)) {
                    val runtime = factory(targetId)
                    val newEntry = PoolEntry(
                        pageId = targetId,
                        runtime = runtime,
                        state = SlotState.LOADING
                    )
                    entries[targetId] = newEntry
                    toLoad.add(targetId to newEntry)
                }
            }
        }

        toTrim.forEach { heavyCacheTrimmer(it) }
        toEvict.forEach { scheduleAsyncClose(it) }
        toLoad.forEach { (id, entry) -> launchHydration(id, entry) }
    }

    /**
     * Ensures [pageId] is present and hydrated (used when Undo/Redo or Jump targets a currently cold page).
     */
    suspend fun ensureHydrated(pageId: String): T {
        val (entry, existingJob) = synchronized(lock) {
            val current = entries[pageId]
            if (current != null) {
                current to current.loadJob
            } else {
                val runtime = factory(pageId)
                val created = PoolEntry(
                    pageId = pageId,
                    runtime = runtime,
                    state = SlotState.LOADING
                )
                entries[pageId] = created
                val job = scope.launch(ioDispatcher) {
                    hydrator(pageId, runtime)
                }
                created.loadJob = job
                created to job
            }
        }
        existingJob?.join()
        val becameReady = synchronized(lock) {
            if (entries[pageId] === entry && entry.state != SlotState.READY) {
                entry.state = SlotState.READY
                true
            } else {
                false
            }
        }
        if (becameReady) {
            onRuntimeReady(pageId, entry.runtime)
        }
        return entry.runtime
    }

    suspend fun flushAll(flusher: suspend (pageId: String, runtime: T) -> Unit) {
        val snapshot = synchronized(lock) { entries.values.toList() }
        for (entry in snapshot) {
            entry.loadJob?.join()
            flusher(entry.pageId, entry.runtime)
        }
    }

    suspend fun closeAll() {
        val snapshot = synchronized(lock) {
            val all = entries.values.toList()
            entries.clear()
            all
        }
        for (entry in snapshot) {
            entry.loadJob?.join()
            withContext(ioDispatcher) {
                closer(entry.pageId, entry.runtime)
            }
        }
        val pendingSnapshot = synchronized(pendingCloseJobs) { pendingCloseJobs.toList() }
        for (job in pendingSnapshot) {
            job.join()
        }
    }

    private fun launchHydration(pageId: String, entry: PoolEntry<T>) {
        val job = scope.launch(ioDispatcher) {
            hydrator(pageId, entry.runtime)
            val stillActive = synchronized(lock) {
                if (entries[pageId] === entry) {
                    entry.state = SlotState.READY
                    true
                } else {
                    false
                }
            }
            if (stillActive) {
                onRuntimeReady(pageId, entry.runtime)
            }
        }
        entry.loadJob = job
    }

    private fun scheduleAsyncClose(entry: PoolEntry<T>) {
        val job = scope.launch(ioDispatcher) {
            try {
                entry.loadJob?.join()
                closer(entry.pageId, entry.runtime)
            } finally {
                // Job removes itself via invokeOnCompletion below.
            }
        }
        pendingCloseJobs.add(job)
        job.invokeOnCompletion { pendingCloseJobs.remove(job) }
    }

    companion object {
        const val DEFAULT_PREFETCH_RADIUS: Int = 1
        const val DEFAULT_EVICT_RADIUS: Int = 2
        const val DEFAULT_TRIM_HEAVY_CACHE_RADIUS: Int = 1
    }
}

/**
 * Live in-memory runtime bundle for one warm page in an open multi-page notebook.
 */
class NotebookPageRuntime(
    val pageId: String,
    val canvasStateHolder: CanvasStateHolder,
    val strokeJournal: StrokeJournal,
    val dryInkRasterCache: DryInkRasterCache,
    val oledStrokeCache: OledStrokeRenderCache
) {
    @Volatile
    var isHydrated: Boolean = false

    @Volatile
    var isReadOnly: Boolean = false

    @Volatile
    var usedBrushFallback: Boolean = false

    @Volatile
    var sourceFormatVersion: Int = PageFileFormat.CURRENT_FORMAT_VERSION
}

data class MultiPageHistoryResult(
    val pageId: String,
    val action: CanvasAction,
    val affectedBoundsLocal: DocRect?,
    val isReadOnly: Boolean,
    val didRehydratePage: Boolean
)

/**
 * Orchestrates continuous multi-page sessions for a single open notebook (`key(notebookId)`):
 * - Computes & updates [ContinuousPageLayout] whenever the notebook's [PageEntity] list changes
 * - Maintains a warm sliding window of `active ± 1` pages with async `|index - active| > 2` eviction
 * - Enforces a strict aggregate tile-memory ceiling (`<= 192 MB`) across warm raster caches
 * - Routes chronological Undo/Redo across pages via [NotebookUndoCoordinator].
 */
class MultiPageSessionManager(
    private val filesDir: File,
    private val activeBrushConfigProvider: () -> BrushConfig,
    private val onLocalMutationCommitted: (pageId: String) -> Unit,
    private val onPageReadyCallback: (pageId: String, runtime: NotebookPageRuntime) -> Unit,
    totalTileBudgetBytes: Long = DryInkRasterCache.DEFAULT_MAX_CACHE_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val perPageTileBudgetBytes: Long = max(
        MIN_PER_PAGE_TILE_BUDGET_BYTES,
        totalTileBudgetBytes / MAX_HEAVY_RASTER_PAGES
    )

    val undoCoordinator = NotebookUndoCoordinator()

    @Volatile
    var currentPages: List<PageEntity> = emptyList()
        private set

    @Volatile
    var currentLayout: ContinuousPageLayout = ContinuousPageLayout.build(emptyList())
        private set

    @Volatile
    private var lastLayoutSignature: List<PageLayoutSignature>? = null

    private data class PageLayoutSignature(
        val id: String,
        val index: Int,
        val presetName: String,
        val canvasModeName: String,
        val templateName: String
    )

    private val pool = SlidingWindowPagePool(
        scope = sessionScope,
        ioDispatcher = ioDispatcher,
        factory = { pageId -> createPageRuntime(pageId) },
        hydrator = { _, runtime -> hydrateRuntimeFromDisk(runtime) },
        heavyCacheTrimmer = { runtime -> runtime.dryInkRasterCache.clearAll() },
        closer = { _, runtime ->
            runtime.canvasStateHolder.onUndoActionPushed = null
            runtime.dryInkRasterCache.clearAll()
            runtime.strokeJournal.close(compactBeforeClose = false)
        },
        onRuntimeReady = { pageId, runtime -> onPageReadyCallback(pageId, runtime) }
    )

    fun updatePagesAndFocus(
        pages: List<PageEntity>,
        activePageIndex: Int,
        gapPt: Float = ContinuousPageLayout.DEFAULT_PAGE_GAP_PT
    ): ContinuousPageLayout {
        val sorted = pages.sortedBy { it.pageIndexInNotebook }
        currentPages = sorted

        val signature = sorted.map {
            PageLayoutSignature(
                id = it.id,
                index = it.pageIndexInNotebook,
                presetName = it.pageSizePreset.name,
                canvasModeName = it.canvasMode.name,
                templateName = it.templateType.name
            )
        }
        if (signature != lastLayoutSignature) {
            lastLayoutSignature = signature
            currentLayout = ContinuousPageLayout.build(sorted, gapPt = gapPt)
        }

        pool.updateWindow(
            pageIds = sorted.map { it.id },
            newActiveIndex = activePageIndex
        )
        return currentLayout
    }

    fun updateActiveFocus(activePageIndex: Int) {
        val pages = currentPages
        if (pages.isEmpty()) return
        pool.updateWindow(
            pageIds = pages.map { it.id },
            newActiveIndex = activePageIndex
        )
    }

    fun getRuntime(pageId: String): NotebookPageRuntime? = pool.getRuntime(pageId)

    fun getReadyRuntime(pageId: String): NotebookPageRuntime? = pool.getReadyRuntime(pageId)

    fun readyPageIdsSnapshot(): Set<String> = pool.readyPageIdsSnapshot()

    fun activePageIdsSnapshot(): Set<String> = pool.activePageIdsSnapshot()

    fun notifyPageDeleted(pageId: String) {
        undoCoordinator.removePageEntries(pageId)
    }

    suspend fun applyUndo(): MultiPageHistoryResult? = applyHistoryTransition(isUndo = true)

    suspend fun applyRedo(): MultiPageHistoryResult? = applyHistoryTransition(isUndo = false)

    private suspend fun applyHistoryTransition(isUndo: Boolean): MultiPageHistoryResult? {
        val entry = (if (isUndo) undoCoordinator.popUndo() else undoCoordinator.popRedo())
            ?: return null

        val wasWarm = pool.isReady(entry.pageId)
        val runtime = pool.ensureHydrated(entry.pageId)
        val affectedBounds = if (isUndo) {
            runtime.canvasStateHolder.applyExternalActionInverse(entry.action)
        } else {
            runtime.canvasStateHolder.applyExternalActionForward(entry.action)
        }
        affectedBounds?.let { runtime.dryInkRasterCache.invalidateDocRect(it) }

        if (!runtime.isReadOnly) {
            if (isUndo) {
                runtime.strokeJournal.enqueueCanvasActionInverse(entry.action)
            } else {
                runtime.strokeJournal.enqueueCanvasActionForward(entry.action)
            }
        }

        return MultiPageHistoryResult(
            pageId = entry.pageId,
            action = entry.action,
            affectedBoundsLocal = affectedBounds,
            isReadOnly = runtime.isReadOnly,
            didRehydratePage = !wasWarm
        )
    }

    suspend fun flushAllJournals() {
        pool.flushAll { _, runtime ->
            runtime.strokeJournal.flushAndSync()
        }
    }

    fun flushAllJournalsBlocking() {
        runBlocking {
            flushAllJournals()
        }
    }

    suspend fun disposeSuspending() {
        pool.closeAll()
        sessionScope.cancel()
    }

    fun disposeBlocking() {
        runBlocking {
            pool.closeAll()
        }
        sessionScope.cancel()
    }

    private fun createPageRuntime(pageId: String): NotebookPageRuntime {
        val stateHolder = CanvasStateHolder().apply {
            onUndoActionPushed = { action ->
                undoCoordinator.record(pageId, action)
            }
        }
        val pageDir = File(filesDir, "pages/$pageId")
        val journal = StrokeJournal(
            pageDirectory = pageDir,
            activeBrushConfigProvider = activeBrushConfigProvider,
            shapeMetaProvider = { strokeId -> stateHolder.getShapeMeta(strokeId) },
            onMutationCommitted = { onLocalMutationCommitted(pageId) }
        )
        return NotebookPageRuntime(
            pageId = pageId,
            canvasStateHolder = stateHolder,
            strokeJournal = journal,
            dryInkRasterCache = DryInkRasterCache(maxCacheBytes = perPageTileBudgetBytes),
            oledStrokeCache = OledStrokeRenderCache()
        )
    }

    private suspend fun hydrateRuntimeFromDisk(runtime: NotebookPageRuntime) {
        val recovery = runtime.strokeJournal.openAndRecover()
        val loadedStrokes = ArrayList<IndexedDryStroke>(recovery.strokes.size)
        val loadedMetas = LinkedHashMap<String, ShapeMeta>()
        var anyFallback = recovery.usedBrushFallback
        for (record in recovery.strokes) {
            val (dry, fallbackUsed) = StrokeBinarySerializer.toIndexedDryStroke(record)
            loadedStrokes.add(dry)
            if (fallbackUsed) anyFallback = true
            record.shapeMeta?.let { loadedMetas[record.id] = it }
        }
        runtime.canvasStateHolder.restoreLoadedStrokes(loadedStrokes, loadedMetas)
        runtime.canvasStateHolder.restoreLoadedObjects(recovery.objects)
        runtime.isReadOnly = recovery.isReadOnly
        runtime.usedBrushFallback = anyFallback
        runtime.sourceFormatVersion = recovery.sourceFormatVersion
        runtime.isHydrated = true
    }

    companion object {
        // At most 3 pages (active - 1, active, active + 1) retain active tile caches simultaneously.
        private const val MAX_HEAVY_RASTER_PAGES: Long = 3L
        private const val MIN_PER_PAGE_TILE_BUDGET_BYTES: Long = 32L * 1024L * 1024L
    }
}
