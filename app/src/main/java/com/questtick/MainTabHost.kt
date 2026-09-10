package com.questtick

/** 主 Tab 容器及各顶层页面之间的切换动画。 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.Account
import com.questtick.i18n.localizedText
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.pager.SyncedHorizontalPager
import com.questtick.ui.components.pager.pagerBeyondViewportCount
import com.questtick.ui.components.pager.pagerSelectionFraction
import com.questtick.ui.screens.AccountsScreen
import com.questtick.ui.screens.HomeScreen
import com.questtick.ui.screens.LogsScreen
import com.questtick.ui.screens.RecordsScreen
import com.questtick.ui.screens.SettingsScreen
import com.questtick.ui.theme.LocalAppUiTheme
import com.questtick.ui.vm.AccountsViewModel
import com.questtick.ui.vm.HomeViewModel
import com.questtick.ui.vm.LogsViewModel
import com.questtick.ui.vm.RecordsViewModel
import com.questtick.ui.vm.SettingsViewModel

internal enum class Tab(
    val label: String,
    val icon: ImageVector,
) {
    Home("主页", Icons.Filled.Home),
    Accounts("账号", Icons.Filled.People),
    Records("记录", Icons.Filled.History),
    Logs("日志", Icons.AutoMirrored.Filled.ListAlt),
    Settings("设置", Icons.Filled.Settings),
}

@Composable
internal fun MainTabContent(
    tab: Int,
    warmedTabs: Set<Int>,
    contentPadding: PaddingValues,
    settingsVisibleCount: Int,
    homeVm: HomeViewModel,
    accountsVm: AccountsViewModel,
    recordsVm: RecordsViewModel,
    logsVm: LogsViewModel,
    settingsVm: SettingsViewModel,
    onTabChange: (Int) -> Unit,
    onVisualTabPositionChange: (Float) -> Unit,
    onStartEdit: (Account, Boolean) -> Unit,
) {
    val tabModifier =
        Modifier
            .fillMaxSize()
            .padding(bottom = contentPadding.calculateBottomPadding())
            .windowInsetsPadding(
                WindowInsets.statusBars.union(
                    WindowInsets.displayCutout.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                    ),
                ),
            )
    val pagerBeyond = pagerBeyondViewportCount(warmedTabs.size, MainActivity.TAB_COUNT)

    SyncedHorizontalPager(
        selectedPage = tab,
        pageCount = MainActivity.TAB_COUNT,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = pagerBeyond,
        onVisualPagePositionChange = onVisualTabPositionChange,
        onPageSettled = onTabChange,
    ) { page, pageVisible ->
        Box(tabModifier) {
            when (page) {
                MainActivity.TAB_HOME ->
                    HomeScreen(
                        isVisible = pageVisible,
                        onGoAccounts = { onTabChange(MainActivity.TAB_ACCOUNTS) },
                        viewModel = homeVm,
                    )
                MainActivity.TAB_ACCOUNTS ->
                    AccountsScreen(
                        onStartEdit = onStartEdit,
                        isVisible = pageVisible,
                        viewModel = accountsVm,
                    )
                MainActivity.TAB_RECORDS ->
                    RecordsScreen(
                        isVisible = pageVisible,
                        viewModel = recordsVm,
                    )
                MainActivity.TAB_LOGS ->
                    LogsScreen(
                        isVisible = pageVisible,
                        viewModel = logsVm,
                    )
                MainActivity.TAB_SETTINGS ->
                    SettingsScreen(
                        visibleKey = settingsVisibleCount,
                        isVisible = pageVisible,
                        viewModel = settingsVm,
                    )
            }
        }
    }
}

@Composable
internal fun GalaxyBottomBar(
    visualPosition: Float,
    onSelect: (Int) -> Unit,
) {
    val appTheme = LocalAppUiTheme.current
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(appTheme.hairline))
        Row(
            Modifier
                .fillMaxWidth()
                .background(appTheme.card)
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEachIndexed { index, t ->
                val selectionFraction = pagerSelectionFraction(index, visualPosition)
                val selected = selectionFraction > 0.5f
                val indicatorWidth = 22.dp * selectionFraction
                val tint = lerp(appTheme.textSecondary, appTheme.brand, selectionFraction)

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickableNoRipple { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = indicatorWidth, height = 3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(appTheme.brand),
                    )
                    Spacer(Modifier.height(6.dp))
                    Icon(
                        t.icon,
                        contentDescription = localizedText(t.label),
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.label,
                        fontSize = 11.sp,
                        color = tint,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
