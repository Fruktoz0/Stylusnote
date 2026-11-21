package hu.bdev.stylusnote.ui.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import hu.bdev.stylusnote.core.code.CodeSyntaxHighlighter
import hu.bdev.stylusnote.core.math.ColorLuminanceInverter
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.domain.model.ClozeTapePattern
import hu.bdev.stylusnote.domain.model.PageObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Renders [PageObject.CodeBlockObject] zones directly onto an Android [Canvas] in document
 * coordinates (`PDF pt`). Runs in Layer 2b (under ink) without text reflow on resize so external
 * handwritten arrows and annotations stay anchored to the exact lines they point at.
 */
object CodeBlockCanvasRenderer {

    fun drawCodeBlock(
        canvas: Canvas,
        obj: PageObject.CodeBlockObject,
        isOledMode: Boolean
    ) {
        val rect = RectF(obj.bounds.leftPt, obj.bounds.topPt, obj.bounds.rightPt, obj.bounds.bottomPt)
        if (rect.width() < 12f || rect.height() < 12f) return

        val cornerRadius = 6f
        val saveCount = canvas.save()
        try {
            val deg = (obj.rotationRadians * 180.0 / PI).toFloat()
            if (abs(deg) > 0.01f) {
                canvas.rotate(deg, rect.centerX(), rect.centerY())
            }

            val clipPath = Path().apply {
                addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = CodeSyntaxHighlighter.cardBackgroundArgb(isOledMode)
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

            val headerHeight = (obj.fontSizePt * 1.85f).coerceIn(16f, minOf(26f, rect.height() * 0.45f))
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = CodeSyntaxHighlighter.cardHeaderArgb(isOledMode)
            }
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + headerHeight, headerPaint)

            // Header divider line
            val borderColor = CodeSyntaxHighlighter.cardBorderArgb(isOledMode)
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.7f
                color = borderColor
            }
            canvas.drawLine(rect.left, rect.top + headerHeight, rect.right, rect.top + headerHeight, linePaint)

            // Language pill badge in header
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                isFakeBoldText = true
                textSize = (headerHeight * 0.50f).coerceIn(6.5f, 10.5f)
                color = CodeSyntaxHighlighter.badgeTextArgb(isOledMode)
            }
            val badgeLabel = obj.language.badge
            val badgeTextW = badgeTextPaint.measureText(badgeLabel)
            val badgePadX = 5f
            val badgeH = headerHeight * 0.68f
            val badgeTop = rect.top + (headerHeight - badgeH) * 0.5f
            val badgeRect = RectF(
                rect.left + 6f,
                badgeTop,
                rect.left + 6f + badgeTextW + badgePadX * 2f,
                badgeTop + badgeH
            )
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = CodeSyntaxHighlighter.badgeBackgroundArgb(isOledMode)
            }
            canvas.drawRoundRect(badgeRect, 3.5f, 3.5f, badgeBgPaint)
            val badgeBaseline = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) * 0.5f
            canvas.drawText(badgeLabel, badgeRect.left + badgePadX, badgeBaseline, badgeTextPaint)

            // Optional title next to badge
            val cleanTitle = obj.title.trim()
            if (cleanTitle.isNotEmpty() && badgeRect.right + 16f < rect.right - 6f) {
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textSize = (headerHeight * 0.50f).coerceIn(7f, 10.5f)
                    color = if (isOledMode) 0xFFCBD5E1.toInt() else 0xFF334155.toInt()
                }
                val titleX = badgeRect.right + 7f
                val maxTitleW = (rect.right - 8f - titleX).coerceAtLeast(0f)
                val renderedTitle = truncateWithEllipsis(cleanTitle, titlePaint, maxTitleW)
                if (renderedTitle.isNotEmpty()) {
                    val titleBaseline = rect.top + headerHeight * 0.5f - (titlePaint.descent() + titlePaint.ascent()) * 0.5f
                    canvas.drawText(renderedTitle, titleX, titleBaseline, titlePaint)
                }
            }

            // Code body (monospace, no wrapping/reflow so external annotations remain fixed)
            val bodyTop = rect.top + headerHeight + 5f
            val bodyBottom = rect.bottom - 5f
            if (bodyBottom > bodyTop + 4f) {
                canvas.save()
                canvas.clipRect(rect.left + 2f, bodyTop - 2f, rect.right - 3f, bodyBottom + 2f)

                val fontSize = obj.fontSizePt.coerceIn(7.5f, 22f)
                val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = fontSize
                }
                val lineHeight = fontSize * 1.42f
                val tokenizedLines = CodeSyntaxHighlighter.tokenizeDocument(
                    code = obj.codeText.ifEmpty { defaultPlaceholderFor(obj.language) },
                    language = obj.language
                )

                val gutterDigits = max(2, tokenizedLines.size.toString().length)
                val charW = codePaint.measureText("0")
                val gutterWidth = if (obj.showLineNumbers) gutterDigits * charW + 10f else 0f
                val codeStartX = rect.left + 7f + gutterWidth

                if (obj.showLineNumbers && gutterWidth > 0f && rect.left + 7f + gutterWidth < rect.right) {
                    val gutterRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 0.55f
                        color = borderColor
                    }
                    val ruleX = rect.left + 5f + gutterWidth - 3f
                    canvas.drawLine(ruleX, bodyTop - 2f, ruleX, bodyBottom, gutterRulePaint)
                }

                val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = fontSize * 0.88f
                    textAlign = Paint.Align.RIGHT
                    color = CodeSyntaxHighlighter.gutterTextArgb(isOledMode)
                }

                val maxVisibleLines = ceil(((bodyBottom - bodyTop) / lineHeight).toDouble()).toInt().coerceAtLeast(1)
                val maxDrawCount = minOf(tokenizedLines.size, maxVisibleLines + 1)

                var baselineY = bodyTop - codePaint.ascent()
                for (lineIdx in 0 until maxDrawCount) {
                    if (baselineY - codePaint.descent() > bodyBottom + lineHeight) break
                    if (obj.showLineNumbers) {
                        val numX = rect.left + 5f + gutterWidth - 6f
                        canvas.drawText((lineIdx + 1).toString(), numX, baselineY, gutterPaint)
                    }
                    var cursorX = codeStartX
                    val tokens = tokenizedLines[lineIdx]
                    for (tIdx in 0 until tokens.size) {
                        if (cursorX >= rect.right - 3f) break
                        val token = tokens[tIdx]
                        if (token.text.isEmpty()) continue
                        codePaint.color = CodeSyntaxHighlighter.tokenColorArgb(token.type, isOledMode)
                        val expanded = token.text.replace("\t", "    ")
                        canvas.drawText(expanded, cursorX, baselineY, codePaint)
                        cursorX += codePaint.measureText(expanded)
                    }
                    baselineY += lineHeight
                }
                canvas.restore()
            }

            // Crisp outer border on top
            val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.9f
                color = borderColor
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outerBorderPaint)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun truncateWithEllipsis(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 4f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (paint.measureText(text.substring(0, mid) + ellipsis) <= maxWidth) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return if (low <= 0) "" else text.substring(0, low) + ellipsis
    }

    private fun defaultPlaceholderFor(language: hu.bdev.stylusnote.core.code.CodeLanguage): String =
        when (language) {
            hu.bdev.stylusnote.core.code.CodeLanguage.SQL -> "-- Üres SQL blokk"
            hu.bdev.stylusnote.core.code.CodeLanguage.PYTHON -> "# Üres Python blokk"
            hu.bdev.stylusnote.core.code.CodeLanguage.JS_TS -> "// Üres JS/TS blokk"
            hu.bdev.stylusnote.core.code.CodeLanguage.CSHARP -> "// Üres C# blokk"
        }
}

