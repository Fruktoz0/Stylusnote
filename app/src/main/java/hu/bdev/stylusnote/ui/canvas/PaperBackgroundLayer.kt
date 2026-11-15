package hu.bdev.stylusnote.ui.canvas

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withSave
import hu.bdev.stylusnote.core.math.CanvasTransformMatrix
import hu.bdev.stylusnote.core.math.ContinuousPageLayout
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.PageLayoutSlot
import hu.bdev.stylusnote.domain.model.CanvasMode
import hu.bdev.stylusnote.domain.model.PagePaperConfig
import hu.bdev.stylusnote.domain.model.PaperTemplateType
import hu.bdev.stylusnote.ui.theme.StylusSurfacePalette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Layer 1 vector paper background renderer (Section 3 & Section 8).
 *
 * Draws either a physical fixed sheet (`A4`, `B5`, `16:10`) or an unbounded infinite canvas with
 * crisp zoom-compensated rule widths across all 6 templates: Blank, Dotted (5 mm), Grid (5 mm),
 * Ruled (8 mm), Cornell, and Music Staff.
 */
object PaperBackgroundRenderer {

    private val docToScreenMatrix = Matrix()

    private val paperFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paperBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val minorRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val majorRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val marginAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val adjacentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var minorLinesBuffer = FloatArray(512)
    private var majorLinesBuffer = FloatArray(256)
    private var dotsBuffer = FloatArray(4096)

    private fun ensureCapacity(buffer: FloatArray, required: Int): FloatArray {
        if (buffer.size >= required) return buffer
        var nextSize = buffer.size.coerceAtLeast(64)
        while (nextSize < required) {
            nextSize = nextSize shl 1
        }
        return FloatArray(nextSize)
    }

    @Synchronized
    fun drawPaperLayer(
        canvas: Canvas,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        transformMatrix: CanvasTransformMatrix,
        paperConfig: PagePaperConfig,
        palette: StylusSurfacePalette
    ) {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return

        val snapshot = transformMatrix.snapshot()
        val scale = snapshot.scale.coerceAtLeast(0.05f)
        transformMatrix.populateDocToScreenMatrix(docToScreenMatrix)

        val isInfinite = paperConfig.canvasMode == CanvasMode.INFINITE_CANVAS
        val visibleDocRect = transformMatrix.screenRectToDocRect(
            leftPx = 0f,
            topPx = 0f,
            rightPx = viewportWidthPx.toFloat(),
            bottomPx = viewportHeightPx.toFloat()
        )

        val drawBounds = if (isInfinite) {
            visibleDocRect
        } else {
            DocRect(
                leftPt = 0f,
                topPt = 0f,
                rightPt = paperConfig.pageDimensions.widthPt,
                bottomPt = paperConfig.pageDimensions.heightPt
            )
        }

        configurePaints(scale = scale, palette = palette)

        canvas.save()
        canvas.concat(docToScreenMatrix)

        if (isInfinite) {
            canvas.drawRect(
                visibleDocRect.leftPt,
                visibleDocRect.topPt,
                visibleDocRect.rightPt,
                visibleDocRect.bottomPt,
                paperFillPaint
            )
            if (!visibleDocRect.isEmpty) {
                drawTemplatePattern(
                    canvas = canvas,
                    pageBounds = drawBounds,
                    clippedRect = visibleDocRect,
                    scale = scale,
                    paperConfig = paperConfig,
                    isInfinite = true
                )
            }
        } else {
            val clippedRect = DocRect(
                leftPt = max(drawBounds.leftPt, visibleDocRect.leftPt),
                topPt = max(drawBounds.topPt, visibleDocRect.topPt),
                rightPt = min(drawBounds.rightPt, visibleDocRect.rightPt),
                bottomPt = min(drawBounds.bottomPt, visibleDocRect.bottomPt)
            )
            drawFixedSheetBackgroundAndTemplate(
                canvas = canvas,
                drawBounds = drawBounds,
                clippedRect = clippedRect,
                scale = scale,
                paperConfig = paperConfig
            )
        }

        canvas.restore()
    }

