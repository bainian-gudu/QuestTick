package com.questtick.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

/** 全应用相同用途交互共用的动画规范，避免页面自行硬编码时长和缓动。 */
object AppMotion {
    const val ScreenDurationMillis = 280
    const val ScreenFadeDurationMillis = 200
    const val ExpandDurationMillis = 220
    const val ExpandFadeDurationMillis = 180
    const val ArrowDurationMillis = ExpandDurationMillis

    fun screenEnter(): EnterTransition =
        slideInVertically(
            animationSpec = tween(ScreenDurationMillis, easing = FastOutSlowInEasing),
            initialOffsetY = { it },
        ) + fadeIn(tween(ScreenFadeDurationMillis))

    fun screenExit(): ExitTransition =
        slideOutVertically(
            animationSpec = tween(ScreenDurationMillis, easing = FastOutSlowInEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(ScreenFadeDurationMillis))

    fun expandEnter(): EnterTransition =
        expandVertically(animationSpec = tween(ExpandDurationMillis, easing = FastOutSlowInEasing)) +
            fadeIn(tween(ExpandFadeDurationMillis))

    fun expandExit(): ExitTransition =
        shrinkVertically(animationSpec = tween(ExpandDurationMillis, easing = FastOutSlowInEasing)) +
            fadeOut(tween(ExpandFadeDurationMillis))
}
