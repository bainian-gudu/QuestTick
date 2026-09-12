package com.questtick.ui.components.home

/*
 * 运行结果行：展示单个任务的图标、游戏名、状态与奖励，是运行结果列表的最小单元。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.FailureCategory
import com.questtick.data.Games
import com.questtick.data.TaskResult
import com.questtick.R
import com.questtick.ui.components.CachedRewardIcon
import com.questtick.ui.components.GameIcon
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber

@Composable
fun ResultRow(
    r: TaskResult,
    loadRewardImage: Boolean,
) {
    val game = remember(r.gameKey) { Games.byKey(r.gameKey) }
    val (icon, tint) =
        remember(r.skipped, r.success, r.alreadySigned, r.failureCategory) {
            when {
                r.failureCategory == FailureCategory.RESULT_UNKNOWN -> Icons.Filled.ErrorOutline to WarnAmber
                r.skipped -> Icons.Filled.SkipNext to TextSecondary
                r.success && r.alreadySigned -> Icons.Filled.DoneAll to WarnAmber
                r.success -> Icons.Filled.CheckCircle to SuccessGreen
                else -> Icons.Filled.ErrorOutline to DangerRed
            }
        }

    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (game != null) {
                GameIcon(game, size = 30.dp, corner = 9.dp)
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${r.accountLabel} · ${r.game}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(r.message, fontSize = 11.sp, color = TextSecondary)
            }
            Spacer(Modifier.size(8.dp))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        if (r.rewardName.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier =
                    Modifier
                        .padding(start = if (game != null) 40.dp else 0.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loadRewardImage && r.gameKey == "MysCoin") {
                    Image(
                        painter = painterResource(R.drawable.mys_coin),
                        contentDescription = r.rewardName,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                } else if (loadRewardImage && r.rewardIcon.isNotBlank()) {
                    CachedRewardIcon(
                        url = r.rewardIcon,
                        contentDescription = r.rewardName,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                } else {
                    Icon(
                        Icons.Filled.CardGiftcard,
                        contentDescription = r.rewardName,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Column {
                    Text(
                        "${r.rewardName} ×${r.rewardCount}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (r.totalSignDay > 0) {
                        Text("累计签到 ${r.totalSignDay} 天", fontSize = 10.sp, color = TextSecondary)
                    }
                }
            }
        }
        if (r.coinBalance >= 0 || r.coinGained >= 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (r.coinBalance >= 0) append("打卡后米游币：${r.coinBalance}")
                    if (r.coinGained >= 0) {
                        if (isNotEmpty()) append(" · ")
                        append("本次获取：${r.coinGained}")
                    }
                },
                modifier = Modifier.padding(start = if (game != null) 40.dp else 0.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
