package hu.bdev.stylusnote.domain.model

import hu.bdev.stylusnote.core.code.CodeLanguage
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.ShapeMeta
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Z-ordered canvas layer for persisted ink strokes (Section 3 & 9.2).
 */
enum class StrokeLayerType(val code: Int) {
    HIGHLIGHTER(0),
    INK(1);

    companion object {
        fun fromCode(code: Int): StrokeLayerType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Serializable representation of a dry ink stroke stored inside `page.snote` snapshots and
 * `page.journal` append-only logs (Section 9.2).
 *
 * Stores both Jetpack Ink's compact `ink-storage` encoded [inkEncodedInputs] bytes (when produced
 * on device) and deterministic [samples] coordinates so round-trips remain exact on both Android
 * hardware and desktop JVM unit tests.
 */
data class PersistedStrokeRecord(
    val id: String,
    val layer: StrokeLayerType,
    val zOrder: Long,
    val brushConfig: BrushConfig,
    val canonicalColorArgb: Int,
    val sizePt: Float,
    val inkEncodedInputs: ByteArray? = null,
    val samples: List<StylusPointSample> = emptyList(),
    val bounds: DocRect,
    val shapeMeta: ShapeMeta? = null,
    val serializedBrushFamily: ByteArray? = null,
    val usedFallbackBrush: Boolean = false,
    val attachedObjectId: String? = null
) {
    fun withTransform(affine: AffineTransform2D): PersistedStrokeRecord {
        val transformedSamples = samples.map { pt ->
            val mapped = affine.transformPoint(pt.xPt, pt.yPt)
            pt.copy(xPt = mapped.xPt, yPt = mapped.yPt)
        }
        val scaledSize = (sizePt * affine.averageScaleFactor())
            .coerceIn(BrushConfig.MIN_BRUSH_SIZE_PT, BrushConfig.MAX_BRUSH_SIZE_PT)
        return copy(
            sizePt = scaledSize,
            brushConfig = brushConfig.copy(sizePt = scaledSize),
            inkEncodedInputs = null,
            samples = transformedSamples,
            bounds = affine.transformRect(bounds),
            shapeMeta = shapeMeta?.transform(affine)
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersistedStrokeRecord) return false
        if (id != other.id) return false
        if (layer != other.layer) return false
        if (zOrder != other.zOrder) return false
        if (brushConfig != other.brushConfig) return false
        if (canonicalColorArgb != other.canonicalColorArgb) return false
        if (sizePt != other.sizePt) return false
        if (!nullableByteArrayEquals(inkEncodedInputs, other.inkEncodedInputs)) return false
        if (samples != other.samples) return false
        if (bounds != other.bounds) return false
        if (shapeMeta != other.shapeMeta) return false
        if (!nullableByteArrayEquals(serializedBrushFamily, other.serializedBrushFamily)) return false
        if (usedFallbackBrush != other.usedFallbackBrush) return false
        if (attachedObjectId != other.attachedObjectId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + layer.hashCode()
        result = 31 * result + zOrder.hashCode()
        result = 31 * result + brushConfig.hashCode()
        result = 31 * result + canonicalColorArgb
        result = 31 * result + sizePt.hashCode()
        result = 31 * result + (inkEncodedInputs?.contentHashCode() ?: 0)
        result = 31 * result + samples.hashCode()
        result = 31 * result + bounds.hashCode()
        result = 31 * result + (shapeMeta?.hashCode() ?: 0)
        result = 31 * result + (serializedBrushFamily?.contentHashCode() ?: 0)
        result = 31 * result + usedFallbackBrush.hashCode()
        result = 31 * result + (attachedObjectId?.hashCode() ?: 0)
        return result
    }

    private fun nullableByteArrayEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.contentEquals(b)
    }
}

/**
 * Layer-5 embedded vector objects and PDF references persisted alongside strokes inside `page.snote`
 * and `page.journal` (Section 3 & 9.2).
 */
sealed interface PageObject {
    val id: String
    val zOrder: Long
    val bounds: DocRect

    fun transform(affine: AffineTransform2D): PageObject

    /** Returns a duplicate of this object translated/transformed with a fresh [newId] and [newZOrder]. */
    fun duplicateWith(newId: String, newZOrder: Long, affine: AffineTransform2D): PageObject

