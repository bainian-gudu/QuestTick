package com.questtick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.questtick.ui.components.GalaxyBackground

/** 安全检测设置页面。安全基线由应用强制执行，页面只负责展示状态。 */
@Composable
internal fun SecurityDetailPage(onBack: () -> Unit) {
    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("安全检测", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "sec_toggle") {
                    SettingsSwitchCard(
                        title = "Root检测",
                        subtitle = "",
                        checked = true,
                        enabled = false,
                        onCheckedChange = {},
                    )
                }
                item(key = "sec_block") {
                    SettingsSwitchCard(
                        title = "检测到Root阻断签到",
                        subtitle = "",
                        checked = true,
                        enabled = false,
                        onCheckedChange = {},
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}