    /**
     * Lightweight preview sheets drawn strictly when a fixed-page user scrolls past the top or
     * bottom margin (`currentPageIndexOneBased +/- 1`). Immediately returns in O(1) when viewing
     * inside the active sheet so 144 Hz pan/zoom performance inside a page is completely unaffected.
     */
    @Synchronized
    fun drawAdjacentFixedPagesIfVisible(
        canvas: Canvas,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        transformMatrix: CanvasTransformMatrix,
        paperConfig: PagePaperConfig,
        palette: StylusSurfacePalette,
        currentPageIndexOneBased: Int,
        totalPages: Int
    ) {
        if (
            paperConfig.canvasMode != CanvasMode.FIXED_PAGE ||
            totalPages <= 1 ||
            viewportWidthPx <= 0 ||
            viewportHeightPx <= 0
        ) {
            return
        }
        val visibleDocRect = transformMatrix.screenRectToDocRect(
            leftPx = 0f,
            topPx = 0f,
            rightPx = viewportWidthPx.toFloat(),
            bottomPx = viewportHeightPx.toFloat()
        )
        val pageW = paperConfig.pageDimensions.widthPt
        val pageH = paperConfig.pageDimensions.heightPt
        val gapPt = 28f

        val showPrev = currentPageIndexOneBased > 1 && visibleDocRect.topPt < -72f
        val showNext = currentPageIndexOneBased < totalPages && visibleDocRect.bottomPt > pageH + 72f
        if (!showPrev && !showNext) return

        val scale = transformMatrix.snapshot().scale.coerceAtLeast(0.05f)
        transformMatrix.populateDocToScreenMatrix(docToScreenMatrix)
        configurePaints(scale = scale, palette = palette)

        val fullSheet = DocRect(0f, 0f, pageW, pageH)
        val prevLabelScreen = if (showPrev) {
            transformMatrix.docToScreen(pageW * 0.5f, -(gapPt + 14f))
        } else {
            null
        }
        val nextLabelScreen = if (showNext) {
            transformMatrix.docToScreen(pageW * 0.5f, pageH + gapPt + 26f)
        } else {
            null
        }

        canvas.save()
        canvas.concat(docToScreenMatrix)

        if (showPrev) {
            val prevClipped = DocRect(
                leftPt = max(0f, visibleDocRect.leftPt),
                topPt = max(0f, visibleDocRect.topPt + pageH + gapPt),
                rightPt = min(pageW, visibleDocRect.rightPt),
                bottomPt = min(pageH, visibleDocRect.bottomPt + pageH + gapPt)
            )
            canvas.save()
            canvas.translate(0f, -(pageH + gapPt))
            drawFixedSheetBackgroundAndTemplate(
                canvas = canvas,
                drawBounds = fullSheet,
                clippedRect = prevClipped,
                scale = scale,
                paperConfig = paperConfig
            )
            canvas.restore()
        }

        if (showNext) {
            val nextClipped = DocRect(
                leftPt = max(0f, visibleDocRect.leftPt),
                topPt = max(0f, visibleDocRect.topPt - (pageH + gapPt)),
                rightPt = min(pageW, visibleDocRect.rightPt),
                bottomPt = min(pageH, visibleDocRect.bottomPt - (pageH + gapPt))
            )
            canvas.save()
            canvas.translate(0f, pageH + gapPt)
            drawFixedSheetBackgroundAndTemplate(
                canvas = canvas,
                drawBounds = fullSheet,
                clippedRect = nextClipped,
                scale = scale,
                paperConfig = paperConfig
            )
            canvas.restore()
        }

        canvas.restore()

        // Draw labels in screen space at constant font size to avoid Skia glyph atlas churn during zoom.
        adjacentLabelPaint.color = palette.pageBorder.toArgb()
        adjacentLabelPaint.textSize = 28f
        if (prevLabelScreen != null) {
            canvas.drawText(
                "${currentPageIndexOneBased - 1}. oldal",
                prevLabelScreen.xPx,
                prevLabelScreen.yPx,
                adjacentLabelPaint
            )
        }
        if (nextLabelScreen != null) {
            canvas.drawText(
                "${currentPageIndexOneBased + 1}. oldal",
                nextLabelScreen.xPx,
                nextLabelScreen.yPx,
                adjacentLabelPaint
            )
        }
    }

