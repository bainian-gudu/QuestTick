package com.questtick.ui.components

/*
 * 自定义开关：带过渡动画的开 / 关控件，用于替代原生 Switch 以统一各处设置项的视觉与点击区域。
 */
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.questtick.ui.theme.SuccessGreen

/** 带弹性滑块与平滑轨道过渡的自定义开关。 */
@Composable
fun AnimatedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onColor: Color = SuccessGreen,
    offColor: Color = Color(0xFF2C2747),
    disabledColor: Color = Color(0xFFB8C0CC),
    thumbColor: Color = Color.White,
) {
    val trackWidth = 46.dp
    val trackHeight = 26.dp
    val thumbSize = 20.dp
    val padding = 3.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - padding else padding,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "thumbOffset",
    )
    val trackColor by animateColorAsState(
        targetValue =
            when {
                !enabled -> disabledColor
                checked -> onColor
                else -> offColor
            },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "trackColor",
    )

    Box(
        modifier =
            modifier
                .size(width = trackWidth, height = trackHeight)
                .clip(CircleShape)
                .background(trackColor)
                .then(if (enabled) Modifier.clickableNoRipple { onCheckedChange(!checked) } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}
