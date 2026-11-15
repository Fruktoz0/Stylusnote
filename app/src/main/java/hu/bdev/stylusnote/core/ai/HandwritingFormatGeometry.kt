package hu.bdev.stylusnote.core.ai

import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.core.math.ShapeMeta
import hu.bdev.stylusnote.core.math.ShapeRecognizer
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin geometry and coordinate-mapping helpers shared between AI page/lasso formatting
 * passes and JVM unit tests.
 */
object HandwritingFormatGeometry {

    fun parseRecognizedShapeType(raw: String): RecognizedShapeType? = when (raw.trim().lowercase()) {
        "line" -> RecognizedShapeType.LINE
        "arrow" -> RecognizedShapeType.ARROW
        "circle" -> RecognizedShapeType.CIRCLE
        "ellipse", "oval" -> RecognizedShapeType.ELLIPSE
        "triangle" -> RecognizedShapeType.TRIANGLE
        "rectangle", "rect", "square", "box" -> RecognizedShapeType.RECTANGLE
        else -> null
    }

    fun normalizedYxPointToDoc(yNormalized0To1000: Float, xNormalized0To1000: Float, frame: DocRect): DocPoint {
        val xFrac = xNormalized0To1000.coerceIn(0f, 1000f) / 1000f
        val yFrac = yNormalized0To1000.coerceIn(0f, 1000f) / 1000f
        return DocPoint(
            xPt = frame.leftPt + xFrac * frame.widthPt,
            yPt = frame.topPt + yFrac * frame.heightPt
        )
    }

    /**
     * Returns `true` when [stroke] belongs to a recognized linear element (`LINE` / `ARROW`)
     * running from [segmentStart] to [segmentEnd], avoiding swallowing nearby text labels inside
     * the diagonal bounding rectangle.
     */
    fun doesStrokeMatchLinearSegment(
        stroke: PersistedStrokeRecord,
        segmentStart: DocPoint,
        segmentEnd: DocPoint
    ): Boolean {
        val segLen = hypot(segmentEnd.xPt - segmentStart.xPt, segmentEnd.yPt - segmentStart.yPt)
        if (segLen < 6f) {
            return stroke.bounds.inflate(14f).contains(segmentStart.xPt, segmentStart.yPt)
        }
        val maxCenterDist = max(24f, min(38f, segLen * 0.22f))
        val cx = stroke.bounds.centerXPt
        val cy = stroke.bounds.centerYPt
        val centerDist = pointToSegmentDistance(cx, cy, segmentStart, segmentEnd)
        if (centerDist > maxCenterDist) return false

        val samples = stroke.samples
        if (samples.isEmpty()) return true
        // At least half the stroke's points should track reasonably near the arrow/line segment.
        val sampleMaxDist = max(28f, min(46f, segLen * 0.28f))
        var nearCount = 0
        for (i in samples.indices) {
            val pt = samples[i]
            if (pointToSegmentDistance(pt.xPt, pt.yPt, segmentStart, segmentEnd) <= sampleMaxDist) {
                nearCount++
            }
        }
        return nearCount * 2 >= samples.size
    }

