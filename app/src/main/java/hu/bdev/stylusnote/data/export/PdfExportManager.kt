package hu.bdev.stylusnote.data.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.ai.FunctionEvaluator
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.GeometricShapeRenderer
import hu.bdev.stylusnote.core.math.PageDimensionsPt
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.ui.canvas.PaperBackgroundRenderer
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
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

                // Layer 3: Highlighters (zOrder sorted), each isolated with saveLayerAlpha so
                // self-overlap stays uniform translucent without additive darkening.
                val sortedStrokes = exportPage.strokes.sortedBy { it.zOrder }
                for (record in sortedStrokes) {
                    if (record.layer != StrokeLayerType.HIGHLIGHTER) continue
                    val path = buildStrokePath(record, exportPage.liveInkStrokesById[record.id])
                    if (path.isEmpty) continue

                    val alphaByte = (BrushConfig.HIGHLIGHTER_OPACITY * 255f).roundToInt().coerceIn(1, 255)
                    val layerBounds = RectF(
                        record.bounds.leftPt - record.sizePt,
                        record.bounds.topPt - record.sizePt,
                        record.bounds.rightPt + record.sizePt,
                        record.bounds.bottomPt + record.sizePt
                    )
                    val saveId = canvas.saveLayerAlpha(layerBounds, alphaByte)
                    fillPaint.color = (0xFF shl 24) or (record.canonicalColorArgb and 0x00FFFFFF)
                    canvas.drawPath(path, fillPaint)
                    canvas.restoreToCount(saveId)
                }

                // Layer 4: Standard Ink strokes drawn as crisp filled vector outline Paths.
                for (record in sortedStrokes) {
                    if (record.layer != StrokeLayerType.INK) continue
                    val path = buildStrokePath(record, exportPage.liveInkStrokesById[record.id])
                    if (path.isEmpty) continue

                    fillPaint.color = record.brushConfig.effectiveColorArgb(record.canonicalColorArgb)
                    canvas.drawPath(path, fillPaint)
                }

                // Layer 5: Embedded LaTeX, function curves, and derivation cards.
                val sortedObjects = exportPage.objects.sortedBy { it.zOrder }
                for (obj in sortedObjects) {
                    drawPageObjectOnPdfCanvas(canvas, obj)
                }

                pdfDocument.finishPage(page)
            }

            pdfDocument.writeTo(outputStream)
            outputStream.flush()
        } finally {
            pdfDocument.close()
        }
    }

    private fun buildStrokePath(
        record: PersistedStrokeRecord,
        liveStroke: Stroke?
    ): Path {
        val inkLoops = liveStroke?.let { StrokeOutlineExtractor.extractOutlineLoops(it) }.orEmpty()
        val loops = if (inkLoops.isNotEmpty()) {
            inkLoops
        } else {
            StrokeOutlineExtractor.buildAnalyticalStrokeOutlineLoops(
                samples = record.samples,
                baseSizePt = record.sizePt,
                toolType = record.brushConfig.toolType
            )
        }
        return StrokeOutlineExtractor.loopsToAndroidPath(loops)
    }

    private fun drawPageObjectOnPdfCanvas(canvas: Canvas, obj: PageObject) {
        when (obj) {
            is PageObject.LatexObject -> {
                val appContext = context
                if (appContext != null) {
                    try {
                        val drawable = JLatexMathDrawable.builder(obj.latexSource)
                            .textSize(obj.fontSizePt)
                            .color(obj.canonicalColorArgb)
                            .padding(4)
                            .build()
                        canvas.save()
                        canvas.translate(obj.bounds.leftPt, obj.bounds.topPt)
                        if (abs(obj.rotationRadians) > 1e-4f) {
                            canvas.rotate(
                                (obj.rotationRadians * 180.0 / PI).toFloat(),
                                obj.bounds.widthPt * 0.5f,
                                obj.bounds.heightPt * 0.5f
                            )
                        }
                        drawable.setBounds(
                            0,
                            0,
                            obj.bounds.widthPt.roundToInt().coerceAtLeast(16),
                            obj.bounds.heightPt.roundToInt().coerceAtLeast(16)
                        )
                        drawable.draw(canvas)
                        canvas.restore()
                        return
                    } catch (_: Throwable) {
                        // Fall back to clean monospaced formula text if drawable fails to parse/load.
                    }
                }
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = obj.canonicalColorArgb
                    textSize = obj.fontSizePt.coerceIn(10f, 36f)
                }
                canvas.drawText(obj.latexSource, obj.bounds.leftPt + 4f, obj.bounds.topPt + textPaint.textSize, textPaint)
            }

            is PageObject.FunctionPlotObject -> {
                drawPlotBox(canvas, obj)
            }

            is PageObject.DerivationCardObject -> {
                val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.rgb(245, 247, 250)
                }
                val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.0f
                    color = Color.rgb(180, 190, 204)
                }
                val rect = RectF(obj.bounds.leftPt, obj.bounds.topPt, obj.bounds.rightPt, obj.bounds.bottomPt)
                canvas.drawRoundRect(rect, 8f, 8f, cardBg)
                canvas.drawRoundRect(rect, 8f, 8f, cardBorder)

                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(20, 23, 28)
                    textSize = 12f
                    isFakeBoldText = true
                }
                val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(55, 65, 81)
                    textSize = 10f
                }
                canvas.drawText(obj.title, rect.left + 10f, rect.top + 18f, titlePaint)
                var yCursor = rect.top + 34f
                for (line in obj.stepsMarkdownLatex.lines()) {
                    if (yCursor > rect.bottom - 8f) break
                    canvas.drawText(line, rect.left + 10f, yCursor, bodyPaint)
                    yCursor += 13f
                }
            }

            is PageObject.PdfPageRefObject -> Unit

            is PageObject.FormattedTextObject -> {
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = obj.canonicalColorArgb
                    textSize = obj.fontSizePt.coerceIn(10f, 72f)
                    typeface = Typeface.create("cursive", Typeface.ITALIC)
                }
                val layoutWidthPx = (obj.bounds.widthPt - 8f).roundToInt().coerceAtLeast(32)
                val layout = StaticLayout.Builder
                    .obtain(obj.text, 0, obj.text.length, textPaint, layoutWidthPx)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.1f)
                    .setIncludePad(false)
                    .build()
                canvas.save()
                canvas.translate(obj.bounds.leftPt + 4f, obj.bounds.topPt + 4f)
                if (abs(obj.rotationRadians) > 1e-4f) {
                    canvas.rotate(
                        (obj.rotationRadians * 180.0 / PI).toFloat(),
                        obj.bounds.widthPt * 0.5f,
                        obj.bounds.heightPt * 0.5f
                    )
                }
                layout.draw(canvas)
                canvas.restore()
            }

            is PageObject.GeometricShapeObject -> {
                GeometricShapeRenderer.drawShape(
                    canvas = canvas,
                    shapeType = obj.shapeType,
                    bounds = RectF(obj.bounds.leftPt, obj.bounds.topPt, obj.bounds.rightPt, obj.bounds.bottomPt),
                    strokeWidthPx = obj.strokeWidthPt.coerceIn(0.4f, 12f),
                    strokeColorArgb = obj.canonicalStrokeColorArgb,
                    hasFill = obj.hasFill,
                    fillColorArgb = obj.canonicalFillColorArgb,
                    rotationRadians = obj.rotationRadians
                )
            }

            is PageObject.ImageObject -> {
                val appContext = context ?: return
                val imgFile = File(appContext.filesDir, obj.imageAssetRelativePath)
                if (!imgFile.isFile) return
                val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath) ?: return
                try {
                    val dstRect = RectF(obj.bounds.leftPt, obj.bounds.topPt, obj.bounds.rightPt, obj.bounds.bottomPt)
                    val saveCount = canvas.save()
                    val deg = (obj.rotationRadians * 180.0 / PI).toFloat()
                    if (abs(deg) > 0.01f) {
                        canvas.rotate(deg, dstRect.centerX(), dstRect.centerY())
                    }
                    val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(bitmap, null, dstRect, imgPaint)
                    canvas.restoreToCount(saveCount)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun drawPlotBox(canvas: Canvas, plot: PageObject.FunctionPlotObject) {
        val rect = RectF(plot.bounds.leftPt, plot.bounds.topPt, plot.bounds.rightPt, plot.bounds.bottomPt)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = Color.rgb(140, 150, 165)
        }
        canvas.drawRect(rect, borderPaint)

        // Axes when zero lies within domain/range
        val xSpan = max(1e-3f, plot.domainMaxX - plot.domainMinX)
        val ySpan = max(1e-3f, plot.rangeMaxY - plot.rangeMinY)
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
            color = Color.rgb(90, 98, 110)
        }
        if (plot.domainMinX <= 0f && plot.domainMaxX >= 0f) {
            val xZero = rect.left + ((0f - plot.domainMinX) / xSpan) * rect.width()
            canvas.drawLine(xZero, rect.top, xZero, rect.bottom, axisPaint)
        }
        if (plot.rangeMinY <= 0f && plot.rangeMaxY >= 0f) {
            val yZero = rect.bottom - ((0f - plot.rangeMinY) / ySpan) * rect.height()
            canvas.drawLine(rect.left, yZero, rect.right, yZero, axisPaint)
        }

        val curvePath = FunctionEvaluator.buildPlotPathForBounds(plot)
        if (!curvePath.isEmpty) {
            val saveCount = canvas.save()
            canvas.clipRect(rect)
            val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = plot.strokeWidthPt.coerceIn(0.6f, 8f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = plot.canonicalColorArgb
            }
            canvas.drawPath(curvePath, curvePaint)
            canvas.restoreToCount(saveCount)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = plot.canonicalColorArgb
            textSize = 9.5f
        }
        canvas.drawText("y = ${plot.expression}", rect.left + 6f, rect.top + 12f, labelPaint)
    }
}

