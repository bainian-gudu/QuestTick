package com.questtick.ui.components.home

/** 首页账号卡片，展示账号状态、游戏和米游币打卡标识。 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.Account
import com.questtick.data.Games
import com.questtick.ui.components.AnimatedSwitch
import com.questtick.ui.components.AccountTagChip
import com.questtick.ui.components.GameIcon
import com.questtick.ui.components.PanelCard
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary

@Composable
fun HomeAccountCard(
    account: Account,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeGames =
        remember(account) {
            Games.ALL.filter { game ->
                account.isGameSelected(game.key) &&
                    when {
                        game.cloud && game.key == "CloudYS" -> account.genshinToken.isNotBlank() || account.hasGenshinCloudKeepLogin
                        game.cloud && game.key == "CloudSR" -> account.starrailToken.isNotBlank() || account.hasStarrailCloudKeepLogin
                        else -> account.mysCookie.isNotBlank() || account.hasMysKeepLogin
                    }
            }
        }

    PanelCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.label,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (account.hasMysKeepLogin) {
                        Spacer(Modifier.size(8.dp))
                        AccountTagChip("米游社扫码✓", color = SuccessGreen)
                    }
                    if (account.hasGenshinCloudKeepLogin) {
                        Spacer(Modifier.size(8.dp))
                        AccountTagChip("云原神扫码✓", color = SuccessGreen)
                    }
                    if (account.hasStarrailCloudKeepLogin) {
                        Spacer(Modifier.size(8.dp))
                        AccountTagChip("云崩铁扫码✓", color = SuccessGreen)
                    }
                    if (com.questtick.sign.MysCoinCheckIn.featureEnabled && account.mysCoinEnabled) {
                        Spacer(Modifier.size(8.dp))
                        AccountTagChip("米游币", color = Color(0xFFE0A22B))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activeGames.isEmpty()) {
                        AccountTagChip("未配置")
                    } else {
                        activeGames.take(6).forEach { game ->
                            GameIcon(game, size = 22.dp, corner = 7.dp)
                        }
                        if (activeGames.size > 6) {
                            AccountTagChip("+${activeGames.size - 6}", color = TextSecondary)
                        }
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AnimatedSwitch(
                    checked = account.enabled,
                    onCheckedChange = onToggle,
                    offColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                )
                Text(
                    if (account.enabled) "已启用" else "已关闭",
                    fontSize = 11.sp,
                    color = if (account.enabled) SuccessGreen else TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
