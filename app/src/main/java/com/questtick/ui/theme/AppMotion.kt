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
    const val SCREEN_DURATION_MILLIS = 280
    const val SCREEN_FADE_DURATION_MILLIS = 200
    const val EXPAND_DURATION_MILLIS = 220
    const val EXPAND_FADE_DURATION_MILLIS = 180
    const val ARROW_DURATION_MILLIS = EXPAND_DURATION_MILLIS

    fun screenEnter(): EnterTransition =
        slideInVertically(
            animationSpec = tween(SCREEN_DURATION_MILLIS, easing = FastOutSlowInEasing),
            initialOffsetY = { it },
        ) + fadeIn(tween(SCREEN_FADE_DURATION_MILLIS))

    fun screenExit(): ExitTransition =
        slideOutVertically(
            animationSpec = tween(SCREEN_DURATION_MILLIS, easing = FastOutSlowInEasing),
            targetOffsetY = { it },
        ) + fadeOut(tween(SCREEN_FADE_DURATION_MILLIS))

    fun expandEnter(): EnterTransition =
        expandVertically(animationSpec = tween(EXPAND_DURATION_MILLIS, easing = FastOutSlowInEasing)) +
            fadeIn(tween(EXPAND_FADE_DURATION_MILLIS))

    fun expandExit(): ExitTransition =
        shrinkVertically(animationSpec = tween(EXPAND_DURATION_MILLIS, easing = FastOutSlowInEasing)) +
            fadeOut(tween(EXPAND_FADE_DURATION_MILLIS))
}
