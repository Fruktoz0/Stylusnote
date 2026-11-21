package hu.bdev.stylusnote.core.math

import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Document-space coordinates expressed in PDF points (1 pt = 1/72 inch).
 */
data class DocPoint(
    val xPt: Float,
    val yPt: Float
)

/**
 * Screen-space coordinates expressed in physical pixels relative to the drawing surface.
 */
data class ScreenPoint(
    val xPx: Float,
    val yPx: Float
)

/**
 * Axis-aligned bounding rectangle in PDF point document space.
 */
data class DocRect(
    val leftPt: Float,
    val topPt: Float,
    val rightPt: Float,
    val bottomPt: Float
) {
    val widthPt: Float get() = rightPt - leftPt
    val heightPt: Float get() = bottomPt - topPt
    val centerXPt: Float get() = (leftPt + rightPt) * 0.5f
    val centerYPt: Float get() = (topPt + bottomPt) * 0.5f
    val isEmpty: Boolean get() = rightPt <= leftPt || bottomPt <= topPt

    fun contains(xPt: Float, yPt: Float): Boolean =
        xPt in leftPt..rightPt && yPt in topPt..bottomPt

    fun intersects(other: DocRect): Boolean =
        leftPt < other.rightPt &&
            rightPt > other.leftPt &&
            topPt < other.bottomPt &&
            bottomPt > other.topPt

    fun inflate(deltaPt: Float): DocRect = DocRect(
        leftPt = leftPt - deltaPt,
        topPt = topPt - deltaPt,
        rightPt = rightPt + deltaPt,
        bottomPt = bottomPt + deltaPt
    )

    companion object {
        fun fromPoints(x1: Float, y1: Float, x2: Float, y2: Float): DocRect = DocRect(
            leftPt = min(x1, x2),
            topPt = min(y1, y2),
            rightPt = max(x1, x2),
            bottomPt = max(y1, y2)
        )
    }
}

/**
 * Standard physical page sizes in PDF points (1/72 inch).
 */
enum class PageDimensionsPt(
    val widthPt: Float,
    val heightPt: Float,
    val displayName: String
) {
    A4_PORTRAIT(widthPt = 595f, heightPt = 842f, displayName = "A4"),
    B5_ISO_PORTRAIT(widthPt = 498.9f, heightPt = 708.66f, displayName = "B5"),
    LANDSCAPE_16_10(widthPt = 842f, heightPt = 526.25f, displayName = "16:10")
}

/**
 * Immutable snapshot of the current viewport transform for draw-phase reading.
 */
data class TransformSnapshot(
    val scale: Float,
    val panXPx: Float,
    val panYPx: Float,
    val version: Long
)

/**
 * Resolution- and orientation-independent anchor inside a single document page, allowing saved
 * zoom and focal scroll sections to restore accurately across portrait/landscape rotations.
 */
data class SavedViewportAnchor(
    val pageId: String,
    val localCenterXPt: Float,
    val localCenterYPt: Float,
    val relativeZoomFactor: Float
)

/**
 * Synchronized mapping between PDF-point document space (1/72 in) and screen pixel space.
 *
 * All four rendering layers (PaperBackgroundLayer, DryInkRasterCache, InProgressStrokesView,
 * and HoverCursorOverlay) consume matrices populated from this single source of truth during
 * the draw phase so pan/zoom never triggers Compose recomposition and stylus tip alignment
 * stays within 1 px even at 400%+ zoom.
 */
