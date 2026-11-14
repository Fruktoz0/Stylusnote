package hu.bdev.stylusnote.domain.model

import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.ui.canvas.IndexedDryStroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-Kotlin 2D affine transform operating in PDF-point (`1/72 in`) document space:
 * ```
 * x' = scaleX * x + shearX * y + translateXPt
 * y' = shearY * x + scaleY * y + translateYPt
 * ```
 */
data class AffineTransform2D(
    val scaleX: Float = 1f,
    val shearX: Float = 0f,
    val translateXPt: Float = 0f,
    val shearY: Float = 0f,
    val scaleY: Float = 1f,
    val translateYPt: Float = 0f
) {
    fun transformPoint(xPt: Float, yPt: Float): DocPoint = DocPoint(
        xPt = scaleX * xPt + shearX * yPt + translateXPt,
        yPt = shearY * xPt + scaleY * yPt + translateYPt
    )

    fun transformRect(rect: DocRect): DocRect {
        val p1 = transformPoint(rect.leftPt, rect.topPt)
        val p2 = transformPoint(rect.rightPt, rect.topPt)
        val p3 = transformPoint(rect.rightPt, rect.bottomPt)
        val p4 = transformPoint(rect.leftPt, rect.bottomPt)
        return DocRect(
            leftPt = min(min(p1.xPt, p2.xPt), min(p3.xPt, p4.xPt)),
            topPt = min(min(p1.yPt, p2.yPt), min(p3.yPt, p4.yPt)),
            rightPt = max(max(p1.xPt, p2.xPt), max(p3.xPt, p4.xPt)),
            bottomPt = max(max(p1.yPt, p2.yPt), max(p3.yPt, p4.yPt))
        )
    }

    /**
     * Composes `this` followed by [other] (`other(this(p))`).
     */
    fun then(other: AffineTransform2D): AffineTransform2D = AffineTransform2D(
        scaleX = other.scaleX * scaleX + other.shearX * shearY,
        shearX = other.scaleX * shearX + other.shearX * scaleY,
        translateXPt = other.scaleX * translateXPt + other.shearX * translateYPt + other.translateXPt,
        shearY = other.shearY * scaleX + other.scaleY * shearY,
        scaleY = other.shearY * shearX + other.scaleY * scaleY,
        translateYPt = other.shearY * translateXPt + other.scaleY * translateYPt + other.translateYPt
    )

    /**
     * Returns the inverse affine transform, or `null` if the linear part is singular.
     */
    fun invert(): AffineTransform2D? {
        val det = scaleX * scaleY - shearX * shearY
        if (abs(det) < 1e-8f) return null
        val invDet = 1f / det
        val invScaleX = scaleY * invDet
        val invShearX = -shearX * invDet
        val invShearY = -shearY * invDet
        val invScaleY = scaleX * invDet
        val invTx = -(invScaleX * translateXPt + invShearX * translateYPt)
        val invTy = -(invShearY * translateXPt + invScaleY * translateYPt)
        return AffineTransform2D(
            scaleX = invScaleX,
            shearX = invShearX,
            translateXPt = invTx,
            shearY = invShearY,
            scaleY = invScaleY,
            translateYPt = invTy
        )
    }

    /**
     * Estimates uniform stroke-width scale factor from the linear basis vectors.
     */
    fun averageScaleFactor(): Float {
        val sx = hypot(scaleX, shearY)
        val sy = hypot(shearX, scaleY)
        return ((sx + sy) * 0.5f).coerceAtLeast(0.05f)
    }

    companion object {
        val IDENTITY: AffineTransform2D = AffineTransform2D()

        fun translation(dxPt: Float, dyPt: Float): AffineTransform2D =
            AffineTransform2D(translateXPt = dxPt, translateYPt = dyPt)

        fun scalingAround(
            scaleX: Float,
            scaleY: Float,
            pivotXPt: Float,
            pivotYPt: Float
        ): AffineTransform2D = AffineTransform2D(
            scaleX = scaleX,
            scaleY = scaleY,
            translateXPt = pivotXPt * (1f - scaleX),
            translateYPt = pivotYPt * (1f - scaleY)
        )

        fun rotationAround(
            angleRad: Float,
            pivotXPt: Float,
            pivotYPt: Float
        ): AffineTransform2D {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return AffineTransform2D(
                scaleX = c,
                shearX = -s,
                translateXPt = pivotXPt - c * pivotXPt + s * pivotYPt,
                shearY = s,
                scaleY = c,
                translateYPt = pivotYPt - s * pivotXPt - c * pivotYPt
            )
        }
    }
}

