package hu.bdev.stylusnote.core.ai

import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.SpatialIndex
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Timed point passed to ML Kit Digital Ink Recognition in PDF points and milliseconds. */
data class OcrTimedPoint(
    val xPt: Float,
    val yPt: Float,
    val timestampMs: Long
)

/** Single ordered stroke belonging to one segmented OCR text line. */
data class OcrStrokePoints(
    val strokeId: String,
    val zOrder: Long,
    val bounds: DocRect,
    val points: List<OcrTimedPoint>
)

/**
 * One segmented line of handwritten text ready to be recognized sequentially by ML Kit Digital Ink
 * with `WritingArea` dimensions and `RecognitionContext` pre-context (Section 7).
 */
data class SegmentedOcrLine(
    val lineIndex: Int,
    val bounds: DocRect,
    val writingAreaWidthPt: Float,
    val writingAreaHeightPt: Float,
    val strokes: List<OcrStrokePoints>
) {
    fun toMlKitInk(): Ink {
        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val strokeBuilder = Ink.Stroke.builder()
            for (pt in stroke.points) {
                strokeBuilder.addPoint(Ink.Point.create(pt.xPt, pt.yPt, pt.timestampMs))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }

    fun buildRecognitionContext(previousLineText: String?): RecognitionContext {
        val preContext = previousLineText
            ?.trim()
            ?.takeLast(MAX_PRE_CONTEXT_CHARS)
            .orEmpty()
        return RecognitionContext.builder()
            .setPreContext(preContext)
            .setWritingArea(WritingArea(writingAreaWidthPt, writingAreaHeightPt))
            .build()
    }

    companion object {
        const val MAX_PRE_CONTEXT_CHARS: Int = 20
    }
}

/**
 * SpatialIndex-backed handwriting line segmenter (Section 7):
 * - Excludes highlighters, snapped geometric shapes, oversize diagrams, and Layer-5 objects.
 * - Separates small Hungarian diacritic/dot strokes (`'`, `"`, `¨`, `.`) and attaches them to the
 *   immediately underlying text line.
 * - Clusters strokes by vertical overlap & center distance via [SpatialIndex], orders lines top-to-bottom,
 *   orders strokes in writing order (`zOrder` / sample timestamps), and ensures strictly non-decreasing
 *   point timestamps for ML Kit Digital Ink.
 */
class OcrLineSegmenter(
    private val maxSingleStrokeHeightPt: Float = DEFAULT_MAX_SINGLE_STROKE_HEIGHT_PT,
    private val maxDiagramWidthPt: Float = DEFAULT_MAX_DIAGRAM_WIDTH_PT,
    private val maxDiagramHeightPt: Float = DEFAULT_MAX_DIAGRAM_HEIGHT_PT,
    private val maxStrokeAreaPt2: Float = DEFAULT_MAX_STROKE_AREA_PT2
) {

    fun isCandidateHandwritingStroke(
        record: PersistedStrokeRecord,
        nonTextObjectBounds: List<DocRect> = emptyList()
    ): Boolean {
        if (record.samples.isEmpty()) return false
        if (record.layer != StrokeLayerType.INK) return false

        when (record.brushConfig.toolType) {
            BrushToolType.HIGHLIGHTER,
            BrushToolType.VECTOR_ERASER,
            BrushToolType.PRECISION_ERASER,
            BrushToolType.LASSO,
            BrushToolType.CLOZE_TAPE -> return false
            BrushToolType.FOUNTAIN_PEN,
            BrushToolType.BALLPOINT_PEN,
            BrushToolType.PENCIL -> Unit
        }

        if (record.shapeMeta != null) return false

        val bounds = record.bounds
        val width = bounds.widthPt.coerceAtLeast(0f)
        val height = bounds.heightPt.coerceAtLeast(0f)
        val area = width * height

        if (height > maxSingleStrokeHeightPt) return false
        if (width > maxDiagramWidthPt && height > maxDiagramHeightPt) return false
        if (area > maxStrokeAreaPt2) return false

        val cx = rectCenterX(bounds)
        val cy = rectCenterY(bounds)
        for (objBounds in nonTextObjectBounds) {
            if (objBounds.contains(cx, cy)) return false
        }
        return true
    }

    fun segmentIntoLines(
        strokes: List<PersistedStrokeRecord>,
        pageObjects: List<PageObject> = emptyList()
    ): List<SegmentedOcrLine> {
        if (strokes.isEmpty()) return emptyList()

        // Ignore full-page PDF backdrop references when filtering handwriting on annotated slides.
        val excludedObjectBounds = pageObjects
            .filter { it !is PageObject.PdfPageRefObject }
            .map { it.bounds.inflate(OBJECT_EXCLUSION_MARGIN_PT) }

        val candidates = strokes.filter { record ->
            isCandidateHandwritingStroke(record, excludedObjectBounds)
        }
        if (candidates.isEmpty()) return emptyList()

        val spatialIndex = SpatialIndex<PersistedStrokeRecord>()
        for (stroke in candidates) {
            spatialIndex.insert(stroke.id, stroke.bounds, stroke)
        }

        val diacriticCandidates = ArrayList<PersistedStrokeRecord>()
        val primaryStrokes = ArrayList<PersistedStrokeRecord>()
        for (stroke in candidates) {
            if (isSmallDiacriticCandidate(stroke.bounds)) {
                diacriticCandidates.add(stroke)
            } else {
                primaryStrokes.add(stroke)
            }
        }

        val baseStrokes = if (primaryStrokes.isNotEmpty()) primaryStrokes else candidates
        val remainingDiacritics = if (primaryStrokes.isNotEmpty()) diacriticCandidates else emptyList()

        val referenceLineHeightPt = computeReferenceLineHeightPt(baseStrokes)
        val clusters = clusterPrimaryStrokes(baseStrokes, spatialIndex, referenceLineHeightPt)

        attachDiacriticsToClusters(
            diacritics = remainingDiacritics,
            clusters = clusters,
            referenceLineHeightPt = referenceLineHeightPt
        )

        clusters.sortBy { rectCenterY(it.bounds) }

        return clusters.mapIndexed { lineIdx, cluster ->
            val orderedStrokes = cluster.members
                .sortedWith(
                    compareBy<PersistedStrokeRecord> { it.zOrder }
                        .thenBy { it.samples.firstOrNull()?.elapsedTimeMillis ?: Long.MAX_VALUE }
                        .thenBy { it.bounds.leftPt }
                )
                .map(::toOcrStrokePoints)

            val lineBounds = cluster.bounds
            val writingWidth = max(lineBounds.widthPt + 24f, MIN_WRITING_AREA_WIDTH_PT)
            val writingHeight = max(lineBounds.heightPt + 12f, referenceLineHeightPt * 1.4f)

            SegmentedOcrLine(
                lineIndex = lineIdx,
                bounds = lineBounds,
                writingAreaWidthPt = writingWidth,
                writingAreaHeightPt = writingHeight,
                strokes = orderedStrokes
            )
        }
    }

    /**
     * Packages an explicit subset of strokes (e.g., Lasso math selection or left-of-equals expression
     * strokes) into a single ordered [SegmentedOcrLine] for on-device ML Kit recognition without
     * splitting fractions/exponents or rejecting large diagrams.
     */
    fun buildSingleLineForRecognition(strokes: List<PersistedStrokeRecord>): SegmentedOcrLine? {
        val inkStrokes = strokes.filter { it.layer == StrokeLayerType.INK && it.samples.isNotEmpty() }
        if (inkStrokes.isEmpty()) return null
        val ordered = inkStrokes.sortedWith(
            compareBy<PersistedStrokeRecord> { it.zOrder }
                .thenBy { it.samples.firstOrNull()?.elapsedTimeMillis ?: Long.MAX_VALUE }
                .thenBy { it.bounds.leftPt }
        )
        var minL = Float.POSITIVE_INFINITY
        var minT = Float.POSITIVE_INFINITY
        var maxR = Float.NEGATIVE_INFINITY
        var maxB = Float.NEGATIVE_INFINITY
        for (i in 0 until ordered.size) {
            val b = ordered[i].bounds
            minL = min(minL, b.leftPt)
            minT = min(minT, b.topPt)
            maxR = max(maxR, b.rightPt)
            maxB = max(maxB, b.bottomPt)
        }
        val bounds = DocRect(minL, minT, maxR, maxB)
        return SegmentedOcrLine(
            lineIndex = 0,
            bounds = bounds,
            writingAreaWidthPt = max(bounds.widthPt + 24f, MIN_WRITING_AREA_WIDTH_PT),
            writingAreaHeightPt = max(bounds.heightPt + 16f, 24f),
            strokes = ordered.map(::toOcrStrokePoints)
        )
    }

    private fun clusterPrimaryStrokes(
        primaryStrokes: List<PersistedStrokeRecord>,
        spatialIndex: SpatialIndex<PersistedStrokeRecord>,
        referenceLineHeightPt: Float
    ): MutableList<LineCluster> {
        val sortedByY = primaryStrokes.sortedWith(
            compareBy<PersistedStrokeRecord> { rectCenterY(it.bounds) }
                .thenBy { it.bounds.leftPt }
        )

        val clusterByStrokeId = HashMap<String, LineCluster>()
        val clusters = ArrayList<LineCluster>()
        val verticalTolerancePt = referenceLineHeightPt * 0.65f

        for (stroke in sortedByY) {
            val searchBand = DocRect(
                leftPt = Float.NEGATIVE_INFINITY,
                topPt = stroke.bounds.topPt - verticalTolerancePt,
                rightPt = Float.POSITIVE_INFINITY,
                bottomPt = stroke.bounds.bottomPt + verticalTolerancePt
            )
            // Broad spatial query constrained vertically via the R-tree bounds.
            val nearbyClusters = LinkedHashSet<LineCluster>()
            val queryBox = DocRect(
                leftPt = -1_000_000f,
                topPt = searchBand.topPt,
                rightPt = 1_000_000f,
                bottomPt = searchBand.bottomPt
            )
            for (entry in spatialIndex.queryRect(queryBox)) {
                clusterByStrokeId[entry.id]?.let { nearbyClusters.add(it) }
            }

            var bestCluster: LineCluster? = null
            var bestScore = Float.POSITIVE_INFINITY
            val strokeCy = rectCenterY(stroke.bounds)

            for (cluster in nearbyClusters) {
                val clusterCy = rectCenterY(cluster.bounds)
                val yDelta = abs(strokeCy - clusterCy)
                val overlap = verticalOverlapRatio(stroke.bounds, cluster.bounds)
                val allowedDelta = 0.6f * max(cluster.bounds.heightPt, referenceLineHeightPt)
                if (overlap >= 0.35f || yDelta <= allowedDelta) {
                    val score = yDelta - overlap * referenceLineHeightPt * 0.5f
                    if (score < bestScore) {
                        bestScore = score
                        bestCluster = cluster
                    }
                }
            }

            if (bestCluster == null) {
                val created = LineCluster(bounds = stroke.bounds, members = mutableListOf(stroke))
                clusters.add(created)
                clusterByStrokeId[stroke.id] = created
            } else {
                bestCluster.add(stroke)
                clusterByStrokeId[stroke.id] = bestCluster
            }
        }
        return clusters
    }

    private fun attachDiacriticsToClusters(
        diacritics: List<PersistedStrokeRecord>,
        clusters: MutableList<LineCluster>,
        referenceLineHeightPt: Float
    ) {
        if (diacritics.isEmpty()) return

        for (diacritic in diacritics) {
            val dx = rectCenterX(diacritic.bounds)
            val dy = rectCenterY(diacritic.bounds)

            var bestHorizontalCluster: LineCluster? = null
            var bestDistance = Float.POSITIVE_INFINITY

            for (cluster in clusters) {
                val cb = cluster.bounds
                val inHorizontalSpan = dx in (cb.leftPt - 12f)..(cb.rightPt + 12f)
                val slightlyAboveOrInside = dy in (cb.topPt - referenceLineHeightPt * 0.85f)..(cb.bottomPt + 6f)
                if (inHorizontalSpan && slightlyAboveOrInside) {
                    val dist = abs(rectCenterY(cb) - dy)
                    if (dist < bestDistance) {
                        bestDistance = dist
                        bestHorizontalCluster = cluster
                    }
                }
            }

            if (bestHorizontalCluster != null) {
                bestHorizontalCluster.add(diacritic)
                continue
            }

            // Fallback: attach to vertically closest cluster or start a standalone cluster.
            var fallbackCluster: LineCluster? = null
            var fallbackDelta = Float.POSITIVE_INFINITY
            for (cluster in clusters) {
                val delta = abs(rectCenterY(cluster.bounds) - dy)
                if (delta <= referenceLineHeightPt * 0.7f && delta < fallbackDelta) {
                    fallbackDelta = delta
                    fallbackCluster = cluster
                }
            }

            if (fallbackCluster != null) {
                fallbackCluster.add(diacritic)
            } else {
                clusters.add(LineCluster(bounds = diacritic.bounds, members = mutableListOf(diacritic)))
            }
        }
    }

    private fun computeReferenceLineHeightPt(strokes: List<PersistedStrokeRecord>): Float {
        if (strokes.isEmpty()) return DEFAULT_REFERENCE_LINE_HEIGHT_PT
        val heights = strokes.map { it.bounds.heightPt.coerceAtLeast(4f) }.sorted()
        val median = heights[heights.size / 2]
        return median.coerceIn(12f, 42f)
    }

    private fun isSmallDiacriticCandidate(bounds: DocRect): Boolean =
        bounds.heightPt <= 6.5f && bounds.widthPt <= 18f

    private fun toOcrStrokePoints(
        record: PersistedStrokeRecord,
        timeOffsetMs: Long = 0L
    ): OcrStrokePoints {
        val normalized = ArrayList<OcrTimedPoint>(record.samples.size)
        var lastT = Long.MIN_VALUE
        for (sample in record.samples) {
            val rawT = timeOffsetMs + sample.elapsedTimeMillis.coerceAtLeast(0L)
            val t = if (lastT == Long.MIN_VALUE || rawT > lastT) {
                rawT
            } else {
                lastT + 1L
            }
            lastT = t
            normalized.add(
                OcrTimedPoint(
                    xPt = sample.xPt,
                    yPt = sample.yPt,
                    timestampMs = t
                )
            )
        }
        return OcrStrokePoints(
            strokeId = record.id,
            zOrder = record.zOrder,
            bounds = record.bounds,
            points = normalized
        )
    }

    /**
     * Builds one ML Kit Digital Ink sequence specifically for Lasso-selected handwritten math
     * (`∑ LaTeX`), keeping snapped/freehand line strokes (e.g., `-`, `=`, fraction bars) and
     * assigning strictly increasing per-stroke timestamps so Digital Ink recognizes multi-stroke
     * expressions accurately even when individual strokes share `t = 0 ms` start offsets.
     */
    fun buildMathLineForRecognition(strokes: List<PersistedStrokeRecord>): SegmentedOcrLine? {
        val valid = strokes.filter { record ->
            if (record.samples.isEmpty()) return@filter false
            if (record.layer != StrokeLayerType.INK) return@filter false
            when (record.brushConfig.toolType) {
                BrushToolType.FOUNTAIN_PEN,
                BrushToolType.BALLPOINT_PEN,
                BrushToolType.PENCIL -> true
                else -> false
            }
        }
        if (valid.isEmpty()) return null

        val ordered = valid.sortedWith(
            compareBy<PersistedStrokeRecord> { it.zOrder }
                .thenBy { it.bounds.leftPt }
                .thenBy { it.bounds.topPt }
        )

        var minL = Float.POSITIVE_INFINITY
        var minT = Float.POSITIVE_INFINITY
        var maxR = Float.NEGATIVE_INFINITY
        var maxB = Float.NEGATIVE_INFINITY
        val ocrStrokes = ArrayList<OcrStrokePoints>(ordered.size)
        var cursorTimeMs = 0L
        for (rec in ordered) {
            val pts = toOcrStrokePoints(rec, timeOffsetMs = cursorTimeMs)
            if (pts.points.isEmpty()) continue
            ocrStrokes.add(pts)
            cursorTimeMs = pts.points.last().timestampMs + 40L
            minL = min(minL, rec.bounds.leftPt)
            minT = min(minT, rec.bounds.topPt)
            maxR = max(maxR, rec.bounds.rightPt)
            maxB = max(maxB, rec.bounds.bottomPt)
        }
        if (ocrStrokes.isEmpty()) return null

        val bounds = DocRect(minL, minT, max(maxR, minL + 8f), max(maxB, minT + 8f))
        return SegmentedOcrLine(
            lineIndex = 0,
            bounds = bounds,
            writingAreaWidthPt = max(bounds.widthPt + 24f, MIN_WRITING_AREA_WIDTH_PT),
            writingAreaHeightPt = max(bounds.heightPt + 16f, 36f),
            strokes = ocrStrokes
        )
    }

    private fun verticalOverlapRatio(a: DocRect, b: DocRect): Float {
        val top = max(a.topPt, b.topPt)
        val bottom = min(a.bottomPt, b.bottomPt)
        val intersection = (bottom - top).coerceAtLeast(0f)
        val minHeight = min(a.heightPt, b.heightPt).coerceAtLeast(1f)
        return intersection / minHeight
    }

    private fun rectCenterX(rect: DocRect): Float = (rect.leftPt + rect.rightPt) * 0.5f
    private fun rectCenterY(rect: DocRect): Float = (rect.topPt + rect.bottomPt) * 0.5f

    private class LineCluster(
        var bounds: DocRect,
        val members: MutableList<PersistedStrokeRecord>
    ) {
        fun add(stroke: PersistedStrokeRecord) {
            members.add(stroke)
            bounds = DocRect(
                leftPt = min(bounds.leftPt, stroke.bounds.leftPt),
                topPt = min(bounds.topPt, stroke.bounds.topPt),
                rightPt = max(bounds.rightPt, stroke.bounds.rightPt),
                bottomPt = max(bounds.bottomPt, stroke.bounds.bottomPt)
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_SINGLE_STROKE_HEIGHT_PT: Float = 90f
        const val DEFAULT_MAX_DIAGRAM_WIDTH_PT: Float = 220f
        const val DEFAULT_MAX_DIAGRAM_HEIGHT_PT: Float = 65f
        const val DEFAULT_MAX_STROKE_AREA_PT2: Float = 12_000f
        private const val DEFAULT_REFERENCE_LINE_HEIGHT_PT: Float = 20f
        private const val MIN_WRITING_AREA_WIDTH_PT: Float = 120f
        private const val OBJECT_EXCLUSION_MARGIN_PT: Float = 2f
    }
}