class CanvasTransformMatrix(
    initialScale: Float = 1.0f,
    initialPanXPx: Float = 0f,
    initialPanYPx: Float = 0f
) {
    private var _baseFitScale: Float = 1.0f
    private var _scale: Float = clampScale(initialScale)
    private var _panXPx: Float = initialPanXPx
    private var _panYPx: Float = initialPanYPx
    private var _version: Long = 1L

    val scale: Float
        @Synchronized get() = _scale

    val baseFitScale: Float
        @Synchronized get() = _baseFitScale

    /**
     * Zoom percentage normalized so the page-fit scale (`fitPageToViewport`) reads as 100%.
     */
    val relativeZoomPercent: Int
        @Synchronized get() = ((_scale / _baseFitScale.coerceAtLeast(0.01f)) * 100f)
            .roundToInt()
            .coerceIn(10, 2000)

    private fun clampInstanceScale(candidate: Float): Float {
        val minBound = max(MIN_SCALE, _baseFitScale * 0.65f)
        val maxBound = max(MAX_SCALE, _baseFitScale * 5.0f)
        return candidate.coerceIn(minBound, maxBound)
    }

    val panXPx: Float
        @Synchronized get() = _panXPx

    val panYPx: Float
        @Synchronized get() = _panYPx

    val version: Long
        @Synchronized get() = _version

    @Synchronized
    fun snapshot(): TransformSnapshot = TransformSnapshot(
        scale = _scale,
        panXPx = _panXPx,
        panYPx = _panYPx,
        version = _version
    )

    @Synchronized
    fun setTransform(scale: Float, panXPx: Float, panYPx: Float) {
        val clamped = clampInstanceScale(scale)
        if (clamped == _scale && panXPx == _panXPx && panYPx == _panYPx) return
        _scale = clamped
        _panXPx = panXPx
        _panYPx = panYPx
        _version++
    }

    @Synchronized
    fun panBy(dxPx: Float, dyPx: Float) {
        if (dxPx == 0f && dyPx == 0f) return
        _panXPx += dxPx
        _panYPx += dyPx
        _version++
    }

    /**
     * Advances vertical pan by [dyPx] while clamping against document boundaries `[minPanYPx, maxPanYPx]`.
     * Allows movement back inside bounds if already slightly outside, but stops at the edge and
     * returns `true` if a boundary stopped further travel.
     */
    @Synchronized
    fun panYWithBoundaryClamp(
        dyPx: Float,
        minPanYPx: Float,
        maxPanYPx: Float
    ): Boolean {
        if (!dyPx.isFinite() || dyPx == 0f) return false
        val current = _panYPx
        val candidate = current + dyPx
        var hitBoundary = false
        val clamped = when {
            dyPx < 0f && candidate <= minPanYPx -> {
                hitBoundary = true
                min(current, minPanYPx)
            }
            dyPx > 0f && candidate >= maxPanYPx -> {
                hitBoundary = true
                max(current, maxPanYPx)
            }
            else -> candidate
        }
        if (clamped != current) {
            _panYPx = clamped
            _version++
        }
        return hitBoundary
    }

    /**
     * Scales the canvas around [focalXPx], [focalYPx] in screen coordinates while keeping
     * the underlying document point stationary under the user's fingers.
     */
    @Synchronized
    fun zoomAroundScreenPoint(
        focalXPx: Float,
        focalYPx: Float,
        zoomFactor: Float,
        panDeltaXPx: Float = 0f,
        panDeltaYPx: Float = 0f
    ) {
        if (zoomFactor <= 0f || !zoomFactor.isFinite()) return

        val oldScale = _scale
        val newScale = clampInstanceScale(oldScale * zoomFactor)

        // Anchor document coordinate under the focal point before applying the new scale.
        val docX = (focalXPx - _panXPx) / oldScale
        val docY = (focalYPx - _panYPx) / oldScale

        val newPanX = focalXPx - docX * newScale + panDeltaXPx
        val newPanY = focalYPx - docY * newScale + panDeltaYPx

        if (newScale == _scale && newPanX == _panXPx && newPanY == _panYPx) return
        _scale = newScale
        _panXPx = newPanX
        _panYPx = newPanY
        _version++
    }

    private fun computePageFitScale(
        pageWidthPt: Float,
        pageHeightPt: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        paddingPx: Float
    ): Float? {
        if (pageWidthPt <= 0f || pageHeightPt <= 0f ||
            viewportWidthPx <= paddingPx * 2f || viewportHeightPx <= paddingPx * 2f
        ) {
            return null
        }
        val availWidth = viewportWidthPx - paddingPx * 2f
        val availHeight = viewportHeightPx - paddingPx * 2f
        return min(availWidth / pageWidthPt, availHeight / pageHeightPt)
            .coerceIn(MIN_SCALE, MAX_SCALE * 2.0f)
    }

    /**
     * Centers and scales a page of [pageWidthPt] x [pageHeightPt] inside a viewport of
     * [viewportWidthPx] x [viewportHeightPx] with optional [paddingPx].
     */
    @Synchronized
    fun fitPageToViewport(
        pageWidthPt: Float,
        pageHeightPt: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        paddingPx: Float = 48f
    ) {
        val targetScale = computePageFitScale(
            pageWidthPt = pageWidthPt,
            pageHeightPt = pageHeightPt,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            paddingPx = paddingPx
        ) ?: return

        val scaledWidth = pageWidthPt * targetScale
        val scaledHeight = pageHeightPt * targetScale

        _baseFitScale = targetScale.coerceAtLeast(0.1f)
        _scale = targetScale
        _panXPx = (viewportWidthPx - scaledWidth) * 0.5f
        _panYPx = (viewportHeightPx - scaledHeight) * 0.5f
        _version++
    }

    /**
     * Re-establishes [_baseFitScale] for the target page in the current viewport, then centers the
     * viewport onto the page-local point `(localCenterXPt, localCenterYPt)` at
     * `baseFitScale * relativeZoomFactor`.
     */
    @Synchronized
    fun applySavedPageLocalViewport(
        pageOriginXPt: Float,
        pageOriginYPt: Float,
        pageWidthPt: Float,
        pageHeightPt: Float,
        localCenterXPt: Float,
        localCenterYPt: Float,
        relativeZoomFactor: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        paddingPx: Float = 48f
    ): Boolean {
        if (!pageOriginXPt.isFinite() || !pageOriginYPt.isFinite() ||
            !localCenterXPt.isFinite() || !localCenterYPt.isFinite() ||
            !relativeZoomFactor.isFinite() || relativeZoomFactor <= 0f
        ) {
            return false
        }
        val fitScale = computePageFitScale(
            pageWidthPt = pageWidthPt,
            pageHeightPt = pageHeightPt,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            paddingPx = paddingPx
        ) ?: return false

        _baseFitScale = fitScale.coerceAtLeast(0.1f)
        val targetScale = clampInstanceScale(_baseFitScale * relativeZoomFactor)
        val wsCenterX = pageOriginXPt + localCenterXPt
        val wsCenterY = pageOriginYPt + localCenterYPt
        _scale = targetScale
        _panXPx = viewportWidthPx * 0.5f - wsCenterX * targetScale
        _panYPx = viewportHeightPx * 0.5f - wsCenterY * targetScale
        _version++
        return true
    }

    /**
     * Captures the viewport center in page-local coordinates together with the current zoom
     * multiplier relative to [_baseFitScale].
     */
    @Synchronized
    fun capturePageLocalViewportAnchor(
        pageId: String,
        pageOriginXPt: Float,
        pageOriginYPt: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float
    ): SavedViewportAnchor? {
        if (pageId.isBlank() || viewportWidthPx <= 0f || viewportHeightPx <= 0f) return null
        val centerDocX = (viewportWidthPx * 0.5f - _panXPx) / _scale
        val centerDocY = (viewportHeightPx * 0.5f - _panYPx) / _scale
        if (!centerDocX.isFinite() || !centerDocY.isFinite()) return null
        val relZoom = (_scale / _baseFitScale.coerceAtLeast(0.1f)).coerceIn(0.65f, 5.0f)
        return SavedViewportAnchor(
            pageId = pageId,
            localCenterXPt = centerDocX - pageOriginXPt,
            localCenterYPt = centerDocY - pageOriginYPt,
            relativeZoomFactor = relZoom
        )
    }

    /**
     * Pans and (when zoomed out far below base page readability) zooms the viewport so
     * [targetRect] sits cleanly in the center safe zone away from top toolbars.
     * Returns `true` if the viewport transform changed.
     */
    @Synchronized
    fun centerOnDocRectIfNeeded(
        targetRect: DocRect,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        forceCenter: Boolean = false
    ): Boolean {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return false
        val sLeft = targetRect.leftPt * _scale + _panXPx
        val sTop = targetRect.topPt * _scale + _panYPx
        val sRight = targetRect.rightPt * _scale + _panXPx
        val sBottom = targetRect.bottomPt * _scale + _panYPx
        val alreadyReadableInSafeZone = !forceCenter &&
            _scale >= _baseFitScale * 0.85f &&
            sLeft >= 32f &&
            sRight <= viewportWidthPx - 32f &&
            sTop >= 120f &&
            sBottom <= viewportHeightPx - 96f
        if (alreadyReadableInSafeZone) return false

        val minReadableScale = (_baseFitScale * 1.15f).coerceIn(MIN_SCALE, MAX_SCALE)
        val targetScale = max(_scale, minReadableScale).coerceIn(MIN_SCALE, MAX_SCALE)
        _scale = targetScale
        _panXPx = viewportWidthPx * 0.5f - targetRect.centerXPt * targetScale
        _panYPx = viewportHeightPx * 0.45f - targetRect.centerYPt * targetScale
        _version++
        return true
    }

    /**
     * Populates [outMatrix] with the document-to-screen transform without allocating.
     */
    @Synchronized
    fun populateDocToScreenMatrix(outMatrix: Matrix) {
        outMatrix.setScale(_scale, _scale)
        outMatrix.postTranslate(_panXPx, _panYPx)
    }

    /**
     * Populates [outMatrix] with the screen-to-document inverse transform without allocating.
     */
    @Synchronized
    fun populateScreenToDocMatrix(outMatrix: Matrix) {
        val invScale = 1f / _scale
        outMatrix.setTranslate(-_panXPx, -_panYPx)
        outMatrix.postScale(invScale, invScale)
    }

    /**
     * Writes row-major 3x3 android.graphics.Matrix values for document-to-screen mapping.
     */
    @Synchronized
    fun writeDocToScreenMatrixValues(out9: FloatArray) {
        require(out9.size >= 9) { "Matrix value array must have length >= 9" }
        out9[0] = _scale; out9[1] = 0f; out9[2] = _panXPx
        out9[3] = 0f; out9[4] = _scale; out9[5] = _panYPx
        out9[6] = 0f; out9[7] = 0f; out9[8] = 1f
    }

    /**
     * Writes row-major 3x3 android.graphics.Matrix values for screen-to-document mapping.
     */
    @Synchronized
    fun writeScreenToDocMatrixValues(out9: FloatArray) {
        require(out9.size >= 9) { "Matrix value array must have length >= 9" }
        val invScale = 1f / _scale
        out9[0] = invScale; out9[1] = 0f; out9[2] = -_panXPx * invScale
        out9[3] = 0f; out9[4] = invScale; out9[5] = -_panYPx * invScale
        out9[6] = 0f; out9[7] = 0f; out9[8] = 1f
    }

    @Synchronized
    fun docToScreen(xPt: Float, yPt: Float): ScreenPoint = ScreenPoint(
        xPx = xPt * _scale + _panXPx,
        yPx = yPt * _scale + _panYPx
    )

    @Synchronized
    fun screenToDoc(xPx: Float, yPx: Float): DocPoint {
        val invScale = 1f / _scale
        return DocPoint(
            xPt = (xPx - _panXPx) * invScale,
            yPt = (yPx - _panYPx) * invScale
        )
    }

    @Synchronized
    fun docRectToScreenRect(docRect: DocRect): DocRect {
        val leftPx = docRect.leftPt * _scale + _panXPx
        val topPx = docRect.topPt * _scale + _panYPx
        val rightPx = docRect.rightPt * _scale + _panXPx
        val bottomPx = docRect.bottomPt * _scale + _panYPx
        return DocRect(leftPx, topPx, rightPx, bottomPx)
    }

    @Synchronized
    fun screenRectToDocRect(
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float
    ): DocRect {
        val invScale = 1f / _scale
        return DocRect(
            leftPt = (min(leftPx, rightPx) - _panXPx) * invScale,
            topPt = (min(topPx, bottomPx) - _panYPx) * invScale,
            rightPt = (max(leftPx, rightPx) - _panXPx) * invScale,
            bottomPt = (max(topPx, bottomPx) - _panYPx) * invScale
        )
    }

    @Synchronized
    fun docSizeToScreenPx(sizePt: Float): Float = sizePt * _scale

    @Synchronized
    fun screenPxToDocSize(sizePx: Float): Float = sizePx / _scale

    companion object {
        // Prevent extreme zoom-out where too many 512x512 dry-ink tiles enter viewport simultaneously.
        const val MIN_SCALE: Float = 0.65f
        const val MAX_SCALE: Float = 5.0f

        const val MM_TO_PT: Float = 72f / 25.4f
        const val GRID_5MM_PT: Float = 5f * MM_TO_PT
        const val RULED_8MM_PT: Float = 8f * MM_TO_PT

        fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}

