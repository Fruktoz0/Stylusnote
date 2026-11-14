package hu.bdev.stylusnote.core.math

import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.min

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
    private var _scale: Float = clampScale(initialScale)
    private var _panXPx: Float = initialPanXPx
    private var _panYPx: Float = initialPanYPx
    private var _version: Long = 1L

    val scale: Float
        @Synchronized get() = _scale

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
        val clamped = clampScale(scale)
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
        val newScale = clampScale(oldScale * zoomFactor)

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
        if (pageWidthPt <= 0f || pageHeightPt <= 0f ||
            viewportWidthPx <= paddingPx * 2f || viewportHeightPx <= paddingPx * 2f
        ) {
            return
        }

        val availWidth = viewportWidthPx - paddingPx * 2f
        val availHeight = viewportHeightPx - paddingPx * 2f
        val targetScale = clampScale(min(availWidth / pageWidthPt, availHeight / pageHeightPt))

        val scaledWidth = pageWidthPt * targetScale
        val scaledHeight = pageHeightPt * targetScale

        _scale = targetScale
        _panXPx = (viewportWidthPx - scaledWidth) * 0.5f
        _panYPx = (viewportHeightPx - scaledHeight) * 0.5f
        _version++
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
        const val MIN_SCALE: Float = 0.25f
        const val MAX_SCALE: Float = 5.0f

        const val MM_TO_PT: Float = 72f / 25.4f
        const val GRID_5MM_PT: Float = 5f * MM_TO_PT
        const val RULED_8MM_PT: Float = 8f * MM_TO_PT

        fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}

