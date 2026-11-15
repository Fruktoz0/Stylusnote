package hu.bdev.stylusnote.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.ui.canvas.PaperBackgroundRenderer
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Extracts closed 2D outline polygon loops from Jetpack Ink [Stroke] meshes (Section 8) or falls
 * back to analytical variable-width ribbon contours so vector PDF export works both on Android
 * hardware and inside pure JVM unit tests.
 */
object StrokeOutlineExtractor {

    /**
     * Queries every render group and outline loop in `stroke.shape` (`PartitionedMesh`).
     * Returns `emptyList()` when native Ink JNI is unavailable.
     */
    fun extractOutlineLoops(stroke: Stroke): List<List<DocPoint>> {
        return try {
            val mesh = stroke.shape
            val groupCount = mesh.getRenderGroupCount()
            if (groupCount <= 0) return emptyList()

            val loops = ArrayList<List<DocPoint>>()
            val scratch = MutableVec()
            for (groupIdx in 0 until groupCount) {
                val outlineCount = mesh.getOutlineCount(groupIdx)
                for (outlineIdx in 0 until outlineCount) {
                    val vertexCount = mesh.getOutlineVertexCount(groupIdx, outlineIdx)
                    if (vertexCount < 3) continue
                    val loop = ArrayList<DocPoint>(vertexCount)
                    for (v in 0 until vertexCount) {
                        mesh.populateOutlinePosition(groupIdx, outlineIdx, v, scratch)
                        loop.add(DocPoint(scratch.x, scratch.y))
                    }
                    loops.add(loop)
                }
            }
            loops
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Pure-Kotlin analytical variable-width capsule/ribbon outline generator used whenever
     * [PersistedStrokeRecord] points are exported directly or native mesh outline extraction is unavailable.
     */
    fun buildAnalyticalStrokeOutlineLoops(
        samples: List<StylusPointSample>,
        baseSizePt: Float,
        toolType: BrushToolType
    ): List<List<DocPoint>> {
        if (samples.isEmpty()) return emptyList()

        val clampedBase = baseSizePt.coerceIn(BrushConfig.MIN_BRUSH_SIZE_PT, BrushConfig.MAX_BRUSH_SIZE_PT)
        fun radiusAt(sample: StylusPointSample): Float {
            val factor = when (toolType) {
                BrushToolType.FOUNTAIN_PEN -> 0.5f + sample.pressure.coerceIn(0f, 1f) * 0.9f
                BrushToolType.PENCIL -> (0.7f + (sample.tiltRadians / (PI.toFloat() * 0.5f)) * 0.8f)
                BrushToolType.BALLPOINT_PEN,
                BrushToolType.HIGHLIGHTER -> 1.0f
                else -> 1.0f
            }
            return (clampedBase * 0.5f * factor).coerceAtLeast(0.25f)
        }

        if (samples.size == 1) {
            val c = samples[0]
            val r = radiusAt(c)
            val segments = 12
            val cap = ArrayList<DocPoint>(segments)
            for (i in 0 until segments) {
                val theta = (2.0 * PI * i / segments).toFloat()
                cap.add(DocPoint(c.xPt + r * cos(theta), c.yPt + r * sin(theta)))
            }
            return listOf(cap)
        }

        val leftSide = ArrayList<DocPoint>(samples.size + 6)
        val rightSide = ArrayList<DocPoint>(samples.size + 6)

        for (i in 0 until samples.size) {
            val prev = samples[(i - 1).coerceAtLeast(0)]
            val curr = samples[i]
            val next = samples[(i + 1).coerceAtMost(samples.lastIndex)]

            var tx = next.xPt - prev.xPt
            var ty = next.yPt - prev.yPt
            val len = hypot(tx, ty)
            if (len <= 1e-4f) {
                tx = 1f
                ty = 0f
            } else {
                tx /= len
                ty /= len
            }
            val nx = -ty
            val ny = tx
            val r = radiusAt(curr)

            if (i == 0) {
                // Semicircle start cap
                val baseAngle = kotlin.math.atan2(ny, nx)
                for (step in 1..4) {
                    val a = baseAngle + (PI.toFloat() * (1f - step / 4f))
                    leftSide.add(DocPoint(curr.xPt + r * cos(a), curr.yPt + r * sin(a)))
                }
            }

            leftSide.add(DocPoint(curr.xPt + nx * r, curr.yPt + ny * r))
            rightSide.add(DocPoint(curr.xPt - nx * r, curr.yPt - ny * r))

            if (i == samples.lastIndex) {
                // Semicircle end cap
                val baseAngle = kotlin.math.atan2(-ny, -nx)
                for (step in 1..4) {
                    val a = baseAngle + (PI.toFloat() * step / 4f)
                    leftSide.add(DocPoint(curr.xPt + r * cos(a), curr.yPt + r * sin(a)))
                }
            }
        }

        val combined = ArrayList<DocPoint>(leftSide.size + rightSide.size)
        combined.addAll(leftSide)
        for (i in rightSide.lastIndex downTo 0) {
            combined.add(rightSide[i])
        }
        return listOf(combined)
    }

    fun loopsToAndroidPath(loops: List<List<DocPoint>>): Path {
        val path = Path().apply { fillType = Path.FillType.WINDING }
        for (loop in loops) {
            if (loop.size < 3) continue
            path.moveTo(loop[0].xPt, loop[0].yPt)
            for (i in 1 until loop.size) {
                path.lineTo(loop[i].xPt, loop[i].yPt)
            }
            path.close()
        }
        return path
    }
}

data class ExportablePdfPage(
    val pageNumberOneBased: Int,
    val paperConfig: PagePaperConfig,
    val strokes: List<PersistedStrokeRecord>,
    val objects: List<PageObject> = emptyList(),
    val backgroundPdfAssetId: String? = null,
    val backgroundPdfPageIndex: Int? = null,
    val liveInkStrokesById: Map<String, Stroke> = emptyMap()
)

/**
 * Vector PDF exporter built on [PdfDocument] (Section 8).
 *
 * - Exports in exact PDF-point dimensions (`PageDimensionsPt`).
 * - Always renders using **canonical light-mode colors** regardless of active OLED dark theme.
 * - Converts `PartitionedMesh` outlines into software [Path] polygons.
 * - Renders Layer 3 highlighter strokes with per-stroke `canvas.saveLayerAlpha` (`0.35f`) and
 *   opaque RGB color inside the layer so overlapping loops within one stroke do not darken.
 */
class PdfExportManager(
    private val context: Context? = null,
    private val pdfTileRenderer: PdfTileRenderer? = null
) {

    private val lightExportPalette = StylusSurfacePalette(
        isOledDeepBlack = false,
        deskBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        pagePaperBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        pageBorder = androidx.compose.ui.graphics.Color(0xFFD0D7DE),
        defaultInkColor = androidx.compose.ui.graphics.Color(0xFF14171C)
    )

    suspend fun exportPagesToPdf(
        pages: List<ExportablePdfPage>,
        outputStream: OutputStream
    ) {
        require(pages.isNotEmpty()) { "Legalább egy exportálandó oldal szükséges." }

        val pdfDocument = PdfDocument()
        try {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val identityTransform = CanvasTransformMatrix(
                initialScale = 1.0f,
                initialPanXPx = 0f,
                initialPanYPx = 0f
            )

            for ((index, exportPage) in pages.withIndex()) {
                val dims: PageDimensionsPt = exportPage.paperConfig.pageDimensions
                val pageWidth = dims.widthPt.roundToInt().coerceAtLeast(72)
                val pageHeight = dims.heightPt.roundToInt().coerceAtLeast(72)
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageWidth,
                    pageHeight,
                    index + 1
                ).create()

                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Layer 1: Canonical light vector paper background.
                PaperBackgroundRenderer.drawPaperLayer(
                    canvas = canvas,
                    viewportWidthPx = pageWidth,
                    viewportHeightPx = pageHeight,
                    transformMatrix = identityTransform,
                    paperConfig = exportPage.paperConfig,
                    palette = lightExportPalette
                )

                // Layer 2: Optional PDF backdrop rendered at canonical light colors.
                val bgAsset = exportPage.backgroundPdfAssetId
                val bgPageIdx = exportPage.backgroundPdfPageIndex
                if (bgAsset != null && bgPageIdx != null && pdfTileRenderer != null) {
                    pdfTileRenderer.renderPageForExport(
                        pdfAssetId = bgAsset,
                        pageIndex = bgPageIdx,
                        targetCanvas = canvas,
                        targetWidthPt = dims.widthPt,
                        targetHeightPt = dims.heightPt
                    )
                }

                // Layers 2b–5: Under-ink images, highlighters, ink strokes, and embedded objects.
                CanvasPageVectorRenderer.drawPageForegroundLayers(
                    canvas = canvas,
                    strokes = exportPage.strokes,
                    objects = exportPage.objects,
                    liveInkStrokesById = exportPage.liveInkStrokesById,
                    isOledMode = false,
                    context = context,
                    fillPaint = fillPaint
                )

                pdfDocument.finishPage(page)
            }

            pdfDocument.writeTo(outputStream)
            outputStream.flush()
        } finally {
            pdfDocument.close()
        }
    }
}

