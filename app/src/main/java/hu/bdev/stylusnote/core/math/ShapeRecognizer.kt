package hu.bdev.stylusnote.core.math

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.domain.model.AffineTransform2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometric primitives supported by the Hold-to-Snap recognizer (Section 5.2).
 */
enum class RecognizedShapeType(val displayName: String) {
    LINE("Egyenes"),
    CIRCLE("Kör"),
    ELLIPSE("Ellipszis"),
    TRIANGLE("Háromszög"),
    RECTANGLE("Téglalap"),
    ARROW("Nyíl")
}

/**
 * Editable shape metadata stored alongside a snapped [androidx.ink.strokes.Stroke] so the user
 * can later stretch or rotate the shape via its [controlPoints] and regenerate the stroke cleanly.
 */
data class ShapeMeta(
    val shapeType: RecognizedShapeType,
    val controlPoints: List<DocPoint>,
    val rotationRadians: Float = 0f
) {
    fun transform(affine: AffineTransform2D): ShapeMeta = copy(
        controlPoints = controlPoints.map { affine.transformPoint(it.xPt, it.yPt) }
    )

    /**
     * Generates clean document-space samples from [controlPoints] using [templateSample] for
     * pressure/tilt/tool metadata.
     */
    fun generateSamples(
        templateSample: StylusPointSample,
        totalDurationMs: Long = 180L
    ): List<StylusPointSample> {
        val rawPoints: List<DocPoint> = when (shapeType) {
            RecognizedShapeType.LINE -> {
                if (controlPoints.size < 2) return emptyList()
                interpolatePolyline(listOf(controlPoints[0], controlPoints[1]), pointsPerSegment = 24)
            }
            RecognizedShapeType.ARROW -> {
                if (controlPoints.size < 2) return emptyList()
                buildArrowPolyline(controlPoints[0], controlPoints[1])
            }
            RecognizedShapeType.CIRCLE -> {
                if (controlPoints.size < 2) return emptyList()
                val center = controlPoints[0]
                val rim = controlPoints[1]
                val radius = hypot(rim.xPt - center.xPt, rim.yPt - center.yPt).coerceAtLeast(2f)
                buildEllipsePolyline(center.xPt, center.yPt, radius, radius, rotationRadians = 0f, segments = 64)
            }
            RecognizedShapeType.ELLIPSE -> {
                if (controlPoints.size < 3) return emptyList()
                val center = controlPoints[0]
                val axisX = hypot(controlPoints[1].xPt - center.xPt, controlPoints[1].yPt - center.yPt)
                    .coerceAtLeast(2f)
                val axisY = hypot(controlPoints[2].xPt - center.xPt, controlPoints[2].yPt - center.yPt)
                    .coerceAtLeast(2f)
                buildEllipsePolyline(center.xPt, center.yPt, axisX, axisY, rotationRadians, segments = 72)
            }
            RecognizedShapeType.TRIANGLE -> {
                if (controlPoints.size < 3) return emptyList()
                val closed = listOf(controlPoints[0], controlPoints[1], controlPoints[2], controlPoints[0])
                interpolatePolyline(closed, pointsPerSegment = 16)
            }
            RecognizedShapeType.RECTANGLE -> {
                if (controlPoints.size < 4) return emptyList()
                val closed = listOf(
                    controlPoints[0],
                    controlPoints[1],
                    controlPoints[2],
                    controlPoints[3],
                    controlPoints[0]
                )
                interpolatePolyline(closed, pointsPerSegment = 16)
            }
        }

        if (rawPoints.size < 2) return emptyList()
        val duration = totalDurationMs.coerceAtLeast(rawPoints.size.toLong())
        val lastIdx = rawPoints.lastIndex
        return rawPoints.mapIndexed { idx, pt ->
            val t = (idx.toFloat() / lastIdx.toFloat())
            StylusPointSample(
                xPt = pt.xPt,
                yPt = pt.yPt,
                elapsedTimeMillis = (t * duration).toLong(),
                pressure = templateSample.pressure.coerceIn(0.25f, 1.0f),
                tiltRadians = 0f,
                orientationRadians = 0f,
                isEraserTool = false
            )
        }
    }

    companion object {
        private fun interpolatePolyline(
            vertices: List<DocPoint>,
            pointsPerSegment: Int
        ): List<DocPoint> {
            if (vertices.size < 2) return vertices
            val result = ArrayList<DocPoint>((vertices.size - 1) * pointsPerSegment + 1)
            for (i in 0 until vertices.size - 1) {
                val a = vertices[i]
                val b = vertices[i + 1]
                val startStep = if (i == 0) 0 else 1
                for (step in startStep..pointsPerSegment) {
                    val t = step.toFloat() / pointsPerSegment.toFloat()
                    result.add(
                        DocPoint(
                            xPt = a.xPt + (b.xPt - a.xPt) * t,
                            yPt = a.yPt + (b.yPt - a.yPt) * t
                        )
                    )
                }
            }
            return result
        }

        private fun buildEllipsePolyline(
            cx: Float,
            cy: Float,
            rx: Float,
            ry: Float,
            rotationRadians: Float,
            segments: Int
        ): List<DocPoint> {
            val cosR = cos(rotationRadians)
            val sinR = sin(rotationRadians)
            val pts = ArrayList<DocPoint>(segments + 1)
            for (i in 0..segments) {
                val theta = (2.0 * PI * i.toDouble() / segments.toDouble()).toFloat()
                val lx = rx * cos(theta)
                val ly = ry * sin(theta)
                pts.add(
                    DocPoint(
                        xPt = cx + lx * cosR - ly * sinR,
                        yPt = cy + lx * sinR + ly * cosR
                    )
                )
            }
            return pts
        }

        private fun buildArrowPolyline(start: DocPoint, tip: DocPoint): List<DocPoint> {
            val dx = tip.xPt - start.xPt
            val dy = tip.yPt - start.yPt
            val length = hypot(dx, dy).coerceAtLeast(1f)
            val ux = dx / length
            val uy = dy / length
            val headLen = (length * 0.22f).coerceIn(10f, 32f)
            val halfAngle = (32.0 * PI / 180.0).toFloat()
            val backCos = cos(PI.toFloat() - halfAngle)
            val backSin = sin(PI.toFloat() - halfAngle)

            val wing1 = DocPoint(
                xPt = tip.xPt + headLen * (ux * backCos - uy * backSin),
                yPt = tip.yPt + headLen * (ux * backSin + uy * backCos)
            )
            val wing2 = DocPoint(
                xPt = tip.xPt + headLen * (ux * backCos + uy * backSin),
                yPt = tip.yPt + headLen * (-ux * backSin + uy * backCos)
            )

            return interpolatePolyline(
                vertices = listOf(start, tip, wing1, tip, wing2),
                pointsPerSegment = 12
            )
        }
    }
}

