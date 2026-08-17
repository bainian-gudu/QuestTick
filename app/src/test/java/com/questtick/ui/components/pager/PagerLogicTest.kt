package com.questtick.ui.components.pager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerLogicTest {
    @Test
    fun coercePagerPageClampsToValidRange() {
        assertEquals(0, coercePagerPage(-1, 5))
        assertEquals(2, coercePagerPage(2, 5))
        assertEquals(4, coercePagerPage(9, 5))
        assertEquals(0, coercePagerPage(9, 1))
        assertEquals(0, coercePagerPage(9, 0))
    }

    @Test
    fun pagerBeyondViewportCountKeepsAtLeastAdjacentPageWhenPossible() {
        assertEquals(0, pagerBeyondViewportCount(warmedPageCount = 0, pageCount = 1))
        assertEquals(1, pagerBeyondViewportCount(warmedPageCount = 0, pageCount = 5))
        assertEquals(1, pagerBeyondViewportCount(warmedPageCount = 2, pageCount = 5))
        assertEquals(1, pagerBeyondViewportCount(warmedPageCount = 9, pageCount = 5))
    }


    @Test
    fun pagerAnimationStartPageKeepsLongJumpToSingleAnimatedStep() {
        assertEquals(0, pagerAnimationStartPage(currentPage = 0, targetPage = 1, pageCount = 5))
        assertEquals(3, pagerAnimationStartPage(currentPage = 0, targetPage = 4, pageCount = 5))
        assertEquals(1, pagerAnimationStartPage(currentPage = 4, targetPage = 0, pageCount = 5))
        assertEquals(0, pagerAnimationStartPage(currentPage = 0, targetPage = 9, pageCount = 1))
    }

    @Test
    fun pagerVisualPositionTracksOffsetContinuously() {
        assertEquals(0f, pagerVisualPosition(currentPage = 0, currentPageOffsetFraction = 0f, pageCount = 5), 0.0001f)
        assertEquals(0.25f, pagerVisualPosition(currentPage = 0, currentPageOffsetFraction = 0.25f, pageCount = 5), 0.0001f)
        assertEquals(0.6f, pagerVisualPosition(currentPage = 1, currentPageOffsetFraction = -0.4f, pageCount = 5), 0.0001f)
        assertEquals(4f, pagerVisualPosition(currentPage = 4, currentPageOffsetFraction = 0.5f, pageCount = 5), 0.0001f)
    }

    @Test
    fun pagerSelectionFractionFallsOffWithDistanceFromVisualPosition() {
        assertEquals(1f, pagerSelectionFraction(page = 1, visualPosition = 1f), 0.0001f)
        assertEquals(0.5f, pagerSelectionFraction(page = 1, visualPosition = 1.5f), 0.0001f)
        assertEquals(0f, pagerSelectionFraction(page = 1, visualPosition = 2.2f), 0.0001f)
    }

    @Test
    fun shouldNotifySettledPageSuppressesProgrammaticJumpIntermediatePage() {
        assertFalse(
            shouldNotifySettledPage(
                currentPage = 3,
                selectedPage = 4,
                isScrollInProgress = false,
                programmaticTargetPage = 4,
            ),
        )
    }

    @Test
    fun shouldNotifySettledPageOnlyAfterScrollSettlesOnDifferentPage() {
        assertFalse(shouldNotifySettledPage(currentPage = 1, selectedPage = 0, isScrollInProgress = true))
        assertFalse(shouldNotifySettledPage(currentPage = 0, selectedPage = 0, isScrollInProgress = false))
        assertTrue(shouldNotifySettledPage(currentPage = 1, selectedPage = 0, isScrollInProgress = false))
    }

    @Test
    fun isPagerPageVisibleKeepsCurrentAndAdjacentPagesVisibleWhileScrolling() {
        assertFalse(
            isPagerPageVisible(page = 0, selectedPage = 2, currentPage = 2, isScrollInProgress = true),
        )
        assertTrue(
            isPagerPageVisible(page = 1, selectedPage = 2, currentPage = 2, isScrollInProgress = true),
        )
        assertTrue(
            isPagerPageVisible(page = 2, selectedPage = 2, currentPage = 2, isScrollInProgress = true),
        )
        assertTrue(
            isPagerPageVisible(page = 3, selectedPage = 2, currentPage = 2, isScrollInProgress = true),
        )
        assertFalse(
            isPagerPageVisible(page = 4, selectedPage = 2, currentPage = 2, isScrollInProgress = true),
        )
    }

    @Test
    fun isPagerPageVisibleKeepsSelectedAndCurrentPagesVisibleAfterScrollStops() {
        assertTrue(
            isPagerPageVisible(page = 0, selectedPage = 0, currentPage = 1, isScrollInProgress = false),
        )
        assertTrue(
            isPagerPageVisible(page = 1, selectedPage = 0, currentPage = 1, isScrollInProgress = false),
        )
        assertFalse(
            isPagerPageVisible(page = 2, selectedPage = 0, currentPage = 1, isScrollInProgress = false),
        )
    }
}
