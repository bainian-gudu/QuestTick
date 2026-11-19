package com.questtick.ui.components

/*
 * 签到运行的触发来源标签：区分用户手动签到与系统自动签到，记录页与首页「最近签到」共用。
 */

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.RunTrigger
import com.questtick.ui.theme.InfoBlue
import com.questtick.ui.theme.TextSecondary

/** 触发来源的展示文案；实际译文由本地化 Text 从词典解析。 */
internal fun runTriggerLabel(trigger: RunTrigger): String =
    when (trigger) {
        RunTrigger.MANUAL -> "手动"
        RunTrigger.SCHEDULED -> "自动"
        RunTrigger.RETRY -> "自动重试"
    }

/**
 * 记录卡片上的触发来源标签。
 *
 * 手动用主题色强调「人触发的」，定时用中性色，自动重试单独给一个可区分的蓝色。
 */
@Composable
internal fun RunTriggerTag(
    trigger: RunTrigger,
    modifier: Modifier = Modifier,
) {
    val color =
        when (trigger) {
            RunTrigger.MANUAL -> MaterialTheme.colorScheme.primary
            RunTrigger.SCHEDULED -> TextSecondary
            RunTrigger.RETRY -> InfoBlue
        }
    StatusBadge(
        text = runTriggerLabel(trigger),
        color = color,
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        horizontalPadding = 6.dp,
        verticalPadding = 2.dp,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        backgroundAlpha = 0.12f,
    )
}