    /** Returns a recolored copy if this object supports canonical ink/stroke colors, or null otherwise. */
    fun withCanonicalColorOrNull(canonicalColorArgb: Int): PageObject? = null

    data class LatexObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val latexSource: String,
        val fontSizePt: Float = 18f,
        val canonicalColorArgb: Int = BrushConfig.DEFAULT_INK_COLOR_ARGB,
        val rotationRadians: Float = 0f,
        val sourceStrokeIds: List<String> = emptyList(),
        val originalStrokes: List<PersistedStrokeRecord> = emptyList()
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject {
            val scale = affine.averageScaleFactor()
            val newCenter = affine.transformPoint(bounds.centerXPt, bounds.centerYPt)
            val halfW = (bounds.widthPt * 0.5f * scale).coerceAtLeast(8f)
            val halfH = (bounds.heightPt * 0.5f * scale).coerceAtLeast(8f)
            val deltaRot = atan2(affine.shearY, affine.scaleX)
            return copy(
                bounds = DocRect(
                    leftPt = newCenter.xPt - halfW,
                    topPt = newCenter.yPt - halfH,
                    rightPt = newCenter.xPt + halfW,
                    bottomPt = newCenter.yPt + halfH
                ),
                fontSizePt = (fontSizePt * scale).coerceIn(8f, 120f),
                rotationRadians = rotationRadians + deltaRot,
                originalStrokes = originalStrokes.map { it.withTransform(affine) }
            )
        }

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as LatexObject).copy(
            id = newId,
            zOrder = newZOrder,
            sourceStrokeIds = emptyList()
        )