    /**
     * Renders a single fixed-page sheet inside [ContinuousPageLayout] at `(slot.leftPt, slot.topPt)`
     * in Layer 1 vector space with exact zoom-compensated stroke widths.
     */
    @Synchronized
    fun drawContinuousSlotBackground(
        canvas: Canvas,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        transformMatrix: CanvasTransformMatrix,
        slot: PageLayoutSlot,
        palette: StylusSurfacePalette
    ) {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return

        val snap = transformMatrix.snapshot()
        val scale = snap.scale.coerceAtLeast(0.05f)
        val visibleWorkspaceRect = transformMatrix.screenRectToDocRect(
            leftPx = 0f,
            topPx = 0f,
            rightPx = viewportWidthPx.toFloat(),
            bottomPx = viewportHeightPx.toFloat()
        )
        val localVisible = DocRect(
            leftPt = max(0f, visibleWorkspaceRect.leftPt - slot.leftPt),
            topPt = max(0f, visibleWorkspaceRect.topPt - slot.topPt),
            rightPt = min(slot.widthPt, visibleWorkspaceRect.rightPt - slot.leftPt),
            bottomPt = min(slot.heightPt, visibleWorkspaceRect.bottomPt - slot.topPt)
        )
        val localBounds = DocRect(0f, 0f, slot.widthPt, slot.heightPt)

        configurePaints(scale = scale, palette = palette)
        transformMatrix.populateDocToScreenMatrix(docToScreenMatrix)
        canvas.withSave {
            concat(docToScreenMatrix)
            translate(slot.leftPt, slot.topPt)
            drawFixedSheetBackgroundAndTemplate(
                canvas = this,
                drawBounds = localBounds,
                clippedRect = localVisible,
                scale = scale,
                paperConfig = slot.config
            )
        }
    }

    /**
     * Draws a crisp screen-space page indicator ("2 / 9") centered in the gap above each sheet
     * (or cleanly above page 1 when zoomed) without modifying or overlapping sheet contents.
     */
    @Synchronized
    fun drawContinuousPageGapLabel(
        canvas: Canvas,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        transformMatrix: CanvasTransformMatrix,
        slot: PageLayoutSlot,
        totalPages: Int,
        gapPt: Float,
        palette: StylusSurfacePalette
    ) {
        if (totalPages <= 1 || viewportWidthPx <= 0 || viewportHeightPx <= 0) return
        val labelWorkspaceYPt = slot.topPt - gapPt * 0.5f
        val screenCenter = transformMatrix.docToScreen(slot.centerXPt, labelWorkspaceYPt)
        if (screenCenter.yPx < -32f || screenCenter.yPx > viewportHeightPx + 32f) return

        val density = canvas.density.takeIf { it > 0 }?.div(160f) ?: 1.5f
        adjacentLabelPaint.textSize = 11f * density
        adjacentLabelPaint.color = if (palette.isOledDeepBlack) 0xFF8B94A6.toInt() else 0xFF6B778C.toInt()
        val fm = adjacentLabelPaint.fontMetrics
        val baselineOffset = -(fm.ascent + fm.descent) * 0.5f
        canvas.drawText(
            "${slot.pageIndex + 1} / $totalPages",
            screenCenter.xPx,
            screenCenter.yPx + baselineOffset,
            adjacentLabelPaint
        )
    }

