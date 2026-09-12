package com.questtick.ui.components

/*
 * Modifier 扩展：无涟漪点击等通用修饰，供需要自定义反馈的组件复用。
 */
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 无水波纹点击修饰符。
 *
 * 通过自定义 [MutableInteractionSource] 并传入 `indication = null`，保留可点击与交互状态，
 * 但不绘制默认 ripple，适合底部 Tab 与自定义卡片按钮。
 */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}
