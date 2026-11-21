package hu.bdev.stylusnote.core.ai

import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.core.math.RecognizedShapeType
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class EqualsTriggerContext(
    val pageId: String?,
    val equalsStrokeIds: Set<String>,
    val expressionStrokeIds: List<String>,
    val equalsBounds: DocRect,
    val expressionBounds: DocRect
)

sealed interface LiveMathSuggestion {
    data class NumericResult(
        val normalizedExpr: String,
        val formattedResult: String,
        val latexResult: String
    ) : LiveMathSuggestion

    data class FunctionPlotOffer(
        val normalizedExpr: String,
        val plotExpression: String
    ) : LiveMathSuggestion

    data class OfferAiSolve(
        val rawPreview: String
    ) : LiveMathSuggestion
}

/**
 * Pure-Kotlin geometric detector for a handwritten trailing `=` sign at the end of a line band
 * (`EqualsGestureDetector`).
 */
object EqualsGestureDetector {

    private const val MIN_BAR_WIDTH_PT = 7f
    private const val MAX_BAR_WIDTH_PT = 48f
    private const val MAX_EQUALS_HEIGHT_PT = 36f
    private const val MAX_SLOPE = 0.30f
    private const val MAX_EXPRESSION_HORIZONTAL_GAP_PT = 140f

    fun detectTrailingEquals(
        strokes: List<PersistedStrokeRecord>,
        pageId: String? = null
    ): EqualsTriggerContext? {
        if (strokes.size < 3) return null
        val inkStrokes = strokes
            .filter { it.layer == StrokeLayerType.INK && it.samples.isNotEmpty() }
            .sortedBy { it.zOrder }
        if (inkStrokes.size < 3) return null

        val barB = inkStrokes[inkStrokes.size - 1]
        val barA = inkStrokes[inkStrokes.size - 2]
        if (!isHorizontalEqualsBar(barA) || !isHorizontalEqualsBar(barB)) return null

        val topBar = if (barA.bounds.centerYPt <= barB.bounds.centerYPt) barA else barB
        val bottomBar = if (topBar === barA) barB else barA

        val overlapLeft = max(topBar.bounds.leftPt, bottomBar.bounds.leftPt)
        val overlapRight = min(topBar.bounds.rightPt, bottomBar.bounds.rightPt)
        val overlapWidth = (overlapRight - overlapLeft).coerceAtLeast(0f)
        val shorterWidth = min(topBar.bounds.widthPt, bottomBar.bounds.widthPt).coerceAtLeast(1f)
        if (overlapWidth < shorterWidth * 0.45f) return null

        val verticalCenterGap = bottomBar.bounds.centerYPt - topBar.bounds.centerYPt
        if (verticalCenterGap < 1.5f || verticalCenterGap > 26f) return null

        val equalsBounds = DocRect(
            leftPt = min(topBar.bounds.leftPt, bottomBar.bounds.leftPt),
            topPt = min(topBar.bounds.topPt, bottomBar.bounds.topPt),
            rightPt = max(topBar.bounds.rightPt, bottomBar.bounds.rightPt),
            bottomPt = max(topBar.bounds.bottomPt, bottomBar.bounds.bottomPt)
        )
        if (equalsBounds.heightPt > MAX_EQUALS_HEIGHT_PT) return null

        val bandToleranceY = max(28f, equalsBounds.heightPt * 2.2f)
        val bandMinY = equalsBounds.centerYPt - bandToleranceY
        val bandMaxY = equalsBounds.centerYPt + bandToleranceY

        val candidatesLeft = ArrayList<PersistedStrokeRecord>()
        for (i in 0 until inkStrokes.size - 2) {
            val s = inkStrokes[i]
            val b = s.bounds
            if (b.centerYPt in bandMinY..bandMaxY && b.rightPt <= equalsBounds.leftPt + 6f) {
                candidatesLeft.add(s)
            }
        }
        if (candidatesLeft.isEmpty()) return null

        // Walk right-to-left from `=` collecting horizontally connected tokens on this line band.
        val sortedByRightDesc = candidatesLeft.sortedByDescending { it.bounds.rightPt }
        val connected = ArrayList<PersistedStrokeRecord>()
        var frontierLeftPt = equalsBounds.leftPt
        for (s in sortedByRightDesc) {
            val gap = frontierLeftPt - s.bounds.rightPt
            if (gap > MAX_EXPRESSION_HORIZONTAL_GAP_PT) break
            connected.add(s)
            frontierLeftPt = min(frontierLeftPt, s.bounds.leftPt)
        }
        if (connected.isEmpty()) return null

        val orderedConnected = connected.sortedWith(
            compareBy<PersistedStrokeRecord> { it.zOrder }.thenBy { it.bounds.leftPt }
        )
        var exprMinL = Float.POSITIVE_INFINITY
        var exprMinT = Float.POSITIVE_INFINITY
        var exprMaxR = Float.NEGATIVE_INFINITY
        var exprMaxB = Float.NEGATIVE_INFINITY
        val exprIds = ArrayList<String>(orderedConnected.size)
        for (s in orderedConnected) {
            exprIds.add(s.id)
            exprMinL = min(exprMinL, s.bounds.leftPt)
            exprMinT = min(exprMinT, s.bounds.topPt)
            exprMaxR = max(exprMaxR, s.bounds.rightPt)
            exprMaxB = max(exprMaxB, s.bounds.bottomPt)
        }

        return EqualsTriggerContext(
            pageId = pageId,
            equalsStrokeIds = setOf(barA.id, barB.id),
            expressionStrokeIds = exprIds,
            equalsBounds = equalsBounds,
            expressionBounds = DocRect(exprMinL, exprMinT, exprMaxR, exprMaxB)
        )
    }

