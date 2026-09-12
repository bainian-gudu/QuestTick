package com.questtick.ui.components

// 应用内状态标签的统一视觉组件。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 3.dp,
    fontSize: TextUnit = 11.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    backgroundAlpha: Float = 0.16f,
    borderAlpha: Float = 0f,
    selected: Boolean = false,
    selectedContentColor: Color = Color.White,
    selectedBackgroundAlpha: Float = 1f,
    selectedBorderAlpha: Float = 0f,
    maxLines: Int = 1,
    leadingDotColor: Color? = null,
    leadingDotSize: Dp = 6.dp,
    leadingDotSpacing: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
) {
    val effectiveBackgroundAlpha = if (selected) selectedBackgroundAlpha else backgroundAlpha
    val effectiveBorderAlpha = if (selected) selectedBorderAlpha else borderAlpha
    val contentColor = if (selected) selectedContentColor else color
    val shaped =
        modifier
            .clip(shape)
            .background(color.copy(alpha = effectiveBackgroundAlpha))
    val bordered =
        if (effectiveBorderAlpha > 0f) {
            shaped.border(1.dp, color.copy(alpha = effectiveBorderAlpha), shape)
        } else {
            shaped
        }
    val clickable =
        if (onClick != null) {
            bordered.clickableNoRipple(onClick)
        } else {
            bordered
        }

    Box(
        clickable.padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingDotColor != null) {
                Box(
                    Modifier
                        .size(leadingDotSize)
                        .clip(CircleShape)
                        .background(leadingDotColor),
                )
                Spacer(Modifier.size(leadingDotSpacing))
            }
            Text(text, fontSize = fontSize, color = contentColor, fontWeight = fontWeight, maxLines = maxLines)
        }
    }
}