    /**
     * Renders the complete fixed-page sheet (paper fill, clipped ruling/grid, and border) into an
     * offscreen bitmap whose origin (0, 0) corresponds to document origin scaled by [pageScale].
     */
    @Synchronized
    fun drawFixedPageIntoBitmapCanvas(
        canvas: Canvas,
        pageScale: Float,
        paperConfig: PagePaperConfig,
        palette: StylusSurfacePalette
    ) {
        val safeScale = pageScale.coerceAtLeast(0.05f)
        val drawBounds = DocRect(
            leftPt = 0f,
            topPt = 0f,
            rightPt = paperConfig.pageDimensions.widthPt,
            bottomPt = paperConfig.pageDimensions.heightPt
        )
        configurePaints(scale = safeScale, palette = palette)
        canvas.save()
        canvas.scale(safeScale, safeScale)
        drawFixedSheetBackgroundAndTemplate(
            canvas = canvas,
            drawBounds = drawBounds,
            clippedRect = drawBounds,
            scale = safeScale,
            paperConfig = paperConfig
        )
        canvas.restore()
    }

    private fun configurePaints(scale: Float, palette: StylusSurfacePalette) {
        val ruleColorArgb = if (palette.isOledDeepBlack) 0xFF242B36.toInt() else 0xFFD9E1EC.toInt()
        val majorRuleArgb = if (palette.isOledDeepBlack) 0xFF333D4D.toInt() else 0xFFB8C4D4.toInt()
        val accentRedArgb = if (palette.isOledDeepBlack) 0xFF9E4747.toInt() else 0xFFE57373.toInt()

        paperFillPaint.color = palette.pagePaperBackground.toArgb()
        paperBorderPaint.color = palette.pageBorder.toArgb()
        paperBorderPaint.strokeWidth = (1.5f / scale)

        minorRulePaint.color = ruleColorArgb
        minorRulePaint.strokeWidth = (1.0f / scale)

        majorRulePaint.color = majorRuleArgb
        majorRulePaint.strokeWidth = (1.3f / scale)

        marginAccentPaint.color = accentRedArgb
        marginAccentPaint.strokeWidth = (1.3f / scale)

        dotPaint.color = majorRuleArgb
    }

    private fun drawFixedSheetBackgroundAndTemplate(
        canvas: Canvas,
        drawBounds: DocRect,
        clippedRect: DocRect,
        scale: Float,
        paperConfig: PagePaperConfig
    ) {
        canvas.drawRect(
            drawBounds.leftPt,
            drawBounds.topPt,
            drawBounds.rightPt,
            drawBounds.bottomPt,
            paperFillPaint
        )
        // Clip pattern lines cleanly inside the fixed sheet boundaries.
        canvas.save()
        canvas.clipRect(
            drawBounds.leftPt,
            drawBounds.topPt,
            drawBounds.rightPt,
            drawBounds.bottomPt
        )
        if (!clippedRect.isEmpty) {
            drawTemplatePattern(
                canvas = canvas,
                pageBounds = drawBounds,
                clippedRect = clippedRect,
                scale = scale,
                paperConfig = paperConfig,
                isInfinite = false
            )
        }
        canvas.restore() // End clipRect before drawing sheet border.
        canvas.drawRect(
            drawBounds.leftPt,
            drawBounds.topPt,
            drawBounds.rightPt,
            drawBounds.bottomPt,
            paperBorderPaint
        )
    }

    private fun drawTemplatePattern(
        canvas: Canvas,
        pageBounds: DocRect,
        clippedRect: DocRect,
        scale: Float,
        paperConfig: PagePaperConfig,
        isInfinite: Boolean
    ) {
        when (paperConfig.templateType) {
            PaperTemplateType.BLANK -> Unit
            PaperTemplateType.DOTTED_5MM -> drawDottedGrid(
                canvas = canvas,
                bounds = clippedRect,
                spacingPt = CanvasTransformMatrix.GRID_5MM_PT,
                dotRadiusPt = (1.15f / scale).coerceIn(0.6f, 2.0f)
            )
            PaperTemplateType.GRID_SIMPLE_5MM -> drawSimpleSquareGrid(
                canvas = canvas,
                bounds = clippedRect,
                spacingPt = CanvasTransformMatrix.GRID_5MM_PT
            )
            PaperTemplateType.GRID_5MM -> drawSquareGrid(
                canvas = canvas,
                bounds = clippedRect,
                spacingPt = CanvasTransformMatrix.GRID_5MM_PT
            )
            PaperTemplateType.RULED_8MM -> drawRuledPaper(
                canvas = canvas,
                pageBounds = pageBounds,
                visibleBounds = clippedRect,
                lineSpacingPt = CanvasTransformMatrix.RULED_8MM_PT,
                isInfinite = isInfinite
            )
            PaperTemplateType.CORNELL -> drawCornellPaper(
                canvas = canvas,
                pageBounds = pageBounds,
                visibleBounds = clippedRect,
                lineSpacingPt = CanvasTransformMatrix.RULED_8MM_PT,
                isInfinite = isInfinite
            )
            PaperTemplateType.MUSIC_STAFF -> drawMusicStaffPaper(
                canvas = canvas,
                pageBounds = pageBounds,
                visibleBounds = clippedRect,
                isInfinite = isInfinite
            )
        }
    }

