package com.questtick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.sign.MysCoinCheckIn
import com.questtick.ui.components.AnimatedSwitch
import com.questtick.ui.components.PanelCard
import com.questtick.ui.theme.TextSecondary

/**
 * 米游币打卡社区多选卡片。
 *
 * 每个社区独立打卡、独立结算米游币；界面不允许取消最后一个，避免开启功能后任务什么都不做。
 */
@Composable
internal fun MysCoinForumsCard(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    PanelCard {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "打卡社区",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("多个社区会依次打卡，每天各自结算", fontSize = 12.sp, color = TextSecondary)
            MysCoinCheckIn.FORUMS.forEach { forum ->
                // 只有开关本身响应点击：整行再绑一次 onToggle 会让一次点击走到两条路径。
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        forum.name,
                        Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    AnimatedSwitch(
                        checked = forum.id in selected,
                        onCheckedChange = { onToggle(forum.id) },
                        offColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}
