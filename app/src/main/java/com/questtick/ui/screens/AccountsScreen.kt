package com.questtick.ui.screens

import com.questtick.i18n.localizedText
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.data.Account
import com.questtick.data.AccountRecoveryIssue
import com.questtick.data.Games
import com.questtick.repository.base.RepositoryLoadState
import com.questtick.repository.run.ExecutionStateRepository
import com.questtick.ui.components.AppDialogActionStyle
import com.questtick.ui.components.AppMessageDialog
import com.questtick.ui.components.AccountTagChip
import com.questtick.ui.components.EmptyState
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.GameIcon
import com.questtick.ui.components.PageTitle
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.SkeletonCard
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.vm.AccountsViewModel

@Composable
fun AccountsScreen(
    onStartEdit: (Account, Boolean) -> Unit,
    isVisible: Boolean = true,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val recoveryIssues by viewModel.recoveryIssues.collectAsStateWithLifecycle()
    val executionGuards by viewModel.executionGuards.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var deleting by remember { mutableStateOf<Account?>(null) }
    var deletingRecoveryIssue by remember { mutableStateOf<AccountRecoveryIssue?>(null) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            viewModel.refresh()
        } else {
            deleting = null
            deletingRecoveryIssue = null
            listState.scrollToItem(0)
        }
    }

    GalaxyBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "spacer_top") { Spacer(Modifier.height(8.dp)) }
                item(key = "page_title") {
                    PageTitle("账号管理", "可用 ${accounts.size} 个，需处理 ${recoveryIssues.size} 个")
                }
                item(key = "add_button") {
                    Button(
                        onClick = { onStartEdit(viewModel.newAccountTemplate(), true) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("添加账号", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                when {
                    loadState is RepositoryLoadState.Error && accounts.isEmpty() && recoveryIssues.isEmpty() -> {
                        item(key = "load_error") {
                            EmptyState(
                                badge = {
                                    Box(
                                        Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Filled.PersonOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    }
                                },
                                title = "账号读取失败",
                                subtitle = "本地账号暂时无法读取，请重试",
                                action = { Button(onClick = viewModel::refresh) { Text("重新加载") } },
                            )
                        }
                    }
                    loadState is RepositoryLoadState.InitialLoading -> {
                        item(key = "skeleton_1") {
                            SkeletonCard(modifier = Modifier.fillMaxWidth(), titleWidth = 90.dp, lineCount = 4)
                        }
                        item(key = "skeleton_2") {
                            SkeletonCard(modifier = Modifier.fillMaxWidth(), titleWidth = 72.dp, lineCount = 3)
                        }
                    }
                    accounts.isEmpty() && recoveryIssues.isEmpty() -> {
                        item(key = "empty_state") {
                            EmptyState(
                                badge = {
                                    Box(
                                        Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.PersonOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                },
                                title = "还没有账号",
                                subtitle = "点击上方「添加账号」开始配置",
                            )
                        }
                    }
                }

                items(recoveryIssues, key = { "recovery_${it.accountId}" }) { issue ->
                    AccountRecoveryCard(issue, onDelete = { deletingRecoveryIssue = issue })
                }

                items(accounts, key = { it.id }) { acc ->
                    AccountCard(
                        acc,
                        onEdit = { onStartEdit(acc, false) },
                        onDelete = { deleting = acc },
                        onToggle = { viewModel.toggleAccount(acc, it) },
                        executionGuard = executionGuards[acc.id],
                        onResume = { viewModel.resumeAccount(acc.id) },
                    )
                }
                item(key = "spacer_bottom") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    deletingRecoveryIssue?.let { issue ->
        AppMessageDialog(
            title = "删除无法恢复的账号",
            message =
                "「${issue.label.ifBlank { "未命名账号" }}」的本地凭证无法解密或格式已损坏。" +
                    "应用不会导出密文；删除后可重新登录添加。该操作不可恢复。",
            confirmLabel = "删除并重新添加",
            onConfirm = {
                viewModel.deleteRecoveryIssue(issue.accountId)
                deletingRecoveryIssue = null
            },
            confirmStyle = AppDialogActionStyle.DESTRUCTIVE,
            dismissLabel = "取消",
            onDismissAction = { deletingRecoveryIssue = null },
            onDismissRequest = { deletingRecoveryIssue = null },
        )
    }

    deleting?.let { acc ->
        AppMessageDialog(
            title = "删除账号",
            message = "确定删除「${acc.label}」吗？该操作不可恢复。",
            confirmLabel = "删除",
            onConfirm = {
                viewModel.deleteAccount(acc.id)
                deleting = null
            },
            confirmStyle = AppDialogActionStyle.DESTRUCTIVE,
            dismissLabel = "取消",
            onDismissAction = { deleting = null },
            onDismissRequest = { deleting = null },
        )
    }
}

@Composable
private fun AccountRecoveryCard(
    issue: AccountRecoveryIssue,
    onDelete: () -> Unit,
) {
    PanelCard(modifier = Modifier.fillMaxWidth(), bg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    issue.label.ifBlank { "无法识别的账号" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (issue.reason == AccountRecoveryIssue.Reason.DECRYPTION_FAILED) {
                        "本地凭证无法解密，请删除后重新登录"
                    } else {
                        "本地账号格式已损坏，请删除后重新登录"
                    },
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
            TextButton(onClick = onDelete) { Text("处理", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * 账号卡片。
 *
 * 点击卡片进入编辑页；左侧展示账号名、扫码状态与已选游戏图标，右侧提供启用开关和删除按钮。
 */
@Composable
private fun AccountCard(
    account: Account,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    executionGuard: ExecutionStateRepository.AccountGuard?,
    onResume: () -> Unit,
) {
    val activeGames =
        remember(account) {
            Games.ALL.filter { g ->
                account.isGameSelected(g.key) &&
                    when {
                        g.cloud && g.key == "CloudYS" -> account.genshinToken.isNotBlank() || account.hasGenshinCloudKeepLogin
                        g.cloud && g.key == "CloudSR" -> account.starrailToken.isNotBlank() || account.hasStarrailCloudKeepLogin
                        else -> account.mysCookie.isNotBlank() || account.hasMysKeepLogin
                    }
            }
        }

    PanelCard(modifier = Modifier.fillMaxWidth().clickableNoRipple(onEdit)) {
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
                        Spacer(Modifier.width(8.dp))
                        AccountTagChip("米游社扫码✓", color = SuccessGreen)
                    }
                    if (account.hasGenshinCloudKeepLogin) {
                        Spacer(Modifier.width(8.dp))
                        AccountTagChip("云原神扫码✓", color = SuccessGreen)
                    }
                    if (account.hasStarrailCloudKeepLogin) {
                        Spacer(Modifier.width(8.dp))
                        AccountTagChip("云崩铁扫码✓", color = SuccessGreen)
                    }
                    if (com.questtick.sign.MysCoinCheckIn.featureEnabled && account.mysCoinEnabled) {
                        Spacer(Modifier.width(8.dp))
                        AccountTagChip("米游币", color = Color(0xFFE0A22B))
                    }
                }
                executionGuard?.let { guard ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AccountTagChip(
                            text =
                                when (guard.reason) {
                                    com.questtick.data.FailureCategory.AUTH_EXPIRED -> "后台已暂停：需重新登录"
                                    com.questtick.data.FailureCategory.CAPTCHA_REQUIRED -> "后台已暂停：需完成验证码"
                                    com.questtick.data.FailureCategory.SMS_REQUIRED -> "后台已暂停：需完成短信验证"
                                    else -> "后台签到已暂停"
                                },
                            color = DangerRed,
                        )
                        TextButton(onClick = onResume) {
                            Text("已处理，恢复", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        }
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
                        activeGames.take(6).forEach { g ->
                            GameIcon(g, size = 22.dp, corner = 7.dp)
                        }
                        if (activeGames.size > 6) {
                            AccountTagChip("+${activeGames.size - 6}", color = TextSecondary)
                        }
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.questtick.ui.components.AnimatedSwitch(
                    checked = account.enabled,
                    onCheckedChange = onToggle,
                    offColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DangerRed.copy(alpha = 0.10f))
                        .clickableNoRipple(onDelete)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = localizedText("删除"), tint = DangerRed, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("删除", fontSize = 11.sp, color = DangerRed, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