        override fun withCanonicalColorOrNull(canonicalColorArgb: Int): PageObject =
            copy(canonicalColorArgb = canonicalColorArgb)
    }

    data class FunctionPlotObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val expression: String,
        val domainMinX: Float = -10f,
        val domainMaxX: Float = 10f,
        val rangeMinY: Float = -10f,
        val rangeMaxY: Float = 10f,
        val canonicalColorArgb: Int = BrushConfig.DEFAULT_INK_COLOR_ARGB,
        val strokeWidthPt: Float = 1.8f,
        val showGrid: Boolean = true
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject = copy(
            bounds = affine.transformRect(bounds)
        )

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as FunctionPlotObject).copy(
            id = newId,
            zOrder = newZOrder
        )

        override fun withCanonicalColorOrNull(canonicalColorArgb: Int): PageObject =
            copy(canonicalColorArgb = canonicalColorArgb)
    }

    data class DerivationCardObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val title: String,
        val stepsMarkdownLatex: String,
        val relatedLatexObjectId: String? = null,
        val isCollapsed: Boolean = false
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject = copy(
            bounds = affine.transformRect(bounds)
        )

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as DerivationCardObject).copy(
            id = newId,
            zOrder = newZOrder
        )
    }

    data class PdfPageRefObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val pdfAssetRelativePath: String,
        val pageIndexZeroBased: Int,
        val invertInOledMode: Boolean = true
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject = copy(
            bounds = affine.transformRect(bounds)
        )

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as PdfPageRefObject).copy(
            id = newId,
            zOrder = newZOrder
        )
    }

    /**
     * AI-formatted handwritten text object rendered in human-like cursive typography on the canvas
     * and exported as italic serif/cursive text in vector PDFs.
     */
    data class FormattedTextObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val text: String,
        val fontSizePt: Float = 18f,
        val canonicalColorArgb: Int = BrushConfig.DEFAULT_INK_COLOR_ARGB,
        val rotationRadians: Float = 0f,
        val sourceStrokeIds: List<String> = emptyList(),
        val originalStrokes: List<PersistedStrokeRecord> = emptyList()
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject {
            val scale = affine.averageScaleFactor()
            val newCenter = affine.transformPoint(bounds.centerXPt, bounds.centerYPt)
            val halfW = (bounds.widthPt * 0.5f * scale).coerceAtLeast(8f)
            val halfH = (bounds.heightPt * 0.5f * scale).coerceAtLeast(8f)
            val deltaRot = atan2(affine.shearY, affine.scaleX)
            return copy(
                bounds = DocRect(
                    leftPt = newCenter.xPt - halfW,
                    topPt = newCenter.yPt - halfH,
                    rightPt = newCenter.xPt + halfW,
                    bottomPt = newCenter.yPt + halfH
                ),
                fontSizePt = (fontSizePt * scale).coerceIn(10f, 96f),
                rotationRadians = rotationRadians + deltaRot,
                originalStrokes = originalStrokes.map { it.withTransform(affine) }
            )
        }

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as FormattedTextObject).copy(
            id = newId,
            zOrder = newZOrder,
            sourceStrokeIds = emptyList()
        )

        override fun withCanonicalColorOrNull(canonicalColorArgb: Int): PageObject =
            copy(canonicalColorArgb = canonicalColorArgb)
    }

    /**
     * Movable, resizable, freely rotatable vector geometric shape inserted from the toolbar Shape picker.
     */
    data class GeometricShapeObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val shapeType: GeometricShapeType,
        val strokeWidthPt: Float = 1.6f,
        val canonicalStrokeColorArgb: Int = BrushConfig.DEFAULT_INK_COLOR_ARGB,
        val hasFill: Boolean = false,
        val canonicalFillColorArgb: Int = 0x221E293B,
        val rotationRadians: Float = 0f
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject {
            val scale = affine.averageScaleFactor()
            val newCenter = affine.transformPoint(bounds.centerXPt, bounds.centerYPt)
            val halfW = (bounds.widthPt * 0.5f * scale).coerceAtLeast(8f)
            val halfH = (bounds.heightPt * 0.5f * scale).coerceAtLeast(8f)
            val deltaRot = atan2(affine.shearY, affine.scaleX)
            return copy(
                bounds = DocRect(
                    leftPt = newCenter.xPt - halfW,
                    topPt = newCenter.yPt - halfH,
                    rightPt = newCenter.xPt + halfW,
                    bottomPt = newCenter.yPt + halfH
                ),
                rotationRadians = rotationRadians + deltaRot
            )
        }

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as GeometricShapeObject).copy(
            id = newId,
            zOrder = newZOrder
        )

        override fun withCanonicalColorOrNull(canonicalColorArgb: Int): PageObject = copy(
            canonicalStrokeColorArgb = canonicalColorArgb,
            canonicalFillColorArgb = (canonicalColorArgb and 0x00FFFFFF) or 0x28000000
        )
    }

    /**
     * Movable, resizable, freely rotatable raster image inserted onto the paper from gallery/files.
     */
    data class ImageObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val imageAssetRelativePath: String,
        val rotationRadians: Float = 0f
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject {
            val scale = affine.averageScaleFactor()
            val newCenter = affine.transformPoint(bounds.centerXPt, bounds.centerYPt)
            val halfW = (bounds.widthPt * 0.5f * scale).coerceAtLeast(12f)
            val halfH = (bounds.heightPt * 0.5f * scale).coerceAtLeast(12f)
            val deltaRot = atan2(affine.shearY, affine.scaleX)
            return copy(
                bounds = DocRect(
                    leftPt = newCenter.xPt - halfW,
                    topPt = newCenter.yPt - halfH,
                    rightPt = newCenter.xPt + halfW,
                    bottomPt = newCenter.yPt + halfH
                ),
                rotationRadians = rotationRadians + deltaRot
            )
        }

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as ImageObject).copy(
            id = newId,
            zOrder = newZOrder
        )
    }

    /**
     * Syntax-highlighted monospace code zone rendered under ink (Layer 2b) so annotations and
     * arrows drawn onto or around the card sit cleanly on top and follow block transforms.
     */
    data class CodeBlockObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val language: CodeLanguage = CodeLanguage.PYTHON,
        val codeText: String = "",
        val fontSizePt: Float = 11f,
        val title: String = "",
        val showLineNumbers: Boolean = true,
        val rotationRadians: Float = 0f
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject {
            val scale = affine.averageScaleFactor()
            val newCenter = affine.transformPoint(bounds.centerXPt, bounds.centerYPt)
            val halfW = (bounds.widthPt * 0.5f * scale).coerceAtLeast(48f)
            val halfH = (bounds.heightPt * 0.5f * scale).coerceAtLeast(28f)
            val deltaRot = atan2(affine.shearY, affine.scaleX)
            return copy(
                bounds = DocRect(
                    leftPt = newCenter.xPt - halfW,
                    topPt = newCenter.yPt - halfH,
                    rightPt = newCenter.xPt + halfW,
                    bottomPt = newCenter.yPt + halfH
                ),
                fontSizePt = (fontSizePt * scale).coerceIn(8f, 22f),
                rotationRadians = rotationRadians + deltaRot
            )
        }

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as CodeBlockObject).copy(
            id = newId,
            zOrder = newZOrder
        )
    }

    /**
     * Layer-5b Cloze / Masking tape placed over definitions or labels; tapping reveals temporarily
     * during study and re-covers on next tap without disturbing underlying strokes.
     */
    data class ClozeTapeObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val canonicalColorArgb: Int = BrushConfig.DEFAULT_CLOZE_TAPE_COLOR_ARGB,
        val patternStyle: ClozeTapePattern = ClozeTapePattern.DIAGONAL_STRIPES,
        val rotationRadians: Float = 0f,
        val label: String = ""
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject {
            val scale = affine.averageScaleFactor()
            val newCenter = affine.transformPoint(bounds.centerXPt, bounds.centerYPt)
            val halfW = (bounds.widthPt * 0.5f * scale).coerceAtLeast(12f)
            val halfH = (bounds.heightPt * 0.5f * scale).coerceAtLeast(10f)
            val deltaRot = atan2(affine.shearY, affine.scaleX)
            return copy(
                bounds = DocRect(
                    leftPt = newCenter.xPt - halfW,
                    topPt = newCenter.yPt - halfH,
                    rightPt = newCenter.xPt + halfW,
                    bottomPt = newCenter.yPt + halfH
                ),
                rotationRadians = rotationRadians + deltaRot
            )
        }

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as ClozeTapeObject).copy(
            id = newId,
            zOrder = newZOrder
        )

        override fun withCanonicalColorOrNull(canonicalColorArgb: Int): PageObject =
            copy(canonicalColorArgb = canonicalColorArgb)
    }

    /**
     * Layer-2b static table grid rendered underneath highlighters & ink. Locked immediately after
     * pre-placement drafting so stylus writing, highlighting, and erasing operate inside cells
     * without accidentally selecting, moving, or editing the table structure.
     */
    data class TableObject(
        override val id: String,
        override val zOrder: Long,
        override val bounds: DocRect,
        val rows: Int,
        val columns: Int,
        val borderStrokeWidthPt: Float = DEFAULT_BORDER_WIDTH_PT,
        val canonicalBorderColorArgb: Int = DEFAULT_BORDER_COLOR_ARGB,
        val hasHeaderRow: Boolean = true
    ) : PageObject {
        init {
            require(rows in MIN_ROWS..MAX_ROWS) { "rows must be $MIN_ROWS..$MAX_ROWS" }
            require(columns in MIN_COLS..MAX_COLS) { "columns must be $MIN_COLS..$MAX_COLS" }
        }

        override fun transform(affine: AffineTransform2D): PageObject {
            val scale = affine.averageScaleFactor()
            val newCenter = affine.transformPoint(bounds.centerXPt, bounds.centerYPt)
            val halfW = (bounds.widthPt * 0.5f * scale).coerceAtLeast(columns * 18f)
            val halfH = (bounds.heightPt * 0.5f * scale).coerceAtLeast(rows * 14f)
            return copy(
                bounds = DocRect(
                    leftPt = newCenter.xPt - halfW,
                    topPt = newCenter.yPt - halfH,
                    rightPt = newCenter.xPt + halfW,
                    bottomPt = newCenter.yPt + halfH
                )
            )
        }

        override fun duplicateWith(
            newId: String,
            newZOrder: Long,
            affine: AffineTransform2D
        ): PageObject = (transform(affine) as TableObject).copy(
            id = newId,
            zOrder = newZOrder
        )

        override fun withCanonicalColorOrNull(canonicalColorArgb: Int): PageObject =
            copy(canonicalBorderColorArgb = canonicalColorArgb)

        companion object {
            const val MIN_ROWS: Int = 1
            const val MAX_ROWS: Int = 20
            const val MIN_COLS: Int = 1
            const val MAX_COLS: Int = 12
            const val DEFAULT_BORDER_WIDTH_PT: Float = 1.1f
            const val DEFAULT_BORDER_COLOR_ARGB: Int = 0xFF334155.toInt()
        }
    }
}