data class ShapeRecognitionResult(
    val shapeMeta: ShapeMeta,
    val snappedSamples: List<StylusPointSample>,
    val confidence: Float
)

/**
 * Hold-to-Snap geometric recognizer (Section 5.2):
 * Recognizes Line, Circle, Ellipse, Triangle, Rectangle, and Arrow when the stylus is held steady
 * (`< 4 pt` drift for `>= 300 ms`) at the end of a stroke longer than `40 pt`.
 */
object ShapeRecognizer {
    const val MIN_HOLD_DURATION_MS: Long = 300L
    const val MAX_HOLD_DRIFT_PT: Float = 4.0f
    const val MIN_PATH_LENGTH_PT: Float = 40.0f
    const val DEFAULT_MIN_CONFIDENCE: Float = 0.72f

    fun computePathLengthPt(samples: List<StylusPointSample>): Float {
        if (samples.size < 2) return 0f
        var length = 0f
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            length += hypot(b.xPt - a.xPt, b.yPt - a.yPt)
        }
        return length
    }

    /**
     * Checks whether the tail of [samples] stayed within [maxDriftPt] of the final sample
     * for at least [minHoldMs] (default `< 4 pt` over `>= 300 ms`), and returns false if the
     * stroke never moved beyond [MIN_PATH_LENGTH_PT].
     */
    fun isHoldToSnapTriggered(
        samples: List<StylusPointSample>,
        minHoldMs: Long = MIN_HOLD_DURATION_MS,
        maxDriftPt: Float = MAX_HOLD_DRIFT_PT
    ): Boolean {
        if (samples.size < 2) return false
        val end = samples.last()
        var stationaryStartIndex = samples.lastIndex

        for (i in samples.lastIndex - 1 downTo 0) {
            val p = samples[i]
            val distFromEnd = hypot(p.xPt - end.xPt, p.yPt - end.yPt)
            if (distFromEnd < maxDriftPt) {
                stationaryStartIndex = i
            } else {
                break
            }
        }

        val holdDuration = end.elapsedTimeMillis - samples[stationaryStartIndex].elapsedTimeMillis
        return holdDuration >= minHoldMs
    }

    fun recognize(
        samples: List<StylusPointSample>,
        requireHoldAtEnd: Boolean = true,
        lineOnly: Boolean = false,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE
    ): ShapeRecognitionResult? {
        if (samples.size < 2) return null
        val totalLen = computePathLengthPt(samples)
        if (totalLen <= MIN_PATH_LENGTH_PT) return null
        if (requireHoldAtEnd && !isHoldToSnapTriggered(samples)) return null

        // Strip trailing stationary hold jitter before evaluating geometry.
        val end = samples.last()
        var lastMovingIdx = samples.lastIndex
        while (lastMovingIdx > 1 &&
            hypot(samples[lastMovingIdx - 1].xPt - end.xPt, samples[lastMovingIdx - 1].yPt - end.yPt) < 1.5f
        ) {
            lastMovingIdx--
        }
        val core = samples.subList(0, lastMovingIdx + 1)
        if (core.size < 2) return null

        val template = core[core.size / 2]

        // 1. Straight line check (also the sole candidate for Highlighters when lineOnly = true)
        recognizeLine(core)?.let { (meta, confidence) ->
            if (confidence >= minConfidence) {
                return ShapeRecognitionResult(
                    shapeMeta = meta,
                    snappedSamples = meta.generateSamples(template),
                    confidence = confidence
                )
            }
        }
        if (lineOnly) return null

        // 2. Closed contour checks: Triangle, Rectangle, Circle, Ellipse
        val startPt = core.first()
        val endPt = core.last()
        val closureGap = hypot(endPt.xPt - startPt.xPt, endPt.yPt - startPt.yPt)
        val coreLength = computePathLengthPt(core)
        val maxClosureAllowed = max(28f, coreLength * 0.22f)

        var bestResult: Pair<ShapeMeta, Float>? = null
        if (closureGap <= maxClosureAllowed && core.size >= 6) {
            val circleOrEllipse = recognizeCircleOrEllipse(core)
            val polygon = recognizeTriangleOrRectangle(core)
            bestResult = when {
                polygon != null && circleOrEllipse != null ->
                    if (polygon.second >= circleOrEllipse.second) polygon else circleOrEllipse
                polygon != null -> polygon
                else -> circleOrEllipse
            }
        }

        // 3. Arrow check (open stroke with straight shaft and V-barbs at tip)
        if (bestResult == null || bestResult.second < minConfidence) {
            val arrow = recognizeArrow(core)
            if (arrow != null && (bestResult == null || arrow.second > bestResult.second)) {
                bestResult = arrow
            }
        }

        val winner = bestResult ?: return null
        if (winner.second < minConfidence) return null
        return ShapeRecognitionResult(
            shapeMeta = winner.first,
            snappedSamples = winner.first.generateSamples(template),
            confidence = winner.second
        )
    }

    private fun recognizeLine(core: List<StylusPointSample>): Pair<ShapeMeta, Float>? {
        val a = core.first()
        val b = core.last()
        val chord = hypot(b.xPt - a.xPt, b.yPt - a.yPt)
        val pathLen = computePathLengthPt(core)
        if (chord <= MIN_PATH_LENGTH_PT * 0.85f || pathLen <= 1e-3f) return null

        val directness = chord / pathLen
        if (directness < 0.90f) return null

        var maxPerpDev = 0f
        for (i in 1 until core.lastIndex) {
            val p = core[i]
            val dev = perpendicularDistance(p.xPt, p.yPt, a.xPt, a.yPt, b.xPt, b.yPt)
            if (dev > maxPerpDev) maxPerpDev = dev
        }
        val relDev = maxPerpDev / chord
        if (relDev > 0.08f) return null

        val confidence = (1f - relDev * 3.5f).coerceIn(0f, 1f) * directness.coerceIn(0f, 1f)
        val meta = ShapeMeta(
            shapeType = RecognizedShapeType.LINE,
            controlPoints = listOf(DocPoint(a.xPt, a.yPt), DocPoint(b.xPt, b.yPt)),
            rotationRadians = atan2(b.yPt - a.yPt, b.xPt - a.xPt)
        )
        return meta to confidence
    }

    private fun recognizeCircleOrEllipse(core: List<StylusPointSample>): Pair<ShapeMeta, Float>? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in core) {
            minX = min(minX, p.xPt)
            minY = min(minY, p.yPt)
            maxX = max(maxX, p.xPt)
            maxY = max(maxY, p.yPt)
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val rx = ((maxX - minX) * 0.5f).coerceAtLeast(1f)
        val ry = ((maxY - minY) * 0.5f).coerceAtLeast(1f)
        if (rx < 10f && ry < 10f) return null

        // Require angular coverage around center across at least 6 of 8 octants to reject open arcs.
        var octantMask = 0
        var sumErr = 0f
        var cornerPullCount = 0
        for (p in core) {
            val nx = (p.xPt - cx) / rx
            val ny = (p.yPt - cy) / ry
            val rNorm = hypot(nx, ny)
            sumErr += abs(rNorm - 1f)
            if (abs(nx) > 0.85f && abs(ny) > 0.85f) {
                cornerPullCount++
            }
            var deg = (atan2(ny.toDouble(), nx.toDouble()) * 180.0 / PI).toFloat()
            if (deg < 0f) deg += 360f
            val oct = (deg / 45f).toInt().coerceIn(0, 7)
            octantMask = octantMask or (1 shl oct)
        }
        val octantsCovered = Integer.bitCount(octantMask)
        if (octantsCovered < 6) return null

        val meanErr = sumErr / core.size
        // Corner-heavy shapes indicate a rectangle/triangle rather than a smooth circle/ellipse.
        val cornerRatio = cornerPullCount.toFloat() / core.size.toFloat()
        if (meanErr > 0.19f || cornerRatio > 0.22f) return null

        val confidence = (1f - meanErr * 1.4f - cornerRatio * 0.6f).coerceIn(0f, 1f)
        val aspectDiff = abs(rx - ry) / max(rx, ry)
        return if (aspectDiff <= 0.18f) {
            val radius = (rx + ry) * 0.5f
            val meta = ShapeMeta(
                shapeType = RecognizedShapeType.CIRCLE,
                controlPoints = listOf(DocPoint(cx, cy), DocPoint(cx + radius, cy))
            )
            meta to confidence
        } else {
            val meta = ShapeMeta(
                shapeType = RecognizedShapeType.ELLIPSE,
                controlPoints = listOf(
                    DocPoint(cx, cy),
                    DocPoint(cx + rx, cy),
                    DocPoint(cx, cy + ry)
                )
            )
            meta to confidence
        }
    }

    private fun recognizeTriangleOrRectangle(core: List<StylusPointSample>): Pair<ShapeMeta, Float>? {
        val pts = core.map { DocPoint(it.xPt, it.yPt) }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in pts) {
            minX = min(minX, p.xPt)
            minY = min(minY, p.yPt)
            maxX = max(maxX, p.xPt)
            maxY = max(maxY, p.yPt)
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 20f) return null

        val epsilon = max(7f, diag * 0.06f)
        // Simplify open trace, then drop the redundant closing vertex if close to first vertex.
        val simplified = rdpSimplify(pts, epsilon).toMutableList()
        if (simplified.size >= 2 &&
            hypot(simplified.last().xPt - simplified.first().xPt, simplified.last().yPt - simplified.first().yPt) < diag * 0.20f
        ) {
            simplified.removeAt(simplified.lastIndex)
        }
        val corners = mergeNearlyCollinearCorners(simplified, minCornerAngleDeg = 25f)

        return when (corners.size) {
            3 -> {
                val fitErr = maxEdgeFitErrorRatio(pts, corners, diag)
                if (fitErr > 0.11f) return null
                val confidence = (0.94f - fitErr * 1.8f).coerceIn(0f, 1f)
                ShapeMeta(
                    shapeType = RecognizedShapeType.TRIANGLE,
                    controlPoints = corners
                ) to confidence
            }
            4 -> {
                if (!hasNearlyRightAngles(corners, maxAngleDeviationDeg = 26f)) return null
                val fitErr = maxEdgeFitErrorRatio(pts, corners, diag)
                if (fitErr > 0.11f) return null
                val confidence = (0.95f - fitErr * 1.8f).coerceIn(0f, 1f)
                ShapeMeta(
                    shapeType = RecognizedShapeType.RECTANGLE,
                    controlPoints = corners
                ) to confidence
            }
            else -> null
        }
    }

    private fun recognizeArrow(core: List<StylusPointSample>): Pair<ShapeMeta, Float>? {
        if (core.size < 6) return null
        val start = core.first()

        // Find the tip: point farthest from start before backtracking for arrowhead wings.
        var tipIdx = 0
        var maxDistFromStart = 0f
        for (i in 1 until core.size) {
            val d = hypot(core[i].xPt - start.xPt, core[i].yPt - start.yPt)
            if (d > maxDistFromStart) {
                maxDistFromStart = d
                tipIdx = i
            }
        }
        if (maxDistFromStart < MIN_PATH_LENGTH_PT * 0.8f) return null
        if (tipIdx <= 1 || tipIdx >= core.lastIndex) return null

        val tip = core[tipIdx]
        val shaftSamples = core.subList(0, tipIdx + 1)
        val shaftLen = computePathLengthPt(shaftSamples)
        if (shaftLen <= 1e-3f || (maxDistFromStart / shaftLen) < 0.90f) return null

        // Verify shaft straightness
        var maxShaftDev = 0f
        for (i in 1 until tipIdx) {
            val dev = perpendicularDistance(
                core[i].xPt,
                core[i].yPt,
                start.xPt,
                start.yPt,
                tip.xPt,
                tip.yPt
            )
            if (dev > maxShaftDev) maxShaftDev = dev
        }
        if (maxShaftDev / maxDistFromStart > 0.09f) return null

        // Verify tail after tip turns backward by ~110°–165° relative to shaft vector (barb).
        val sx = (tip.xPt - start.xPt) / maxDistFromStart
        val sy = (tip.yPt - start.yPt) / maxDistFromStart
        val tail = core.subList(tipIdx, core.size)
        var maxBackwardAngleDeg = 0f
        var barbSpan = 0f
        for (i in 1 until tail.size) {
            val vx = tail[i].xPt - tip.xPt
            val vy = tail[i].yPt - tip.yPt
            val vLen = hypot(vx, vy)
            if (vLen > barbSpan) barbSpan = vLen
            if (vLen >= 5f) {
                val dot = ((vx / vLen) * sx + (vy / vLen) * sy).coerceIn(-1f, 1f)
                val angleDeg = (acos(dot.toDouble()) * 180.0 / PI).toFloat()
                if (angleDeg > maxBackwardAngleDeg) maxBackwardAngleDeg = angleDeg
            }
        }
        if (barbSpan < 8f || barbSpan > maxDistFromStart * 0.65f) return null
        if (maxBackwardAngleDeg < 110f || maxBackwardAngleDeg > 170f) return null

        val confidence = (0.90f - (maxShaftDev / maxDistFromStart) * 2.0f).coerceIn(0f, 1f)
        val meta = ShapeMeta(
            shapeType = RecognizedShapeType.ARROW,
            controlPoints = listOf(DocPoint(start.xPt, start.yPt), DocPoint(tip.xPt, tip.yPt)),
            rotationRadians = atan2(tip.yPt - start.yPt, tip.xPt - start.xPt)
        )
        return meta to confidence
    }

    private fun rdpSimplify(points: List<DocPoint>, epsilon: Float): List<DocPoint> {
        if (points.size <= 2) return points
        var maxDist = 0f
        var splitIdx = 0
        val first = points.first()
        val last = points.last()

        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(
                points[i].xPt,
                points[i].yPt,
                first.xPt,
                first.yPt,
                last.xPt,
                last.yPt
            )
            if (d > maxDist) {
                maxDist = d
                splitIdx = i
            }
        }

        return if (maxDist > epsilon) {
            val left = rdpSimplify(points.subList(0, splitIdx + 1), epsilon)
            val right = rdpSimplify(points.subList(splitIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun mergeNearlyCollinearCorners(
        corners: List<DocPoint>,
        minCornerAngleDeg: Float
    ): List<DocPoint> {
        if (corners.size < 3) return corners
        val out = ArrayList<DocPoint>(corners.size)
        val n = corners.size
        for (i in 0 until n) {
            val prev = corners[(i - 1 + n) % n]
            val curr = corners[i]
            val next = corners[(i + 1) % n]
            val ax = prev.xPt - curr.xPt
            val ay = prev.yPt - curr.yPt
            val bx = next.xPt - curr.xPt
            val by = next.yPt - curr.yPt
            val lenA = hypot(ax, ay)
            val lenB = hypot(bx, by)
            if (lenA < 1e-3f || lenB < 1e-3f) continue
            val dot = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1f, 1f)
            val interiorDeg = (acos(dot.toDouble()) * 180.0 / PI).toFloat()
            // Discard shallow ~180° bends that aren't true polygon vertices.
            if (180f - interiorDeg >= minCornerAngleDeg) {
                out.add(curr)
            }
        }
        return out
    }

    private fun hasNearlyRightAngles(
        corners: List<DocPoint>,
        maxAngleDeviationDeg: Float
    ): Boolean {
        if (corners.size != 4) return false
        for (i in 0 until 4) {
            val prev = corners[(i + 3) % 4]
            val curr = corners[i]
            val next = corners[(i + 1) % 4]
            val ax = prev.xPt - curr.xPt
            val ay = prev.yPt - curr.yPt
            val bx = next.xPt - curr.xPt
            val by = next.yPt - curr.yPt
            val lenA = hypot(ax, ay)
            val lenB = hypot(bx, by)
            if (lenA < 1f || lenB < 1f) return false
            val dot = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1f, 1f)
            val angleDeg = (acos(dot.toDouble()) * 180.0 / PI).toFloat()
            if (abs(angleDeg - 90f) > maxAngleDeviationDeg) return false
        }
        return true
    }

    private fun maxEdgeFitErrorRatio(
        points: List<DocPoint>,
        polygon: List<DocPoint>,
        diag: Float
    ): Float {
        var totalErr = 0f
        for (p in points) {
            var bestEdgeDist = Float.POSITIVE_INFINITY
            for (i in 0 until polygon.size) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                val d = distanceToSegment(p.xPt, p.yPt, a.xPt, a.yPt, b.xPt, b.yPt)
                if (d < bestEdgeDist) bestEdgeDist = d
            }
            totalErr += bestEdgeDist
        }
        return (totalErr / points.size) / diag.coerceAtLeast(1f)
    }

    private fun perpendicularDistance(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        if (len <= 1e-6f) return hypot(px - ax, py - ay)
        return abs(dy * px - dx * py + bx * ay - by * ax) / len
    }

    private fun distanceToSegment(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val lenSq = vx * vx + vy * vy
        if (lenSq <= 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / lenSq).coerceIn(0f, 1f)
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }
}