    private fun isHorizontalEqualsBar(record: PersistedStrokeRecord): Boolean {
        val meta = record.shapeMeta
        if (meta != null && meta.shapeType != RecognizedShapeType.LINE) return false
        val b = record.bounds
        val w = b.widthPt
        if (w < MIN_BAR_WIDTH_PT || w > MAX_BAR_WIDTH_PT) return false
        if (b.heightPt > max(8f, w * 0.45f)) return false

        val first = record.samples.first()
        val last = record.samples.last()
        val dx = abs(last.xPt - first.xPt)
        if (dx < MIN_BAR_WIDTH_PT * 0.65f) return false
        val dy = abs(last.yPt - first.yPt)
        return (dy / dx) <= MAX_SLOPE
    }
}

/**
 * Pure-Kotlin normalizer, offline evaluator, function-plot extractor, and basic LaTeX converter
 * powering both live `=` suggestions and offline `∑ LaTeX` / plot extraction.
 */
object LiveMathSuggestionEngine {

    private val commaDecimalRegex = Regex("(\\d),(\\d)")
    private val digitXDigitRegex = Regex("(\\d)\\s*[xX]\\s*(\\d)")
    private val leadingAssignmentRegex = Regex("^(?:[fg]\\s*\\(\\s*x\\s*\\)|y)\\s*=\\s*", RegexOption.IGNORE_CASE)
    private val variableXRegex = Regex("(?<![a-zA-Z])x(?![a-zA-Z])")
    private val mathSignalRegex = Regex(".*[0-9+\\-*/^=()\\\\]|.*\\b(?:sin|cos|tan|sqrt|ln|log|int|sum|lim)\\b.*", RegexOption.IGNORE_CASE)