enum class ClozeTapePattern(val displayNameHu: String) {
    SOLID("Egyszínű"),
    DIAGONAL_STRIPES("Csíkozott"),
    DOTS("Pontozott")
}

/**
 * Pure geometry helpers for attaching handwritten ink strokes to under-ink [PageObject]s
 * ([PageObject.ImageObject], [PageObject.CodeBlockObject], and [PageObject.TableObject])
 * and classifying all Layer-2b under-ink rendered objects.
 */
object ObjectAttachmentGeometry {
    const val DEFAULT_ATTACHMENT_MARGIN_PT: Float = 2f

    fun isUnderInkAttachableObject(obj: PageObject): Boolean =
        obj is PageObject.ImageObject ||
            obj is PageObject.CodeBlockObject ||
            obj is PageObject.TableObject

    fun isUnderInkRenderedObject(obj: PageObject): Boolean =
        isUnderInkAttachableObject(obj)

    fun rotationRadiansOf(obj: PageObject): Float = when (obj) {
        is PageObject.ImageObject -> obj.rotationRadians
        is PageObject.CodeBlockObject -> obj.rotationRadians
        is PageObject.ClozeTapeObject -> obj.rotationRadians
        is PageObject.GeometricShapeObject -> obj.rotationRadians
        is PageObject.FormattedTextObject -> obj.rotationRadians
        is PageObject.LatexObject -> obj.rotationRadians
        else -> 0f
    }