    private fun drawDottedGrid(
        canvas: Canvas,
        bounds: DocRect,
        spacingPt: Float,
        dotRadiusPt: Float
    ) {
        val startXIdx = floor(bounds.leftPt / spacingPt).toInt()
        val endXIdx = ceil(bounds.rightPt / spacingPt).toInt()
        val startYIdx = floor(bounds.topPt / spacingPt).toInt()
        val endYIdx = ceil(bounds.bottomPt / spacingPt).toInt()
        val cols = (endXIdx - startXIdx + 1).coerceAtLeast(0)
        val rows = (endYIdx - startYIdx + 1).coerceAtLeast(0)
        val totalFloats = cols * rows * 2
        if (totalFloats <= 0) return

        dotsBuffer = ensureCapacity(dotsBuffer, totalFloats)
        var offset = 0
        for (yi in startYIdx..endYIdx) {
            val y = yi * spacingPt
            for (xi in startXIdx..endXIdx) {
                dotsBuffer[offset++] = xi * spacingPt
                dotsBuffer[offset++] = y
            }
        }
        dotPaint.strokeWidth = dotRadiusPt * 2f
        canvas.drawPoints(dotsBuffer, 0, offset, dotPaint)
    }

    private fun drawSquareGrid(
        canvas: Canvas,
        bounds: DocRect,
        spacingPt: Float
    ) {
        val startXIdx = floor(bounds.leftPt / spacingPt).toInt()
        val endXIdx = ceil(bounds.rightPt / spacingPt).toInt()
        val startYIdx = floor(bounds.topPt / spacingPt).toInt()
        val endYIdx = ceil(bounds.bottomPt / spacingPt).toInt()

        val xCount = (endXIdx - startXIdx + 1).coerceAtLeast(0)
        val yCount = (endYIdx - startYIdx + 1).coerceAtLeast(0)
        val maxFloats = (xCount + yCount) * 4
        if (maxFloats <= 0) return

        minorLinesBuffer = ensureCapacity(minorLinesBuffer, maxFloats)
        majorLinesBuffer = ensureCapacity(majorLinesBuffer, maxFloats)
        var minorOffset = 0
        var majorOffset = 0

        for (xi in startXIdx..endXIdx) {
            val x = xi * spacingPt
            if (xi % 5 == 0) {
                majorLinesBuffer[majorOffset++] = x
                majorLinesBuffer[majorOffset++] = bounds.topPt
                majorLinesBuffer[majorOffset++] = x
                majorLinesBuffer[majorOffset++] = bounds.bottomPt
            } else {
                minorLinesBuffer[minorOffset++] = x
                minorLinesBuffer[minorOffset++] = bounds.topPt
                minorLinesBuffer[minorOffset++] = x
                minorLinesBuffer[minorOffset++] = bounds.bottomPt
            }
        }
        for (yi in startYIdx..endYIdx) {
            val y = yi * spacingPt
            if (yi % 5 == 0) {
                majorLinesBuffer[majorOffset++] = bounds.leftPt
                majorLinesBuffer[majorOffset++] = y
                majorLinesBuffer[majorOffset++] = bounds.rightPt
                majorLinesBuffer[majorOffset++] = y
            } else {
                minorLinesBuffer[minorOffset++] = bounds.leftPt
                minorLinesBuffer[minorOffset++] = y
                minorLinesBuffer[minorOffset++] = bounds.rightPt
                minorLinesBuffer[minorOffset++] = y
            }
        }

        if (minorOffset > 0) {
            canvas.drawLines(minorLinesBuffer, 0, minorOffset, minorRulePaint)
        }
        if (majorOffset > 0) {
            canvas.drawLines(majorLinesBuffer, 0, majorOffset, majorRulePaint)
        }
    }

