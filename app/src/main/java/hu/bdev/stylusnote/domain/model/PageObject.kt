package hu.bdev.stylusnote.domain.model

import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.ShapeMeta

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
        val sourceStrokeIds: List<String> = emptyList()
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject = copy(
            bounds = affine.transformRect(bounds),
            fontSizePt = (fontSizePt * affine.averageScaleFactor()).coerceIn(8f, 120f)
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
        val sourceStrokeIds: List<String> = emptyList()
    ) : PageObject {
        override fun transform(affine: AffineTransform2D): PageObject = copy(
            bounds = affine.transformRect(bounds),
            fontSizePt = (fontSizePt * affine.averageScaleFactor()).coerceIn(10f, 96f)
        )
    }
}

