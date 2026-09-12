package com.questtick.ui.components

/*
 * 游戏宫格单元：图标 + 名称的单个游戏入口，选中态由外层选择行提供，本组件本身无状态。
 */
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.GameInfo

/** 圆角游戏图标卡片与名称标签。 */
@Composable
fun GameTile(
    game: GameInfo,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GameIcon(game, size = 58.dp, corner = 18.dp)
        Spacer(Modifier.height(7.dp))
        Text(
            game.name,
            fontSize = 11.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