    private fun drawSimpleSquareGrid(
        canvas: Canvas,
        bounds: DocRect,
        spacingPt: Float
    ) {
        val startXIdx = floor(bounds.leftPt / spacingPt).toInt()
        val endXIdx = ceil(bounds.rightPt / spacingPt).toInt()
        val startYIdx = floor(bounds.topPt / spacingPt).toInt()
        val endYIdx = ceil(bounds.bottomPt / spacingPt).toInt()

        val xCount = (endXIdx - startXIdx + 1).coerceAtLeast(0)
        val yCount = (endYIdx - startYIdx + 1).coerceAtLeast(0)
        val maxFloats = (xCount + yCount) * 4
        if (maxFloats <= 0) return

        minorLinesBuffer = ensureCapacity(minorLinesBuffer, maxFloats)
        var minorOffset = 0

        for (xi in startXIdx..endXIdx) {
            val x = xi * spacingPt
            minorLinesBuffer[minorOffset++] = x
            minorLinesBuffer[minorOffset++] = bounds.topPt
            minorLinesBuffer[minorOffset++] = x
            minorLinesBuffer[minorOffset++] = bounds.bottomPt
        }
        for (yi in startYIdx..endYIdx) {
            val y = yi * spacingPt
            minorLinesBuffer[minorOffset++] = bounds.leftPt
            minorLinesBuffer[minorOffset++] = y
            minorLinesBuffer[minorOffset++] = bounds.rightPt
            minorLinesBuffer[minorOffset++] = y
        }

        if (minorOffset > 0) {
            canvas.drawLines(minorLinesBuffer, 0, minorOffset, minorRulePaint)
        }
    }

    private fun drawRuledPaper(
        canvas: Canvas,
        pageBounds: DocRect,
        visibleBounds: DocRect,
        lineSpacingPt: Float,
        isInfinite: Boolean
    ) {
        val headerBottom = if (isInfinite) Float.NEGATIVE_INFINITY else pageBounds.topPt + 64f
        val startY = max(visibleBounds.topPt, headerBottom)
        val firstLineIdx = ceil(startY / lineSpacingPt).toInt()
        val lastLineIdx = floor(visibleBounds.bottomPt / lineSpacingPt).toInt()
        val lineCount = (lastLineIdx - firstLineIdx + 1).coerceAtLeast(0)

        if (lineCount > 0) {
            minorLinesBuffer = ensureCapacity(minorLinesBuffer, lineCount * 4)
            var offset = 0
            for (yi in firstLineIdx..lastLineIdx) {
                val y = yi * lineSpacingPt
                minorLinesBuffer[offset++] = visibleBounds.leftPt
                minorLinesBuffer[offset++] = y
                minorLinesBuffer[offset++] = visibleBounds.rightPt
                minorLinesBuffer[offset++] = y
            }
            canvas.drawLines(minorLinesBuffer, 0, offset, minorRulePaint)
        }

        if (!isInfinite) {
            canvas.drawLine(
                pageBounds.leftPt,
                headerBottom,
                pageBounds.rightPt,
                headerBottom,
                majorRulePaint
            )
        }
    }

