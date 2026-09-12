package com.questtick.ui.screens

/*
 * 账号编辑页的游戏选择标签页：勾选该账号参与签到的游戏列表。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.GameInfo
import com.questtick.data.Games
import com.questtick.sign.MysCoinCheckIn
import com.questtick.ui.components.AnimatedSwitch
import com.questtick.ui.components.GameIcon
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.TextSecondary

/** 账号编辑页的游戏选择展示组件。 */

private val mysGames = Games.ALL.filter { !it.cloud && !it.preview }
private val previewGames = Games.ALL.filter { it.preview }
private val cloudGames = Games.ALL.filter { it.cloud }

@Composable
internal fun GamesTab(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    mysCoinEnabled: Boolean,
    onMysCoinEnabledChange: (Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        if (MysCoinCheckIn.featureEnabled) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupLabel("米游社")
                    Spacer(Modifier.weight(1f))
                    Text("米游币打卡", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.size(8.dp))
                    AnimatedSwitch(checked = mysCoinEnabled, onCheckedChange = onMysCoinEnabledChange, offColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                }
            }
        }
        items(mysGames, key = { it.key }) { game ->
            GameSelectRow(game, selected.contains(game.key)) { onToggle(game.key) }
        }
        item {
            Spacer(Modifier.height(4.dp))
            GroupLabel("预支持游戏")
        }
        items(previewGames, key = { it.key }) { game -> GameSelectRow(game, checked = false, enabled = false) {} }
        item {
            Spacer(Modifier.height(4.dp))
            GroupLabel("云游戏")
        }
        items(cloudGames, key = { it.key }) { game ->
            GameSelectRow(game, selected.contains(game.key)) { onToggle(game.key) }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** 米游币移动端接口使用 SToken，并要求 v2 SToken 配套 mid、旧版 SToken 配套 stuid。 */
internal fun hasMysCoinCredential(
    cookie: String,
    savedStoken: String,
    savedMid: String,
    savedUid: String,
): Boolean {
    fun field(name: String): String =
        Regex("(?:^|;)\\s*${Regex.escape(name)}\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(cookie)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    val stoken = field("stoken_v2").ifBlank { field("stoken") }.ifBlank { savedStoken.trim() }
    if (stoken.isBlank()) return false
    val v2 = stoken.startsWith("v2_", ignoreCase = true)
    val identity =
        if (v2) {
            field("mid").ifBlank { field("stmid") }.ifBlank { field("account_mid_v2") }.ifBlank { savedMid.trim() }
        } else {
            field("stuid").ifBlank { field("account_id") }.ifBlank { savedUid.trim() }
        }
    return identity.isNotBlank()
}

@Composable
private fun GameSelectRow(
    game: GameInfo,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val border = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val rowModifier = Modifier.fillMaxWidth().border(1.5.dp, border, RoundedCornerShape(18.dp)).then(if (enabled) Modifier.clickableNoRipple(onToggle) else Modifier)
    PanelCard(modifier = rowModifier) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            GameIcon(game, size = 48.dp, corner = 14.dp)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(game.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Text(game.reward, fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(Modifier.size(10.dp))
            if (enabled) CheckCircle(checked) else Text("待确认", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CheckCircle(checked: Boolean) {
    if (checked) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, TextSecondary.copy(alpha = 0.5f), CircleShape))
    }
}
