package com.questtick.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import com.questtick.i18n.formatLocalizedClock
import com.questtick.i18n.formatLocalizedFullDate
import com.questtick.i18n.formatLocalizedSecond
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.data.GameInfo
import com.questtick.data.Games
import com.questtick.sign.RunProgressPhase
import com.questtick.ui.components.AppMessageDialog
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.GameTile
import com.questtick.ui.components.HomeThemeDashboard
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.SignInCalendarCard
import com.questtick.ui.components.SkeletonCard
import com.questtick.ui.components.home.HomeAccountCard
import com.questtick.ui.components.home.LatestRunCard
import com.questtick.ui.components.home.buildHomeDashboardStats
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.vm.HomeViewModel
import kotlinx.coroutines.isActive

// 预计算常量，减少重组期间的对象分配。

// 游戏网格行布局预计算；Games.ALL 是静态列表。
private val GamesGridRows: List<List<GameInfo>> = Games.ALL.chunked(4)
private const val SIGN_IN_COMPLETED_BUTTON_DURATION_MS = 3_000L

private fun formatDay(ts: Long) = formatLocalizedFullDate(ts)

private fun formatClock(ts: Long) = formatLocalizedClock(ts)

private fun formatSec(ts: Long) = formatLocalizedSecond(ts)

@Composable
fun HomeScreen(
    isVisible: Boolean = true,
    onGoAccounts: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val accountsState = viewModel.accounts.collectAsStateWithLifecycle()
    val historyState = viewModel.history.collectAsStateWithLifecycle()
    val accounts by accountsState
    val history by historyState
    val historyLoaded by viewModel.historyLoaded.collectAsStateWithLifecycle()
    val calendarDays by viewModel.calendarDays.collectAsStateWithLifecycle()
    val calendarLoaded by viewModel.calendarLoaded.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val rootBlockWarning by viewModel.rootBlockWarning.collectAsStateWithLifecycle()

    val dashboardStats by
        remember(accountsState, historyState) {
            derivedStateOf { buildHomeDashboardStats(accountsState.value, historyState.value) }
        }
    val last = remember(historyState) { derivedStateOf { historyState.value.firstOrNull() } }.value
    val listState = rememberLazyListState()

    LaunchedEffect(isVisible) {
        if (isVisible) viewModel.refreshHistory()
    }

    // 首页时钟每秒刷新…
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        while (isActive) {
            // 先同步当前时间再 delay，确保 Tab 重新可见时第一帧就是准确时间。
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    rootBlockWarning?.let { warning ->
        AppMessageDialog(
            title = warning.title,
            message = warning.message,
            confirmLabel = "知道了",
            onConfirm = viewModel::dismissRootBlockWarning,
            onDismissRequest = viewModel::dismissRootBlockWarning,
        )
    }

    GalaxyBackground {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "home_dashboard", contentType = "dashboard") {
                val clockText = remember(now / 60_000) { formatClock(now) }
                val secText = remember(now / 1000) { formatSec(now) }
                val dayText = remember(now / 60_000) { formatDay(now) }
                HomeThemeDashboard(
                    clockText = clockText,
                    secText = secText,
                    dayText = dayText,
                    enabledAccounts = dashboardStats.enabledAccounts,
                    totalAccounts = dashboardStats.totalAccounts,
                    gamesCount = Games.DISPLAY_GAME_COUNT,
                    success = dashboardStats.success,
                    already = dashboardStats.alreadySigned,
                    failed = dashboardStats.failed,
                    running = running,
                    completed = shouldShowSignInCompleted(progress.phase, progress.finishedAt, now),
                    onRun = viewModel::runNow,
                )
            }

            item(key = "signin_calendar", contentType = "calendar") {
                when {
                    !calendarLoaded -> {
                        SkeletonCard(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            titleWidth = 96.dp,
                            lineCount = 6,
                        )
                    }
                    else -> {
                        SignInCalendarCard(
                            days = calendarDays,
                            currentMillis = now,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }

            if (accounts.isEmpty()) {
                item(key = "home_accounts_title_empty", contentType = "title") {
                    CenterSectionTitle(
                        title = "账号状态",
                        subtitle = "已启用 ${dashboardStats.enabledAccounts}/${dashboardStats.totalAccounts}",
                    )
                }
                item(
                    key = "empty_accounts",
                    contentType = "empty",
                ) { EmptyAccountsCard(onGoAccounts, Modifier.padding(horizontal = 16.dp)) }
            } else {
                item(key = "home_accounts_title", contentType = "title") {
                    CenterSectionTitle(
                        title = "账号状态",
                        subtitle = "已启用 ${dashboardStats.enabledAccounts}/${dashboardStats.totalAccounts}",
                    )
                }
                items(accounts, key = { "home_account_${it.id}" }, contentType = { "account" }) { account ->
                    HomeAccountCard(
                        account = account,
                        onToggle = { enabled -> viewModel.toggleAccount(account, enabled) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (accounts.isNotEmpty() && !historyLoaded) {
                item(key = "recent_title_skeleton", contentType = "title") {
                    CenterSectionTitle(title = "最近签到")
                }
                item(key = "latest_run_skeleton", contentType = "skeleton") {
                    SkeletonCard(
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        titleWidth = 88.dp,
                        lineCount = 4,
                    )
                }
            } else if (last != null) {
                item(key = "recent_title", contentType = "title") {
                    CenterSectionTitle(title = "最近签到")
                }
                item(key = "latest_run", contentType = "run") {
                    LatestRunCard(
                        record = last,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "games_title", contentType = "title") {
                CenterSectionTitle(
                    title = "支持的游戏",
                    subtitle = "${Games.DISPLAY_GAME_COUNT} 款游戏可配置",
                )
            }
            item(key = "games_grid", contentType = "grid") { GamesGrid(Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

internal fun shouldShowSignInCompleted(
    phase: RunProgressPhase,
    finishedAt: Long,
    now: Long,
): Boolean =
    phase == RunProgressPhase.FINISHED &&
        finishedAt > 0L &&
        now >= finishedAt &&
        now - finishedAt < SIGN_IN_COMPLETED_BUTTON_DURATION_MS

@Composable
private fun CenterSectionTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyAccountsCard(
    onGoAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("还没有添加任何账号", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text("请前往「账号」页面添加", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onGoAccounts,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("去添加账号", color = Color.White) }
        }
    }
}

@Composable
private fun GamesGrid(modifier: Modifier = Modifier) {
    // 使用预计算的游戏行，避免重组时重复 chunked。
    val rows = remember { GamesGridRows }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { game ->
                    Box(Modifier.weight(1f)) { GameTile(game) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
