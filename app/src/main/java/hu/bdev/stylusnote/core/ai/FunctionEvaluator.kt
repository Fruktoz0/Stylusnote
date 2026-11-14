package hu.bdev.stylusnote.core.ai

import android.graphics.Path
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.domain.model.PageObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.cosh

class FunctionParseException(message: String) : IllegalArgumentException(message)

data class PlotPoint(
    val x: Double,
    val y: Double
)

data class PlotCurveSegment(
    val points: List<PlotPoint>
)

/**
 * Pure-Kotlin recursive-descent mathematical expression evaluator and adaptive curve sampler for
 * StylusNote's local vector function plotter (Section 6).
 */
object FunctionEvaluator {

    sealed interface ExprNode {
        fun eval(x: Double): Double
    }

    class CompiledExpression internal constructor(
        val normalizedSource: String,
        private val root: ExprNode
    ) {
        fun evaluate(x: Double): Double = root.eval(x)
    }

    private enum class TokenKind {
        NUMBER,
        VARIABLE_X,
        IDENT,
        PLUS,
        MINUS,
        STAR,
        SLASH,
        CARET,
        LPAREN,
        RPAREN,
        COMMA
    }

    private data class Token(
        val kind: TokenKind,
        val text: String,
        val numberValue: Double = 0.0
    )

    private data class ConstNode(val value: Double) : ExprNode {
        override fun eval(x: Double): Double = value
    }

    private object VarXNode : ExprNode {
        override fun eval(x: Double): Double = x
    }

    private data class UnaryNegNode(val inner: ExprNode) : ExprNode {
        override fun eval(x: Double): Double = -inner.eval(x)
    }

    private data class BinaryNode(
        val op: TokenKind,
        val left: ExprNode,
        val right: ExprNode
    ) : ExprNode {
        override fun eval(x: Double): Double {
            val a = left.eval(x)
            val b = right.eval(x)
            return when (op) {
                TokenKind.PLUS -> a + b
                TokenKind.MINUS -> a - b
                TokenKind.STAR -> a * b
                TokenKind.SLASH -> a / b
                TokenKind.CARET -> a.pow(b)
                else -> Double.NaN
            }
        }
    }

    private data class FunctionCallNode(
        val name: String,
        val args: List<ExprNode>
    ) : ExprNode {
        override fun eval(x: Double): Double {
            val a = args[0].eval(x)
            if (args.size == 1) {
                return when (name) {
                    "sin" -> sin(a)
                    "cos" -> cos(a)
                    "tan", "tg" -> tan(a)
                    "cot", "ctg" -> 1.0 / tan(a)
                    "sec" -> 1.0 / cos(a)
                    "csc" -> 1.0 / sin(a)
                    "sinh", "sh" -> sinh(a)
                    "cosh", "ch" -> cosh(a)
                    "tanh", "th" -> tanh(a)
                    "asin", "arcsin" -> asin(a)
                    "acos", "arccos" -> acos(a)
                    "atan", "arctan", "arctg" -> atan(a)
                    "sqrt" -> sqrt(a)
                    "cbrt" -> cbrt(a)
                    "abs" -> abs(a)
                    "ln" -> ln(a)
                    "log", "lg" -> log10(a)
                    "log2" -> kotlin.math.log2(a)
                    "exp" -> exp(a)
                    "floor" -> floor(a)
                    "ceil" -> ceil(a)
                    "round" -> round(a)
                    "sign", "sgn" -> sign(a)
                    else -> Double.NaN
                }
            }
            val b = args[1].eval(x)
            return when (name) {
                "min" -> min(a, b)
                "max" -> max(a, b)
                "pow" -> a.pow(b)
                else -> Double.NaN
            }
        }
    }

    fun compile(rawExpression: String): CompiledExpression {
        val cleaned = stripFunctionPrefix(rawExpression)
        if (cleaned.isEmpty()) {
            throw FunctionParseException("A kifejezés nem lehet üres.")
        }
        val rawTokens = tokenize(cleaned)
        val tokens = insertImplicitMultiplications(rawTokens)
        val parser = RecursiveDescentParser(tokens)
        val root = parser.parseFullExpression()
        return CompiledExpression(normalizedSource = cleaned, root = root)
    }