    fun normalizeRecognizedMath(raw: String): String {
        var s = raw.trim()
        while (s.endsWith("=")) {
            s = s.dropLast(1).trim()
        }
        if (s.isEmpty()) return ""
        s = s.replace('×', '*')
            .replace('·', '*')
            .replace('∗', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace('–', '-')
            .replace('—', '-')
            .replace("²", "^2")
            .replace("³", "^3")
            .replace("√", "sqrt")
            .replace("π", "pi")
        s = commaDecimalRegex.replace(s, "$1.$2")
        s = digitXDigitRegex.replace(s, "$1*$2")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun evaluateRecognizedExpression(
        rawText: String,
        aiAvailable: Boolean
    ): LiveMathSuggestion? {
        val cleaned = normalizeRecognizedMath(rawText)
        if (cleaned.isEmpty()) return null

        val hasExplicitFnPrefix = leadingAssignmentRegex.containsMatchIn(cleaned)
        val rhs = leadingAssignmentRegex.replaceFirst(cleaned, "").trim()
        if (rhs.isEmpty()) return null

        val hasVariableX = variableXRegex.containsMatchIn(rhs)
        if (hasVariableX || hasExplicitFnPrefix) {
            val compiled = runCatching { FunctionEvaluator.compile(rhs) }.getOrNull()
            if (compiled != null) {
                val hasFiniteSamples = listOf(-3.0, -1.0, 0.0, 1.0, 3.0).any { x ->
                    val y = compiled.evaluate(x)
                    y.isFinite()
                }
                if (hasFiniteSamples && hasVariableX) {
                    return LiveMathSuggestion.FunctionPlotOffer(
                        normalizedExpr = cleaned,
                        plotExpression = compiled.normalizedSource
                    )
                }
                if (!hasVariableX) {
                    val value = compiled.evaluate(0.0)
                    if (value.isFinite()) {
                        val formatted = formatNumericResult(value)
                        return LiveMathSuggestion.NumericResult(
                            normalizedExpr = rhs,
                            formattedResult = formatted,
                            latexResult = "${toBasicLatex(rhs)} = $formatted"
                        )
                    }
                }
            }
        } else {
            val compiled = runCatching { FunctionEvaluator.compile(rhs) }.getOrNull()
            if (compiled != null) {
                val value = compiled.evaluate(0.0)
                if (value.isFinite()) {
                    val formatted = formatNumericResult(value)
                    return LiveMathSuggestion.NumericResult(
                        normalizedExpr = rhs,
                        formattedResult = formatted,
                        latexResult = "${toBasicLatex(rhs)} = $formatted"
                    )
                }
            }
        }

        return if (aiAvailable && mathSignalRegex.matches(cleaned)) {
            LiveMathSuggestion.OfferAiSolve(rawPreview = cleaned)
        } else {
            null
        }
    }

    fun formatNumericResult(value: Double): String {
        if (!value.isFinite()) return "NaN"
        val cleanZero = if (abs(value) < 1e-10) 0.0 else value
        val nearestLong = cleanZero.roundToLong()
        if (abs(cleanZero - nearestLong.toDouble()) < 1e-9 && abs(cleanZero) < 1e14) {
            return nearestLong.toString()
        }
        val rounded = "%.4f".format(java.util.Locale.US, cleanZero)
        return rounded.trimEnd('0').trimEnd('.')
    }

    fun toBasicLatex(rawText: String): String {
        var s = rawText.trim()
        if (s.isEmpty()) return ""
        s = s.replace("<=", "\\le ")
            .replace(">=", "\\ge ")
            .replace("!=", "\\ne ")
            .replace("~=", "\\approx ")
            .replace("->", "\\to ")
            .replace("+-", "\\pm ")
            .replace("≤", "\\le ")
            .replace("≥", "\\ge ")
            .replace("≠", "\\ne ")
            .replace("≈", "\\approx ")
            .replace("→", "\\to ")
            .replace("±", "\\pm ")
            .replace("∞", "\\infty ")
            .replace("∫", "\\int ")
            .replace("∑", "\\sum ")
            .replace("×", "\\cdot ")
            .replace("·", "\\cdot ")
            .replace("*", "\\cdot ")
            .replace("÷", "\\div ")
            .replace("π", "\\pi ")
            .replace("√", "\\sqrt")
        s = Regex("(?<!\\\\)\\bsqrt\\s*\\(([^()]+)\\)").replace(s, "\\\\sqrt{$1}")
        s = Regex("(?<!\\\\)\\bsqrt\\s+([A-Za-z0-9]+)").replace(s, "\\\\sqrt{$1}")
        s = Regex("\\\\sqrt\\(([^()]+)\\)").replace(s, "\\\\sqrt{$1}")
        s = Regex("(?<!\\\\)\\b(alpha|beta|gamma|delta|theta|lambda|sigma|omega|phi|psi|pi)\\b")
            .replace(s, "\\\\$1 ")
        s = Regex("(?<!\\\\)\\b(Delta|Sigma|Omega|Gamma|Phi|Psi)\\b")
            .replace(s, "\\\\$1 ")
        s = Regex("(?<!\\\\)\\b(infty|inf)\\b").replace(s, "\\\\infty ")
        s = Regex("(?<!\\\\)\\b(sin|cos|tan|tg|cot|ctg|ln|log|lim|int|sum)\\b").replace(s, "\\\\$1")
        // Inline fractions without touching already-formed \frac{}{} or commands
        s = Regex("\\(([^()]+)\\)\\s*/\\s*\\(([^()]+)\\)").replace(s, "\\\\frac{$1}{$2}")
        s = Regex("(?<![\\\\a-zA-Z0-9])(\\d+(?:\\.\\d+)?|[A-Za-z](?:\\^\\{?[0-9A-Za-z+-]+\\}?)?)\\s*/\\s*(\\d+(?:\\.\\d+)?|[A-Za-z](?:\\^\\{?[0-9A-Za-z+-]+\\}?)?)(?![a-zA-Z0-9])")
            .replace(s, "\\\\frac{$1}{$2}")
        s = Regex("\\^\\(([^()]+)\\)").replace(s, "^{$1}")
        s = Regex("\\^([+-]?[0-9a-zA-Z]+)").replace(s, "^{$1}")
        s = Regex("_\\(([^()]+)\\)").replace(s, "_{$1}")
        s = Regex("_([0-9a-zA-Z]+)").replace(s, "_{$1}")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun extractPlotExpressionFromLatex(latexSource: String): String? {
        var s = latexSource.trim()
        if (s.isEmpty()) return null
        // Take RHS of f(x) = ... or y = ...
        val eqIdx = s.indexOf('=')
        if (eqIdx >= 0) {
            val lhs = s.substring(0, eqIdx).trim()
            val rhs = s.substring(eqIdx + 1).trim()
            val validFnLhs = lhs.matches(Regex("^(?:y|[fg]\\s*\\(\\s*x\\s*\\))$", RegexOption.IGNORE_CASE))
            s = if (validFnLhs || variableXRegex.containsMatchIn(rhs)) rhs else lhs
        }
        // Unwrap nested \frac{...}{...} and \sqrt{...} with balanced brace matching
        s = unwrapLatexFracAndSqrt(s)
        s = s.replace("\\left", "")
            .replace("\\right", "")
            .replace("\\cdot", "*")
            .replace("\\times", "*")
            .replace("\\div", "/")
            .replace("\\sin", "sin")
            .replace("\\cos", "cos")
            .replace("\\tan", "tan")
            .replace("\\tg", "tg")
            .replace("\\ln", "ln")
            .replace("\\log", "log")
            .replace("\\exp", "exp")
            .replace("\\pi", "pi")
            .replace("^{", "^(")
            .replace("{", "(")
            .replace("}", ")")
            .trim()

        if (!variableXRegex.containsMatchIn(s)) return null
        val compiled = runCatching { FunctionEvaluator.compile(s) }.getOrNull() ?: return null
        val anyFinite = listOf(-2.0, 0.0, 2.0).any { compiled.evaluate(it).isFinite() }
        return if (anyFinite) compiled.normalizedSource else null
    }

    private fun unwrapLatexFracAndSqrt(src: String): String {
        var current = src
        repeat(6) {
            var changed = false
            val fracIdx = current.indexOf("\\frac")
            if (fracIdx >= 0) {
                val (num, afterNum) = extractBraceGroup(current, fracIdx + 5) ?: (null to -1)
                if (num != null) {
                    val (den, afterDen) = extractBraceGroup(current, afterNum) ?: (null to -1)
                    if (den != null) {
                        current = current.substring(0, fracIdx) +
                            "(($num)/($den))" +
                            current.substring(afterDen)
                        changed = true
                    }
                }
            }
            val sqrtIdx = current.indexOf("\\sqrt")
            if (sqrtIdx >= 0) {
                val (rad, afterRad) = extractBraceGroup(current, sqrtIdx + 5) ?: (null to -1)
                if (rad != null) {
                    current = current.substring(0, sqrtIdx) +
                        "sqrt($rad)" +
                        current.substring(afterRad)
                    changed = true
                }
            }
            if (!changed) return current
        }
        return current
    }

    private fun extractBraceGroup(src: String, startIndex: Int): Pair<String, Int>? {
        var pos = startIndex
        while (pos < src.length && src[pos].isWhitespace()) pos++
        if (pos >= src.length || src[pos] != '{') return null
        var depth = 0
        val contentStart = pos + 1
        while (pos < src.length) {
            when (src[pos]) {
                '\\' -> pos += 2
                '{' -> {
                    depth++
                    pos++
                }
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return src.substring(contentStart, pos) to (pos + 1)
                    }
                    pos++
                }
                else -> pos++
            }
        }
        return null
    }
}

/**
 * Pure-Kotlin geometric fraction detector that splits handwritten strokes around a horizontal
 * division bar into prefix, numerator, denominator, and suffix groups for offline LaTeX OCR.
 */
object MathFractionLayoutAnalyzer {

