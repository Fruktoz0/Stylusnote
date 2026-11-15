package hu.bdev.stylusnote.core.ai

import hu.bdev.stylusnote.core.math.DocRect
import hu.bdev.stylusnote.domain.model.PageObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InNotebookSearchEngineTest {

    @Test
    fun accentInsensitiveWordSearchClampsHighlightDocRectOntoMatchedWordStrokes() {
        val fragment = IndexedSearchFragment(
            text = "Másodfokú egyenlet megoldása",
            docRect = DocRect(50f, 100f, 450f, 132f),
            source = SearchMatchSource.HANDWRITING_OCR,
            strokeBoxes = listOf(
                DocRect(52f, 104f, 162f, 128f),  // "Másodfokú"
                DocRect(182f, 104f, 282f, 129f), // "egyenlet"
                DocRect(305f, 103f, 442f, 130f)  // "megoldása"
            )
        )

        val eqHits = SearchTextIndexer.matchPageFragments(
            pageId = "page-1",
            pageIndexOneBased = 1,
            pageTitle = "1. oldal",
            fragments = listOf(fragment),
            rawQuery = "egyenlet"
        )
        assertEquals(1, eqHits.size)
        val eqRect = eqHits.single().highlightDocRect
        assertNotNull(eqRect)
        assertTrue("Expected left >= 170, got ${eqRect!!.leftPt}", eqRect.leftPt >= 170f)
        assertTrue("Expected right <= 295, got ${eqRect.rightPt}", eqRect.rightPt <= 295f)

        val accentHits = SearchTextIndexer.matchPageFragments(
            pageId = "page-1",
            pageIndexOneBased = 1,
            pageTitle = "1. oldal",
            fragments = listOf(fragment),
            rawQuery = "masodfoku"
        )
        assertEquals(1, accentHits.size)
        val accentRect = accentHits.single().highlightDocRect!!
        assertTrue(accentRect.leftPt <= 55f)
        assertTrue(accentRect.rightPt <= 175f)
    }

    @Test
    fun extractsAndMatchesFormattedTextLatexDerivationCardAndPageTitleWithBandBounds() {
        val objects = listOf(
            PageObject.FormattedTextObject(
                id = "fmt-1",
                zOrder = 10L,
                bounds = DocRect(40f, 80f, 400f, 160f), // height 80 -> line 1: 80..120, line 2: 120..160
                text = "Bevezetés\nMásodik sor tükörfúrógép"
            ),
            PageObject.LatexObject(
                id = "latex-1",
                zOrder = 11L,
                bounds = DocRect(60f, 200f, 240f, 260f),
                latexSource = "\\frac{mv^2}{2}"
            ),
            PageObject.DerivationCardObject(
                id = "deriv-1",
                zOrder = 12L,
                bounds = DocRect(60f, 300f, 420f, 420f),
                title = "Energiamegmaradás tétele",
                stepsMarkdownLatex = "T + V = állandó"
            )
        )

        val fragments = SearchTextIndexer.extractSearchableObjectFragments(
            objects = objects,
            pageTitle = "Fizika ősz 2026"
        )

        val titleHit = SearchTextIndexer.matchPageFragments("p1", 1, "Fizika ősz 2026", fragments, "fizika")
        assertEquals(SearchMatchSource.PAGE_TITLE, titleHit.single().source)

        val formattedHit = SearchTextIndexer.matchPageFragments("p1", 1, "p1", fragments, "tukorfurogep")
        assertEquals(1, formattedHit.size)
        assertEquals(SearchMatchSource.FORMATTED_TEXT, formattedHit.single().source)
        assertTrue(
            "Second line hit topPt should lie in bottom band (~120pt)",
            formattedHit.single().highlightDocRect!!.topPt >= 115f
        )

        val latexHit = SearchTextIndexer.matchPageFragments("p1", 1, "p1", fragments, "mv^2")
        assertEquals(SearchMatchSource.LATEX_FORMULA, latexHit.single().source)

        val derivHit = SearchTextIndexer.matchPageFragments("p1", 1, "p1", fragments, "energiamegmaradas")
        assertEquals(SearchMatchSource.DERIVATION_CARD, derivHit.single().source)
    }

    @Test
    fun combinePageIndexTextMergesOcrAndVectorObjectsWithoutDuplicates() {
        val objects = listOf(
            PageObject.FormattedTextObject(
                id = "fmt-dup",
                zOrder = 1L,
                bounds = DocRect(0f, 0f, 100f, 30f),
                text = "Közös sor"
            )
        )
        val combined = SearchTextIndexer.combinePageIndexText(
            ocrLines = listOf("Közös sor", "Kézzel írt szó"),
            objects = objects,
            pageTitle = "Oldalcím"
        )
        assertEquals("Közös sor\nKézzel írt szó\nOldalcím", combined)
    }
}