/**
 * Shared renderer for [PageObject.ClozeTapeObject] (Layer 5b masking tape), used across
 * live Compose overlay cards, drag previews, PDF exports, and page thumbnails.
 */
object ClozeTapeCanvasRenderer {

    fun drawClozeTape(
        canvas: Canvas,
        bounds: DocRect,
        canonicalColorArgb: Int,
        patternStyle: ClozeTapePattern,
        rotationRadians: Float,
        label: String,
        isOledMode: Boolean,
        isRevealed: Boolean = false
    ) {
        val rect = RectF(bounds.leftPt, bounds.topPt, bounds.rightPt, bounds.bottomPt)
        if (rect.width() < 4f || rect.height() < 4f) return

        val displayBase = if (isOledMode) {
            ColorLuminanceInverter.invertArgbLuminance(canonicalColorArgb)
        } else {
            canonicalColorArgb
        }
        val radius = minOf(4f, rect.height() * 0.22f)
        val saveCount = canvas.save()
        try {
            val deg = (rotationRadians * 180.0 / PI).toFloat()
            if (abs(deg) > 0.01f) {
                canvas.rotate(deg, rect.centerX(), rect.centerY())
            }

            if (isRevealed) {
                // Faint translucent outline + peel hint so masked content underneath stays clear.
                val revealedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = displayBase
                    alpha = 26
                }
                canvas.drawRoundRect(rect, radius, radius, revealedFill)

                val dashedBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.1f
                    color = darkenArgb(displayBase, 0.35f)
                    alpha = 185
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
                }
                canvas.drawRoundRect(rect, radius, radius, dashedBorder)
                return
            }

            val clipPath = Path().apply {
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = displayBase
                alpha = 248
            }
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            val accentArgb = if (isColorDark(displayBase)) {
                Color.argb(72, 255, 255, 255)
            } else {
                Color.argb(46, 15, 23, 42)
            }