    fun evaluateSingle(rawExpression: String, x: Double): Double =
        compile(rawExpression).evaluate(x)

    /**
     * Samples [compiled] over `[domainMinX, domainMaxX]` with recursive refinement in steep regions
     * and clean segment breaks across `NaN` domains and vertical asymptotes (`1/x`, `tan(x)`).
     */
    fun sampleFunctionSegments(
        compiled: CompiledExpression,
        domainMinX: Double,
        domainMaxX: Double,
        rangeMinY: Double,
        rangeMaxY: Double,
        baseSamples: Int = DEFAULT_BASE_SAMPLES,
        maxSubdivisionDepth: Int = DEFAULT_MAX_SUBDIVISION_DEPTH
    ): List<PlotCurveSegment> {
        require(domainMaxX > domainMinX) { "Az értelmezési tartomány vége nagyobb kell legyen az elejénél." }
        require(rangeMaxY > rangeMinY) { "Az értékkészlet teteje nagyobb kell legyen az aljánál." }

        val steps = baseSamples.coerceIn(32, 1024)
        val ySpan = (rangeMaxY - rangeMinY).coerceAtLeast(1e-6)
        val yClampLow = rangeMinY - ySpan * 2.0
        val yClampHigh = rangeMaxY + ySpan * 2.0

        val rawSamples = ArrayList<PlotPoint>(steps + 1)
        val dx = (domainMaxX - domainMinX) / steps.toDouble()
        for (i in 0..steps) {
            val x = domainMinX + i * dx
            val y = compiled.evaluate(x)
            rawSamples.add(PlotPoint(x, y))
        }

        val refinedPoints = ArrayList<PlotPoint>(steps * 2)
        for (i in 0 until rawSamples.size - 1) {
            val p0 = rawSamples[i]
            val p1 = rawSamples[i + 1]
            refinedPoints.add(p0)
            if (shouldSubdivide(p0, p1, ySpan, yClampLow, yClampHigh)) {
                appendSubdivided(
                    compiled = compiled,
                    p0 = p0,
                    p1 = p1,
                    ySpan = ySpan,
                    yClampLow = yClampLow,
                    yClampHigh = yClampHigh,
                    depthLeft = maxSubdivisionDepth,
                    out = refinedPoints
                )
            }
        }
        refinedPoints.add(rawSamples.last())

        val segments = ArrayList<PlotCurveSegment>()
        var current = ArrayList<PlotPoint>()

        for (pt in refinedPoints) {
            val isVisibleFinite = pt.y.isFinite() && pt.y in yClampLow..yClampHigh
            if (!isVisibleFinite) {
                if (current.size >= 2) {
                    segments.add(PlotCurveSegment(current))
                }
                current = ArrayList()
                continue
            }

            val prev = current.lastOrNull()
            if (prev != null && isDiscontinuityJump(compiled, prev, pt, ySpan)) {
                if (current.size >= 2) {
                    segments.add(PlotCurveSegment(current))
                }
                current = ArrayList()
            }
            current.add(pt)
        }
        if (current.size >= 2) {
            segments.add(PlotCurveSegment(current))
        }
        return segments
    }

    /** Converts a [PageObject.FunctionPlotObject] into a vector [Path] mapped into [PageObject.FunctionPlotObject.bounds]. */
    fun buildPlotPathForBounds(plot: PageObject.FunctionPlotObject): Path {
        val path = Path()
        val compiled = runCatching { compile(plot.expression) }.getOrNull() ?: return path
        val rect: DocRect = plot.bounds
        val xSpan = max(1e-4, (plot.domainMaxX - plot.domainMinX).toDouble())
        val ySpan = max(1e-4, (plot.rangeMaxY - plot.rangeMinY).toDouble())

        val segments = runCatching {
            sampleFunctionSegments(
                compiled = compiled,
                domainMinX = plot.domainMinX.toDouble(),
                domainMaxX = plot.domainMaxX.toDouble(),
                rangeMinY = plot.rangeMinY.toDouble(),
                rangeMaxY = plot.rangeMaxY.toDouble()
            )
        }.getOrElse { return path }

        for (segment in segments) {
            if (segment.points.size < 2) continue
            for ((idx, pt) in segment.points.withIndex()) {
                val sx = rect.leftPt + (((pt.x - plot.domainMinX) / xSpan) * rect.widthPt).toFloat()
                val sy = rect.bottomPt - (((pt.y - plot.rangeMinY) / ySpan) * rect.heightPt).toFloat()
                if (idx == 0) {
                    path.moveTo(sx, sy)
                } else {
                    path.lineTo(sx, sy)
                }
            }
        }
        return path
    }

