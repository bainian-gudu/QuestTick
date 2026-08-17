package com.questtick.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.questtick.data.GameInfo
import com.questtick.i18n.localizedText

/** 可复用的圆角游戏图标。 */
@Composable
fun GameIcon(
    game: GameInfo,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    corner: Dp = 14.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(game.color),
    ) {
        Image(
            painter = painterResource(game.iconRes),
            contentDescription = localizedText(game.name),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
