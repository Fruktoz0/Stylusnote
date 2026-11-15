package hu.bdev.stylusnote.domain.repository

import androidx.annotation.WorkerThread
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.hardware.toStrokeInputBatch
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.core.math.SpatialIndex
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.CanvasAction
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.mergeBounds
import hu.bdev.stylusnote.ui.canvas.DryStrokeStore
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import hu.bdev.stylusnote.ui.canvas.computeStrokeDocBounds
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of transforming a combined Lasso selection of strokes and/or [PageObject]s. */
data class SelectionTransformResult(
    val affectedBounds: DocRect?,
    val updatedObjects: List<PageObject>
)

/** Result of duplicating a combined Lasso selection of strokes and/or [PageObject]s. */
data class CombinedDuplicateResult(
    val duplicatedStrokes: List<IndexedDryStroke>,
    val duplicatedObjects: List<PageObject>,
    val affectedBounds: DocRect?
)

/** Result of recoloring a combined Lasso selection of strokes and/or [PageObject]s. */
data class SelectionRecolorResult(
    val affectedBounds: DocRect?,
    val updatedObjects: List<PageObject>
)

/**
 * Pure-Kotlin result of splitting a polyline against a circular eraser footprint.
 */
data class SplitGeometryResult(
    val didCut: Boolean,
    val segments: List<List<StylusPointSample>>
)

/**
 * Outcome of a single precision-eraser step, carrying the dirty document rectangle for
 * [hu.bdev.stylusnote.ui.canvas.DryInkRasterCache.invalidateDocRect] and counts of removed/created strokes.
 */
data class PrecisionEraseStepResult(
    val invalidatedBounds: DocRect?,
    val removedStrokeCount: Int,
    val createdSegmentCount: Int
) {
    val didModifyCanvas: Boolean
        get() = removedStrokeCount > 0 || createdSegmentCount > 0
}

/**
 * Pure-Kotlin geometric algorithms for segment-circle distance checking and precision stroke splitting.
 * Separated from Android framework types so both JVM unit tests and `@WorkerThread` execution share
 * identical math.
 */
object PrecisionStrokeGeometry {
    private const val EPSILON = 1e-6f
    private const val MIN_POINT_DEDUP_DIST_SQ = 1e-4f

    fun doesPointsIntersectCircle(
        points: List<StylusPointSample>,
        centerXPt: Float,
        centerYPt: Float,
        effectiveRadiusPt: Float
    ): Boolean {
        if (points.isEmpty() || effectiveRadiusPt < 0f) return false
        val radiusSq = effectiveRadiusPt * effectiveRadiusPt

        if (points.size == 1) {
            val dx = points[0].xPt - centerXPt
            val dy = points[0].yPt - centerYPt
            return dx * dx + dy * dy <= radiusSq
        }

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (pointToSegmentDistanceSq(centerXPt, centerYPt, a.xPt, a.yPt, b.xPt, b.yPt) <= radiusSq) {
                return true
            }
        }
        return false
    }

    /**
     * Subtracts the interior of the circle ([centerXPt], [centerYPt], [cutRadiusPt]) from [points],
     * interpolating exact boundary entry/exit vertices along segments that cross the circle so fast
     * strokes split cleanly into 0..N contiguous point chains without jagged gaps.
     * Single-point fragments (`size < 2`) are discarded unless the input stroke itself was a single-point
     * dot outside the eraser circle.
     */
    fun splitPointsByCircle(
        points: List<StylusPointSample>,
        centerXPt: Float,
        centerYPt: Float,
        cutRadiusPt: Float
    ): SplitGeometryResult {
        if (points.isEmpty() || cutRadiusPt <= 0f) {
            return SplitGeometryResult(didCut = false, segments = listOf(points))
        }

        val radiusSq = cutRadiusPt * cutRadiusPt
        if (points.size == 1) {
            val p = points[0]
            val dx = p.xPt - centerXPt
            val dy = p.yPt - centerYPt
            return if (dx * dx + dy * dy <= radiusSq) {
                SplitGeometryResult(didCut = true, segments = emptyList())
            } else {
                SplitGeometryResult(didCut = false, segments = listOf(points))
            }
        }

        val completedChains = ArrayList<MutableList<StylusPointSample>>()
        var currentChain: MutableList<StylusPointSample>? = null
        var didCut = false

        val first = points[0]
        val firstInside = distSq(first.xPt, first.yPt, centerXPt, centerYPt) <= radiusSq
        if (!firstInside) {
            currentChain = ArrayList<StylusPointSample>().apply { add(first) }
        } else {
            didCut = true
        }

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val clip = computeSegmentCircleClip(
                ax = a.xPt,
                ay = a.yPt,
                bx = b.xPt,
                by = b.yPt,
                cx = centerXPt,
                cy = centerYPt,
                radiusPt = cutRadiusPt
            )

            if (!clip.intersectsCircle) {
                if (currentChain == null) {
                    currentChain = ArrayList<StylusPointSample>().apply { add(a) }
                }
                appendUniqueSample(currentChain, b)
                continue
            }

            // Entire segment lies strictly inside or on the eraser boundary.
            if (clip.enterT <= EPSILON && clip.exitT >= 1f - EPSILON) {
                didCut = true
                if (currentChain != null) {
                    if (currentChain.size >= 2) {
                        completedChains.add(currentChain)
                    }
                    currentChain = null
                }
                continue
            }

            // Segment intersects the interior of the circle over (enterT, exitT) within [0, 1].
            didCut = true

            // Segment enters the circle at enterT in (0, 1): close the preceding chain with the entry vertex.
            if (clip.enterT > EPSILON && clip.enterT < 1f - EPSILON) {
                val enterSample = interpolateSample(a, b, clip.enterT)
                val chain = currentChain ?: ArrayList<StylusPointSample>().apply { add(a) }
                appendUniqueSample(chain, enterSample)
                if (chain.size >= 2) {
                    completedChains.add(chain)
                }
                currentChain = null
            } else if (clip.enterT <= EPSILON && currentChain != null) {
                if (currentChain.size >= 2) {
                    completedChains.add(currentChain)
                }
                currentChain = null
            }

            // Segment exits the circle at exitT in (0, 1): open a new chain starting from the exit vertex.
            if (clip.exitT > EPSILON && clip.exitT < 1f - EPSILON) {
                val exitSample = interpolateSample(a, b, clip.exitT)
                val newChain = ArrayList<StylusPointSample>()
                newChain.add(exitSample)
                appendUniqueSample(newChain, b)
                currentChain = newChain
            }
        }

        if (currentChain != null && currentChain.size >= 2) {
            completedChains.add(currentChain)
        }

        if (!didCut) {
            return SplitGeometryResult(didCut = false, segments = listOf(points))
        }

        val validSegments = completedChains.filter { it.size >= 2 }
        return SplitGeometryResult(didCut = true, segments = validSegments)
    }

    private data class SegmentClip(
        val intersectsCircle: Boolean,
        val enterT: Float,
        val exitT: Float
    )

    private fun computeSegmentCircleClip(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
        radiusPt: Float
    ): SegmentClip {
        val dx = bx - ax
        val dy = by - ay
        val fx = ax - cx
        val fy = ay - cy

        val a = dx * dx + dy * dy
        val rSq = radiusPt * radiusPt

        if (a <= 1e-8f) {
            val inside = fx * fx + fy * fy <= rSq
            return if (inside) SegmentClip(true, 0f, 1f) else SegmentClip(false, 1f, 0f)
        }

        val b = 2f * (fx * dx + fy * dy)
        val c = fx * fx + fy * fy - rSq
        val discriminant = b * b - 4f * a * c
        if (discriminant <= 0f) {
            return SegmentClip(false, 1f, 0f)
        }

        val sqrtD = sqrt(discriminant)
        val t1 = (-b - sqrtD) / (2f * a)
        val t2 = (-b + sqrtD) / (2f * a)

        if (t2 <= EPSILON || t1 >= 1f - EPSILON) {
            return SegmentClip(false, 1f, 0f)
        }

        return SegmentClip(
            intersectsCircle = true,
            enterT = max(0f, t1),
            exitT = min(1f, t2)
        )
    }

    private fun appendUniqueSample(
        chain: MutableList<StylusPointSample>,
        candidate: StylusPointSample
    ) {
        val prev = chain.lastOrNull()
        if (prev == null) {
            chain.add(candidate)
            return
        }
        val dSq = distSq(prev.xPt, prev.yPt, candidate.xPt, candidate.yPt)
        val safeElapsed = max(prev.elapsedTimeMillis, candidate.elapsedTimeMillis)
        if (dSq >= MIN_POINT_DEDUP_DIST_SQ) {
            chain.add(
                if (safeElapsed != candidate.elapsedTimeMillis) {
                    candidate.copy(elapsedTimeMillis = safeElapsed)
                } else {
                    candidate
                }
            )
        }
    }

    private fun interpolateSample(
        a: StylusPointSample,
        b: StylusPointSample,
        tRaw: Float
    ): StylusPointSample {
        val t = tRaw.coerceIn(0f, 1f)
        val elapsed = (a.elapsedTimeMillis + (b.elapsedTimeMillis - a.elapsedTimeMillis) * t).toLong()
        return StylusPointSample(
            xPt = a.xPt + (b.xPt - a.xPt) * t,
            yPt = a.yPt + (b.yPt - a.yPt) * t,
            elapsedTimeMillis = max(a.elapsedTimeMillis, elapsed),
            pressure = a.pressure + (b.pressure - a.pressure) * t,
            tiltRadians = a.tiltRadians + (b.tiltRadians - a.tiltRadians) * t,
            orientationRadians = lerpAngleRadians(a.orientationRadians, b.orientationRadians, t),
            isEraserTool = a.isEraserTool
        )
    }

    private fun lerpAngleRadians(a: Float, b: Float, t: Float): Float {
        val twoPi = (Math.PI * 2.0).toFloat()
        var diff = (b - a) % twoPi
        if (diff > Math.PI) diff -= twoPi
        if (diff < -Math.PI) diff += twoPi
        return ((a + diff * t) % twoPi + twoPi) % twoPi
    }

    internal fun pointToSegmentDistanceSq(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val wx = px - ax
        val wy = py - ay
        val lenSq = vx * vx + vy * vy
        if (lenSq <= 1e-8f) return distSq(px, py, ax, ay)
        val proj = ((wx * vx + wy * vy) / lenSq).coerceIn(0f, 1f)
        val closestX = ax + proj * vx
        val closestY = ay + proj * vy
        return distSq(px, py, closestX, closestY)
    }

    private fun distSq(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }
}