    private fun shouldSubdivide(
        p0: PlotPoint,
        p1: PlotPoint,
        ySpan: Double,
        yClampLow: Double,
        yClampHigh: Double
    ): Boolean {
        val f0 = p0.y.isFinite() && p0.y in yClampLow..yClampHigh
        val f1 = p1.y.isFinite() && p1.y in yClampLow..yClampHigh
        if (f0 != f1) return true
        if (!f0) return false
        return abs(p1.y - p0.y) > ySpan * 0.12
    }

    private fun appendSubdivided(
        compiled: CompiledExpression,
        p0: PlotPoint,
        p1: PlotPoint,
        ySpan: Double,
        yClampLow: Double,
        yClampHigh: Double,
        depthLeft: Int,
        out: MutableList<PlotPoint>
    ) {
        if (depthLeft <= 0) return
        val midX = (p0.x + p1.x) * 0.5
        val midY = compiled.evaluate(midX)
        val mid = PlotPoint(midX, midY)

        if (shouldSubdivide(p0, mid, ySpan, yClampLow, yClampHigh)) {
            appendSubdivided(compiled, p0, mid, ySpan, yClampLow, yClampHigh, depthLeft - 1, out)
        }
        out.add(mid)
        if (shouldSubdivide(mid, p1, ySpan, yClampLow, yClampHigh)) {
            appendSubdivided(compiled, mid, p1, ySpan, yClampLow, yClampHigh, depthLeft - 1, out)
        }
    }

    private fun isDiscontinuityJump(
        compiled: CompiledExpression,
        p0: PlotPoint,
        p1: PlotPoint,
        ySpan: Double
    ): Boolean {
        val jump = abs(p1.y - p0.y)
        if (jump <= ySpan * 0.65) return false

        // Sign flip across a steep interval (e.g. 1/x or tan(x)) where midpoint diverges even faster.
        if (p0.y * p1.y < 0.0 && jump > ySpan * 0.9) {
            return true
        }
        val midY = compiled.evaluate((p0.x + p1.x) * 0.5)
        if (!midY.isFinite()) return true
        val minY = min(p0.y, p1.y)
        val maxY = max(p0.y, p1.y)
        val midOutside = midY < minY - ySpan * 0.5 || midY > maxY + ySpan * 0.5
        return midOutside || jump > ySpan * 1.4
    }

