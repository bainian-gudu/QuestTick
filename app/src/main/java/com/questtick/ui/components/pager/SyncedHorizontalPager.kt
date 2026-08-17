package com.questtick.ui.components.pager

/** 与外部选中状态同步的横向分页器组件。 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SyncedHorizontalPager(
    selectedPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    beyondViewportPageCount: Int = 1,
    onVisualPagePositionChange: (Float) -> Unit = {},
    onPageSettled: (Int) -> Unit,
    pageContent: @Composable (page: Int, pageVisible: Boolean) -> Unit,
) {
    val safePageCount = pageCount.coerceAtLeast(1)
    val safeSelectedPage = coercePagerPage(selectedPage, safePageCount)
    val safeBeyondViewportPageCount = beyondViewportPageCount.coerceIn(0, safePageCount - 1)
    val pagerState = rememberPagerState(initialPage = safeSelectedPage) { safePageCount }
    val currentSelectedPage by rememberUpdatedState(safeSelectedPage)
    val currentOnVisualPagePositionChange by rememberUpdatedState(onVisualPagePositionChange)
    val currentOnPageSettled by rememberUpdatedState(onPageSettled)
    var programmaticTargetPage by remember { mutableIntStateOf(-1) }

    LaunchedEffect(safeSelectedPage) {
        if (pagerState.currentPage != safeSelectedPage) {
            programmaticTargetPage = safeSelectedPage
            try {
                val animationStartPage = pagerAnimationStartPage(pagerState.currentPage, safeSelectedPage, safePageCount)
                if (animationStartPage != pagerState.currentPage) {
                    pagerState.scrollToPage(animationStartPage)
                }
                pagerState.animateScrollToPage(safeSelectedPage)
            } finally {
                programmaticTargetPage = -1
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow {
            pagerVisualPosition(
                currentPage = pagerState.currentPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                pageCount = safePageCount,
            )
        }.collect { position -> currentOnVisualPagePositionChange(position) }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (shouldNotifySettledPage(page, currentSelectedPage, scrolling, programmaticTargetPage)) {
                    currentOnPageSettled(page)
                }
            }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        beyondViewportPageCount = safeBeyondViewportPageCount,
    ) { page ->
        pageContent(
            page,
            isPagerPageVisible(
                page = page,
                selectedPage = currentSelectedPage,
                currentPage = pagerState.currentPage,
                isScrollInProgress = pagerState.isScrollInProgress,
            ),
        )
    }
}