/**
 * Delta-based Undo/Redo commands stored by [hu.bdev.stylusnote.domain.repository.CanvasStateHolder].
 *
 * Each command carries only the touched strokes rather than full-canvas snapshots, keeping memory
 * linear in edited points while supporting at least 50 undo levels.
 */
sealed interface CanvasAction {
    val estimatedPointCost: Int
    val affectedBounds: DocRect?

    data class AddedStrokes(
        val strokes: List<IndexedDryStroke>
    ) : CanvasAction {
        override val estimatedPointCost: Int =
            strokes.sumOf { maxOf(1, it.stroke.inputs.size) }

        override val affectedBounds: DocRect? = mergeBounds(strokes.map { it.bounds })
    }

    /**
     * Retains the full removed [IndexedDryStroke] objects (immutable `Stroke` references) so Undo
     * restores exact geometry, brush config, and z-order without re-Authoring.
     */
    data class RemovedStrokes(
        val strokes: List<IndexedDryStroke>
    ) : CanvasAction {
        override val estimatedPointCost: Int =
            strokes.sumOf { maxOf(1, it.stroke.inputs.size) }

        override val affectedBounds: DocRect? = mergeBounds(strokes.map { it.bounds })
    }

    data class TransformedStrokes(
        val strokeIds: List<String>,
        val beforeStrokes: List<IndexedDryStroke>,
        val afterStrokes: List<IndexedDryStroke>,
        val transform: AffineTransform2D
    ) : CanvasAction {
        override val estimatedPointCost: Int =
            beforeStrokes.sumOf { maxOf(1, it.stroke.inputs.size) } +
                afterStrokes.sumOf { maxOf(1, it.stroke.inputs.size) }

        override val affectedBounds: DocRect? = mergeBounds(
            beforeStrokes.map { it.bounds } + afterStrokes.map { it.bounds }
        )
    }

    data class RecoloredStrokes(
        val beforeStrokes: List<IndexedDryStroke>,
        val afterStrokes: List<IndexedDryStroke>
    ) : CanvasAction {
        override val estimatedPointCost: Int = maxOf(1, beforeStrokes.size)

        override val affectedBounds: DocRect? = mergeBounds(afterStrokes.map { it.bounds })
    }

    /**
     * Groups multiple atomic deltas into one Undo/Redo step (e.g., an entire eraser drag or
     * precision stroke split producing N independent segments).
     */
    data class CompositeAction(
        val actions: List<CanvasAction>,
        val description: String = ""
    ) : CanvasAction {
        override val estimatedPointCost: Int =
            maxOf(1, actions.sumOf { it.estimatedPointCost })

        override val affectedBounds: DocRect? =
            mergeBounds(actions.mapNotNull { it.affectedBounds })
    }

    /** Inserts a Layer-5 embedded object (LaTeX formula, derivation card, or function plot). */
    data class AddedObject(
        val pageObject: PageObject
    ) : CanvasAction {
        override val estimatedPointCost: Int = 4
        override val affectedBounds: DocRect = pageObject.bounds
    }

    /** Removes a Layer-5 embedded object while retaining its full snapshot for Undo. */
    data class RemovedObject(
        val pageObject: PageObject
    ) : CanvasAction {
        override val estimatedPointCost: Int = 4
        override val affectedBounds: DocRect = pageObject.bounds
    }

    /** Replaces or transforms a Layer-5 embedded object between [beforeObject] and [afterObject]. */
    data class UpdatedObject(
        val beforeObject: PageObject,
        val afterObject: PageObject
    ) : CanvasAction {
        override val estimatedPointCost: Int = 4
        override val affectedBounds: DocRect? =
            mergeBounds(listOf(beforeObject.bounds, afterObject.bounds))
    }
}

internal fun mergeBounds(rects: List<DocRect>): DocRect? {
    if (rects.isEmpty()) return null
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (i in 0 until rects.size) {
        val r = rects[i]
        minX = min(minX, r.leftPt)
        minY = min(minY, r.topPt)
        maxX = max(maxX, r.rightPt)
        maxY = max(maxY, r.bottomPt)
    }
    return if (minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
        DocRect(minX, minY, maxX, maxY)
    } else {
        null
    }
}

