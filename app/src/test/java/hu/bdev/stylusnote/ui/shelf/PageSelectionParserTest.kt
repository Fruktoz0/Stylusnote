package hu.bdev.stylusnote.ui.shelf

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSelectionParserTest {

    @Test
    fun parsesSinglePagesAndRangesIntoSortedZeroBasedIndices() {
        val result = parsePageSelectionSpec(spec = "1-3, 5, 8-10", totalPages = 12)
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), result)
    }

    @Test
    fun deduplicatesOverlappingRangesAndSorts() {
        val result = parsePageSelectionSpec(spec = "5, 2-4, 3, 1-2", totalPages = 6)
        assertEquals(listOf(0, 1, 2, 3, 4), result)
    }

    @Test
    fun ignoresOutOfRangeReversedAndNonNumericTokens() {
        val result = parsePageSelectionSpec(spec = "0, 1, abc, 6-2, 4, 99", totalPages = 5)
        assertEquals(listOf(0, 3), result)
    }

    @Test
    fun returnsEmptyListForBlankOrZeroTotalPages() {
        assertEquals(emptyList<Int>(), parsePageSelectionSpec(spec = "  ", totalPages = 5))
        assertEquals(emptyList<Int>(), parsePageSelectionSpec(spec = "1-3", totalPages = 0))
    }
}