    data class FractionSplitResult(
        val barStroke: PersistedStrokeRecord,
        val numeratorStrokes: List<PersistedStrokeRecord>,
        val denominatorStrokes: List<PersistedStrokeRecord>,
        val prefixStrokes: List<PersistedStrokeRecord>,
        val suffixStrokes: List<PersistedStrokeRecord>
    )

    fun detectTopLevelFraction(strokes: List<PersistedStrokeRecord>): FractionSplitResult? {
        val ink = strokes.filter { it.layer == StrokeLayerType.INK && it.samples.isNotEmpty() }
        if (ink.size < 3) return null

        val horizontalBars = ink.filter(::isHorizontalBarCandidate)
            .sortedByDescending { it.bounds.widthPt }

        for (bar in horizontalBars) {
            if (isLikelyPairedEqualsBar(bar, horizontalBars, ink)) continue
            val spanLeft = bar.bounds.leftPt - 6f
            val spanRight = bar.bounds.rightPt + 6f
            val barCy = bar.bounds.centerYPt

            val numerator = ArrayList<PersistedStrokeRecord>()
            val denominator = ArrayList<PersistedStrokeRecord>()
            val prefix = ArrayList<PersistedStrokeRecord>()
            val suffix = ArrayList<PersistedStrokeRecord>()

            for (s in ink) {
                if (s.id == bar.id) continue
                val cx = s.bounds.centerXPt
                val cy = s.bounds.centerYPt
                when {
                    cx < spanLeft -> prefix.add(s)
                    cx > spanRight -> suffix.add(s)
                    cy < barCy -> numerator.add(s)
                    else -> denominator.add(s)
                }
            }
            if (numerator.isNotEmpty() && denominator.isNotEmpty()) {
                return FractionSplitResult(
                    barStroke = bar,
                    numeratorStrokes = numerator,
                    denominatorStrokes = denominator,
                    prefixStrokes = prefix,
                    suffixStrokes = suffix
                )
            }
        }
        return null
    }