            when (patternStyle) {
                ClozeTapePattern.SOLID -> Unit
                ClozeTapePattern.DIAGONAL_STRIPES -> {
                    val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = max(1.6f, rect.height() * 0.14f).coerceAtMost(4.5f)
                        color = accentArgb
                    }
                    val spacing = max(7f, rect.height() * 0.45f).coerceAtMost(14f)
                    val h = rect.height()
                    var x = rect.left - h
                    while (x < rect.right + h) {
                        canvas.drawLine(x, rect.bottom, x + h, rect.top, stripePaint)
                        x += spacing
                    }
                }
                ClozeTapePattern.DOTS -> {
                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = accentArgb
                    }
                    val step = max(6f, rect.height() * 0.42f).coerceAtMost(11f)
                    val dotR = max(0.8f, step * 0.14f).coerceAtMost(1.8f)
                    var row = 0
                    var y = rect.top + step * 0.5f
                    while (y < rect.bottom - 1f) {
                        val xOffset = if (row % 2 == 1) step * 0.5f else 0f
                        var x = rect.left + step * 0.4f + xOffset
                        while (x < rect.right - 1f) {
                            canvas.drawCircle(x, y, dotR, dotPaint)
                            x += step
                        }
                        y += step
                        row++
                    }
                }
            }

            if (label.isNotBlank() && rect.width() > 28f && rect.height() >= 10f) {
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textSize = (rect.height() * 0.48f).coerceIn(6.5f, 11f)
                    color = if (isColorDark(displayBase)) 0xFFF8FAFC.toInt() else 0xFF1E293B.toInt()
                    textAlign = Paint.Align.CENTER
                }
                val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) * 0.5f
                canvas.drawText(label.trim(), rect.centerX(), baseline, labelPaint)
            }

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.85f
                color = darkenArgb(displayBase, 0.25f)
            }
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun isColorDark(argb: Int): Boolean {
        val r = Color.red(argb) / 255f
        val g = Color.green(argb) / 255f
        val b = Color.blue(argb) / 255f
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return lum < 0.45f
    }

    private fun darkenArgb(argb: Int, factor: Float): Int {
        val a = Color.alpha(argb)
        val r = (Color.red(argb) * (1f - factor)).toInt().coerceIn(0, 255)
        val g = (Color.green(argb) * (1f - factor)).toInt().coerceIn(0, 255)
        val b = (Color.blue(argb) * (1f - factor)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }
}

/**
 * Shared vector grid renderer for [PageObject.TableObject] (Layer 2b, under ink) and the
 * pre-placement [PendingTableDraftOverlay] preview.
 */
object TableCanvasRenderer {

    fun drawTable(
        canvas: Canvas,
        obj: PageObject.TableObject,
        isOledMode: Boolean
    ) {
        drawTable(
            canvas = canvas,
            bounds = obj.bounds,
            rows = obj.rows,
            columns = obj.columns,
            borderStrokeWidthPt = obj.borderStrokeWidthPt,
            canonicalBorderColorArgb = obj.canonicalBorderColorArgb,
            hasHeaderRow = obj.hasHeaderRow,
            isOledMode = isOledMode
        )
    }

    fun drawTable(
        canvas: Canvas,
        bounds: DocRect,
        rows: Int,
        columns: Int,
        borderStrokeWidthPt: Float,
        canonicalBorderColorArgb: Int,
        hasHeaderRow: Boolean,
        isOledMode: Boolean
    ) {
        val safeRows = rows.coerceIn(PageObject.TableObject.MIN_ROWS, PageObject.TableObject.MAX_ROWS)
        val safeCols = columns.coerceIn(PageObject.TableObject.MIN_COLS, PageObject.TableObject.MAX_COLS)
        val rect = RectF(bounds.leftPt, bounds.topPt, bounds.rightPt, bounds.bottomPt)
        if (rect.width() < 4f || rect.height() < 4f) return

        val displayColor = if (isOledMode) {
            ColorLuminanceInverter.invertArgbLuminance(canonicalBorderColorArgb)
        } else {
            canonicalBorderColorArgb
        }
        val strokePt = borderStrokeWidthPt.coerceIn(0.4f, 6f)
        val rowHeight = rect.height() / safeRows.toFloat()
        val colWidth = rect.width() / safeCols.toFloat()

        // Optional header-row tint rendered cleanly beneath ink
        if (hasHeaderRow && safeRows >= 2) {
            val headerTint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = displayColor
                alpha = if (isOledMode) 36 else 22
            }
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rowHeight, headerTint)
        }

        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (strokePt * 0.85f).coerceAtLeast(0.4f)
            color = displayColor
            alpha = 205
        }
        val headerDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (strokePt * 1.2f).coerceIn(0.6f, 6f)
            color = displayColor
        }

        // Horizontal interior dividers
        for (r in 1 until safeRows) {
            val y = rect.top + r * rowHeight
            val paint = if (hasHeaderRow && safeRows >= 2 && r == 1) headerDividerPaint else innerPaint
            canvas.drawLine(rect.left, y, rect.right, y, paint)
        }

        // Vertical interior dividers
        for (c in 1 until safeCols) {
            val x = rect.left + c * colWidth
            canvas.drawLine(x, rect.top, x, rect.bottom, innerPaint)
        }

        // Crisp outer frame
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePt
            strokeJoin = Paint.Join.MITER
            strokeCap = Paint.Cap.SQUARE
            color = displayColor
        }
        canvas.drawRect(rect, outerPaint)
    }
}