    fun containsPointInRotatedRect(
        xPt: Float,
        yPt: Float,
        bounds: DocRect,
        rotationRadians: Float,
        marginPt: Float = DEFAULT_ATTACHMENT_MARGIN_PT
    ): Boolean {
        val cx = bounds.centerXPt
        val cy = bounds.centerYPt
        val hw = bounds.widthPt * 0.5f + marginPt
        val hh = bounds.heightPt * 0.5f + marginPt
        val dx = xPt - cx
        val dy = yPt - cy
        if (abs(rotationRadians) < 1e-5f) {
            return abs(dx) <= hw && abs(dy) <= hh
        }
        val c = cos(-rotationRadians)
        val s = sin(-rotationRadians)
        val localX = dx * c - dy * s
        val localY = dx * s + dy * c
        return abs(localX) <= hw && abs(localY) <= hh
    }

    fun isStrokeAttachedToObject(
        points: List<DocPoint>,
        target: PageObject,
        marginPt: Float = DEFAULT_ATTACHMENT_MARGIN_PT
    ): Boolean {
        if (points.isEmpty() || !isUnderInkAttachableObject(target)) return false
        val rot = rotationRadiansOf(target)
        var insideCount = 0
        for (i in 0 until points.size) {
            val pt = points[i]
            if (containsPointInRotatedRect(pt.xPt, pt.yPt, target.bounds, rot, marginPt)) {
                insideCount++
            }
        }
        return insideCount * 2 >= points.size
    }

    fun isStrokeAttachedToImage(
        points: List<DocPoint>,
        image: PageObject.ImageObject,
        marginPt: Float = DEFAULT_ATTACHMENT_MARGIN_PT
    ): Boolean = isStrokeAttachedToObject(points, image, marginPt)

    fun findTopmostUnderInkObjectForStroke(
        points: List<DocPoint>,
        objects: Collection<PageObject>,
        marginPt: Float = DEFAULT_ATTACHMENT_MARGIN_PT
    ): PageObject? {
        if (points.isEmpty() || objects.isEmpty()) return null
        var best: PageObject? = null
        for (obj in objects) {
            if (!isUnderInkAttachableObject(obj)) continue
            if (best != null && obj.zOrder <= best.zOrder) continue
            if (isStrokeAttachedToObject(points, obj, marginPt)) {
                best = obj
            }
        }
        return best
    }

    fun findTopmostImageForStroke(
        points: List<DocPoint>,
        objects: Collection<PageObject>,
        marginPt: Float = DEFAULT_ATTACHMENT_MARGIN_PT
    ): PageObject.ImageObject? =
        findTopmostUnderInkObjectForStroke(points, objects.filterIsInstance<PageObject.ImageObject>(), marginPt)
            as? PageObject.ImageObject