    fun pointToSegmentDistance(px: Float, py: Float, a: DocPoint, b: DocPoint): Float {
        val abx = b.xPt - a.xPt
        val aby = b.yPt - a.yPt
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 1e-6f) return hypot(px - a.xPt, py - a.yPt)
        val t = (((px - a.xPt) * abx + (py - a.yPt) * aby) / lenSq).coerceIn(0f, 1f)
        val projX = a.xPt + t * abx
        val projY = a.yPt + t * aby
        return hypot(px - projX, py - projY)
    }

    fun buildShapeMetaForElement(
        shapeType: RecognizedShapeType,
        strokes: List<PersistedStrokeRecord>,
        bounds: DocRect,
        aiStartPt: DocPoint? = null,
        aiEndPt: DocPoint? = null
    ): ShapeMeta {
        if (shapeType == RecognizedShapeType.LINE || shapeType == RecognizedShapeType.ARROW) {
            val (tailPt, tipPt) = resolveExactLinearEndpoints(
                strokes = strokes,
                bounds = bounds,
                aiStartPt = aiStartPt,
                aiEndPt = aiEndPt
            )
            return ShapeMeta(
                shapeType = shapeType,
                controlPoints = listOf(tailPt, tipPt),
                rotationRadians = atan2(tipPt.yPt - tailPt.yPt, tipPt.xPt - tailPt.xPt)
            )
        }
        return buildShapeMetaForBounds(shapeType, bounds)
    }

    /**
     * Resolves exact `(start/tail, end/tip)` points for a `LINE` or `ARROW`, combining the
     * AI-detected orientation (`aiStartPt -> aiEndPt`) with projection onto the actual hand-drawn
     * ink samples so arrows keep both their original placement and their true direction/angle.
     */
    fun resolveExactLinearEndpoints(
        strokes: List<PersistedStrokeRecord>,
        bounds: DocRect,
        aiStartPt: DocPoint? = null,
        aiEndPt: DocPoint? = null
    ): Pair<DocPoint, DocPoint> {
        if (aiStartPt != null && aiEndPt != null) {
            val dx = aiEndPt.xPt - aiStartPt.xPt
            val dy = aiEndPt.yPt - aiStartPt.yPt
            val segLen = hypot(dx, dy)
            if (segLen >= 6f) {
                val ux = dx / segLen
                val uy = dy / segLen
                val allPoints = collectSamplePoints(strokes)
                if (allPoints.size >= 2) {
                    val maxPerpDist = max(36f, min(64f, segLen * 0.35f))
                    var minProj = Float.POSITIVE_INFINITY
                    var maxProj = Float.NEGATIVE_INFINITY
                    var tailCandidate: DocPoint? = null
                    var tipCandidate: DocPoint? = null
                    for (pt in allPoints) {
                        val perp = pointToSegmentDistance(pt.xPt, pt.yPt, aiStartPt, aiEndPt)
                        if (perp > maxPerpDist) continue
                        val proj = (pt.xPt - aiStartPt.xPt) * ux + (pt.yPt - aiStartPt.yPt) * uy
                        if (proj < minProj) {
                            minProj = proj
                            tailCandidate = pt
                        }
                        if (proj > maxProj) {
                            maxProj = proj
                            tipCandidate = pt
                        }
                    }
                    if (tailCandidate != null && tipCandidate != null &&
                        hypot(tipCandidate.xPt - tailCandidate.xPt, tipCandidate.yPt - tailCandidate.yPt) >= 6f
                    ) {
                        return tailCandidate to tipCandidate
                    }
                }
                return aiStartPt to aiEndPt
            }
        }

        // Local geometric recognition fallback when Gemini omits startPoint/endPoint.
        if (strokes.size == 1) {
            val local = ShapeRecognizer.recognize(
                samples = strokes.first().samples,
                requireHoldAtEnd = false,
                minConfidence = 0.65f
            )
            if (local != null &&
                (local.shapeMeta.shapeType == RecognizedShapeType.ARROW ||
                    local.shapeMeta.shapeType == RecognizedShapeType.LINE) &&
                local.shapeMeta.controlPoints.size >= 2
            ) {
                return local.shapeMeta.controlPoints[0] to local.shapeMeta.controlPoints[1]
            }
        }

        val longestSamples = strokes.maxByOrNull { it.samples.size }?.samples.orEmpty()
        if (longestSamples.size >= 2) {
            val start = longestSamples.first()
            val startPt = DocPoint(start.xPt, start.yPt)
            var farthest = longestSamples.last()
            var maxDist = hypot(farthest.xPt - start.xPt, farthest.yPt - start.yPt)
            for (pt in longestSamples) {
                val d = hypot(pt.xPt - start.xPt, pt.yPt - start.yPt)
                if (d > maxDist) {
                    maxDist = d
                    farthest = pt
                }
            }
            if (maxDist >= 6f) {
                return startPt to DocPoint(farthest.xPt, farthest.yPt)
            }
        }

        val cx = bounds.centerXPt
        val cy = bounds.centerYPt
        return if (bounds.widthPt >= bounds.heightPt * 0.6f) {
            DocPoint(bounds.leftPt, cy) to DocPoint(bounds.rightPt, cy)
        } else {
            DocPoint(cx, bounds.topPt) to DocPoint(cx, bounds.bottomPt)
        }
    }

    fun buildShapeMetaForBounds(shapeType: RecognizedShapeType, bounds: DocRect): ShapeMeta {
        val cx = bounds.centerXPt
        val cy = bounds.centerYPt
        val pts: List<DocPoint> = when (shapeType) {
            RecognizedShapeType.LINE, RecognizedShapeType.ARROW -> {
                if (bounds.widthPt >= bounds.heightPt * 0.6f) {
                    listOf(DocPoint(bounds.leftPt, cy), DocPoint(bounds.rightPt, cy))
                } else {
                    listOf(DocPoint(cx, bounds.topPt), DocPoint(cx, bounds.bottomPt))
                }
            }
            RecognizedShapeType.CIRCLE -> {
                val r = (min(bounds.widthPt, bounds.heightPt) * 0.5f).coerceAtLeast(10f)
                listOf(DocPoint(cx, cy), DocPoint(cx + r, cy))
            }
            RecognizedShapeType.ELLIPSE -> listOf(
                DocPoint(cx, cy),
                DocPoint(bounds.rightPt, cy),
                DocPoint(cx, bounds.bottomPt)
            )
            RecognizedShapeType.TRIANGLE -> listOf(
                DocPoint(cx, bounds.topPt),
                DocPoint(bounds.rightPt, bounds.bottomPt),
                DocPoint(bounds.leftPt, bounds.bottomPt)
            )
            RecognizedShapeType.RECTANGLE -> listOf(
                DocPoint(bounds.leftPt, bounds.topPt),
                DocPoint(bounds.rightPt, bounds.topPt),
                DocPoint(bounds.rightPt, bounds.bottomPt),
                DocPoint(bounds.leftPt, bounds.bottomPt)
            )
        }
        return ShapeMeta(shapeType = shapeType, controlPoints = pts)
    }

    private fun collectSamplePoints(strokes: List<PersistedStrokeRecord>): List<DocPoint> {
        val out = ArrayList<DocPoint>()
        for (s in strokes) {
            if (s.samples.isNotEmpty()) {
                for (sample in s.samples) {
                    out.add(DocPoint(sample.xPt, sample.yPt))
                }
            } else {
                out.add(DocPoint(s.bounds.leftPt, s.bounds.topPt))
                out.add(DocPoint(s.bounds.rightPt, s.bounds.bottomPt))
            }
        }
        return out
    }
}