/**
 * Stateful canvas domain repository uniting:
 * - [DryStrokeStore] (`spatialIndex` R-Tree + layer-separated Highlighter/Ink z-ordering)
 * - Delta-based Undo/Redo stack (`minUndoSteps >= 50` + point-budget trimming)
 * - Vector Eraser (`PartitionedMesh.intersects` + circular distance check)
 * - Precision Eraser (`@WorkerThread` geometric segment-circle interpolation splitting a stroke into N pieces)
 * - Lasso / Property delta mutators (`transformStrokes`, `recolorStrokes`)
 *
 * All structural mutations synchronize cleanly on [stateLock] so background worker erasing/splitting
 * and draw-phase spatial queries never observe partial state.
 */
class CanvasStateHolder(
    private val minUndoSteps: Int = DEFAULT_MIN_UNDO_STEPS,
    private val maxUndoSteps: Int = DEFAULT_MAX_UNDO_STEPS,
    private val maxPointBudget: Int = DEFAULT_MAX_POINT_BUDGET,
    private val uuidGenerator: () -> String = { UUID.randomUUID().toString() }
) : DryStrokeStore {

    init {
        require(minUndoSteps >= 1) { "minUndoSteps must be >= 1" }
        require(maxUndoSteps >= minUndoSteps) { "maxUndoSteps must be >= minUndoSteps" }
        require(maxPointBudget >= 1) { "maxPointBudget must be >= 1" }
    }

    override val spatialIndex: SpatialIndex<IndexedDryStroke> = SpatialIndex()

    private val stateLock = Any()
    private val strokesById = LinkedHashMap<String, IndexedDryStroke>()
    private val objectsById = LinkedHashMap<String, PageObject>()
    private val shapeMetaById = ConcurrentHashMap<String, ShapeMeta>()
    private val undoStack = ArrayDeque<CanvasAction>()
    private val redoStack = ArrayDeque<CanvasAction>()
    private var totalUndoPointCost: Int = 0

    // Separate z-order bands ensure Layer 3 (Highlighters) always renders underneath Layer 4 (Ink).
    private val nextHighlighterZ = AtomicLong(1L)
    private val nextInkZ = AtomicLong(INK_Z_ORDER_BASE)
    private val nextObjectZ = AtomicLong(OBJECT_Z_ORDER_BASE)

    private var activeEraserSession: EraserSessionAccumulator? = null

    val canUndo: Boolean
        get() = synchronized(stateLock) { undoStack.isNotEmpty() }

    val canRedo: Boolean
        get() = synchronized(stateLock) { redoStack.isNotEmpty() }

    val undoDepth: Int
        get() = synchronized(stateLock) { undoStack.size }

    val redoDepth: Int
        get() = synchronized(stateLock) { redoStack.size }

    val strokeCount: Int
        get() = synchronized(stateLock) { strokesById.size }

    /**
     * Allocates a strictly ordered z-index honoring the 4-layer stack:
     * Highlighters always receive lower indices (`< 1_000_000_000`) than normal ink strokes.
     */
    fun allocateZIndex(isHighlighter: Boolean): Long = if (isHighlighter) {
        nextHighlighterZ.getAndIncrement()
    } else {
        nextInkZ.getAndIncrement()
    }

    fun getStroke(id: String): IndexedDryStroke? = synchronized(stateLock) { strokesById[id] }

    fun allStrokesSorted(): List<IndexedDryStroke> = synchronized(stateLock) {
        strokesById.values.sortedBy { it.zIndex }
    }

    /**
     * Commits newly authored wet->dry strokes from [hu.bdev.stylusnote.ui.canvas.StylusDrawingSurface]
     * and pushes an atomic [CanvasAction.AddedStrokes] delta onto the Undo stack.
     */
    override fun addCommittedStrokes(strokes: List<IndexedDryStroke>) {
        if (strokes.isEmpty()) return
        synchronized(stateLock) {
            val normalized = strokes.map { ensureProperLayerZIndex(it) }
            insertStrokesLocked(normalized)
            pushUndoActionLocked(CanvasAction.AddedStrokes(normalized))
        }
    }

    /**
     * Removes a single stroke by [id] and records a [CanvasAction.RemovedStrokes] delta if found.
     */
    override fun removeStroke(id: String): Boolean = synchronized(stateLock) {
        val existing = strokesById[id] ?: return false
        removeStrokesByIdLocked(listOf(id))
        pushUndoActionLocked(CanvasAction.RemovedStrokes(listOf(existing)))
        true
    }

    override fun clear() {
        synchronized(stateLock) {
            activeEraserSession = null
            strokesById.clear()
            objectsById.clear()
            shapeMetaById.clear()
            spatialIndex.clear()
            undoStack.clear()
            redoStack.clear()
            totalUndoPointCost = 0
            nextHighlighterZ.set(1L)
            nextInkZ.set(INK_Z_ORDER_BASE)
            nextObjectZ.set(OBJECT_Z_ORDER_BASE)
        }
    }

    val hasPageObjects: Boolean
        get() = synchronized(stateLock) { objectsById.isNotEmpty() }

    fun allPageObjects(): List<PageObject> = synchronized(stateLock) {
        objectsById.values.sortedBy { it.zOrder }
    }

    fun getPageObject(id: String): PageObject? = synchronized(stateLock) { objectsById[id] }

    fun restoreLoadedObjects(objects: Collection<PageObject>) {
        synchronized(stateLock) {
            objectsById.clear()
            var maxObjZ = OBJECT_Z_ORDER_BASE - 1L
            for (obj in objects) {
                objectsById[obj.id] = obj
                maxObjZ = max(maxObjZ, obj.zOrder)
            }
            nextObjectZ.set(max(OBJECT_Z_ORDER_BASE, maxObjZ + 1L))
        }
    }

    fun allocateObjectZOrder(): Long = nextObjectZ.getAndIncrement()

    fun addPageObject(pageObject: PageObject): DocRect? = synchronized(stateLock) {
        val action = CanvasAction.AddedObject(pageObject)
        applyForwardLocked(action)
        pushUndoActionLocked(action)
        action.affectedBounds
    }

    fun updatePageObject(updated: PageObject): DocRect? = synchronized(stateLock) {
        val existing = objectsById[updated.id] ?: return null
        val action = CanvasAction.UpdatedObject(beforeObject = existing, afterObject = updated)
        applyForwardLocked(action)
        pushUndoActionLocked(action)
        action.affectedBounds
    }

    fun removePageObject(objectId: String): DocRect? = synchronized(stateLock) {
        val existing = objectsById[objectId] ?: return null
        val action = CanvasAction.RemovedObject(existing)
        applyForwardLocked(action)
        pushUndoActionLocked(action)
        action.affectedBounds
    }

    /**
     * Atomically replaces lasso-selected handwriting [strokeIds] with [pageObject] (e.g. a
     * validated [PageObject.LatexObject]) inside a single Undo-compatible [CanvasAction.CompositeAction]
     * (Section 6).
     */
    fun replaceStrokesWithPageObject(
        strokeIds: Collection<String>,
        pageObject: PageObject
    ): DocRect? = synchronized(stateLock) {
        val removed = strokeIds.distinct().mapNotNull { strokesById[it] }
        val subActions = ArrayList<CanvasAction>(2).apply {
            if (removed.isNotEmpty()) {
                add(CanvasAction.RemovedStrokes(removed))
            }
            add(CanvasAction.AddedObject(pageObject))
        }
        val composite = CanvasAction.CompositeAction(
            actions = subActions,
            description = "Kézírás → vektoros objektum csere"
        )
        applyForwardLocked(composite)
        pushUndoActionLocked(composite)
        composite.affectedBounds
    }

    /**
     * Atomically removes an AI-formatted [PageObject] ([objectId]) and restores [restoredEntries]
     * inside one Undo/Redo-compatible [CanvasAction.CompositeAction], returning the dirty canvas
     * bounds and normalized [IndexedDryStroke]s.
     */
    fun restoreObjectToOriginalStrokes(
        objectId: String,
        restoredEntries: List<IndexedDryStroke>,
        shapeMetas: Map<String, ShapeMeta> = emptyMap()
    ): Pair<DocRect?, List<IndexedDryStroke>> = synchronized(stateLock) {
        val existingObj = objectsById[objectId] ?: return null to emptyList()
        if (restoredEntries.isEmpty()) return null to emptyList()

        val normalized = restoredEntries.map { ensureProperLayerZIndex(it) }
        if (shapeMetas.isNotEmpty()) {
            shapeMetaById.putAll(shapeMetas)
        }

        val composite = CanvasAction.CompositeAction(
            actions = listOf(
                CanvasAction.RemovedObject(existingObj),
                CanvasAction.AddedStrokes(normalized)
            ),
            description = "Eredeti kézírás visszaállítása"
        )
        applyForwardLocked(composite)
        pushUndoActionLocked(composite)
        val dirtyBounds = mergeBounds(listOf(existingObj.bounds) + normalized.map { it.bounds })
        dirtyBounds to normalized
    }

    /**
     * Replaces the canvas contents with persisted strokes loaded from [StrokeJournal] without
     * recording an initial Undo step (Section 9.3).
     */
    fun restoreLoadedStrokes(
        strokes: List<IndexedDryStroke>,
        shapeMetas: Map<String, ShapeMeta> = emptyMap()
    ) {
        synchronized(stateLock) {
            activeEraserSession = null
            strokesById.clear()
            shapeMetaById.clear()
            spatialIndex.clear()
            undoStack.clear()
            redoStack.clear()
            totalUndoPointCost = 0

            var maxHlZ = 0L
            var maxInkZ = INK_Z_ORDER_BASE - 1L
            val normalized = strokes.map { entry ->
                val adjusted = ensureProperLayerZIndex(entry)
                if (adjusted.isHighlighter) {
                    maxHlZ = max(maxHlZ, adjusted.zIndex)
                } else {
                    maxInkZ = max(maxInkZ, adjusted.zIndex)
                }
                adjusted
            }
            insertStrokesLocked(normalized)
            shapeMetaById.putAll(shapeMetas)
            nextHighlighterZ.set(maxHlZ + 1L)
            nextInkZ.set(max(INK_Z_ORDER_BASE, maxInkZ + 1L))
        }
    }

    fun putShapeMeta(strokeId: String, meta: ShapeMeta) {
        shapeMetaById[strokeId] = meta
    }

    fun getShapeMeta(strokeId: String): ShapeMeta? = shapeMetaById[strokeId]

    /**
     * Queries [spatialIndex] using [SpatialIndex.queryLassoPolygon] where a stroke is selected
     * if at least [minInsideRatio] (default 60%, per Section 5.5) of its input points lie inside
     * [polygonDocPt].
     */
    fun selectStrokesByLasso(
        polygonDocPt: List<DocPoint>,
        minInsideRatio: Float = SpatialIndex.DEFAULT_LASSO_INSIDE_RATIO
    ): List<IndexedDryStroke> {
        if (polygonDocPt.size < 3) return emptyList()
        return spatialIndex.queryLassoPolygon(
            polygon = polygonDocPt,
            pointExtractor = { indexed ->
                extractStylusPoints(indexed.stroke).map { DocPoint(it.xPt, it.yPt) }
            },
            minInsideRatio = minInsideRatio
        ).map { it.payload }.sortedBy { it.zIndex }
    }

    /**
     * Queries Layer-5 [PageObject]s intersecting [polygonDocPt] (excluding background PDF references)
     * by testing rotated perimeter + center sample points inside the lasso loop.
     */
    fun selectPageObjectsByLasso(
        polygonDocPt: List<DocPoint>
    ): List<PageObject> {
        if (polygonDocPt.size < 3) return emptyList()
        val polyBounds = SpatialIndex.computePolygonBounds(polygonDocPt)
        val matched = synchronized(stateLock) {
            objectsById.values.filter { obj ->
                if (obj is PageObject.PdfPageRefObject) return@filter false
                val b = obj.bounds
                val pad = max(b.widthPt, b.heightPt) * 0.25f
                val roughOverlap = (b.rightPt + pad) >= polyBounds.leftPt &&
                    (b.leftPt - pad) <= polyBounds.rightPt &&
                    (b.bottomPt + pad) >= polyBounds.topPt &&
                    (b.topPt - pad) <= polyBounds.bottomPt
                if (!roughOverlap) return@filter false

                val rotRad = when (obj) {
                    is PageObject.GeometricShapeObject -> obj.rotationRadians
                    is PageObject.ImageObject -> obj.rotationRadians
                    is PageObject.FormattedTextObject -> obj.rotationRadians
                    is PageObject.LatexObject -> obj.rotationRadians
                    else -> 0f
                }
                val cx = b.centerXPt
                val cy = b.centerYPt
                val hw = b.widthPt * 0.5f
                val hh = b.heightPt * 0.5f
                val cosR = cos(rotRad)
                val sinR = sin(rotRad)
                var insideCount = 0
                for (sy in -1..1) {
                    for (sx in -1..1) {
                        val lx = sx * hw
                        val ly = sy * hh
                        val pt = DocPoint(
                            xPt = cx + lx * cosR - ly * sinR,
                            yPt = cy + lx * sinR + ly * cosR
                        )
                        if (SpatialIndex.isPointInPolygon(pt, polygonDocPt)) {
                            insideCount++
                            if (insideCount >= 2) return@filter true
                        }
                    }
                }
                false
            }
        }
        return matched.sortedBy { it.zOrder }
    }

    fun computeSelectionBounds(strokeIds: Collection<String>): DocRect? =
        computeCombinedSelectionBounds(strokeIds, emptyList())

    fun computeCombinedSelectionBounds(
        strokeIds: Collection<String>,
        objectIds: Collection<String>
    ): DocRect? {
        if (strokeIds.isEmpty() && objectIds.isEmpty()) return null
        val rects = synchronized(stateLock) {
            val acc = ArrayList<DocRect>(strokeIds.size + objectIds.size)
            for (id in strokeIds) strokesById[id]?.bounds?.let { acc.add(it) }
            for (id in objectIds) objectsById[id]?.bounds?.let { acc.add(it) }
            acc
        }
        return mergeBounds(rects)
    }

    /**
     * Duplicates the specified [strokeIds] with a screen-visible offset and records an
     * atomic Undo delta. Returns the created stroke duplicates.
     */
    fun duplicateStrokes(
        strokeIds: Collection<String>,
        offsetXPt: Float = 18f,
        offsetYPt: Float = 18f
    ): List<IndexedDryStroke> = duplicateSelection(
        strokeIds = strokeIds,
        objectIds = emptyList(),
        offsetXPt = offsetXPt,
        offsetYPt = offsetYPt
    ).duplicatedStrokes

    /**
     * Atomically duplicates [strokeIds] and [objectIds] together in one Undo/Redo step.
     */
    fun duplicateSelection(
        strokeIds: Collection<String>,
        objectIds: Collection<String>,
        offsetXPt: Float = 18f,
        offsetYPt: Float = 18f
    ): CombinedDuplicateResult {
        if (strokeIds.isEmpty() && objectIds.isEmpty()) {
            return CombinedDuplicateResult(emptyList(), emptyList(), null)
        }
        val (originalStrokes, originalObjects) = synchronized(stateLock) {
            strokeIds.distinct().mapNotNull { strokesById[it] } to
                objectIds.distinct().mapNotNull { objectsById[it] }
        }
        if (originalStrokes.isEmpty() && originalObjects.isEmpty()) {
            return CombinedDuplicateResult(emptyList(), emptyList(), null)
        }

        val translate = AffineTransform2D.translation(offsetXPt, offsetYPt)
        val copiedStrokes = ArrayList<IndexedDryStroke>(originalStrokes.size)
        val copiedShapeMetas = HashMap<String, ShapeMeta>()
        for (entry in originalStrokes) {
            val pts = extractStylusPoints(entry.stroke)
            if (pts.isEmpty()) continue
            val mapped = pts.map { p ->
                val tp = translate.transformPoint(p.xPt, p.yPt)
                p.copy(xPt = tp.xPt, yPt = tp.yPt)
            }
            val newStroke = Stroke(
                brush = entry.stroke.brush,
                inputs = mapped.toStrokeInputBatch(extractToolType(entry.stroke))
            )
            val copyId = uuidGenerator()
            copiedStrokes.add(
                IndexedDryStroke(
                    id = copyId,
                    stroke = newStroke,
                    bounds = computeStrokeDocBounds(newStroke),
                    zIndex = allocateZIndex(entry.isHighlighter),
                    isHighlighter = entry.isHighlighter
                )
            )
            shapeMetaById[entry.id]?.let { meta ->
                copiedShapeMetas[copyId] = meta.transform(translate)
            }
        }

        val copiedObjects = originalObjects.map { obj ->
            obj.duplicateWith(
                newId = uuidGenerator(),
                newZOrder = allocateObjectZOrder(),
                affine = translate
            )
        }

        val subActions = ArrayList<CanvasAction>(1 + copiedObjects.size)
        if (copiedStrokes.isNotEmpty()) {
            subActions.add(CanvasAction.AddedStrokes(copiedStrokes))
        }
        for (obj in copiedObjects) {
            subActions.add(CanvasAction.AddedObject(obj))
        }
        if (subActions.isEmpty()) {
            return CombinedDuplicateResult(emptyList(), emptyList(), null)
        }
        val undoAction = if (subActions.size == 1) {
            subActions.first()
        } else {
            CanvasAction.CompositeAction(subActions, "Kijelölés másolása")
        }

        synchronized(stateLock) {
            shapeMetaById.putAll(copiedShapeMetas)
            applyForwardLocked(undoAction)
            pushUndoActionLocked(undoAction)
        }
        return CombinedDuplicateResult(copiedStrokes, copiedObjects, undoAction.affectedBounds)
    }

    /**
     * Deletes multiple strokes in one Undo-able [CanvasAction.RemovedStrokes] step.
     */
    fun deleteStrokes(strokeIds: Collection<String>): DocRect? =
        deleteSelection(strokeIds, emptyList())

    /**
     * Deletes [strokeIds] and [objectIds] atomically in a single Undo/Redo step.
     */
    fun deleteSelection(
        strokeIds: Collection<String>,
        objectIds: Collection<String>
    ): DocRect? {
        if (strokeIds.isEmpty() && objectIds.isEmpty()) return null
        synchronized(stateLock) {
            val removedStrokes = removeStrokesByIdLocked(strokeIds.distinct())
            val removedObjects = objectIds.distinct().mapNotNull { id -> objectsById.remove(id) }
            val subActions = ArrayList<CanvasAction>(1 + removedObjects.size)
            if (removedStrokes.isNotEmpty()) {
                subActions.add(CanvasAction.RemovedStrokes(removedStrokes))
            }
            for (obj in removedObjects) {
                subActions.add(CanvasAction.RemovedObject(obj))
            }
            if (subActions.isEmpty()) return null
            val undoAction = if (subActions.size == 1) {
                subActions.first()
            } else {
                CanvasAction.CompositeAction(subActions, "Kijelölés törlése")
            }
            pushUndoActionLocked(undoAction)
            return undoAction.affectedBounds
        }
    }

    /**
     * Replaces an already-committed freehand stroke with a snapped geometric [Stroke] inside one
     * [CanvasAction.CompositeAction] and stores [shapeMeta] so the next Undo restores the freehand
     * original stroke (Section 5.2).
     */
    fun replaceStrokeWithSnappedShape(
        freehandStrokeId: String,
        snappedStroke: Stroke,
        shapeMeta: ShapeMeta
    ): IndexedDryStroke? = synchronized(stateLock) {
        val original = strokesById[freehandStrokeId] ?: return null
        val snappedEntry = IndexedDryStroke(
            id = uuidGenerator(),
            stroke = snappedStroke,
            bounds = computeStrokeDocBounds(snappedStroke),
            zIndex = original.zIndex,
            isHighlighter = original.isHighlighter
        )
        shapeMetaById[snappedEntry.id] = shapeMeta

        val action = CanvasAction.CompositeAction(
            actions = listOf(
                CanvasAction.RemovedStrokes(listOf(original)),
                CanvasAction.AddedStrokes(listOf(snappedEntry))
            ),
            description = "Hold-to-Snap (${shapeMeta.shapeType.displayName})"
        )
        applyForwardLocked(action)
        pushUndoActionLocked(action)
        snappedEntry
    }

    /**
     * Replaces one or more freehand strokes (e.g. a multi-stroke hand-drawn shape inside a lasso
     * selection) with a single snapped geometric [Stroke] atomically in one [CanvasAction.CompositeAction].
     */
    fun replaceStrokesWithSnappedShape(
        strokeIds: Collection<String>,
        snappedStroke: Stroke,
        shapeMeta: ShapeMeta
    ): IndexedDryStroke? = synchronized(stateLock) {
        val removed = strokeIds.distinct().mapNotNull { strokesById[it] }
        if (removed.isEmpty()) return null
        val maxZ = removed.maxOf { it.zIndex }
        val isHighlighter = removed.all { it.isHighlighter }
        val snappedEntry = IndexedDryStroke(
            id = uuidGenerator(),
            stroke = snappedStroke,
            bounds = computeStrokeDocBounds(snappedStroke),
            zIndex = maxZ,
            isHighlighter = isHighlighter
        )
        shapeMetaById[snappedEntry.id] = shapeMeta

        val action = CanvasAction.CompositeAction(
            actions = listOf(
                CanvasAction.RemovedStrokes(removed),
                CanvasAction.AddedStrokes(listOf(snappedEntry))
            ),
            description = "Alakzat formázás (${shapeMeta.shapeType.displayName})"
        )
        applyForwardLocked(action)
        pushUndoActionLocked(action)
        snappedEntry
    }

    /**
     * Begins coalescing a continuous eraser gesture (`ACTION_DOWN`..`ACTION_UP`) so multiple
     * vector deletions or chained precision splits become one Undo step on [finishEraserSession].
     */
    fun beginEraserSession() {
        synchronized(stateLock) {
            activeEraserSession = EraserSessionAccumulator()
        }
    }

    /**
     * Finalizes the active eraser drag and pushes a single minimal delta ([CanvasAction.RemovedStrokes]
     * or [CanvasAction.CompositeAction]) onto the Undo stack. Returns the recorded action or `null`
     * if nothing on the canvas was touched during the drag.
     */
    fun finishEraserSession(): CanvasAction? = synchronized(stateLock) {
        val session = activeEraserSession
        activeEraserSession = null
        val action = session?.buildCoalescedAction() ?: return null
        pushUndoActionLocked(action)
        return action
    }

    /**
     * Vector Eraser (Section 5.5): deletes entire strokes intersecting the screen-constant eraser
     * circle at ([centerXPt], [centerYPt]) with radius [radiusPt].
     *
     * Returns the bounding rectangle of erased strokes for cache invalidation, or `null` if none matched.
     */
    fun eraseWholeStrokesAt(
        centerXPt: Float,
        centerYPt: Float,
        radiusPt: Float
    ): DocRect? {
        if (radiusPt <= 0f || !radiusPt.isFinite()) return null
        val candidates = spatialIndex.queryCircle(centerXPt, centerYPt, radiusPt)
        if (candidates.isEmpty()) return null

        val diameter = radiusPt * 2f
        val eraserBox = ImmutableParallelogram.fromCenterAndDimensions(
            center = ImmutableVec(centerXPt, centerYPt),
            width = diameter,
            height = diameter
        )

        val hits = ArrayList<IndexedDryStroke>()
        for (i in 0 until candidates.size) {
            val candidate = candidates[i].payload
            val effectiveRadius = radiusPt + candidate.stroke.brush.size * 0.5f
            val meshHit = try {
                with(Intersection) {
                    candidate.stroke.shape.intersects(eraserBox, AffineTransform.IDENTITY)
                }
            } catch (_: Throwable) {
                true
            }
            if (!meshHit) continue

            val points = extractStylusPoints(candidate.stroke)
            if (PrecisionStrokeGeometry.doesPointsIntersectCircle(points, centerXPt, centerYPt, effectiveRadius)) {
                hits.add(candidate)
            }
        }

        if (hits.isEmpty()) return null

        synchronized(stateLock) {
            val removed = removeStrokesByIdLocked(hits.map { it.id })
            if (removed.isEmpty()) return null
            val session = activeEraserSession
            if (session != null) {
                session.recordRemovedStrokes(removed)
            } else {
                pushUndoActionLocked(CanvasAction.RemovedStrokes(removed))
            }
            return mergeBounds(removed.map { it.bounds })
        }
    }

    /**
     * Precision Eraser (Section 5.5): cuts intersected strokes along the eraser circle circumference
     * using interpolated segment-circle boundaries and rebuilds `0..N` independent [Stroke] objects
     * with fresh UUIDs. Runs cleanly on a `@WorkerThread` while synchronizing commits with [stateLock].
     */
    @WorkerThread
    fun erasePartialStrokesAt(
        centerXPt: Float,
        centerYPt: Float,
        radiusPt: Float
    ): PrecisionEraseStepResult {
        if (radiusPt <= 0f || !radiusPt.isFinite()) {
            return PrecisionEraseStepResult(null, 0, 0)
        }

        val candidates = spatialIndex.queryCircle(centerXPt, centerYPt, radiusPt)
        if (candidates.isEmpty()) {
            return PrecisionEraseStepResult(null, 0, 0)
        }

        // Compute stroke splits off the lock first so heavy geometry work never blocks draw reads.
        data class PendingSplit(
            val original: IndexedDryStroke,
            val replacementStrokes: List<IndexedDryStroke>
        )

        val pendingSplits = ArrayList<PendingSplit>()
        for (i in 0 until candidates.size) {
            val original = candidates[i].payload
            val cutRadius = radiusPt + original.stroke.brush.size * 0.5f
            val points = extractStylusPoints(original.stroke)
            if (points.isEmpty()) continue

            if (!PrecisionStrokeGeometry.doesPointsIntersectCircle(points, centerXPt, centerYPt, cutRadius)) {
                continue
            }

            val splitResult = PrecisionStrokeGeometry.splitPointsByCircle(
                points = points,
                centerXPt = centerXPt,
                centerYPt = centerYPt,
                cutRadiusPt = cutRadius
            )
            if (!splitResult.didCut) continue

            val toolType = extractToolType(original.stroke)
            val replacements = splitResult.segments.mapNotNull { segmentPoints ->
                if (segmentPoints.size < 2) {
                    null
                } else {
                    val batch = segmentPoints.toStrokeInputBatch(toolType)
                    val newStroke = Stroke(brush = original.stroke.brush, inputs = batch)
                    IndexedDryStroke(
                        id = uuidGenerator(),
                        stroke = newStroke,
                        bounds = computeStrokeDocBounds(newStroke),
                        zIndex = original.zIndex,
                        isHighlighter = original.isHighlighter
                    )
                }
            }
            pendingSplits.add(PendingSplit(original = original, replacementStrokes = replacements))
        }

        if (pendingSplits.isEmpty()) {
            return PrecisionEraseStepResult(null, 0, 0)
        }

        synchronized(stateLock) {
            val actuallyRemoved = ArrayList<IndexedDryStroke>()
            val actuallyAdded = ArrayList<IndexedDryStroke>()

            for (i in 0 until pendingSplits.size) {
                val split = pendingSplits[i]
                // Verify the exact stroke instance is still live (protects against concurrent cuts).
                val current = strokesById[split.original.id]
                if (current == null || current != split.original) continue

                removeStrokesByIdLocked(listOf(current.id))
                insertStrokesLocked(split.replacementStrokes)
                actuallyRemoved.add(current)
                actuallyAdded.addAll(split.replacementStrokes)
            }

            if (actuallyRemoved.isEmpty()) {
                return PrecisionEraseStepResult(null, 0, 0)
            }

            val session = activeEraserSession
            if (session != null) {
                session.recordRemovedStrokes(actuallyRemoved)
                session.recordAddedStrokes(actuallyAdded)
            } else {
                val actions = ArrayList<CanvasAction>(2).apply {
                    add(CanvasAction.RemovedStrokes(actuallyRemoved))
                    if (actuallyAdded.isNotEmpty()) {
                        add(CanvasAction.AddedStrokes(actuallyAdded))
                    }
                }
                pushUndoActionLocked(
                    CanvasAction.CompositeAction(
                        actions = actions,
                        description = "Precíziós radír"
                    )
                )
            }

            val dirtyBounds = mergeBounds(
                actuallyRemoved.map { it.bounds } + actuallyAdded.map { it.bounds }
            )
            return PrecisionEraseStepResult(
                invalidatedBounds = dirtyBounds,
                removedStrokeCount = actuallyRemoved.size,
                createdSegmentCount = actuallyAdded.size
            )
        }
    }

    /**
     * Rebuilds the specified [strokeIds] using [transform] (immutable `Stroke` reconstruction on `ACTION_UP`),
     * updates [spatialIndex], and pushes a [CanvasAction.TransformedStrokes] delta.
     */
    fun transformStrokes(
        strokeIds: Collection<String>,
        transform: AffineTransform2D
    ): DocRect? = transformSelection(strokeIds, emptyList(), transform).affectedBounds

    /**
     * Atomically transforms both strokes ([strokeIds]) and embedded [PageObject]s ([objectIds])
     * in a single Undo/Redo step.
     */
    fun transformSelection(
        strokeIds: Collection<String>,
        objectIds: Collection<String>,
        transform: AffineTransform2D
    ): SelectionTransformResult {
        if (strokeIds.isEmpty() && objectIds.isEmpty()) {
            return SelectionTransformResult(null, emptyList())
        }

        val (originalStrokes, originalObjects) = synchronized(stateLock) {
            strokeIds.distinct().mapNotNull { strokesById[it] } to
                objectIds.distinct().mapNotNull { objectsById[it] }
        }
        if (originalStrokes.isEmpty() && originalObjects.isEmpty()) {
            return SelectionTransformResult(null, emptyList())
        }

        val scaleMultiplier = transform.averageScaleFactor()
        val transformedStrokes = ArrayList<IndexedDryStroke>(originalStrokes.size)
        for (i in 0 until originalStrokes.size) {
            val entry = originalStrokes[i]
            val pts = extractStylusPoints(entry.stroke)
            if (pts.isEmpty()) continue
            val mappedPoints = pts.map { pt ->
                val p = transform.transformPoint(pt.xPt, pt.yPt)
                pt.copy(xPt = p.xPt, yPt = p.yPt)
            }
            val newSize = (entry.stroke.brush.size * scaleMultiplier)
                .coerceIn(BrushConfig.MIN_BRUSH_SIZE_PT, BrushConfig.MAX_BRUSH_SIZE_PT)
            val newBrush = entry.stroke.brush.copy(size = newSize)
            val newStroke = Stroke(
                brush = newBrush,
                inputs = mappedPoints.toStrokeInputBatch(extractToolType(entry.stroke))
            )
            transformedStrokes.add(
                entry.copy(
                    stroke = newStroke,
                    bounds = computeStrokeDocBounds(newStroke)
                )
            )
        }

        val updatedObjects = originalObjects.map { obj -> obj to obj.transform(transform) }

        synchronized(stateLock) {
            val subActions = ArrayList<CanvasAction>(1 + updatedObjects.size)
            val liveBeforeStrokes = transformedStrokes.mapNotNull { strokesById[it.id] }
            if (liveBeforeStrokes.size == transformedStrokes.size && transformedStrokes.isNotEmpty()) {
                removeStrokesByIdLocked(liveBeforeStrokes.map { it.id })
                insertStrokesLocked(transformedStrokes)
                for (item in transformedStrokes) {
                    shapeMetaById[item.id]?.let { existingMeta ->
                        shapeMetaById[item.id] = existingMeta.transform(transform)
                    }
                }
                subActions.add(
                    CanvasAction.TransformedStrokes(
                        strokeIds = transformedStrokes.map { it.id },
                        beforeStrokes = liveBeforeStrokes,
                        afterStrokes = transformedStrokes,
                        transform = transform
                    )
                )
            }

            val appliedUpdatedObjects = ArrayList<PageObject>(updatedObjects.size)
            for ((beforeObj, afterObj) in updatedObjects) {
                val liveObj = objectsById[beforeObj.id] ?: continue
                objectsById[afterObj.id] = afterObj
                appliedUpdatedObjects.add(afterObj)
                subActions.add(CanvasAction.UpdatedObject(beforeObject = liveObj, afterObject = afterObj))
            }

            if (subActions.isEmpty()) return SelectionTransformResult(null, emptyList())
            val undoAction = if (subActions.size == 1) {
                subActions.first()
            } else {
                CanvasAction.CompositeAction(subActions, "Kijelölés átalakítása")
            }
            pushUndoActionLocked(undoAction)
            return SelectionTransformResult(undoAction.affectedBounds, appliedUpdatedObjects)
        }
    }

    /**
     * Updates the color of [strokeIds] via `Brush.copyWithColorIntArgb` and records a
     * [CanvasAction.RecoloredStrokes] delta.
     */
    fun recolorStrokes(
        strokeIds: Collection<String>,
        newColorArgb: Int
    ): DocRect? = recolorSelection(strokeIds, emptyList(), newColorArgb).affectedBounds

    /**
     * Atomically recolors [strokeIds] and color-supporting [objectIds] in one Undo/Redo step.
     */
    fun recolorSelection(
        strokeIds: Collection<String>,
        objectIds: Collection<String>,
        newColorArgb: Int
    ): SelectionRecolorResult {
        if (strokeIds.isEmpty() && objectIds.isEmpty()) {
            return SelectionRecolorResult(null, emptyList())
        }

        val (originalStrokes, originalObjects) = synchronized(stateLock) {
            strokeIds.distinct().mapNotNull { strokesById[it] } to
                objectIds.distinct().mapNotNull { objectsById[it] }
        }
        val recoloredStrokes = originalStrokes.map { entry ->
            val newBrush = entry.stroke.brush.copyWithColorIntArgb(newColorArgb)
            entry.copy(stroke = entry.stroke.copy(brush = newBrush))
        }
        val recoloredObjects = originalObjects.mapNotNull { obj ->
            val after = obj.withCanonicalColorOrNull(newColorArgb) ?: return@mapNotNull null
            obj to after
        }
        if (recoloredStrokes.isEmpty() && recoloredObjects.isEmpty()) {
            return SelectionRecolorResult(null, emptyList())
        }

        synchronized(stateLock) {
            val subActions = ArrayList<CanvasAction>(1 + recoloredObjects.size)
            val liveBefore = recoloredStrokes.mapNotNull { strokesById[it.id] }
            if (liveBefore.size == recoloredStrokes.size && recoloredStrokes.isNotEmpty()) {
                removeStrokesByIdLocked(liveBefore.map { it.id })
                insertStrokesLocked(recoloredStrokes)
                subActions.add(CanvasAction.RecoloredStrokes(liveBefore, recoloredStrokes))
            }

            val appliedObjects = ArrayList<PageObject>(recoloredObjects.size)
            for ((beforeObj, afterObj) in recoloredObjects) {
                val liveObj = objectsById[beforeObj.id] ?: continue
                objectsById[afterObj.id] = afterObj
                appliedObjects.add(afterObj)
                subActions.add(CanvasAction.UpdatedObject(liveObj, afterObj))
            }

            if (subActions.isEmpty()) return SelectionRecolorResult(null, emptyList())
            val undoAction = if (subActions.size == 1) {
                subActions.first()
            } else {
                CanvasAction.CompositeAction(subActions, "Kijelölés átszínezése")
            }
            pushUndoActionLocked(undoAction)
            return SelectionRecolorResult(undoAction.affectedBounds, appliedObjects)
        }
    }

    /**
     * Pushes a custom externally constructed [CanvasAction] (such as Hold-to-Snap replacement in Phase 2c)
     * and applies it atomically to the dry-stroke store.
     */
    fun applyAndRecordAction(action: CanvasAction): DocRect? = synchronized(stateLock) {
        applyForwardLocked(action)
        pushUndoActionLocked(action)
        action.affectedBounds
    }

    /**
     * Reverts the latest action from the Undo stack and moves it to the Redo stack.
     * Returns the affected document bounds for targeted tile cache invalidation.
     */
    fun undo(): DocRect? = synchronized(stateLock) {
        val action = undoStack.removeLastOrNull() ?: return null
        totalUndoPointCost = max(0, totalUndoPointCost - action.estimatedPointCost)
        applyInverseLocked(action)
        redoStack.addLast(action)
        action.affectedBounds
    }

    /**
     * Re-applies the latest undone action from the Redo stack and moves it back onto Undo.
     * Returns the affected document bounds for targeted tile cache invalidation.
     */
    fun redo(): DocRect? = synchronized(stateLock) {
        val action = redoStack.removeLastOrNull() ?: return null
        applyForwardLocked(action)
        undoStack.addLast(action)
        totalUndoPointCost += action.estimatedPointCost
        enforceUndoBudgetLocked()
        action.affectedBounds
    }

    private fun ensureProperLayerZIndex(entry: IndexedDryStroke): IndexedDryStroke {
        val inHighlighterBand = entry.zIndex < INK_Z_ORDER_BASE
        return if (entry.isHighlighter == inHighlighterBand && entry.zIndex > 0L) {
            // Advance monotonic counters past imported values so subsequent strokes never collide.
            if (entry.isHighlighter) {
                nextHighlighterZ.updateAndGet { current -> max(current, entry.zIndex + 1L) }
            } else {
                nextInkZ.updateAndGet { current -> max(current, entry.zIndex + 1L) }
            }
            entry
        } else {
            entry.copy(zIndex = allocateZIndex(entry.isHighlighter))
        }
    }

    private fun pushUndoActionLocked(action: CanvasAction) {
        redoStack.clear()
        undoStack.addLast(action)
        totalUndoPointCost += action.estimatedPointCost
        enforceUndoBudgetLocked()
    }

    private fun enforceUndoBudgetLocked() {
        while (undoStack.size > maxUndoSteps) {
            val dropped = undoStack.removeFirst()
            totalUndoPointCost = max(0, totalUndoPointCost - dropped.estimatedPointCost)
        }
        // Guarantee at least minUndoSteps (50) regardless of point count before trimming for memory.
        while (undoStack.size > minUndoSteps && totalUndoPointCost > maxPointBudget) {
            val dropped = undoStack.removeFirst()
            totalUndoPointCost = max(0, totalUndoPointCost - dropped.estimatedPointCost)
        }
    }

    private fun applyForwardLocked(action: CanvasAction) {
        when (action) {
            is CanvasAction.AddedStrokes -> insertStrokesLocked(action.strokes)
            is CanvasAction.RemovedStrokes -> removeStrokesByIdLocked(action.strokes.map { it.id })
            is CanvasAction.TransformedStrokes -> {
                removeStrokesByIdLocked(action.beforeStrokes.map { it.id })
                insertStrokesLocked(action.afterStrokes)
            }
            is CanvasAction.RecoloredStrokes -> {
                removeStrokesByIdLocked(action.beforeStrokes.map { it.id })
                insertStrokesLocked(action.afterStrokes)
            }
            is CanvasAction.CompositeAction -> {
                for (i in 0 until action.actions.size) {
                    applyForwardLocked(action.actions[i])
                }
            }
            is CanvasAction.AddedObject -> {
                objectsById[action.pageObject.id] = action.pageObject
                nextObjectZ.updateAndGet { current -> max(current, action.pageObject.zOrder + 1L) }
            }
            is CanvasAction.RemovedObject -> {
                objectsById.remove(action.pageObject.id)
            }
            is CanvasAction.UpdatedObject -> {
                objectsById[action.afterObject.id] = action.afterObject
            }
        }
    }

    private fun applyInverseLocked(action: CanvasAction) {
        when (action) {
            is CanvasAction.AddedStrokes -> removeStrokesByIdLocked(action.strokes.map { it.id })
            is CanvasAction.RemovedStrokes -> insertStrokesLocked(action.strokes)
            is CanvasAction.TransformedStrokes -> {
                removeStrokesByIdLocked(action.afterStrokes.map { it.id })
                insertStrokesLocked(action.beforeStrokes)
            }
            is CanvasAction.RecoloredStrokes -> {
                removeStrokesByIdLocked(action.afterStrokes.map { it.id })
                insertStrokesLocked(action.beforeStrokes)
            }
            is CanvasAction.CompositeAction -> {
                for (i in action.actions.size - 1 downTo 0) {
                    applyInverseLocked(action.actions[i])
                }
            }
            is CanvasAction.AddedObject -> {
                objectsById.remove(action.pageObject.id)
            }
            is CanvasAction.RemovedObject -> {
                objectsById[action.pageObject.id] = action.pageObject
            }
            is CanvasAction.UpdatedObject -> {
                objectsById[action.beforeObject.id] = action.beforeObject
            }
        }
    }

    private fun insertStrokesLocked(strokes: List<IndexedDryStroke>) {
        for (i in 0 until strokes.size) {
            val entry = strokes[i]
            strokesById[entry.id] = entry
            spatialIndex.insert(entry.id, entry.bounds, entry)
        }
    }

    private fun removeStrokesByIdLocked(ids: List<String>): List<IndexedDryStroke> {
        val removed = ArrayList<IndexedDryStroke>(ids.size)
        for (i in 0 until ids.size) {
            val entry = strokesById.remove(ids[i])
            if (entry != null) {
                spatialIndex.remove(entry.id)
                removed.add(entry)
            }
        }
        return removed
    }

    /**
     * Coalesces multiple removals and secondary splits within one continuous eraser drag into a
     * minimal delta: original pre-session strokes go into [CanvasAction.RemovedStrokes], while only
     * split segments that still survive at the end of the drag go into [CanvasAction.AddedStrokes].
     */
    private class EraserSessionAccumulator {
        private val erasedOriginals = LinkedHashMap<String, IndexedDryStroke>()
        private val createdInSession = LinkedHashMap<String, IndexedDryStroke>()

        fun recordRemovedStrokes(strokes: List<IndexedDryStroke>) {
            for (i in 0 until strokes.size) {
                val entry = strokes[i]
                // If this segment was created earlier during this same drag, discard it silently
                // rather than polluting the Undo stack with ephemeral intermediate fragments.
                val wasEphemeralSplit = createdInSession.remove(entry.id) != null
                if (!wasEphemeralSplit) {
                    erasedOriginals[entry.id] = entry
                }
            }
        }

        fun recordAddedStrokes(strokes: List<IndexedDryStroke>) {
            for (i in 0 until strokes.size) {
                val entry = strokes[i]
                createdInSession[entry.id] = entry
            }
        }

        fun buildCoalescedAction(): CanvasAction? {
            if (erasedOriginals.isEmpty() && createdInSession.isEmpty()) return null
            val removedList = erasedOriginals.values.toList()
            val addedList = createdInSession.values.toList()
            if (addedList.isEmpty()) {
                return CanvasAction.RemovedStrokes(removedList)
            }
            val subActions = ArrayList<CanvasAction>(2).apply {
                if (removedList.isNotEmpty()) {
                    add(CanvasAction.RemovedStrokes(removedList))
                }
                add(CanvasAction.AddedStrokes(addedList))
            }
            return CanvasAction.CompositeAction(
                actions = subActions,
                description = "Radírozás munkamenet"
            )
        }
    }

    companion object {
        const val DEFAULT_MIN_UNDO_STEPS: Int = 50
        const val DEFAULT_MAX_UNDO_STEPS: Int = 200
        const val DEFAULT_MAX_POINT_BUDGET: Int = 250_000
        const val INK_Z_ORDER_BASE: Long = 1_000_000_000L
        const val OBJECT_Z_ORDER_BASE: Long = 2_000_000_000L

        fun extractStylusPoints(stroke: Stroke): List<StylusPointSample> {
            val inputs = stroke.inputs
            val count = inputs.size
            if (count == 0) return emptyList()

            val result = ArrayList<StylusPointSample>(count)
            val scratch = StrokeInput()
            for (i in 0 until count) {
                inputs.populate(i, scratch)
                val pressure = if (scratch.pressure.isFinite() && scratch.pressure >= 0f) {
                    scratch.pressure.coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                val tilt = if (scratch.tiltRadians.isFinite() && scratch.tiltRadians >= 0f) {
                    scratch.tiltRadians
                } else {
                    0f
                }
                val orient = if (scratch.orientationRadians.isFinite() && scratch.orientationRadians >= 0f) {
                    scratch.orientationRadians
                } else {
                    0f
                }
                result.add(
                    StylusPointSample(
                        xPt = scratch.x,
                        yPt = scratch.y,
                        elapsedTimeMillis = scratch.elapsedTimeMillis,
                        pressure = pressure,
                        tiltRadians = tilt,
                        orientationRadians = orient,
                        isEraserTool = false
                    )
                )
            }
            return result
        }

        fun extractToolType(stroke: Stroke): InputToolType {
            val inputs = stroke.inputs
            if (inputs.size == 0) return InputToolType.STYLUS
            val scratch = StrokeInput()
            inputs.populate(0, scratch)
            return scratch.toolType
        }
    }
}

