package hu.bdev.stylusnote.domain.model

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.ShapeMeta
import kotlin.math.atan2

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
    val usedFallbackBrush: Boolean = false
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
        override fun transform(affine: AffineTransform2D): PageObject = copy(
            bounds = affine.transformRect(bounds),
            fontSizePt = (fontSizePt * affine.averageScaleFactor()).coerceIn(8f, 120f),
            originalStrokes = originalStrokes.map { it.withTransform(affine) }
        )
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
        override fun transform(affine: AffineTransform2D): PageObject = copy(
            bounds = affine.transformRect(bounds),
            fontSizePt = (fontSizePt * affine.averageScaleFactor()).coerceIn(10f, 96f),
            originalStrokes = originalStrokes.map { it.withTransform(affine) }
        )
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
    }
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

