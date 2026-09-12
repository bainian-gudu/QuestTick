package com.questtick.ui.components.pager

// 横向分页器页码边界和切换状态的纯函数。

import kotlin.math.abs

fun coercePagerPage(
    page: Int,
    pageCount: Int,
): Int =
    when {
        pageCount <= 0 -> 0
        else -> page.coerceIn(0, pageCount - 1)
    }

fun pagerBeyondViewportCount(
    warmedPageCount: Int,
    pageCount: Int,
): Int {
    if (pageCount <= 1) return 0
    return minOf(warmedPageCount.coerceAtLeast(2), 2, pageCount) - 1
}

fun pagerAnimationStartPage(
    currentPage: Int,
    targetPage: Int,
    pageCount: Int,
): Int {
    val current = coercePagerPage(currentPage, pageCount)
    val target = coercePagerPage(targetPage, pageCount)
    if (abs(current - target) <= 1) return current
    return if (current < target) target - 1 else target + 1
}

fun shouldNotifySettledPage(
    currentPage: Int,
    selectedPage: Int,
    isScrollInProgress: Boolean,
    programmaticTargetPage: Int = -1,
): Boolean = !isScrollInProgress && programmaticTargetPage < 0 && currentPage != selectedPage

fun pagerVisualPosition(
    currentPage: Int,
    currentPageOffsetFraction: Float,
    pageCount: Int,
): Float {
    if (pageCount <= 1) return 0f
    return (currentPage + currentPageOffsetFraction).coerceIn(0f, (pageCount - 1).toFloat())
}

fun pagerSelectionFraction(
    page: Int,
    visualPosition: Float,
): Float = (1f - abs(page - visualPosition)).coerceIn(0f, 1f)

fun isPagerPageVisible(
    page: Int,
    selectedPage: Int,
    currentPage: Int,
    isScrollInProgress: Boolean,
): Boolean =
    if (isScrollInProgress) {
        abs(page - currentPage) <= 1
    } else {
        page == selectedPage || page == currentPage
    }
