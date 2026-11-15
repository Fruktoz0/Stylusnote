package hu.bdev.stylusnote.core.math

import hu.bdev.stylusnote.data.local.PageEntity
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Spatial placement of a single fixed-size page inside the continuous vertical multi-page
 * workspace coordinate space (PDF points).
 *
 * Disk `.snote` journals and search `DocRect`s remain strictly page-local `(0..widthPt, 0..heightPt)`;
 * [ContinuousPageLayout] maps cleanly between page-local and shared workspace space so reordering or
 * inserting pages never invalidates `.snote` binary files or Cloud Sync SHA-256 checksums.
 */
data class PageLayoutSlot(
    val pageId: String,
    val pageIndex: Int,
    val config: PagePaperConfig,
    val leftPt: Float,
    val topPt: Float,
    val rightPt: Float,
    val bottomPt: Float
) {
    val widthPt: Float get() = rightPt - leftPt
    val heightPt: Float get() = bottomPt - topPt
    val centerXPt: Float get() = (leftPt + rightPt) * 0.5f
    val centerYPt: Float get() = (topPt + bottomPt) * 0.5f
    val boundsPt: DocRect get() = DocRect(leftPt, topPt, rightPt, bottomPt)

    fun containsWorkspacePoint(
        workspaceXPt: Float,
        workspaceYPt: Float,
        marginPt: Float = 0f
    ): Boolean =
        workspaceXPt >= leftPt - marginPt &&
            workspaceXPt <= rightPt + marginPt &&
            workspaceYPt >= topPt - marginPt &&
            workspaceYPt <= bottomPt + marginPt

    fun workspaceToLocalPoint(
        workspaceXPt: Float,
        workspaceYPt: Float,
        clampToPage: Boolean = false
    ): DocPoint {
        val localX = workspaceXPt - leftPt
        val localY = workspaceYPt - topPt
        return if (clampToPage) {
            DocPoint(
                xPt = localX.coerceIn(0f, widthPt),
                yPt = localY.coerceIn(0f, heightPt)
            )
        } else {
            DocPoint(localX, localY)
        }
    }

    fun localToWorkspacePoint(localXPt: Float, localYPt: Float): DocPoint =
        DocPoint(xPt = localXPt + leftPt, yPt = localYPt + topPt)

    fun localRectToWorkspaceRect(localRect: DocRect): DocRect = DocRect(
        leftPt = localRect.leftPt + leftPt,
        topPt = localRect.topPt + topPt,
        rightPt = localRect.rightPt + leftPt,
        bottomPt = localRect.bottomPt + topPt
    )

    fun workspaceRectToLocalRect(workspaceRect: DocRect): DocRect = DocRect(
        leftPt = workspaceRect.leftPt - leftPt,
        topPt = workspaceRect.topPt - topPt,
        rightPt = workspaceRect.rightPt - leftPt,
        bottomPt = workspaceRect.bottomPt - topPt
    )

    /**
     * Squared Euclidean distance in PDF points from [workspaceXPt], [workspaceYPt] to the
     * nearest point on this page rectangle (`0f` when inside).
     */
    fun squaredDistanceToPoint(workspaceXPt: Float, workspaceYPt: Float): Float {
        val dx = when {
            workspaceXPt < leftPt -> leftPt - workspaceXPt
            workspaceXPt > rightPt -> workspaceXPt - rightPt
            else -> 0f
        }
        val dy = when {
            workspaceYPt < topPt -> topPt - workspaceYPt
            workspaceYPt > bottomPt -> workspaceYPt - bottomPt
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}

/**
 * Immutable continuous vertical strip layout for all fixed-size pages in an open notebook.
 *
 * Homogeneous page-size notebooks align every sheet at `leftPt = 0f` (`page 0` occupies
 * `(0f, 0f)..(w, h)`), while mixed page-size notebooks (e.g. A4 + B5 + 16:10 landscape)
 * horizontally center narrower pages inside `[0f .. maxWidthPt]`.
 */
class ContinuousPageLayout private constructor(
    val slots: List<PageLayoutSlot>,
    val gapPt: Float,
    val totalBoundsPt: DocRect
) {
    private val slotById: Map<String, PageLayoutSlot> = slots.associateBy { it.pageId }

    val pageCount: Int get() = slots.size
    val isEmpty: Boolean get() = slots.isEmpty()

    fun slotAt(index: Int): PageLayoutSlot? = slots.getOrNull(index)

    fun slotForPageId(pageId: String): PageLayoutSlot? = slotById[pageId]

    /**
     * Returns all page slots overlapping [visibleWorkspaceRect] (expanded by [paddingPt]),
     * in top-to-bottom order. Uses binary search on vertical bounds so 500+ page PDF notebooks
     * cull in O(log N + K) without allocating intermediate index ranges on empty rects.
     */
    fun resolveVisibleSlots(
        visibleWorkspaceRect: DocRect,
        paddingPt: Float = 0f
    ): List<PageLayoutSlot> {
        if (slots.isEmpty() || visibleWorkspaceRect.isEmpty) return emptyList()
        val queryMinX = visibleWorkspaceRect.leftPt - paddingPt
        val queryMaxX = visibleWorkspaceRect.rightPt + paddingPt
        val queryMinY = visibleWorkspaceRect.topPt - paddingPt
        val queryMaxY = visibleWorkspaceRect.bottomPt + paddingPt

        // Find the first slot whose bottomPt > queryMinY via binary search.
        var low = 0
        var high = slots.lastIndex
        var firstIdx = slots.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (slots[mid].bottomPt > queryMinY) {
                firstIdx = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        if (firstIdx >= slots.size) return emptyList()

        val result = ArrayList<PageLayoutSlot>(min(4, slots.size - firstIdx))
        for (i in firstIdx until slots.size) {
            val slot = slots[i]
            if (slot.topPt >= queryMaxY) break
            if (slot.rightPt > queryMinX && slot.leftPt < queryMaxX) {
                result.add(slot)
            }
        }
        return result
    }

    /**
     * Hit-tests a workspace coordinate against page slots, snapping across inter-page gaps up to
     * [maxSnapDistancePt] so stylus strokes starting slightly on a border or gap attach reliably.
     * Returns `null` if [workspaceXPt], [workspaceYPt] lies farther than [maxSnapDistancePt] from every page.
     */
    fun findSlotAtWorkspacePoint(
        workspaceXPt: Float,
        workspaceYPt: Float,
        maxSnapDistancePt: Float = DEFAULT_STROKE_SNAP_MARGIN_PT
    ): PageLayoutSlot? {
        if (slots.isEmpty()) return null
        val maxDistSq = maxSnapDistancePt * maxSnapDistancePt

        // Narrow candidate window around workspaceYPt via binary search on bottomPt >= workspaceYPt - snap.
        val minCandidateBottom = workspaceYPt - maxSnapDistancePt
        var low = 0
        var high = slots.lastIndex
        var startIdx = slots.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (slots[mid].bottomPt >= minCandidateBottom) {
                startIdx = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        var bestSlot: PageLayoutSlot? = null
        var bestDistSq = Float.POSITIVE_INFINITY
        for (i in startIdx until slots.size) {
            val slot = slots[i]
            if (slot.topPt - maxSnapDistancePt > workspaceYPt) break
            val distSq = slot.squaredDistanceToPoint(workspaceXPt, workspaceYPt)
            if (distSq == 0f) return slot
            if (distSq <= maxDistSq && distSq < bestDistSq) {
                bestDistSq = distSq
                bestSlot = slot
            }
        }
        return bestSlot
    }

    /**
     * Resolves which page occupies the viewport vertical center [centerYPt], used to update the
     * active page indicator and sliding-window preloader smoothly as the user scrolls.
     */
    fun findNearestSlotForViewportCenter(centerYPt: Float): PageLayoutSlot? {
        if (slots.isEmpty()) return null
        if (centerYPt <= slots.first().topPt) return slots.first()
        if (centerYPt >= slots.last().bottomPt) return slots.last()

        var low = 0
        var high = slots.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val slot = slots[mid]
            when {
                centerYPt < slot.topPt -> high = mid - 1
                centerYPt > slot.bottomPt -> low = mid + 1
                else -> return slot
            }
        }
        // centerYPt fell inside the gap between slots[high] and slots[low].
        val upper = slots.getOrNull(high)
        val lower = slots.getOrNull(low)
        if (upper == null) return lower
        if (lower == null) return upper
        val distToUpper = abs(centerYPt - upper.bottomPt)
        val distToLower = abs(lower.topPt - centerYPt)
        return if (distToLower < distToUpper) lower else upper
    }

    /**
     * Computes target `(panXPx, panYPx)` for [CanvasTransformMatrix.setTransform] so [slot]
     * sits cleanly centered horizontally and aligned near top (or centered if smaller than viewport).
     */
    fun computeCameraPanForSlot(
        slot: PageLayoutSlot,
        scale: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        verticalPaddingPx: Float = 48f
    ): Pair<Float, Float> {
        val scaledWidthPx = slot.widthPt * scale
        val scaledHeightPx = slot.heightPt * scale
        val targetPanX = viewportWidthPx * 0.5f - slot.centerXPt * scale
        val targetPanY = if (scaledHeightPx <= viewportHeightPx - verticalPaddingPx * 2f) {
            (viewportHeightPx - scaledHeightPx) * 0.5f - slot.topPt * scale
        } else {
            verticalPaddingPx - slot.topPt * scale
        }
        return targetPanX to targetPanY
    }

    companion object {
        const val DEFAULT_PAGE_GAP_PT: Float = 24f
        const val DEFAULT_STROKE_SNAP_MARGIN_PT: Float = 20f

        fun build(
            pages: List<PageEntity>,
            gapPt: Float = DEFAULT_PAGE_GAP_PT
        ): ContinuousPageLayout {
            if (pages.isEmpty()) {
                return ContinuousPageLayout(
                    slots = emptyList(),
                    gapPt = gapPt,
                    totalBoundsPt = DocRect(0f, 0f, 0f, 0f)
                )
            }
            val ordered = pages.sortedBy { it.pageIndexInNotebook }
            val configs = ordered.map { page ->
                page.id to page.pageSizePreset.toPagePaperConfig(
                    canvasMode = page.canvasMode,
                    templateType = page.templateType
                )
            }
            return buildFromConfigs(entries = configs, gapPt = gapPt)
        }

        fun buildFromConfigs(
            entries: List<Pair<String, PagePaperConfig>>,
            gapPt: Float = DEFAULT_PAGE_GAP_PT
        ): ContinuousPageLayout {
            if (entries.isEmpty()) {
                return ContinuousPageLayout(
                    slots = emptyList(),
                    gapPt = gapPt,
                    totalBoundsPt = DocRect(0f, 0f, 0f, 0f)
                )
            }

            val maxWidthPt = entries.maxOf { (_, cfg) -> cfg.pageDimensions.widthPt }
            val slots = ArrayList<PageLayoutSlot>(entries.size)
            var currentTopPt = 0f
            var minLeftPt = Float.POSITIVE_INFINITY
            var maxRightPt = Float.NEGATIVE_INFINITY

            entries.forEachIndexed { index, (pageId, config) ->
                val widthPt = config.pageDimensions.widthPt
                val heightPt = config.pageDimensions.heightPt
                val leftPt = (maxWidthPt - widthPt) * 0.5f
                val rightPt = leftPt + widthPt
                val bottomPt = currentTopPt + heightPt

                slots.add(
                    PageLayoutSlot(
                        pageId = pageId,
                        pageIndex = index,
                        config = config,
                        leftPt = leftPt,
                        topPt = currentTopPt,
                        rightPt = rightPt,
                        bottomPt = bottomPt
                    )
                )

                minLeftPt = min(minLeftPt, leftPt)
                maxRightPt = max(maxRightPt, rightPt)
                currentTopPt = bottomPt + gapPt
            }

            val totalBounds = DocRect(
                leftPt = minLeftPt,
                topPt = 0f,
                rightPt = maxRightPt,
                bottomPt = slots.last().bottomPt
            )
            return ContinuousPageLayout(
                slots = slots,
                gapPt = gapPt,
                totalBoundsPt = totalBounds
            )
        }
    }
}
