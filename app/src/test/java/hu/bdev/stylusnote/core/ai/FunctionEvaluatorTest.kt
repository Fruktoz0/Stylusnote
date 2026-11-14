package hu.bdev.stylusnote.core.ai

import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.domain.model.PageObject
import hu.bdev.stylusnote.domain.repository.CanvasStateHolder
import hu.bdev.stylusnote.ui.canvas.LatexSyntaxValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class FunctionEvaluatorTest {

    @Test
    fun evaluatorHandlesOperatorPrecedenceImplicitMultiplicationFunctionsAndConstants() {
        val poly = FunctionEvaluator.compile("f(x) = 2x^2 + 3x - 5")
        assertEquals(2.0 * 9.0 + 9.0 - 5.0, poly.evaluate(3.0), 1e-6)

        // Unary minus binds looser than exponentiation: -x^2 at x=4 is -16
        assertEquals(-16.0, FunctionEvaluator.evaluateSingle("-x^2", 4.0), 1e-6)
        assertEquals(16.0, FunctionEvaluator.evaluateSingle("(-x)^2", 4.0), 1e-6)

        // Right-associative exponentiation: 2^3^2 == 2^9 == 512
        assertEquals(512.0, FunctionEvaluator.evaluateSingle("2^3^2", 0.0), 1e-6)

        // Implicit multiplication and constants
        val wave = FunctionEvaluator.compile("y = 2sin(pi*x/2) + (x + 1)(x - 1)")
        assertEquals(2.0 * 1.0 + (2.0 * 0.0), wave.evaluate(1.0), 1e-6)
        assertTrue(abs(FunctionEvaluator.evaluateSingle("ln(e^2) + sqrt(9)", 0.0) - 5.0) < 1e-6)

        try {
            FunctionEvaluator.compile("2x + unknownfunc(x)")
            fail("Expected FunctionParseException for unknown identifier")
        } catch (_: FunctionParseException) {
            // Expected
        }
    }

    @Test
    fun samplerSplitsDiscontinuitiesAndVerticalAsymptotesForReciprocalAndTangent() {
        val reciprocal = FunctionEvaluator.compile("1/x")
        val segments = FunctionEvaluator.sampleFunctionSegments(
            compiled = reciprocal,
            domainMinX = -5.0,
            domainMaxX = 5.0,
            rangeMinY = -4.0,
            rangeMaxY = 4.0
        )

        assertTrue("1/x over [-5, 5] must split into at least 2 disconnected branches", segments.size >= 2)
        val hasLeftBranch = segments.any { seg -> seg.points.all { it.x < 0.0 && it.y < 0.0 } }
        val hasRightBranch = segments.any { seg -> seg.points.all { it.x > 0.0 && it.y > 0.0 } }
        assertTrue("Expected purely negative left branch for 1/x", hasLeftBranch)
        assertTrue("Expected purely positive right branch for 1/x", hasRightBranch)

        // Ensure tan(x) across (-pi, pi) splits at vertical asymptotes -pi/2 and +pi/2
        val tangent = FunctionEvaluator.compile("tan(x)")
        val tanSegments = FunctionEvaluator.sampleFunctionSegments(
            compiled = tangent,
            domainMinX = -PI,
            domainMaxX = PI,
            rangeMinY = -4.0,
            rangeMaxY = 4.0
        )
        assertTrue("tan(x) over [-pi, pi] must have >= 3 separated branches", tanSegments.size >= 3)
    }

    @Test
    fun latexValidatorRejectsMalformedSyntaxAndCanvasStateHolderSupportsAtomicStrokeToLatexUndoRedo() {
        assertTrue(LatexSyntaxValidator.validate("$$\\frac{a + b}{c^2}$$").isValid)
        assertEquals("\\frac{a + b}{c^2}", LatexSyntaxValidator.validate("$$\\frac{a + b}{c^2}$$").normalizedLatex)

        assertFalse(LatexSyntaxValidator.validate("").isValid)
        assertFalse(LatexSyntaxValidator.validate("\\frac{a}").isValid)
        assertFalse(LatexSyntaxValidator.validate("\\begin{align} x = 1").isValid)
        assertFalse(LatexSyntaxValidator.validate("x + {2").isValid)

        val stateHolder = CanvasStateHolder()
        val latexObj = PageObject.LatexObject(
            id = "latex-1",
            zOrder = stateHolder.allocateObjectZOrder(),
            bounds = DocRect(100f, 100f, 220f, 150f),
            latexSource = "\\int_0^1 x^2 \\, dx = \\frac{1}{3}"
        )

        // Composite replacement (even with empty live Ink strokes on JVM, verify Layer-5 Undo/Redo works)
        stateHolder.addPageObject(latexObj)
        assertEquals(1, stateHolder.allPageObjects().size)
        assertNotNull(stateHolder.getPageObject("latex-1"))

        val updatedObj = latexObj.copy(fontSizePt = 28f)
        stateHolder.updatePageObject(updatedObj)
        assertEquals(28f, (stateHolder.getPageObject("latex-1") as PageObject.LatexObject).fontSizePt, 1e-4f)

        assertNotNull(stateHolder.undo())
        assertEquals(18f, (stateHolder.getPageObject("latex-1") as PageObject.LatexObject).fontSizePt, 1e-4f)

        assertNotNull(stateHolder.undo())
        assertNull(stateHolder.getPageObject("latex-1"))
        assertEquals(0, stateHolder.allPageObjects().size)

        assertNotNull(stateHolder.redo())
        assertEquals(1, stateHolder.allPageObjects().size)
    }
}

