package hu.bdev.stylusnote.ui.shelf

import hu.bdev.stylusnote.core.math.DocRect
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedPageScrollNavigationTest {

    private val a4HeightPt = 842f

    @Test
    fun centeredAtDefaultZoomReturnsNone() {
        val action = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, -29f, 595f, 871f),
            centerDocY = 421f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 0,
            totalPages = 3
        )
        assertEquals(FixedPageScrollNavigationAction.NONE, action)
    }

    @Test
    fun zoomedOut65PercentCenteredReturnsNone() {
        val action = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, -271f, 900f, 1113f),
            centerDocY = 421f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 1,
            totalPages = 3
        )
        assertEquals(FixedPageScrollNavigationAction.NONE, action)
    }

    @Test
    fun lightPeekPastBottomReturnsNoneUntilRevealThresholdMet() {
        val action = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, 52f, 595f, 952f),
            centerDocY = 502f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 0,
            totalPages = 3
        )
        assertEquals(FixedPageScrollNavigationAction.NONE, action)
    }

    @Test
    fun moderateScrollDownWithFollowingPageReturnsNextPage() {
        val action = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, 202f, 595f, 1102f),
            centerDocY = 652f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 0,
            totalPages = 3
        )
        assertEquals(FixedPageScrollNavigationAction.NEXT_PAGE, action)
    }

    @Test
    fun zoomedInBottomReadingStaysOnPageThenAdvancesPastThreshold() {
        val stillReadingBottom = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, 432f, 400f, 882f),
            centerDocY = 657f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 0,
            totalPages = 2
        )
        assertEquals(FixedPageScrollNavigationAction.NONE, stillReadingBottom)

        val pulledIntoNextSheet = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, 532f, 400f, 982f),
            centerDocY = 757f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 0,
            totalPages = 2
        )
        assertEquals(FixedPageScrollNavigationAction.NEXT_PAGE, pulledIntoNextSheet)
    }

    @Test
    fun lastPageRequiresDeeperOverscrollToCreateNewPage() {
        val moderateOverscroll = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, 202f, 595f, 1102f),
            centerDocY = 652f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 1,
            totalPages = 2
        )
        assertEquals(FixedPageScrollNavigationAction.NONE, moderateOverscroll)

        val deepOverscroll = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, 302f, 595f, 1202f),
            centerDocY = 752f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 1,
            totalPages = 2
        )
        assertEquals(FixedPageScrollNavigationAction.CREATE_NEXT_PAGE, deepOverscroll)
    }

    @Test
    fun scrollUpReturnsPreviousPageOnlyAfterFirstPage() {
        val onSecondPage = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, -260f, 595f, 640f),
            centerDocY = 190f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 1,
            totalPages = 2
        )
        assertEquals(FixedPageScrollNavigationAction.PREVIOUS_PAGE, onSecondPage)

        val onFirstPage = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, -260f, 595f, 640f),
            centerDocY = 190f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = 0,
            totalPages = 2
        )
        assertEquals(FixedPageScrollNavigationAction.NONE, onFirstPage)
    }

    @Test
    fun unresolvedPageIndexOrZeroHeightReturnsNone() {
        val missingIdx = resolveFixedPageScrollNavigation(
            visibleRect = DocRect(0f, 302f, 595f, 1202f),
            centerDocY = 752f,
            pageHeightPt = a4HeightPt,
            currentPageIndex = -1,
            totalPages = 0
        )
        assertEquals(FixedPageScrollNavigationAction.NONE, missingIdx)
    }
}
