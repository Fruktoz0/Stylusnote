package hu.bdev.stylusnote.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.core.graphics.withSave
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.SpatialIndex
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dry stroke wrapper stored in [SpatialIndex] and rendered by [DryInkRasterCache].
 */
data class IndexedDryStroke(
    val id: String,
    val stroke: Stroke,
    val bounds: DocRect,
    val zIndex: Long,
    val isHighlighter: Boolean = false
)

/**
 * Discrete 512x512 tile coordinate in quantized-scale raster space.
 */
data class TileCoord(
    val tileX: Int,
    val tileY: Int
)

/**
 * Inclusive tile coordinate range covering a rectangular region.
 */
data class TileRange(
    val minTileX: Int,
    val maxTileX: Int,
    val minTileY: Int,
    val maxTileY: Int
)

/**
 * Cache lookup key uniquely identifying a rendered 512x512 tile at a quantized zoom level.
 */
data class TileKey(
    val quantizedScaleMilli: Int,
    val tileX: Int,
    val tileY: Int,
    val isOledMode: Boolean
)

/**
 * Pure-Kotlin byte-budgeted LRU cache tracker for 512x512 dry-ink raster tiles.
 * Enforces the fixed 192 MB upper memory bound without allocating full-page bitmaps.
 */
class TileLruTracker<K, V>(
    val maxBytes: Long = DryInkRasterCache.DEFAULT_MAX_CACHE_BYTES,
    private val onEvict: (K, V) -> Unit = { _, _ -> }
) {
    private data class Entry<V>(
        val value: V,
        val sizeBytes: Long
    )

    private val map = LinkedHashMap<K, Entry<V>>(64, 0.75f, true)
    private var _usedBytes: Long = 0L

    val usedBytes: Long
        @Synchronized get() = _usedBytes

    val size: Int
        @Synchronized get() = map.size

    @Synchronized
    fun get(key: K): V? = map[key]?.value

    @Synchronized
    fun put(key: K, value: V, sizeBytes: Long) {
        require(sizeBytes > 0L) { "Tile sizeBytes must be positive" }
        val existing = map.remove(key)
        if (existing != null) {
            _usedBytes -= existing.sizeBytes
        }

        val iterator = map.entries.iterator()
        while (_usedBytes + sizeBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            _usedBytes -= eldest.value.sizeBytes
            iterator.remove()
            onEvict(eldest.key, eldest.value.value)
        }

        if (sizeBytes <= maxBytes) {
            map[key] = Entry(value, sizeBytes)
            _usedBytes += sizeBytes
        } else {
            onEvict(key, value)
        }
    }

    @Synchronized
    fun remove(key: K): V? {
        val removed = map.remove(key) ?: return null
        _usedBytes -= removed.sizeBytes
        return removed.value
    }

    @Synchronized
    fun removeWhere(predicate: (K) -> Boolean): Int {
        var count = 0
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                _usedBytes -= entry.value.sizeBytes
                iterator.remove()
                onEvict(entry.key, entry.value.value)
                count++
            }
        }
        return count
    }

    @Synchronized
    fun clear() {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            onEvict(entry.key, entry.value.value)
        }
        _usedBytes = 0L
    }
}

/**
 * Tiled offscreen raster cache for committed ("dry") ink strokes (Section 1).
 *
 * - Renders `512 x 512 px` (`ARGB_8888`, 1 MB each) tiles within a strict 192 MB LRU budget.
 * - Never allocates a full-page Bitmap and never iterates all strokes on the page per frame;
 *   each tile queries [SpatialIndex.queryRect] for only the strokes intersecting that tile.
 * - During pan/zoom gestures, existing cached tiles are transformed at 144 Hz; 100 ms after the
 *   gesture settles, [onGestureSettled] updates [bakedScale] so visible tiles re-render crisply
 *   from vector stroke geometry at the new zoom level.
 */