    private fun stripFunctionPrefix(raw: String): String {
        val trimmed = raw.trim().replace('−', '-').replace('·', '*')
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx in 1 until trimmed.length - 1) {
            val lhs = trimmed.substring(0, eqIdx).trim()
            if (lhs.equals("y", ignoreCase = true) ||
                lhs.matches(Regex("""[a-zA-Z]\(\s*x\s*\)"""))
            ) {
                return trimmed.substring(eqIdx + 1).trim()
            }
        }
        return trimmed
    }

    private fun tokenize(src: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            when {
                ch.isWhitespace() -> i++
                ch == '+' -> { tokens.add(Token(TokenKind.PLUS, "+")); i++ }
                ch == '-' -> { tokens.add(Token(TokenKind.MINUS, "-")); i++ }
                ch == '*' -> { tokens.add(Token(TokenKind.STAR, "*")); i++ }
                ch == '/' -> { tokens.add(Token(TokenKind.SLASH, "/")); i++ }
                ch == '^' -> { tokens.add(Token(TokenKind.CARET, "^")); i++ }
                ch == '(' -> { tokens.add(Token(TokenKind.LPAREN, "(")); i++ }
                ch == ')' -> { tokens.add(Token(TokenKind.RPAREN, ")")); i++ }
                ch == ',' -> { tokens.add(Token(TokenKind.COMMA, ",")); i++ }
                ch == 'π' -> { tokens.add(Token(TokenKind.IDENT, "pi")); i++ }
                ch in '0'..'9' || ch == '.' -> {
                    val start = i
                    var seenDot = (ch == '.')
                    i++
                    while (i < src.length) {
                        val c = src[i]
                        if (c in '0'..'9') {
                            i++
                        } else if (c == '.' && !seenDot) {
                            seenDot = true
                            i++
                        } else {
                            break
                        }
                    }
                    // Optional scientific exponent notation (e.g., 1e-3, 2.5E4)
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                        val expSignOrDigit = i + 1
                        if (expSignOrDigit < src.length &&
                            (src[expSignOrDigit] in '0'..'9' ||
                                ((src[expSignOrDigit] == '+' || src[expSignOrDigit] == '-') &&
                                    expSignOrDigit + 1 < src.length && src[expSignOrDigit + 1] in '0'..'9'))
                        ) {
                            i = expSignOrDigit + 1
                            while (i < src.length && src[i] in '0'..'9') i++
                        }
                    }
                    val numStr = src.substring(start, i)
                    val numVal = numStr.toDoubleOrNull()
                        ?: throw FunctionParseException("Érvénytelen számformátum: '$numStr'")
                    tokens.add(Token(TokenKind.NUMBER, numStr, numVal))
                }
                ch.isLetter() || ch == '_' -> {
                    val start = i
                    i++
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) {
                        i++
                    }
                    val word = src.substring(start, i).lowercase()
                    if (word == "x") {
                        tokens.add(Token(TokenKind.VARIABLE_X, "x"))
                    } else {
                        tokens.add(Token(TokenKind.IDENT, word))
                    }
                }
                else -> throw FunctionParseException("Ismeretlen karakter a kifejezésben: '$ch'")
            }
        }
        return tokens
    }

    private fun insertImplicitMultiplications(raw: List<Token>): List<Token> {
        if (raw.size <= 1) return raw
        val out = ArrayList<Token>(raw.size + 4)
        for (idx in raw.indices) {
            val curr = raw[idx]
            if (idx > 0) {
                val prev = raw[idx - 1]
                val prevCanMultiply = prev.kind == TokenKind.NUMBER ||
                    prev.kind == TokenKind.VARIABLE_X ||
                    prev.kind == TokenKind.RPAREN ||
                    (prev.kind == TokenKind.IDENT && prev.text in KNOWN_CONSTANTS)

                val currCanFollowMultiply = curr.kind == TokenKind.VARIABLE_X ||
                    curr.kind == TokenKind.LPAREN ||
                    curr.kind == TokenKind.IDENT ||
                    (curr.kind == TokenKind.NUMBER && prev.kind == TokenKind.RPAREN)

                if (prevCanMultiply && currCanFollowMultiply) {
                    out.add(Token(TokenKind.STAR, "*"))
                }
            }
            out.add(curr)
        }
        return out
    }

    private class RecursiveDescentParser(private val tokens: List<Token>) {
        private var pos = 0

        fun parseFullExpression(): ExprNode {
            val expr = parseExpression()
            if (pos < tokens.size) {
                throw FunctionParseException("Váratlan token a kifejezésben: '${tokens[pos].text}'")
            }
            return expr
        }

        private fun parseExpression(): ExprNode {
            var left = parseTerm()
            while (pos < tokens.size) {
                val op = tokens[pos].kind
                if (op == TokenKind.PLUS || op == TokenKind.MINUS) {
                    pos++
                    val right = parseTerm()
                    left = BinaryNode(op, left, right)
                } else {
                    break
                }
            }
            return left
        }

        private fun parseTerm(): ExprNode {
            var left = parseUnary()
            while (pos < tokens.size) {
                val op = tokens[pos].kind
                if (op == TokenKind.STAR || op == TokenKind.SLASH) {
                    pos++
                    val right = parseUnary()
                    left = BinaryNode(op, left, right)
                } else {
                    break
                }
            }
            return left
        }

        private fun parseUnary(): ExprNode {
            if (pos < tokens.size) {
                val kind = tokens[pos].kind
                if (kind == TokenKind.PLUS) {
                    pos++
                    return parseUnary()
                }
                if (kind == TokenKind.MINUS) {
                    pos++
                    return UnaryNegNode(parseUnary())
                }
            }
            return parsePower()
        }

        private fun parsePower(): ExprNode {
            val base = parsePrimary()
            if (pos < tokens.size && tokens[pos].kind == TokenKind.CARET) {
                pos++
                // Right-associative: 2^3^2 == 2^(3^2), and a^-2 == a^(-2)
                val exponent = parseUnary()
                return BinaryNode(TokenKind.CARET, base, exponent)
            }
            return base
        }

        private fun parsePrimary(): ExprNode {
            if (pos >= tokens.size) {
                throw FunctionParseException("Váratlan kifejezés-vég.")
            }
            val tok = tokens[pos]
            return when (tok.kind) {
                TokenKind.NUMBER -> {
                    pos++
                    ConstNode(tok.numberValue)
                }
                TokenKind.VARIABLE_X -> {
                    pos++
                    VarXNode
                }
                TokenKind.LPAREN -> {
                    pos++
                    val inner = parseExpression()
                    if (pos >= tokens.size || tokens[pos].kind != TokenKind.RPAREN) {
                        throw FunctionParseException("Hiányzó záró zárójel ')'")
                    }
                    pos++
                    inner
                }
                TokenKind.IDENT -> {
                    pos++
                    if (tok.text in KNOWN_CONSTANTS &&
                        (pos >= tokens.size || tokens[pos].kind != TokenKind.LPAREN)
                    ) {
                        val value = if (tok.text == "pi") PI else kotlin.math.E
                        ConstNode(value)
                    } else {
                        if (pos >= tokens.size || tokens[pos].kind != TokenKind.LPAREN) {
                            throw FunctionParseException("Ismeretlen azonosító vagy hiányzó '(' a(z) '${tok.text}' után.")
                        }
                        pos++ // consume '('
                        val args = ArrayList<ExprNode>(2)
                        args.add(parseExpression())
                        while (pos < tokens.size && tokens[pos].kind == TokenKind.COMMA) {
                            pos++
                            args.add(parseExpression())
                        }
                        if (pos >= tokens.size || tokens[pos].kind != TokenKind.RPAREN) {
                            throw FunctionParseException("Hiányzó ')' a(z) '${tok.text}' függvényhívásban.")
                        }
                        pos++
                        validateFunctionArity(tok.text, args.size)
                        FunctionCallNode(tok.text, args)
                    }
                }
                else -> throw FunctionParseException("Váratlan token: '${tok.text}'")
            }
        }

        private fun validateFunctionArity(name: String, arity: Int) {
            when {
                name in UNARY_FUNCTIONS && arity == 1 -> Unit
                name in BINARY_FUNCTIONS && arity == 2 -> Unit
                name in UNARY_FUNCTIONS || name in BINARY_FUNCTIONS ->
                    throw FunctionParseException("Hibás argumentumszám a(z) '$name' függvényben ($arity).")
                else ->
                    throw FunctionParseException("Ismeretlen matematikai függvény: '$name'")
            }
        }
    }

    private const val DEFAULT_BASE_SAMPLES = 240
    private const val DEFAULT_MAX_SUBDIVISION_DEPTH = 4

    private val KNOWN_CONSTANTS = setOf("pi", "e")
    private val UNARY_FUNCTIONS = setOf(
        "sin", "cos", "tan", "tg", "cot", "ctg", "sec", "csc",
        "sinh", "sh", "cosh", "ch", "tanh", "th",
        "asin", "arcsin", "acos", "arccos", "atan", "arctan", "arctg",
        "sqrt", "cbrt", "abs", "ln", "log", "lg", "log2", "exp",
        "floor", "ceil", "round", "sign", "sgn"
    )
    private val BINARY_FUNCTIONS = setOf("min", "max", "pow")
}