    fun computeOrientedBoxAffineTransform(
        beforeBounds: DocRect,
        beforeRotationRad: Float,
        afterBounds: DocRect,
        afterRotationRad: Float
    ): AffineTransform2D {
        val sx = (afterBounds.widthPt / beforeBounds.widthPt.coerceAtLeast(1e-3f)).coerceAtLeast(0.05f)
        val sy = (afterBounds.heightPt / beforeBounds.heightPt.coerceAtLeast(1e-3f)).coerceAtLeast(0.05f)
        return AffineTransform2D.translation(-beforeBounds.centerXPt, -beforeBounds.centerYPt)
            .then(AffineTransform2D.rotationAround(-beforeRotationRad, 0f, 0f))
            .then(AffineTransform2D(scaleX = sx, scaleY = sy))
            .then(AffineTransform2D.rotationAround(afterRotationRad, 0f, 0f))
            .then(AffineTransform2D.translation(afterBounds.centerXPt, afterBounds.centerYPt))
    }

    fun computeObjectInteractionAffine(
        beforeObject: PageObject,
        afterObject: PageObject
    ): AffineTransform2D = computeOrientedBoxAffineTransform(
        beforeBounds = beforeObject.bounds,
        beforeRotationRad = rotationRadiansOf(beforeObject),
        afterBounds = afterObject.bounds,
        afterRotationRad = rotationRadiansOf(afterObject)
    )
}

/**
 * Vector shape catalog ordered with simple 2D shapes first (Page 0) and 3D solids second (Page 1).
 */
enum class GeometricShapeType(
    val displayNameHu: String,
    val is3D: Boolean = false,
    val catalogPage: Int = 0
) {
    // Page 0: Simple 2D shapes, lines, arrows, coordinate axes & basic polygons
    LINE("Egyenes szakasz", catalogPage = 0),
    ARROW("Nyíl", catalogPage = 0),
    DOUBLE_ARROW("Kétirányú nyíl", catalogPage = 0),
    AXES_2D("Koordináta-rendszer", catalogPage = 0),
    RECTANGLE("Téglalap", catalogPage = 0),
    ROUNDED_RECTANGLE("Lekerekített téglalap", catalogPage = 0),
    CIRCLE("Kör", catalogPage = 0),
    ELLIPSE("Ellipszis", catalogPage = 0),
    TRIANGLE("Háromszög", catalogPage = 0),
    RIGHT_TRIANGLE("Derékszögű háromszög", catalogPage = 0),
    DIAMOND("Rombusz", catalogPage = 0),
    PARALLELOGRAM("Paralelogramma", catalogPage = 0),
    TRAPEZOID("Trapéz", catalogPage = 0),
    PENTAGON("Ötszög", catalogPage = 0),
    HEXAGON("Hatszög", catalogPage = 0),
    OCTAGON("Nyolcszög", catalogPage = 0),
    STAR_FIVE("Csillag", catalogPage = 0),

    // Page 1: 3D geometric solids (with dashed hidden back edges)
    CUBE("Kocka", is3D = true, catalogPage = 1),
    RECTANGULAR_PRISM("Téglatest", is3D = true, catalogPage = 1),
    CYLINDER("Henger", is3D = true, catalogPage = 1),
    HOLLOW_CYLINDER("Üreges henger", is3D = true, catalogPage = 1),
    CONE("Kúp", is3D = true, catalogPage = 1),
    TETRAHEDRON("Háromoldalú gúla", is3D = true, catalogPage = 1),
    SQUARE_PYRAMID("Négyoldalú gúla", is3D = true, catalogPage = 1),
    OBLIQUE_PYRAMID("Ferde gúla", is3D = true, catalogPage = 1),
    ROOF_TRIANGULAR_PRISM("Fekvő hasáb", is3D = true, catalogPage = 1),
    UPRIGHT_TRIANGULAR_PRISM("Háromszög hasáb", is3D = true, catalogPage = 1),
    PENTAGONAL_PRISM("Ötszögű hasáb", is3D = true, catalogPage = 1),
    HEXAGONAL_PRISM("Hatszögű hasáb", is3D = true, catalogPage = 1),
    FRUSTUM_PYRAMID("Csonkagúla", is3D = true, catalogPage = 1),
    FRUSTUM_CONE("Csonkakúp", is3D = true, catalogPage = 1),
    HEMISPHERE("Félgömb", is3D = true, catalogPage = 1);

    companion object {
        fun forCatalogPage(page: Int): List<GeometricShapeType> = entries.filter { it.catalogPage == page }
    }
}

