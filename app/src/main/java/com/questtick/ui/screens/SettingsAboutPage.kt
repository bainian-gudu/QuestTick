package com.questtick.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.BuildConfig
import com.questtick.R
import com.questtick.data.AppSettings
import com.questtick.sign.AppUpdateChecker.AppUpdateInfo
import com.questtick.sign.AppUpdateNetwork
import com.questtick.ui.components.GalaxyBackground

/** 关于、更新与许可证入口页面。 */
@Composable
internal fun AboutDetailPage(
    settings: AppSettings,
    onSaveSettings: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onCheckUpdate: (onFinished: (AppUpdateInfo?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val licenseText = remember(context) {
        context.resources.openRawResource(R.raw.gpl_3_0).bufferedReader().use { it.readText() }
    }
    var checkingUpdate by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }

    fun startUpdateCheck() {
        if (checkingUpdate) return
        checkingUpdate = true
        onCheckUpdate { checkingUpdate = false }
    }

    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("关于", onBack)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "about_app") {
                    SettingsActionCard(
                        title = "版本 ${BuildConfig.VERSION_NAME}",
                        value = if (checkingUpdate) "检查中…" else "检查更新",
                        modifier = Modifier.fillParentMaxWidth(),
                        enabled = !checkingUpdate,
                    ) { startUpdateCheck() }
                }
                item(key = "about_update") {
                    SettingsSwitchCard(
                        title = "自动更新",
                        subtitle = "",
                        checked = settings.appUpdateAutoCheck,
                        modifier = Modifier.fillParentMaxWidth(),
                        onCheckedChange = { enabled -> onSaveSettings(settings.copy(appUpdateAutoCheck = enabled)) },
                    )
                }
                item(key = "about_update_source") {
                    SettingsCard {
                        SettingsSectionTitle("自动更新下载源")
                        Spacer(Modifier.height(10.dp))
                        val selectedSource = AppUpdateNetwork.UpdateSource.fromKey(settings.appUpdateSource)
                        Box {
                            OutlinedButton(
                                onClick = { sourceMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Text(selectedSource.displayName, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                Text("⌄", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(
                                expanded = sourceMenuExpanded,
                                onDismissRequest = { sourceMenuExpanded = false },
                            ) {
                                AppUpdateNetwork.UpdateSource.entries.forEach { source ->
                                    DropdownMenuItem(
                                        text = { Text(source.displayName) },
                                        onClick = {
                                            sourceMenuExpanded = false
                                            onSaveSettings(settings.copy(appUpdateSource = source.key))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "about_debug_log") {
                    SettingsSwitchCard(
                        title = "调试日志",
                        subtitle = "",
                        checked = settings.debugLoggingEnabled,
                        modifier = Modifier.fillParentMaxWidth(),
                        onCheckedChange = { enabled -> onSaveSettings(settings.copy(debugLoggingEnabled = enabled)) },
                    )
                }
                item(key = "about_links") {
                    SettingsCard(modifier = Modifier.fillParentMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                        AboutLinkRow("GitHub", "MYS_Signin_Android") {
                            openExternalUrl(context, "https://github.com/moon02222/MYS_Signin_Android")
                        }
                        HairlineSpacer()
                        AboutLinkRow("上游项目", "MYS_Game_Signin") {
                            openExternalUrl(context, "https://github.com/moon02222/MYS_Game_Signin")
                        }
                        HairlineSpacer()
                        AboutLinkRow("开源许可证", "GNU General Public License v3.0") { showLicenseDialog = true }
                    }
                }
                item(key = "about_disclaimer") {
                    SettingsInfoCard(
                        title = "免责声明",
                        text = "本项目仅供学习、交流和测试使用。作者不对因使用本项目产生的任何问题承担责任。请在遵守相关服务条款与法规的前提下低调使用，若不同意请停止使用。",
                        modifier = Modifier.fillParentMaxWidth(),
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
        SettingsPageOverlay(visible = showLicenseDialog) {
            LicenseDetailDialog(licenseText = licenseText, onDismiss = { showLicenseDialog = false })
        }
    }
}