    private fun isHorizontalBarCandidate(record: PersistedStrokeRecord): Boolean {
        val b = record.bounds
        val w = b.widthPt
        if (w < 18f) return false
        if (b.heightPt > max(10f, w * 0.28f)) return false
        val first = record.samples.first()
        val last = record.samples.last()
        val dx = abs(last.xPt - first.xPt)
        if (dx < 14f) return false
        val dy = abs(last.yPt - first.yPt)
        return (dy / dx) <= 0.25f
    }

    private fun isLikelyPairedEqualsBar(
        bar: PersistedStrokeRecord,
        allBars: List<PersistedStrokeRecord>,
        allInk: List<PersistedStrokeRecord>
    ): Boolean {
        for (other in allBars) {
            if (other.id == bar.id) continue
            val gapY = abs(other.bounds.centerYPt - bar.bounds.centerYPt)
            if (gapY > 22f) continue
            val overlapL = max(bar.bounds.leftPt, other.bounds.leftPt)
            val overlapR = min(bar.bounds.rightPt, other.bounds.rightPt)
            val overlapW = (overlapR - overlapL).coerceAtLeast(0f)
            val minW = min(bar.bounds.widthPt, other.bounds.widthPt).coerceAtLeast(1f)
            val maxW = max(bar.bounds.widthPt, other.bounds.widthPt).coerceAtLeast(1f)
            if (overlapW >= minW * 0.5f && (minW / maxW) >= 0.55f) {
                val topY = min(bar.bounds.topPt, other.bounds.topPt)
                val botY = max(bar.bounds.bottomPt, other.bounds.bottomPt)
                val hasContentAbove = allInk.any {
                    it.id != bar.id && it.id != other.id &&
                        it.bounds.centerXPt in (overlapL - 6f)..(overlapR + 6f) &&
                        it.bounds.centerYPt < topY
                }
                val hasContentBelow = allInk.any {
                    it.id != bar.id && it.id != other.id &&
                        it.bounds.centerXPt in (overlapL - 6f)..(overlapR + 6f) &&
                        it.bounds.centerYPt > botY
                }
                if (!hasContentAbove && !hasContentBelow) return true
            }
        }
        return false
    }
}
