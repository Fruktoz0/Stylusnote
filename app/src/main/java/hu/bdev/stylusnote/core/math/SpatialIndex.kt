package hu.bdev.stylusnote.core.math

import androidx.ink.geometry.Box
import kotlin.math.max
import kotlin.math.min

/**
 * Entry stored inside [SpatialIndex] associating an arbitrary payload with a document-space AABB.
 */
data class SpatialEntry<T>(
    val id: String,
    val bounds: DocRect,
    val payload: T
)

/**
 * Converts an Ink geometry [Box] into a PDF-point [DocRect].
 */
fun Box.toDocRect(): DocRect = DocRect(
    leftPt = xMin,
    topPt = yMin,
    rightPt = xMax,
    bottomPt = yMax
)

/**
 * Thread-safe, in-memory dynamic R-Tree over PDF-point [DocRect] bounding boxes.
 *
 * Supports both fixed pages and unbounded infinite canvases (arbitrary negative/positive coordinates)
 * and serves viewport culling, vector/precision eraser candidate lookup, lasso polygon selection,
 * and OCR line grouping without ever iterating over all strokes per frame.
 */
class SpatialIndex<T>(
    private val maxEntriesPerNode: Int = DEFAULT_MAX_ENTRIES,
    private val minEntriesPerNode: Int = DEFAULT_MIN_ENTRIES
) {
    init {
        require(minEntriesPerNode >= 2) { "minEntriesPerNode must be >= 2" }
        require(maxEntriesPerNode >= minEntriesPerNode * 2) {
            "maxEntriesPerNode ($maxEntriesPerNode) must be >= 2 * minEntriesPerNode ($minEntriesPerNode)"
        }
    }

    private sealed class Node<T>(
        var bounds: DocRect,
        var parent: InternalNode<T>? = null
    )

    private class LeafNode<T>(
        bounds: DocRect = EMPTY_BOUNDS,
        parent: InternalNode<T>? = null,
        val entries: MutableList<SpatialEntry<T>> = ArrayList()
    ) : Node<T>(bounds, parent)

    private class InternalNode<T>(
        bounds: DocRect = EMPTY_BOUNDS,
        parent: InternalNode<T>? = null,
        val children: MutableList<Node<T>> = ArrayList()
    ) : Node<T>(bounds, parent)

    private var root: Node<T> = LeafNode()
    private val entryById: MutableMap<String, SpatialEntry<T>> = HashMap()
    private val leafById: MutableMap<String, LeafNode<T>> = HashMap()

    val size: Int
        @Synchronized get() = entryById.size

    @Synchronized
    fun insert(id: String, bounds: DocRect, payload: T) {
        if (entryById.containsKey(id)) {
            remove(id)
        }
        val entry = SpatialEntry(id = id, bounds = normalizeRect(bounds), payload = payload)
        insertEntry(entry)
    }

    @Synchronized
    fun update(id: String, newBounds: DocRect, newPayload: T? = null): Boolean {
        val existing = entryById[id] ?: return false
        val payload = newPayload ?: existing.payload
        remove(id)
        insertEntry(SpatialEntry(id = id, bounds = normalizeRect(newBounds), payload = payload))
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val existing = entryById.remove(id) ?: return false
        val leaf = leafById.remove(id) ?: return false
        val removed = leaf.entries.removeIf { it.id == existing.id }
        if (!removed) return false

        condenseTree(leaf)
        return true
    }

    @Synchronized
    fun get(id: String): SpatialEntry<T>? = entryById[id]

    @Synchronized
    fun clear() {
        root = LeafNode()
        entryById.clear()
        leafById.clear()
    }

    @Synchronized
    fun allEntries(): List<SpatialEntry<T>> = ArrayList(entryById.values)

    /**
     * Returns every indexed entry whose bounding box overlaps [range] (inclusive boundary match
     * so degenerate zero-width vertical or horizontal strokes are preserved).
     */
    @Synchronized
    fun queryRect(range: DocRect): List<SpatialEntry<T>> {
        if (entryById.isEmpty()) return emptyList()
        val normalized = normalizeRect(range)
        val result = ArrayList<SpatialEntry<T>>()
        val stack = ArrayDeque<Node<T>>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is LeafNode -> {
                    for (i in 0 until node.entries.size) {
                        val entry = node.entries[i]
                        if (rectsOverlap(entry.bounds, normalized)) {
                            result.add(entry)
                        }
                    }
                }
                is InternalNode -> {
                    for (i in 0 until node.children.size) {
                        val child = node.children[i]
                        if (rectsOverlap(child.bounds, normalized)) {
                            stack.addLast(child)
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Queries candidates overlapping the circle's bounding box, then filters to entries whose
     * bounding box genuinely intersects the circle centered at ([centerXPt], [centerYPt]).
     */
    @Synchronized
    fun queryCircle(
        centerXPt: Float,
        centerYPt: Float,
        radiusPt: Float
    ): List<SpatialEntry<T>> {
        if (radiusPt < 0f || !radiusPt.isFinite() || entryById.isEmpty()) return emptyList()

        val searchBox = DocRect(
            leftPt = centerXPt - radiusPt,
            topPt = centerYPt - radiusPt,
            rightPt = centerXPt + radiusPt,
            bottomPt = centerYPt + radiusPt
        )
        val candidates = queryRect(searchBox)
        if (candidates.isEmpty()) return emptyList()

        val radiusSq = radiusPt * radiusPt
        val result = ArrayList<SpatialEntry<T>>(candidates.size)
        for (i in 0 until candidates.size) {
            val entry = candidates[i]
            val b = entry.bounds
            val closestX = centerXPt.coerceIn(b.leftPt, b.rightPt)
            val closestY = centerYPt.coerceIn(b.topPt, b.bottomPt)
            val dx = centerXPt - closestX
            val dy = centerYPt - closestY
            if (dx * dx + dy * dy <= radiusSq) {
                result.add(entry)
            }
        }
        return result
    }

    /**
     * Selects indexed entries whose extracted sample points fall inside [polygon] by at least
     * [minInsideRatio] (default 60%, per Section 5.5).
     */
    @Synchronized
    fun queryLassoPolygon(
        polygon: List<DocPoint>,
        pointExtractor: (T) -> List<DocPoint>,
        minInsideRatio: Float = DEFAULT_LASSO_INSIDE_RATIO
    ): List<SpatialEntry<T>> {
        if (polygon.size < 3 || entryById.isEmpty()) return emptyList()

        val polyBounds = computePolygonBounds(polygon)
        val candidates = queryRect(polyBounds)
        if (candidates.isEmpty()) return emptyList()

        val threshold = minInsideRatio.coerceIn(0f, 1f)
        val selected = ArrayList<SpatialEntry<T>>()
        for (i in 0 until candidates.size) {
            val entry = candidates[i]
            val pts = pointExtractor(entry.payload)
            if (pts.isEmpty()) continue

            var insideCount = 0
            for (j in 0 until pts.size) {
                val pt = pts[j]
                if (polyBounds.contains(pt.xPt, pt.yPt) && isPointInPolygon(pt, polygon)) {
                    insideCount++
                }
            }
            val ratio = insideCount.toFloat() / pts.size.toFloat()
            if (ratio >= threshold) {
                selected.add(entry)
            }
        }
        return selected
    }

    private fun insertEntry(entry: SpatialEntry<T>) {
        entryById[entry.id] = entry
        val leaf = chooseLeaf(root, entry.bounds)
        leaf.entries.add(entry)
        leafById[entry.id] = leaf
        leaf.bounds = recomputeLeafBounds(leaf)

        if (leaf.entries.size > maxEntriesPerNode) {
            val (leftLeaf, rightLeaf) = splitLeaf(leaf)
            adjustTreeAfterSplit(leftLeaf, rightLeaf)
        } else {
            adjustBoundsUpward(leaf.parent)
        }
    }

    private fun chooseLeaf(node: Node<T>, targetBounds: DocRect): LeafNode<T> {
        var current = node
        while (current is InternalNode) {
            var bestChild = current.children[0]
            var bestEnlargement = Float.POSITIVE_INFINITY
            var bestArea = Float.POSITIVE_INFINITY

            for (i in 0 until current.children.size) {
                val child = current.children[i]
                val childArea = rectArea(child.bounds)
                val unionArea = rectArea(unionRect(child.bounds, targetBounds))
                val enlargement = unionArea - childArea

                if (enlargement < bestEnlargement ||
                    (enlargement == bestEnlargement && childArea < bestArea)
                ) {
                    bestEnlargement = enlargement
                    bestArea = childArea
                    bestChild = child
                }
            }
            current = bestChild
        }
        return current as LeafNode<T>
    }

    private fun adjustBoundsUpward(start: InternalNode<T>?) {
        var current = start
        while (current != null) {
            current.bounds = recomputeInternalBounds(current)
            current = current.parent
        }
    }

    private fun adjustTreeAfterSplit(nodeA: Node<T>, nodeB: Node<T>) {
        var left: Node<T> = nodeA
        var right: Node<T>? = nodeB

        while (true) {
            val parent = left.parent
            if (parent == null) {
                if (right != null) {
                    val newRoot = InternalNode<T>(
                        bounds = unionRect(left.bounds, right.bounds)
                    )
                    left.parent = newRoot
                    right.parent = newRoot
                    newRoot.children.add(left)
                    newRoot.children.add(right)
                    root = newRoot
                }
                return
            }

            parent.bounds = recomputeInternalBounds(parent)
            if (right != null) {
                right.parent = parent
                parent.children.add(right)
                parent.bounds = unionRect(parent.bounds, right.bounds)

                if (parent.children.size > maxEntriesPerNode) {
                    val (splitA, splitB) = splitInternal(parent)
                    left = splitA
                    right = splitB
                    continue
                }
            }
            adjustBoundsUpward(parent.parent)
            return
        }
    }

    /**
     * Guttman quadratic split for leaf nodes.
     */
    private fun splitLeaf(leaf: LeafNode<T>): Pair<LeafNode<T>, LeafNode<T>> {
        val items = ArrayList(leaf.entries)
        leaf.entries.clear()

        val (seedA, seedB) = pickQuadraticSeeds(items.map { it.bounds })
        val groupA = leaf
        val groupB = LeafNode<T>(parent = leaf.parent)

        val remaining = BooleanArray(items.size) { true }
        remaining[seedA] = false
        remaining[seedB] = false

        groupA.entries.add(items[seedA])
        groupA.bounds = items[seedA].bounds
        leafById[items[seedA].id] = groupA

        groupB.entries.add(items[seedB])
        groupB.bounds = items[seedB].bounds
        leafById[items[seedB].id] = groupB

        var remainingCount = items.size - 2
        while (remainingCount > 0) {
            if (groupA.entries.size + remainingCount == minEntriesPerNode) {
                for (i in items.indices) {
                    if (remaining[i]) {
                        groupA.entries.add(items[i])
                        groupA.bounds = unionRect(groupA.bounds, items[i].bounds)
                        leafById[items[i].id] = groupA
                        remaining[i] = false
                        remainingCount--
                    }
                }
                break
            }
            if (groupB.entries.size + remainingCount == minEntriesPerNode) {
                for (i in items.indices) {
                    if (remaining[i]) {
                        groupB.entries.add(items[i])
                        groupB.bounds = unionRect(groupB.bounds, items[i].bounds)
                        leafById[items[i].id] = groupB
                        remaining[i] = false
                        remainingCount--
                    }
                }
                break
            }

            val nextIdx = pickNextQuadraticIndex(
                boundsList = items.map { it.bounds },
                remaining = remaining,
                boundsA = groupA.bounds,
                boundsB = groupB.bounds
            )
            remaining[nextIdx] = false
            remainingCount--

            val entry = items[nextIdx]
            val areaA = rectArea(groupA.bounds)
            val areaB = rectArea(groupB.bounds)
            val enlA = rectArea(unionRect(groupA.bounds, entry.bounds)) - areaA
            val enlB = rectArea(unionRect(groupB.bounds, entry.bounds)) - areaB

            val addToA = when {
                enlA != enlB -> enlA < enlB
                areaA != areaB -> areaA < areaB
                else -> groupA.entries.size <= groupB.entries.size
            }
            if (addToA) {
                groupA.entries.add(entry)
                groupA.bounds = unionRect(groupA.bounds, entry.bounds)
                leafById[entry.id] = groupA
            } else {
                groupB.entries.add(entry)
                groupB.bounds = unionRect(groupB.bounds, entry.bounds)
                leafById[entry.id] = groupB
            }
        }

        return groupA to groupB
    }

    /**
     * Guttman quadratic split for internal nodes.
     */
    private fun splitInternal(node: InternalNode<T>): Pair<InternalNode<T>, InternalNode<T>> {
        val childrenCopy = ArrayList(node.children)
        node.children.clear()

        val (seedA, seedB) = pickQuadraticSeeds(childrenCopy.map { it.bounds })
        val groupA = node
        val groupB = InternalNode<T>(parent = node.parent)

        val remaining = BooleanArray(childrenCopy.size) { true }
        remaining[seedA] = false
        remaining[seedB] = false

        val childA = childrenCopy[seedA]
        childA.parent = groupA
        groupA.children.add(childA)
        groupA.bounds = childA.bounds

        val childB = childrenCopy[seedB]
        childB.parent = groupB
        groupB.children.add(childB)
        groupB.bounds = childB.bounds

        var remainingCount = childrenCopy.size - 2
        while (remainingCount > 0) {
            if (groupA.children.size + remainingCount == minEntriesPerNode) {
                for (i in childrenCopy.indices) {
                    if (remaining[i]) {
                        val c = childrenCopy[i]
                        c.parent = groupA
                        groupA.children.add(c)
                        groupA.bounds = unionRect(groupA.bounds, c.bounds)
                        remaining[i] = false
                        remainingCount--
                    }
                }
                break
            }
            if (groupB.children.size + remainingCount == minEntriesPerNode) {
                for (i in childrenCopy.indices) {
                    if (remaining[i]) {
                        val c = childrenCopy[i]
                        c.parent = groupB
                        groupB.children.add(c)
                        groupB.bounds = unionRect(groupB.bounds, c.bounds)
                        remaining[i] = false
                        remainingCount--
                    }
                }
                break
            }

            val nextIdx = pickNextQuadraticIndex(
                boundsList = childrenCopy.map { it.bounds },
                remaining = remaining,
                boundsA = groupA.bounds,
                boundsB = groupB.bounds
            )
            remaining[nextIdx] = false
            remainingCount--

            val c = childrenCopy[nextIdx]
            val areaA = rectArea(groupA.bounds)
            val areaB = rectArea(groupB.bounds)
            val enlA = rectArea(unionRect(groupA.bounds, c.bounds)) - areaA
            val enlB = rectArea(unionRect(groupB.bounds, c.bounds)) - areaB

            val addToA = when {
                enlA != enlB -> enlA < enlB
                areaA != areaB -> areaA < areaB
                else -> groupA.children.size <= groupB.children.size
            }
            if (addToA) {
                c.parent = groupA
                groupA.children.add(c)
                groupA.bounds = unionRect(groupA.bounds, c.bounds)
            } else {
                c.parent = groupB
                groupB.children.add(c)
                groupB.bounds = unionRect(groupB.bounds, c.bounds)
            }
        }

        return groupA to groupB
    }

    private fun pickQuadraticSeeds(boundsList: List<DocRect>): Pair<Int, Int> {
        var bestI = 0
        var bestJ = 1
        var maxWaste = Float.NEGATIVE_INFINITY

        for (i in 0 until boundsList.size - 1) {
            val areaI = rectArea(boundsList[i])
            for (j in i + 1 until boundsList.size) {
                val unionArea = rectArea(unionRect(boundsList[i], boundsList[j]))
                val waste = unionArea - areaI - rectArea(boundsList[j])
                if (waste > maxWaste) {
                    maxWaste = waste
                    bestI = i
                    bestJ = j
                }
            }
        }
        return bestI to bestJ
    }

    private fun pickNextQuadraticIndex(
        boundsList: List<DocRect>,
        remaining: BooleanArray,
        boundsA: DocRect,
        boundsB: DocRect
    ): Int {
        val areaA = rectArea(boundsA)
        val areaB = rectArea(boundsB)
        var bestIndex = -1
        var maxDiff = Float.NEGATIVE_INFINITY

        for (i in boundsList.indices) {
            if (!remaining[i]) continue
            val dA = rectArea(unionRect(boundsA, boundsList[i])) - areaA
            val dB = rectArea(unionRect(boundsB, boundsList[i])) - areaB
            val diff = kotlin.math.abs(dA - dB)
            if (diff > maxDiff) {
                maxDiff = diff
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * Maintains R-Tree invariants after removing an entry from [leaf] by removing underfull
     * non-root nodes and re-inserting their orphaned leaf entries.
     */
    private fun condenseTree(leaf: LeafNode<T>) {
        val orphanedEntries = ArrayList<SpatialEntry<T>>()
        var current: Node<T> = leaf

        while (current.parent != null) {
            val parent = current.parent!!
            val itemCount = when (current) {
                is LeafNode -> current.entries.size
                is InternalNode -> current.children.size
            }

            if (itemCount < minEntriesPerNode) {
                parent.children.remove(current)
                collectSubtreeEntries(current, orphanedEntries)
            } else {
                current.bounds = when (current) {
                    is LeafNode -> recomputeLeafBounds(current)
                    is InternalNode -> recomputeInternalBounds(current)
                }
            }
            current = parent
        }

        // Collapse root if an internal root now has a single child.
        while (root is InternalNode && (root as InternalNode<T>).children.size == 1) {
            val onlyChild = (root as InternalNode<T>).children[0]
            onlyChild.parent = null
            root = onlyChild
        }

        if (root is InternalNode && (root as InternalNode<T>).children.isEmpty()) {
            root = LeafNode()
        } else {
            root.bounds = when (val r = root) {
                is LeafNode -> recomputeLeafBounds(r)
                is InternalNode -> recomputeInternalBounds(r)
            }
        }

        for (i in 0 until orphanedEntries.size) {
            val orphan = orphanedEntries[i]
            leafById.remove(orphan.id)
            insertEntry(orphan)
        }
    }

    private fun collectSubtreeEntries(node: Node<T>, out: MutableList<SpatialEntry<T>>) {
        when (node) {
            is LeafNode -> {
                for (i in 0 until node.entries.size) {
                    val e = node.entries[i]
                    leafById.remove(e.id)
                    out.add(e)
                }
                node.entries.clear()
            }
            is InternalNode -> {
                for (i in 0 until node.children.size) {
                    collectSubtreeEntries(node.children[i], out)
                }
                node.children.clear()
            }
        }
    }

    private fun recomputeLeafBounds(leaf: LeafNode<T>): DocRect {
        if (leaf.entries.isEmpty()) return EMPTY_BOUNDS
        var acc = leaf.entries[0].bounds
        for (i in 1 until leaf.entries.size) {
            acc = unionRect(acc, leaf.entries[i].bounds)
        }
        return acc
    }

    private fun recomputeInternalBounds(node: InternalNode<T>): DocRect {
        if (node.children.isEmpty()) return EMPTY_BOUNDS
        var acc = node.children[0].bounds
        for (i in 1 until node.children.size) {
            acc = unionRect(acc, node.children[i].bounds)
        }
        return acc
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 16
        private const val DEFAULT_MIN_ENTRIES = 6
        const val DEFAULT_LASSO_INSIDE_RATIO = 0.60f

        private val EMPTY_BOUNDS = DocRect(0f, 0f, 0f, 0f)

        fun rectsOverlap(a: DocRect, b: DocRect): Boolean =
            a.leftPt <= b.rightPt &&
                a.rightPt >= b.leftPt &&
                a.topPt <= b.bottomPt &&
                a.bottomPt >= b.topPt

        fun normalizeRect(r: DocRect): DocRect =
            if (r.leftPt <= r.rightPt && r.topPt <= r.bottomPt) {
                r
            } else {
                DocRect.fromPoints(r.leftPt, r.topPt, r.rightPt, r.bottomPt)
            }

        private fun unionRect(a: DocRect, b: DocRect): DocRect = DocRect(
            leftPt = min(a.leftPt, b.leftPt),
            topPt = min(a.topPt, b.topPt),
            rightPt = max(a.rightPt, b.rightPt),
            bottomPt = max(a.bottomPt, b.bottomPt)
        )

        private fun rectArea(r: DocRect): Float {
            val w = max(0f, r.rightPt - r.leftPt)
            val h = max(0f, r.bottomPt - r.topPt)
            return w * h
        }

        fun computePolygonBounds(polygon: List<DocPoint>): DocRect {
            if (polygon.isEmpty()) return EMPTY_BOUNDS
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (i in 0 until polygon.size) {
                val p = polygon[i]
                if (p.xPt < minX) minX = p.xPt
                if (p.yPt < minY) minY = p.yPt
                if (p.xPt > maxX) maxX = p.xPt
                if (p.yPt > maxY) maxY = p.yPt
            }
            return DocRect(minX, minY, maxX, maxY)
        }

        /**
         * Even-odd ray-casting point-in-polygon test.
         */
        fun isPointInPolygon(point: DocPoint, polygon: List<DocPoint>): Boolean {
            if (polygon.size < 3) return false
            var inside = false
            var j = polygon.lastIndex
            for (i in polygon.indices) {
                val pi = polygon[i]
                val pj = polygon[j]
                val crossesY = (pi.yPt > point.yPt) != (pj.yPt > point.yPt)
                if (crossesY) {
                    val xIntersect = (pj.xPt - pi.xPt) * (point.yPt - pi.yPt) /
                        (pj.yPt - pi.yPt) + pi.xPt
                    if (point.xPt < xIntersect) {
                        inside = !inside
                    }
                }
                j = i
            }
            return inside
        }
    }
}