    private fun drawCornellPaper(
        canvas: Canvas,
        pageBounds: DocRect,
        visibleBounds: DocRect,
        lineSpacingPt: Float,
        isInfinite: Boolean
    ) {
        val refWidth = if (isInfinite) 595f else pageBounds.widthPt
        val cueX = pageBounds.leftPt + refWidth * 0.30f
        val headerY = if (isInfinite) 68f else pageBounds.topPt + 68f
        val summaryY = if (isInfinite) Float.POSITIVE_INFINITY else pageBounds.bottomPt - 110f

        val notesTop = if (isInfinite) visibleBounds.topPt else max(visibleBounds.topPt, headerY)
        val notesBottom = if (isInfinite) visibleBounds.bottomPt else min(visibleBounds.bottomPt, summaryY)

        if (notesBottom > notesTop) {
            val lineLeft = if (isInfinite) max(visibleBounds.leftPt, cueX) else cueX
            if (visibleBounds.rightPt > lineLeft) {
                val firstLine = ceil(notesTop / lineSpacingPt).toInt()
                val lastLine = floor(notesBottom / lineSpacingPt).toInt()
                val lineCount = (lastLine - firstLine + 1).coerceAtLeast(0)
                if (lineCount > 0) {
                    minorLinesBuffer = ensureCapacity(minorLinesBuffer, lineCount * 4)
                    var offset = 0
                    for (yi in firstLine..lastLine) {
                        val y = yi * lineSpacingPt
                        minorLinesBuffer[offset++] = lineLeft
                        minorLinesBuffer[offset++] = y
                        minorLinesBuffer[offset++] = visibleBounds.rightPt
                        minorLinesBuffer[offset++] = y
                    }
                    canvas.drawLines(minorLinesBuffer, 0, offset, minorRulePaint)
                }
            }
        }

        // Cue column vertical divider
        val dividerTop = if (isInfinite) visibleBounds.topPt else headerY
        val dividerBottom = if (isInfinite) visibleBounds.bottomPt else summaryY
        canvas.drawLine(cueX, dividerTop, cueX, dividerBottom, marginAccentPaint)

        // Header and summary section rules
        if (!isInfinite) {
            canvas.drawLine(pageBounds.leftPt, headerY, pageBounds.rightPt, headerY, majorRulePaint)
            canvas.drawLine(pageBounds.leftPt, summaryY, pageBounds.rightPt, summaryY, majorRulePaint)
        } else if (visibleBounds.topPt <= headerY && visibleBounds.bottomPt >= headerY) {
            canvas.drawLine(visibleBounds.leftPt, headerY, visibleBounds.rightPt, headerY, majorRulePaint)
        }
    }

    private fun drawMusicStaffPaper(
        canvas: Canvas,
        pageBounds: DocRect,
        visibleBounds: DocRect,
        isInfinite: Boolean
    ) {
        val staffSpacingPt = 6.5f
        val systemHeightPt = staffSpacingPt * 4f
        val systemPitchPt = systemHeightPt + 40f

        val marginXLeft = if (isInfinite) visibleBounds.leftPt else pageBounds.leftPt + 36f
        val marginXRight = if (isInfinite) visibleBounds.rightPt else pageBounds.rightPt - 36f
        val firstTop = if (isInfinite) visibleBounds.topPt else max(visibleBounds.topPt, pageBounds.topPt + 56f)
        val lastBottom = if (isInfinite) visibleBounds.bottomPt else min(visibleBounds.bottomPt, pageBounds.bottomPt - 40f)

        if (marginXRight <= marginXLeft || lastBottom <= firstTop) return

        val startSystem = floor(firstTop / systemPitchPt).toInt()
        val endSystem = ceil(lastBottom / systemPitchPt).toInt()
        val sysCount = (endSystem - startSystem + 1).coerceAtLeast(0)
        if (sysCount <= 0) return

        majorLinesBuffer = ensureCapacity(majorLinesBuffer, sysCount * 5 * 4)
        var offset = 0
        for (sys in startSystem..endSystem) {
            val baseY = sys * systemPitchPt
            if (baseY + systemHeightPt < firstTop || baseY > lastBottom) continue
            for (line in 0 until 5) {
                val y = baseY + line * staffSpacingPt
                majorLinesBuffer[offset++] = marginXLeft
                majorLinesBuffer[offset++] = y
                majorLinesBuffer[offset++] = marginXRight
                majorLinesBuffer[offset++] = y
            }
        }
        if (offset > 0) {
            canvas.drawLines(majorLinesBuffer, 0, offset, majorRulePaint)
        }
    }
}

