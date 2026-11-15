package hu.bdev.stylusnote.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.ink.strokes.Stroke
import hu.bdev.stylusnote.core.ai.FunctionEvaluator
import hu.bdev.stylusnote.core.math.ColorLuminanceInverter
import hu.bdev.stylusnote.core.math.GeometricShapeRenderer
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared vector + embedded-object page renderer in document coordinate space (`PDF pt`), reused
 * by both canonical light [PdfExportManager] exports and theme-aware [PageThumbnailRenderer] cards.
 */
object CanvasPageVectorRenderer {

    private fun resolveDisplayColor(canonicalArgb: Int, isOledMode: Boolean): Int =
        if (isOledMode) ColorLuminanceInverter.invertArgbLuminance(canonicalArgb) else canonicalArgb

    fun buildStrokePath(
        record: PersistedStrokeRecord,
        liveStroke: Stroke? = null
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

    /**
     * Renders Layer 2b (`ImageObject` beneath ink), Layer 3 (Highlighters with isolated
     * `saveLayerAlpha`), Layer 4 (Ink), and Layer 5 (LaTeX, formatted text, vector shapes,
     * function plots, derivation cards) onto [canvas].
     */
    fun drawPageForegroundLayers(
        canvas: Canvas,
        strokes: List<PersistedStrokeRecord>,
        objects: List<PageObject>,
        liveInkStrokesById: Map<String, Stroke> = emptyMap(),
        isOledMode: Boolean = false,
        context: Context? = null,
        imageBitmapResolver: ((PageObject.ImageObject) -> Bitmap?)? = null,
        recycleResolvedImageBitmaps: Boolean = false,
        fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    ) {
        val sortedObjects = if (objects.size > 1) objects.sortedBy { it.zOrder } else objects
        val sortedStrokes = if (strokes.size > 1) strokes.sortedBy { it.zOrder } else strokes

        // Layer 2b: Under-ink embedded raster images so strokes drawn onto images render above them.
        for (i in 0 until sortedObjects.size) {
            val obj = sortedObjects[i]
            if (obj is PageObject.ImageObject) {
                drawPageObject(
                    canvas = canvas,
                    obj = obj,
                    isOledMode = isOledMode,
                    context = context,
                    imageBitmapResolver = imageBitmapResolver,
                    recycleResolvedImageBitmaps = recycleResolvedImageBitmaps
                )
            }
        }

        // Layer 3: Highlighters with isolated alpha layers to prevent self-overlap darkening.
        val alphaByte = (BrushConfig.HIGHLIGHTER_OPACITY * 255f).roundToInt().coerceIn(1, 255)
        for (i in 0 until sortedStrokes.size) {
            val record = sortedStrokes[i]
            if (record.layer != StrokeLayerType.HIGHLIGHTER) continue
            val path = buildStrokePath(record, liveInkStrokesById[record.id])
            if (path.isEmpty) continue

            val layerBounds = RectF(
                record.bounds.leftPt - record.sizePt,
                record.bounds.topPt - record.sizePt,
                record.bounds.rightPt + record.sizePt,
                record.bounds.bottomPt + record.sizePt
            )
            val saveId = canvas.saveLayerAlpha(layerBounds, alphaByte)
            val opaqueBase = (0xFF shl 24) or (record.canonicalColorArgb and 0x00FFFFFF)
            fillPaint.color = resolveDisplayColor(opaqueBase, isOledMode)
            canvas.drawPath(path, fillPaint)
            canvas.restoreToCount(saveId)
        }

        // Layer 4: Standard Ink strokes.
        for (i in 0 until sortedStrokes.size) {
            val record = sortedStrokes[i]
            if (record.layer != StrokeLayerType.INK) continue
            val path = buildStrokePath(record, liveInkStrokesById[record.id])
            if (path.isEmpty) continue

            fillPaint.color = resolveDisplayColor(
                record.brushConfig.effectiveColorArgb(record.canonicalColorArgb),
                isOledMode
            )
            canvas.drawPath(path, fillPaint)
        }

        // Layer 5: Embedded non-image page objects above ink.
        for (i in 0 until sortedObjects.size) {
            val obj = sortedObjects[i]
            if (obj is PageObject.ImageObject) continue
            drawPageObject(
                canvas = canvas,
                obj = obj,
                isOledMode = isOledMode,
                context = context,
                imageBitmapResolver = imageBitmapResolver,
                recycleResolvedImageBitmaps = recycleResolvedImageBitmaps
            )
        }
    }

    fun drawPageObject(
        canvas: Canvas,
        obj: PageObject,
        isOledMode: Boolean = false,
        context: Context? = null,
        imageBitmapResolver: ((PageObject.ImageObject) -> Bitmap?)? = null,
        recycleResolvedImageBitmaps: Boolean = false
    ) {
        when (obj) {
            is PageObject.LatexObject -> {
                val displayColor = resolveDisplayColor(obj.canonicalColorArgb, isOledMode)
                if (context != null) {
                    try {
                        val drawable = JLatexMathDrawable.builder(obj.latexSource)
                            .textSize(obj.fontSizePt)
                            .color(displayColor)
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
                        // Fall back to clean plain formula text if drawable fails to parse/load.
                    }
                }
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = displayColor
                    textSize = obj.fontSizePt.coerceIn(10f, 36f)
                }
                canvas.drawText(obj.latexSource, obj.bounds.leftPt + 4f, obj.bounds.topPt + textPaint.textSize, textPaint)
            }

            is PageObject.FunctionPlotObject -> drawPlotBox(canvas, obj, isOledMode)

            is PageObject.DerivationCardObject -> {
                val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = if (isOledMode) Color.rgb(30, 35, 44) else Color.rgb(245, 247, 250)
                }
                val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.0f
                    color = if (isOledMode) Color.rgb(72, 84, 102) else Color.rgb(180, 190, 204)
                }
                val rect = RectF(obj.bounds.leftPt, obj.bounds.topPt, obj.bounds.rightPt, obj.bounds.bottomPt)
                canvas.drawRoundRect(rect, 8f, 8f, cardBg)
                canvas.drawRoundRect(rect, 8f, 8f, cardBorder)

                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isOledMode) Color.rgb(235, 240, 248) else Color.rgb(20, 23, 28)
                    textSize = 12f
                    isFakeBoldText = true
                }
                val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isOledMode) Color.rgb(196, 204, 218) else Color.rgb(55, 65, 81)
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
                val displayColor = resolveDisplayColor(obj.canonicalColorArgb, isOledMode)
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = displayColor
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
                val strokeArgb = resolveDisplayColor(obj.canonicalStrokeColorArgb, isOledMode)
                GeometricShapeRenderer.drawShape(
                    canvas = canvas,
                    shapeType = obj.shapeType,
                    bounds = RectF(obj.bounds.leftPt, obj.bounds.topPt, obj.bounds.rightPt, obj.bounds.bottomPt),
                    strokeWidthPx = obj.strokeWidthPt.coerceIn(0.4f, 12f),
                    strokeColorArgb = strokeArgb,
                    hasFill = obj.hasFill,
                    fillColorArgb = obj.canonicalFillColorArgb,
                    rotationRadians = obj.rotationRadians
                )
            }

            is PageObject.ImageObject -> {
                val resolvedBitmap = imageBitmapResolver?.invoke(obj) ?: run {
                    val appContext = context ?: return
                    val imgFile = File(appContext.filesDir, obj.imageAssetRelativePath)
                    if (!imgFile.isFile) return
                    BitmapFactory.decodeFile(imgFile.absolutePath) ?: return
                }
                if (resolvedBitmap.isRecycled) return
                val shouldRecycle = imageBitmapResolver == null || recycleResolvedImageBitmaps
                try {
                    val dstRect = RectF(obj.bounds.leftPt, obj.bounds.topPt, obj.bounds.rightPt, obj.bounds.bottomPt)
                    val saveCount = canvas.save()
                    val deg = (obj.rotationRadians * 180.0 / PI).toFloat()
                    if (abs(deg) > 0.01f) {
                        canvas.rotate(deg, dstRect.centerX(), dstRect.centerY())
                    }
                    val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(resolvedBitmap, null, dstRect, imgPaint)
                    canvas.restoreToCount(saveCount)
                } finally {
                    if (shouldRecycle && !resolvedBitmap.isRecycled) {
                        resolvedBitmap.recycle()
                    }
                }
            }
        }
    }

    private fun drawPlotBox(
        canvas: Canvas,
        plot: PageObject.FunctionPlotObject,
        isOledMode: Boolean
    ) {
        val rect = RectF(plot.bounds.leftPt, plot.bounds.topPt, plot.bounds.rightPt, plot.bounds.bottomPt)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = if (isOledMode) Color.rgb(92, 102, 118) else Color.rgb(140, 150, 165)
        }
        canvas.drawRect(rect, borderPaint)

        val xSpan = max(1e-3f, plot.domainMaxX - plot.domainMinX)
        val ySpan = max(1e-3f, plot.rangeMaxY - plot.rangeMinY)
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.0f
            color = if (isOledMode) Color.rgb(148, 160, 178) else Color.rgb(90, 98, 110)
        }
        if (plot.domainMinX <= 0f && plot.domainMaxX >= 0f) {
            val xZero = rect.left + ((0f - plot.domainMinX) / xSpan) * rect.width()
            canvas.drawLine(xZero, rect.top, xZero, rect.bottom, axisPaint)
        }
        if (plot.rangeMinY <= 0f && plot.rangeMaxY >= 0f) {
            val yZero = rect.bottom - ((0f - plot.rangeMinY) / ySpan) * rect.height()
            canvas.drawLine(rect.left, yZero, rect.right, yZero, axisPaint)
        }

        val displayColor = resolveDisplayColor(plot.canonicalColorArgb, isOledMode)
        val curvePath = FunctionEvaluator.buildPlotPathForBounds(plot)
        if (!curvePath.isEmpty) {
            val saveCount = canvas.save()
            canvas.clipRect(rect)
            val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = plot.strokeWidthPt.coerceIn(0.6f, 8f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = displayColor
            }
            canvas.drawPath(curvePath, curvePaint)
            canvas.restoreToCount(saveCount)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = displayColor
            textSize = 9.5f
        }
        canvas.drawText("y = ${plot.expression}", rect.left + 6f, rect.top + 12f, labelPaint)
    }
}