class DryInkRasterCache(
    maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES
) {
    private val cacheLock = Any()
    private val bgLock = Any()
    private val bitmapPool = ArrayDeque<Bitmap>(MAX_POOL_BITMAPS)
    private val tileLru = TileLruTracker<TileKey, Bitmap>(
        maxBytes = maxCacheBytes,
        onEvict = { _, bitmap ->
            synchronized(bitmapPool) {
                if (bitmapPool.size < MAX_POOL_BITMAPS && !bitmap.isRecycled) {
                    bitmapPool.addLast(bitmap)
                }
            }
        }
    )
    private val emptyTileKeys = HashSet<TileKey>(256)
    private val strokeRenderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create() }
    private val bgStrokeRenderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create() }

    private val tileStrokeMatrix = Matrix()
    private val bgTileStrokeMatrix = Matrix()
    private val tileBlitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val reusableEntriesScratch = ArrayList<IndexedDryStroke>(128)
    private val bgEntriesScratch = ArrayList<IndexedDryStroke>(256)
    private val reusableClipRect = RectF()
    private data class CompositePageSnapshot(
        val bitmap: Bitmap,
        val bakedScale: Float,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
        val templateType: PaperTemplateType,
        val isOledMode: Boolean,
        val generation: Long
    )

    private val compositeDrawMatrix = Matrix()
    private var stagingCompositeBitmap: Bitmap? = null
    private var stagingCompositeW: Int = 0
    private var stagingCompositeH: Int = 0

    @Volatile
    private var compositeSnapshot: CompositePageSnapshot? = null
    @Volatile
    private var compositeGeneration: Long = 0L
    @Volatile
    private var stableBakedScaleMilli: Int? = null
    @Volatile
    private var cacheGeneration: Long = 0L

    @Volatile
    var bakedScale: Float = 1.0f
        private set

    @Volatile
    var isGestureInProgress: Boolean = false
        private set

    val cachedTileCount: Int
        get() = synchronized(cacheLock) { tileLru.size }

    val usedCacheBytes: Long
        get() = synchronized(cacheLock) { tileLru.usedBytes }

    fun onGestureTransformChanged() {
        isGestureInProgress = true
    }

    fun onGestureSettled(currentScale: Float) {
        isGestureInProgress = false
        bakedScale = CanvasTransformMatrix.clampScale(currentScale)
    }

    fun syncInitialScale(scale: Float) {
        if (!isGestureInProgress) {
            bakedScale = CanvasTransformMatrix.clampScale(scale)
        }
    }

    /**
     * Invalidates every cached tile whose document-space bounds overlap [docRect].
     */
    fun invalidateDocRect(docRect: DocRect) {
        val padded = docRect.inflate(INVALIDATION_MARGIN_PT)
        synchronized(cacheLock) {
            compositeGeneration++
            compositeSnapshot = null
            tileLru.removeWhere { key ->
                val keyScale = key.quantizedScaleMilli / 1000f
                val tileDocRect = computeTileDocRect(key.tileX, key.tileY, keyScale)
                SpatialIndex.rectsOverlap(tileDocRect, padded)
            }
            emptyTileKeys.removeAll { key ->
                val keyScale = key.quantizedScaleMilli / 1000f
                val tileDocRect = computeTileDocRect(key.tileX, key.tileY, keyScale)
                SpatialIndex.rectsOverlap(tileDocRect, padded)
            }
        }
    }

    fun clearAll() {
        synchronized(cacheLock) {
            cacheGeneration++
            compositeGeneration++
            compositeSnapshot = null
            tileLru.clear()
            emptyTileKeys.clear()
            stableBakedScaleMilli = null
        }
    }

    /**
     * Draws the full fixed-page GPU composite bitmap in a single blit when valid and appropriate
     * for the current gesture/scale state. Returns true if drawn, or false to fall back to tiles.
     */
    fun drawCompositePageIfAvailable(
        canvas: Canvas,
        transformMatrix: CanvasTransformMatrix,
        paperConfig: PagePaperConfig,
        isOledMode: Boolean
    ): Boolean {
        if (paperConfig.canvasMode != CanvasMode.FIXED_PAGE) return false
        val snap = compositeSnapshot ?: return false
        if (snap.generation != compositeGeneration ||
            snap.templateType != paperConfig.templateType ||
            snap.isOledMode != isOledMode ||
            snap.pageWidthPt != paperConfig.pageDimensions.widthPt ||
            snap.pageHeightPt != paperConfig.pageDimensions.heightPt
        ) {
            return false
        }
        val currentScale = transformMatrix.snapshot().scale
        // During active pan/zoom, always blit the single GPU texture to preserve the 144Hz budget;
        // once settled and zoomed far past composite DPI, crisp per-tile caches take over.
        if (!isGestureInProgress && currentScale > snap.bakedScale * 1.15f) {
            return false
        }
        transformMatrix.populateDocToScreenMatrix(compositeDrawMatrix)
        val invScale = 1f / snap.bakedScale
        compositeDrawMatrix.preScale(invScale, invScale)
        canvas.drawBitmap(snap.bitmap, compositeDrawMatrix, tileBlitPaint)
        return true
    }

    /**
     * Pre-warms cached tiles covering both the visible viewport (+1 tile ring) and the full page
     * bounds so initial pan/zoom frames hit 100% O(1) cache lookups without UI-thread raster stalls.
     */
    fun prewarmVisibleTiles(
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        transformMatrix: CanvasTransformMatrix,
        spatialIndex: SpatialIndex<IndexedDryStroke>,
        isOledMode: Boolean,
        pageWidthPt: Float = 595f,
        pageHeightPt: Float = 842f,
        paperConfig: PagePaperConfig? = null,
        palette: StylusSurfacePalette? = null,
        strokeAdapter: (IndexedDryStroke) -> Stroke = { it.stroke }
    ) {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return
        val gen = cacheGeneration
        val compGen = compositeGeneration
        val snapshot = transformMatrix.snapshot()
        val clampedScale = CanvasTransformMatrix.clampScale(snapshot.scale)
        val quantizedMilli = quantizeScaleMilli(clampedScale)
        val effectiveScale = quantizedMilli / 1000f

        if (spatialIndex.size > 0) {
            val visibleDocRect = transformMatrix.screenRectToDocRect(
                leftPx = 0f,
                topPx = 0f,
                rightPx = viewportWidthPx.toFloat(),
                bottomPx = viewportHeightPx.toFloat()
            )
            val visibleRange = computeOverlappingTileRange(visibleDocRect, effectiveScale)
            val pageRange = computeOverlappingTileRange(
                docRect = DocRect(0f, 0f, pageWidthPt.coerceAtLeast(1f), pageHeightPt.coerceAtLeast(1f)),
                scale = effectiveScale
            )
            val minTx = min(visibleRange.minTileX - 1, pageRange.minTileX)
            val maxTx = max(visibleRange.maxTileX + 1, pageRange.maxTileX)
            val minTy = min(visibleRange.minTileY - 1, pageRange.minTileY)
            val maxTy = max(visibleRange.maxTileY + 1, pageRange.maxTileY)

            rasterizeTileRangeInBackground(
                expectedGen = gen,
                quantizedMilli = quantizedMilli,
                effectiveScale = effectiveScale,
                minTx = minTx,
                maxTx = maxTx,
                minTy = minTy,
                maxTy = maxTy,
                spatialIndex = spatialIndex,
                isOledMode = isOledMode,
                strokeAdapter = strokeAdapter
            )
        }

        if (paperConfig != null && palette != null) {
            rebuildCompositePageSnapshot(
                expectedCacheGen = gen,
                expectedCompositeGen = compGen,
                desiredScale = clampedScale,
                spatialIndex = spatialIndex,
                paperConfig = paperConfig,
                palette = palette,
                strokeAdapter = strokeAdapter
            )
        }

        if (cacheGeneration == gen) {
            bakedScale = clampedScale
            stableBakedScaleMilli = quantizedMilli
            isGestureInProgress = false
        }
    }

    /**
     * Re-bakes visible (+1 ring) tiles at [targetScale] on a background thread after a gesture
     * settles, flipping [bakedScale] only once the crisp tiles are ready in cache.
     */
    fun rebakeSettledScale(
        targetScale: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        transformMatrix: CanvasTransformMatrix,
        spatialIndex: SpatialIndex<IndexedDryStroke>,
        isOledMode: Boolean,
        paperConfig: PagePaperConfig? = null,
        palette: StylusSurfacePalette? = null,
        strokeAdapter: (IndexedDryStroke) -> Stroke = { it.stroke }
    ) {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) {
            onGestureSettled(targetScale)
            return
        }
        val gen = cacheGeneration
        val compGen = compositeGeneration
        val clampedTarget = CanvasTransformMatrix.clampScale(targetScale)
        val targetMilli = quantizeScaleMilli(clampedTarget)
        val effectiveScale = targetMilli / 1000f

        if (spatialIndex.size > 0) {
            val visibleDocRect = transformMatrix.screenRectToDocRect(
                leftPx = 0f,
                topPx = 0f,
                rightPx = viewportWidthPx.toFloat(),
                bottomPx = viewportHeightPx.toFloat()
            )
            val range = computeOverlappingTileRange(visibleDocRect, effectiveScale)

            rasterizeTileRangeInBackground(
                expectedGen = gen,
                quantizedMilli = targetMilli,
                effectiveScale = effectiveScale,
                minTx = range.minTileX - 1,
                maxTx = range.maxTileX + 1,
                minTy = range.minTileY - 1,
                maxTy = range.maxTileY + 1,
                spatialIndex = spatialIndex,
                isOledMode = isOledMode,
                strokeAdapter = strokeAdapter
            )
        }

        if (paperConfig != null && palette != null) {
            rebuildCompositePageSnapshot(
                expectedCacheGen = gen,
                expectedCompositeGen = compGen,
                desiredScale = clampedTarget,
                spatialIndex = spatialIndex,
                paperConfig = paperConfig,
                palette = palette,
                strokeAdapter = strokeAdapter
            )
        }

        if (cacheGeneration == gen) {
            bakedScale = clampedTarget
            stableBakedScaleMilli = targetMilli
            isGestureInProgress = false
        }
    }

    private fun rebuildCompositePageSnapshot(
        expectedCacheGen: Long,
        expectedCompositeGen: Long,
        desiredScale: Float,
        spatialIndex: SpatialIndex<IndexedDryStroke>,
        paperConfig: PagePaperConfig,
        palette: StylusSurfacePalette,
        strokeAdapter: (IndexedDryStroke) -> Stroke
    ) {
        if (paperConfig.canvasMode != CanvasMode.FIXED_PAGE) {
            compositeSnapshot = null
            return
        }
        if (cacheGeneration != expectedCacheGen || compositeGeneration != expectedCompositeGen) return

        val pageWidthPt = paperConfig.pageDimensions.widthPt.coerceAtLeast(1f)
        val pageHeightPt = paperConfig.pageDimensions.heightPt.coerceAtLeast(1f)
        val maxEdgePt = max(pageWidthPt, pageHeightPt)
        val compositeScale = min(max(desiredScale, 2.0f), MAX_COMPOSITE_EDGE_PX / maxEdgePt)
            .coerceAtLeast(0.5f)

        val existing = compositeSnapshot
        if (existing != null &&
            existing.generation == expectedCompositeGen &&
            existing.templateType == paperConfig.templateType &&
            existing.isOledMode == palette.isOledDeepBlack &&
            existing.pageWidthPt == pageWidthPt &&
            existing.pageHeightPt == pageHeightPt &&
            abs(existing.bakedScale - compositeScale) < 0.001f
        ) {
            return
        }

        val pageDocRect = DocRect(0f, 0f, pageWidthPt, pageHeightPt)
        val pageEntries = spatialIndex.queryRect(pageDocRect)
        // Skip composite caching if any dry stroke lies outside the fixed sheet bounds.
        if (pageEntries.size < spatialIndex.size) {
            compositeSnapshot = null
            return
        }

        val bmpWidth = ceil(pageWidthPt * compositeScale).toInt().coerceAtLeast(1)
        val bmpHeight = ceil(pageHeightPt * compositeScale).toInt().coerceAtLeast(1)

        val uploadedBitmap = synchronized(bgLock) {
            if (cacheGeneration != expectedCacheGen || compositeGeneration != expectedCompositeGen) {
                return
            }
            val staging = stagingCompositeBitmap?.takeIf {
                stagingCompositeW == bmpWidth && stagingCompositeH == bmpHeight && !it.isRecycled
            } ?: Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888).also { created ->
                stagingCompositeBitmap = created
                stagingCompositeW = bmpWidth
                stagingCompositeH = bmpHeight
            }
            staging.eraseColor(AndroidColor.TRANSPARENT)
            val stagingCanvas = Canvas(staging)
            PaperBackgroundRenderer.drawFixedPageIntoBitmapCanvas(
                canvas = stagingCanvas,
                pageScale = compositeScale,
                paperConfig = paperConfig,
                palette = palette
            )

            if (pageEntries.isNotEmpty()) {
                bgEntriesScratch.clear()
                for (i in 0 until pageEntries.size) {
                    bgEntriesScratch.add(pageEntries[i].payload)
                }
                if (bgEntriesScratch.size > 1) {
                    bgEntriesScratch.sortWith { a, b -> a.zIndex.compareTo(b.zIndex) }
                }
                bgTileStrokeMatrix.reset()
                bgTileStrokeMatrix.setScale(compositeScale, compositeScale)
                for (i in 0 until bgEntriesScratch.size) {
                    if (cacheGeneration != expectedCacheGen || compositeGeneration != expectedCompositeGen) {
                        bgEntriesScratch.clear()
                        return
                    }
                    val renderStroke = strokeAdapter(bgEntriesScratch[i])
                    stagingCanvas.withSave {
                        concat(bgTileStrokeMatrix)
                        bgStrokeRenderer.draw(
                            canvas = this,
                            stroke = renderStroke,
                            strokeToScreenTransform = bgTileStrokeMatrix
                        )
                    }
                }
                bgEntriesScratch.clear()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                staging.copy(Bitmap.Config.HARDWARE, false)
                    ?: staging.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                staging.copy(Bitmap.Config.ARGB_8888, false)
            }
        } ?: return

        synchronized(cacheLock) {
            if (cacheGeneration == expectedCacheGen && compositeGeneration == expectedCompositeGen) {
                compositeSnapshot = CompositePageSnapshot(
                    bitmap = uploadedBitmap,
                    bakedScale = compositeScale,
                    pageWidthPt = pageWidthPt,
                    pageHeightPt = pageHeightPt,
                    templateType = paperConfig.templateType,
                    isOledMode = palette.isOledDeepBlack,
                    generation = expectedCompositeGen
                )
            }
        }
    }

    private fun rasterizeTileRangeInBackground(
        expectedGen: Long,
        quantizedMilli: Int,
        effectiveScale: Float,
        minTx: Int,
        maxTx: Int,
        minTy: Int,
        maxTy: Int,
        spatialIndex: SpatialIndex<IndexedDryStroke>,
        isOledMode: Boolean,
        strokeAdapter: (IndexedDryStroke) -> Stroke
    ) {
        for (ty in minTy..maxTy) {
            for (tx in minTx..maxTx) {
                if (cacheGeneration != expectedGen) return
                val key = TileKey(quantizedMilli, tx, ty, isOledMode)
                val alreadyPresent = synchronized(cacheLock) {
                    emptyTileKeys.contains(key) || tileLru.get(key) != null
                }
                if (alreadyPresent) continue

                val tileDocRect = computeTileDocRect(tx, ty, effectiveScale)
                val strokesInTile = spatialIndex.queryRect(tileDocRect)
                if (strokesInTile.isEmpty()) {
                    synchronized(cacheLock) {
                        if (cacheGeneration == expectedGen) {
                            recordEmptyTileKeyLocked(key)
                        }
                    }
                    continue
                }
                rasterizeTileInternal(
                    key = key,
                    tileDocRect = tileDocRect,
                    effectiveBakedScale = effectiveScale,
                    spatialEntries = strokesInTile,
                    strokeAdapter = strokeAdapter,
                    useBackgroundRenderer = true,
                    expectedGen = expectedGen
                )
            }
        }
    }

    /**
     * Draws all visible dry strokes into [canvas] using O(1) cached 512x512 tiles first.
     * Only misses invoke [SpatialIndex.queryRect], with a per-frame rasterization budget so
     * zoom-settle never blows the 144 Hz (6.94 ms) frame ceiling.
     *
     * Returns `true` if any missed tile was deferred to the next frame so the caller can
     * schedule an immediate follow-up frame.
     */
    fun drawVisibleTiles(
        canvas: Canvas,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        transformMatrix: CanvasTransformMatrix,
        spatialIndex: SpatialIndex<IndexedDryStroke>,
        isOledMode: Boolean,
        strokeAdapter: (IndexedDryStroke) -> Stroke = { it.stroke }
    ): Boolean {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return false
        if (spatialIndex.size == 0) return false

        val snapshot = transformMatrix.snapshot()
        val activeBakedScale = if (isGestureInProgress) {
            bakedScale
        } else {
            bakedScale = snapshot.scale
            snapshot.scale
        }
        val quantizedMilli = quantizeScaleMilli(activeBakedScale)
        val effectiveBakedScale = quantizedMilli / 1000f
        val fallbackMilli = stableBakedScaleMilli ?: quantizedMilli

        val visibleDocRect = transformMatrix.screenRectToDocRect(
            leftPx = 0f,
            topPx = 0f,
            rightPx = viewportWidthPx.toFloat(),
            bottomPx = viewportHeightPx.toFloat()
        )
        val range = computeOverlappingTileRange(visibleDocRect, effectiveBakedScale)
        val tileDocSize = TILE_SIZE_PX / effectiveBakedScale
        val relScale = snapshot.scale / effectiveBakedScale

        val frameStartNanos = System.nanoTime()
        var rasterizedThisFrame = 0
        var hasDeferredTiles = false

        for (ty in range.minTileY..range.maxTileY) {
            val tileTopPt = ty * tileDocSize
            val screenY = tileTopPt * snapshot.scale + snapshot.panYPx
            for (tx in range.minTileX..range.maxTileX) {
                val key = TileKey(
                    quantizedScaleMilli = quantizedMilli,
                    tileX = tx,
                    tileY = ty,
                    isOledMode = isOledMode
                )
                // O(1) fast path: skip known empty tiles and draw cached bitmaps without R-tree queries.
                var isKnownEmpty: Boolean
                var tileBitmap: Bitmap?
                synchronized(cacheLock) {
                    isKnownEmpty = emptyTileKeys.contains(key)
                    tileBitmap = if (isKnownEmpty) null else tileLru.get(key)
                }
                if (isKnownEmpty) continue

                if (tileBitmap == null) {
                    val drewFallback = fallbackMilli != quantizedMilli &&
                        drawFallbackTilesForRect(
                            canvas = canvas,
                            targetTileLeftPt = tx * tileDocSize,
                            targetTileTopPt = tileTopPt,
                            targetTileSizePt = tileDocSize,
                            fallbackMilli = fallbackMilli,
                            isOledMode = isOledMode,
                            snapshotScale = snapshot.scale,
                            snapshotPanXPx = snapshot.panXPx,
                            snapshotPanYPx = snapshot.panYPx
                        )
                    // Never block active gesture frames with UI-thread rasterization; wait for background rebake.
                    if (isGestureInProgress || drewFallback) {
                        if (!isGestureInProgress) {
                            hasDeferredTiles = true
                        }
                        continue
                    }

                    val withinFrameBudget = rasterizedThisFrame == 0 ||
                        (System.nanoTime() - frameStartNanos) < FRAME_RASTER_BUDGET_NANOS
                    if (!withinFrameBudget) {
                        hasDeferredTiles = true
                        continue
                    }

                    val tileLeftPt = tx * tileDocSize
                    val tileDocRect = DocRect(
                        leftPt = tileLeftPt,
                        topPt = tileTopPt,
                        rightPt = tileLeftPt + tileDocSize,
                        bottomPt = tileTopPt + tileDocSize
                    )
                    val strokesInTile = spatialIndex.queryRect(tileDocRect)
                    if (strokesInTile.isEmpty()) {
                        synchronized(cacheLock) {
                            recordEmptyTileKeyLocked(key)
                        }
                        continue
                    }
                    tileBitmap = rasterizeTileInternal(
                        key = key,
                        tileDocRect = tileDocRect,
                        effectiveBakedScale = effectiveBakedScale,
                        spatialEntries = strokesInTile,
                        strokeAdapter = strokeAdapter,
                        useBackgroundRenderer = false
                    )
                    rasterizedThisFrame++
                }

                val screenX = (tx * tileDocSize) * snapshot.scale + snapshot.panXPx
                canvas.withSave {
                    translate(screenX, screenY)
                    if (relScale != 1f) {
                        scale(relScale, relScale)
                    }
                    // Paint with bilinear filtering only while temporarily scaled during pan/zoom.
                    tileBlitPaint.isFilterBitmap = relScale != 1f
                    drawBitmap(tileBitmap, 0f, 0f, tileBlitPaint)
                }
            }
        }

        if (!hasDeferredTiles && !isGestureInProgress) {
            stableBakedScaleMilli = quantizedMilli
        }
        return hasDeferredTiles
    }

    private fun recordEmptyTileKeyLocked(key: TileKey) {
        if (emptyTileKeys.size >= MAX_EMPTY_TILE_KEYS) {
            emptyTileKeys.clear()
        }
        emptyTileKeys.add(key)
    }

    private fun drawFallbackTilesForRect(
        canvas: Canvas,
        targetTileLeftPt: Float,
        targetTileTopPt: Float,
        targetTileSizePt: Float,
        fallbackMilli: Int,
        isOledMode: Boolean,
        snapshotScale: Float,
        snapshotPanXPx: Float,
        snapshotPanYPx: Float
    ): Boolean {
        val fallbackScale = fallbackMilli / 1000f
        val docRect = DocRect(
            leftPt = targetTileLeftPt,
            topPt = targetTileTopPt,
            rightPt = targetTileLeftPt + targetTileSizePt,
            bottomPt = targetTileTopPt + targetTileSizePt
        )
        val fbRange = computeOverlappingTileRange(docRect, fallbackScale)
        val fbDocSize = TILE_SIZE_PX / fallbackScale
        val fbRelScale = snapshotScale / fallbackScale

        reusableClipRect.set(
            targetTileLeftPt * snapshotScale + snapshotPanXPx,
            targetTileTopPt * snapshotScale + snapshotPanYPx,
            (targetTileLeftPt + targetTileSizePt) * snapshotScale + snapshotPanXPx,
            (targetTileTopPt + targetTileSizePt) * snapshotScale + snapshotPanYPx
        )

        var drewAny = false
        canvas.withSave {
            clipRect(reusableClipRect)
            tileBlitPaint.isFilterBitmap = true
            for (fy in fbRange.minTileY..fbRange.maxTileY) {
                for (fx in fbRange.minTileX..fbRange.maxTileX) {
                    val fbKey = TileKey(fallbackMilli, fx, fy, isOledMode)
                    val fbBitmap = synchronized(cacheLock) { tileLru.get(fbKey) } ?: continue
                    drewAny = true
                    val sx = (fx * fbDocSize) * snapshotScale + snapshotPanXPx
                    val sy = (fy * fbDocSize) * snapshotScale + snapshotPanYPx
                    withSave {
                        translate(sx, sy)
                        if (fbRelScale != 1f) {
                            scale(fbRelScale, fbRelScale)
                        }
                        drawBitmap(fbBitmap, 0f, 0f, tileBlitPaint)
                    }
                }
            }
        }
        return drewAny
    }

    private fun rasterizeTileInternal(
        key: TileKey,
        tileDocRect: DocRect,
        effectiveBakedScale: Float,
        spatialEntries: List<hu.bdev.stylusnote.core.math.SpatialEntry<IndexedDryStroke>>,
        strokeAdapter: (IndexedDryStroke) -> Stroke,
        useBackgroundRenderer: Boolean,
        expectedGen: Long = cacheGeneration
    ): Bitmap {
        val bitmap = if (useBackgroundRenderer) {
            synchronized(bgLock) {
                renderTileBitmapWithScratch(
                    tileDocRect = tileDocRect,
                    effectiveBakedScale = effectiveBakedScale,
                    spatialEntries = spatialEntries,
                    strokeAdapter = strokeAdapter,
                    entriesScratch = bgEntriesScratch,
                    matrix = bgTileStrokeMatrix,
                    renderer = bgStrokeRenderer
                )
            }
        } else {
            renderTileBitmapWithScratch(
                tileDocRect = tileDocRect,
                effectiveBakedScale = effectiveBakedScale,
                spatialEntries = spatialEntries,
                strokeAdapter = strokeAdapter,
                entriesScratch = reusableEntriesScratch,
                matrix = tileStrokeMatrix,
                renderer = strokeRenderer
            )
        }

        synchronized(cacheLock) {
            if (cacheGeneration == expectedGen) {
                emptyTileKeys.remove(key)
                tileLru.put(key, bitmap, TILE_BYTES_ARGB_8888)
            }
        }
        return bitmap
    }

    private fun renderTileBitmapWithScratch(
        tileDocRect: DocRect,
        effectiveBakedScale: Float,
        spatialEntries: List<hu.bdev.stylusnote.core.math.SpatialEntry<IndexedDryStroke>>,
        strokeAdapter: (IndexedDryStroke) -> Stroke,
        entriesScratch: ArrayList<IndexedDryStroke>,
        matrix: Matrix,
        renderer: CanvasStrokeRenderer
    ): Bitmap {
        entriesScratch.clear()
        for (i in 0 until spatialEntries.size) {
            entriesScratch.add(spatialEntries[i].payload)
        }
        if (entriesScratch.size > 1) {
            entriesScratch.sortWith { a, b -> a.zIndex.compareTo(b.zIndex) }
        }

        val bitmap = synchronized(bitmapPool) { bitmapPool.removeLastOrNull() }
            ?.also { it.eraseColor(AndroidColor.TRANSPARENT) }
            ?: Bitmap.createBitmap(TILE_SIZE_PX, TILE_SIZE_PX, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(bitmap)

        // Map document points inside [tileDocRect] into [0..512] tile pixel space.
        matrix.reset()
        matrix.setTranslate(-tileDocRect.leftPt, -tileDocRect.topPt)
        matrix.postScale(effectiveBakedScale, effectiveBakedScale)

        for (i in 0 until entriesScratch.size) {
            val renderStroke = strokeAdapter(entriesScratch[i])
            tileCanvas.withSave {
                // CanvasStrokeRenderer requires strokeToScreenTransform pre-concatenated on the Canvas.
                concat(matrix)
                renderer.draw(
                    canvas = this,
                    stroke = renderStroke,
                    strokeToScreenTransform = matrix
                )
            }
        }
        entriesScratch.clear()
        return bitmap
    }

    companion object {
        const val TILE_SIZE_PX: Int = 512
        const val TILE_BYTES_ARGB_8888: Long = TILE_SIZE_PX.toLong() * TILE_SIZE_PX.toLong() * 4L
        const val DEFAULT_MAX_CACHE_BYTES: Long = 192L * 1024L * 1024L
        const val SETTLE_DEBOUNCE_MS: Long = 100L
        private const val INVALIDATION_MARGIN_PT: Float = 2f
        private const val MAX_POOL_BITMAPS: Int = 32
        private const val MAX_EMPTY_TILE_KEYS: Int = 4096
        private const val FRAME_RASTER_BUDGET_NANOS: Long = 2_800_000L
        private const val MAX_COMPOSITE_EDGE_PX: Float = 2048f

        fun quantizeScaleMilli(scale: Float): Int =
            max(1, (CanvasTransformMatrix.clampScale(scale) * 1000f).roundToInt())

        fun computeTileDocRect(tileX: Int, tileY: Int, scale: Float): DocRect {
            val tileDocSize = TILE_SIZE_PX / scale
            val left = tileX * tileDocSize
            val top = tileY * tileDocSize
            return DocRect(
                leftPt = left,
                topPt = top,
                rightPt = left + tileDocSize,
                bottomPt = top + tileDocSize
            )
        }

        fun computeOverlappingTileRange(docRect: DocRect, scale: Float): TileRange {
            val minTx = floor((docRect.leftPt * scale) / TILE_SIZE_PX).toInt()
            val maxTx = ceil((docRect.rightPt * scale) / TILE_SIZE_PX).toInt() - 1
            val minTy = floor((docRect.topPt * scale) / TILE_SIZE_PX).toInt()
            val maxTy = ceil((docRect.bottomPt * scale) / TILE_SIZE_PX).toInt() - 1
            return TileRange(
                minTileX = minTx,
                maxTileX = max(minTx, maxTx),
                minTileY = minTy,
                maxTileY = max(minTy, maxTy)
            )
        }
    }
}
