package hu.bdev.stylusnote.domain

import hu.bdev.stylusnote.core.ai.EqualsGestureDetector
import hu.bdev.stylusnote.core.ai.LiveMathSuggestion
import hu.bdev.stylusnote.core.ai.LiveMathSuggestionEngine
import hu.bdev.stylusnote.core.ai.SearchTextIndexer
import hu.bdev.stylusnote.core.code.CodeLanguage
import hu.bdev.stylusnote.core.code.CodeSyntaxHighlighter
import hu.bdev.stylusnote.core.code.CodeTokenType
import hu.bdev.stylusnote.core.hardware.StylusPointSample
import hu.bdev.stylusnote.core.math.DocPoint
import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.data.local.FormatMigrator
import hu.bdev.stylusnote.domain.model.BrushConfig
import hu.bdev.stylusnote.domain.model.BrushToolType
import hu.bdev.stylusnote.domain.model.ClozeTapePattern
import hu.bdev.stylusnote.domain.model.ObjectAttachmentGeometry
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.model.PersistedStrokeRecord
import hu.bdev.stylusnote.domain.model.StrokeLayerType
import hu.bdev.stylusnote.ui.canvas.buildClozeTapeBoundsFromDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiiPhaseValidationUnitTest {

    private fun makeInkStroke(
        id: String,
        zOrder: Long,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): PersistedStrokeRecord {
        val midY = (top + bottom) * 0.5f
        return PersistedStrokeRecord(
            id = id,
            layer = StrokeLayerType.INK,
            zOrder = zOrder,
            brushConfig = BrushConfig(toolType = BrushToolType.FOUNTAIN_PEN, sizePt = 2f),
            canonicalColorArgb = BrushConfig.DEFAULT_INK_COLOR_ARGB,
            sizePt = 2f,
            inkEncodedInputs = ByteArray(0),
            samples = listOf(
                StylusPointSample(left, top, zOrder * 100L, 0.5f, 0f, 0f, false),
                StylusPointSample((left + right) * 0.5f, midY, zOrder * 100L + 10L, 0.5f, 0f, 0f, false),
                StylusPointSample(right, bottom, zOrder * 100L + 20L, 0.5f, 0f, 0f, false)
            ),
            bounds = DocRect(left, top, right, bottom)
        )
    }

    @Test
    fun highlighterAutoSizeFollowsPenSizeAndClampsBounds() {
        assertEquals(9.75f, BrushConfig.computeRecommendedHighlighterSizePt(0.5f), 0.01f)
        assertEquals(11.5f, BrushConfig.computeRecommendedHighlighterSizePt(1.0f), 0.01f)
        assertEquals(15.0f, BrushConfig.computeRecommendedHighlighterSizePt(2.0f), 0.01f)
        assertEquals(22.0f, BrushConfig.computeRecommendedHighlighterSizePt(4.0f), 0.01f)
        assertEquals(36.0f, BrushConfig.computeRecommendedHighlighterSizePt(8.0f), 0.01f)
        assertEquals(9.75f, BrushConfig.computeRecommendedHighlighterSizePt(0.0f), 0.01f)
        assertEquals(36.0f, BrushConfig.computeRecommendedHighlighterSizePt(20.0f), 0.01f)
    }

    @Test
    fun formatMigratorMapsClozeTapeStableToolCodeAndBack() {
        assertEquals(8, FormatMigrator.toStableToolCode(BrushToolType.CLOZE_TAPE))
        val (resolved, usedFallback) = FormatMigrator.resolveBrushToolWithFallback(8)
        assertEquals(BrushToolType.CLOZE_TAPE, resolved)
        assertFalse(usedFallback)
        assertFalse(BrushToolType.CLOZE_TAPE.isDrawingBrush)
        assertTrue(BrushToolType.CLOZE_TAPE.isClozeTape)
    }

    @Test
    fun buildClozeTapeBoundsDistinguishesSwipeBandFromBoxDrag() {
        val swipe = buildClozeTapeBoundsFromDrag(
            startPt = DocPoint(100f, 200f),
            endPt = DocPoint(260f, 204f),
            defaultHeightPt = 18f
        )
        assertEquals(100f, swipe.leftPt, 0.1f)
        assertEquals(260f, swipe.rightPt, 0.1f)
        assertEquals(18f, swipe.heightPt, 0.1f)
        assertEquals(202f, swipe.centerYPt, 0.1f)

        val box = buildClozeTapeBoundsFromDrag(
            startPt = DocPoint(100f, 200f),
            endPt = DocPoint(240f, 270f),
            defaultHeightPt = 18f
        )
        assertEquals(100f, box.leftPt, 0.1f)
        assertEquals(200f, box.topPt, 0.1f)
        assertEquals(240f, box.rightPt, 0.1f)
        assertEquals(270f, box.bottomPt, 0.1f)
    }

    @Test
    fun codeSyntaxHighlighterTokenizesAllFourSupportedLanguages() {
        val sqlTokens = CodeSyntaxHighlighter.tokenizeDocument(
            "SELECT count(id) FROM users -- filter\nWHERE name = 'Anna' AND age >= 18",
            CodeLanguage.SQL
        )
        assertTrue(sqlTokens[0].any { it.text == "SELECT" && it.type == CodeTokenType.KEYWORD })
        assertTrue(sqlTokens[0].any { it.text == "count" && it.type == CodeTokenType.TYPE_OR_BUILTIN })
        assertTrue(sqlTokens[0].any { it.type == CodeTokenType.COMMENT && it.text.contains("--") })
        assertTrue(sqlTokens[1].any { it.type == CodeTokenType.STRING && it.text == "'Anna'" })
        assertTrue(sqlTokens[1].any { it.type == CodeTokenType.NUMBER && it.text == "18" })

        val pyTokens = CodeSyntaxHighlighter.tokenizeDocument(
            "def solve(x): # init\n    return int(x) + 42",
            CodeLanguage.PYTHON
        )
        assertTrue(pyTokens[0].any { it.text == "def" && it.type == CodeTokenType.KEYWORD })
        assertTrue(pyTokens[0].any { it.type == CodeTokenType.COMMENT })
        assertTrue(pyTokens[1].any { it.text == "int" && it.type == CodeTokenType.TYPE_OR_BUILTIN })

        val jsTokens = CodeSyntaxHighlighter.tokenizeDocument(
            "const total: number = 3.14; // pi",
            CodeLanguage.JS_TS
        )
        assertTrue(jsTokens[0].any { it.text == "const" && it.type == CodeTokenType.KEYWORD })
        assertTrue(jsTokens[0].any { it.text == "number" && it.type == CodeTokenType.TYPE_OR_BUILTIN })
        assertTrue(jsTokens[0].any { it.text == "3.14" && it.type == CodeTokenType.NUMBER })

        val csTokens = CodeSyntaxHighlighter.tokenizeDocument(
            "/* blk */\npublic async Task<string> Run() => \"ok\";",
            CodeLanguage.CSHARP
        )
        assertTrue(csTokens[0].any { it.type == CodeTokenType.COMMENT })
        assertTrue(csTokens[1].any { it.text == "public" && it.type == CodeTokenType.KEYWORD })
        assertTrue(csTokens[1].any { it.text == "Task" && it.type == CodeTokenType.TYPE_OR_BUILTIN })
        assertTrue(csTokens[1].any { it.text == "\"ok\"" && it.type == CodeTokenType.STRING })
    }

    @Test
    fun objectAttachmentGeometrySupportsUnderInkCodeBlocksAndSearchIndexing() {
        val codeBlock = PageObject.CodeBlockObject(
            id = "code-zone-1",
            zOrder = 20L,
            bounds = DocRect(50f, 100f, 310f, 240f),
            language = CodeLanguage.PYTHON,
            codeText = "for i in range(5): print(i)",
            title = "Ciklus példa"
        )
        assertTrue(ObjectAttachmentGeometry.isUnderInkAttachableObject(codeBlock))

        val insideArrow = listOf(DocPoint(120f, 150f), DocPoint(180f, 180f))
        val matched = ObjectAttachmentGeometry.findTopmostUnderInkObjectForStroke(insideArrow, listOf(codeBlock))
        assertEquals("code-zone-1", matched?.id)

        val outsideArrow = listOf(DocPoint(360f, 150f), DocPoint(420f, 150f))
        assertNull(ObjectAttachmentGeometry.findTopmostUnderInkObjectForStroke(outsideArrow, listOf(codeBlock)))

        val fragments = SearchTextIndexer.extractSearchableObjectFragments(
            listOf(
                codeBlock,
                PageObject.ClozeTapeObject(
                    id = "tape-1",
                    zOrder = 25L,
                    bounds = DocRect(50f, 260f, 190f, 280f),
                    patternStyle = ClozeTapePattern.SOLID,
                    label = ""
                )
            )
        )
        assertEquals(1, fragments.size)
        assertTrue(fragments[0].text.contains("Ciklus példa"))
        assertTrue(fragments[0].text.contains("range(5)"))
    }

    @Test
    fun equalsGestureDetectorFindsTrailingEqualsAndConnectedLineExpression() {
        val term1 = makeInkStroke("s1", 1L, left = 40f, top = 115f, right = 65f, bottom = 145f)
        val plus = makeInkStroke("s2", 2L, left = 72f, top = 120f, right = 90f, bottom = 140f)
        val term2 = makeInkStroke("s3", 3L, left = 96f, top = 115f, right = 115f, bottom = 145f)
        // Two horizontal strokes forming trailing '='
        val eqTop = PersistedStrokeRecord(
            id = "eq-top",
            layer = StrokeLayerType.INK,
            zOrder = 4L,
            brushConfig = BrushConfig(),
            canonicalColorArgb = BrushConfig.DEFAULT_INK_COLOR_ARGB,
            sizePt = 2f,
            inkEncodedInputs = ByteArray(0),
            samples = listOf(
                StylusPointSample(128f, 126f, 400L, 0.5f, 0f, 0f, false),
                StylusPointSample(150f, 127f, 415L, 0.5f, 0f, 0f, false)
            ),
            bounds = DocRect(128f, 125f, 150f, 128f)
        )
        val eqBot = PersistedStrokeRecord(
            id = "eq-bot",
            layer = StrokeLayerType.INK,
            zOrder = 5L,
            brushConfig = BrushConfig(),
            canonicalColorArgb = BrushConfig.DEFAULT_INK_COLOR_ARGB,
            sizePt = 2f,
            inkEncodedInputs = ByteArray(0),
            samples = listOf(
                StylusPointSample(127f, 134f, 500L, 0.5f, 0f, 0f, false),
                StylusPointSample(151f, 135f, 515L, 0.5f, 0f, 0f, false)
            ),
            bounds = DocRect(127f, 133f, 151f, 136f)
        )

        val ctx = EqualsGestureDetector.detectTrailingEquals(
            strokes = listOf(term1, plus, term2, eqTop, eqBot),
            pageId = "pg-1"
        )
        assertNotNull(ctx)
        assertEquals(setOf("eq-top", "eq-bot"), ctx!!.equalsStrokeIds)
        assertEquals(listOf("s1", "s2", "s3"), ctx.expressionStrokeIds)

        // Vertical stroke at the end must NOT trigger '=' detector
        val nonEq = makeInkStroke("non-eq", 6L, left = 165f, top = 110f, right = 170f, bottom = 150f)
        assertNull(
            EqualsGestureDetector.detectTrailingEquals(
                listOf(term1, plus, term2, eqTop, eqBot, nonEq),
                pageId = "pg-1"
            )
        )
    }

    @Test
    fun liveMathSuggestionEngineEvaluatesArithmeticPlotsFunctionsAndExtractsLatex() {
        val arith = LiveMathSuggestionEngine.evaluateRecognizedExpression("2 + 3 * 4 =", aiAvailable = true)
        assertTrue(arith is LiveMathSuggestion.NumericResult)
        assertEquals("14", (arith as LiveMathSuggestion.NumericResult).formattedResult)

        val trig = LiveMathSuggestionEngine.evaluateRecognizedExpression("sin(0) =", aiAvailable = false)
        assertTrue(trig is LiveMathSuggestion.NumericResult)
        assertEquals("0", (trig as LiveMathSuggestion.NumericResult).formattedResult)

        val fnOffer = LiveMathSuggestionEngine.evaluateRecognizedExpression("y = x^2 - 2*x =", aiAvailable = true)
        assertTrue(fnOffer is LiveMathSuggestion.FunctionPlotOffer)
        assertEquals("x^2 - 2*x", (fnOffer as LiveMathSuggestion.FunctionPlotOffer).plotExpression)

        val symbolicOffline = LiveMathSuggestionEngine.evaluateRecognizedExpression("\\int x dx", aiAvailable = false)
        assertNull(symbolicOffline)

        val symbolicOnline = LiveMathSuggestionEngine.evaluateRecognizedExpression("\\int x dx", aiAvailable = true)
        assertTrue(symbolicOnline is LiveMathSuggestion.OfferAiSolve)

        val extracted = LiveMathSuggestionEngine.extractPlotExpressionFromLatex("f(x) = \\frac{x^{2} + 1}{2}")
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("x"))

        assertNull(LiveMathSuggestionEngine.extractPlotExpressionFromLatex("\\frac{3}{4} + 5"))
    }
}
