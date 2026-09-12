package com.questtick.ui.screens

/*
 * 版本信息设置页：查询并展示米游社与云游戏 App 的当前版本，便于核对接口签名所需的版本号。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.AppSettings
import com.questtick.sign.CloudAppVersionFetcher
import com.questtick.sign.CloudVersionRepository
import com.questtick.sign.MysAppVersionRepository
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.PanelCard
import com.questtick.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 米游社与云游戏客户端版本设置页面。 */

private const val VERSION_FETCHING_MIN_MILLIS = 1500L
private const val VERSION_FETCHED_VISIBLE_MILLIS = 1500L

private enum class VersionFetchState { IDLE, FETCHING, COMPLETED }

@Composable
@Suppress("detekt:LongMethod", "detekt:FunctionNaming")
internal fun MysVersionDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onFetchMysVersion: (onFinished: (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var autoFetchVersion by remember { mutableStateOf(settings.mysAppVersionAutoFetch) }
    var version by remember { mutableStateOf(settings.mysAppVersion) }
    var fetchState by remember { mutableStateOf(VersionFetchState.IDLE) }
    val feedbackScope = rememberCoroutineScope()
    val currentVersion = settings.mysAppVersion.ifBlank { MysAppVersionRepository.currentVersion }

    LaunchedEffect(settings.mysAppVersionAutoFetch) {
        if (autoFetchVersion != settings.mysAppVersionAutoFetch) autoFetchVersion = settings.mysAppVersionAutoFetch
    }
    LaunchedEffect(settings.mysAppVersion) { if (version != settings.mysAppVersion) version = settings.mysAppVersion }

    GalaxyBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(settingsPageInsets())) {
            DetailTopBar("米游社版本", onBack)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "mys_auto_fetch") {
                    SettingsSwitchCard(
                        title = "版本自动获取",
                        subtitle = "仅在签到进行时每 3 天获取一次",
                        checked = autoFetchVersion,
                        onCheckedChange = { enabled ->
                            autoFetchVersion = enabled
                            onSave(settings.copy(mysAppVersionAutoFetch = enabled))
                        },
                    )
                }
                item(key = "mys_version_custom") {
                    PanelCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            VersionSectionHeader(fetchState) {
                                fetchState = VersionFetchState.FETCHING
                                val startedAt = System.nanoTime()
                                onFetchMysVersion { latest ->
                                    feedbackScope.launch {
                                        delay(remainingFetchingMillis(startedAt))
                                        if (!latest.isNullOrBlank()) {
                                            version = latest
                                            fetchState = VersionFetchState.COMPLETED
                                            delay(VERSION_FETCHED_VISIBLE_MILLIS)
                                        }
                                        fetchState = VersionFetchState.IDLE
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            SettingsFieldLabel("米游社版本")
                            FieldBox(version, { version = it }, MysAppVersionRepository.currentVersion, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            Text("当前：$currentVersion", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
                item(key = "mys_save") {
                    PrimaryButton("保存") {
                        onSave(settings.copy(mysAppVersion = version.trim(), mysAppVersionAutoFetch = autoFetchVersion))
                        onBack()
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
internal fun CloudVersionDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onFetchCloudVersion: (onFinished: (CloudAppVersionFetcher.CloudVersions?) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var autoFetch by remember { mutableStateOf(settings.cloudVersionAutoFetch) }
    var ysVersion by remember { mutableStateOf(settings.cloudYsVersion) }
    var srVersion by remember { mutableStateOf(settings.cloudSrVersion) }
    var fetchState by remember { mutableStateOf(VersionFetchState.IDLE) }
    val feedbackScope = rememberCoroutineScope()
    val currentYs = settings.cloudYsVersion.ifBlank { CloudVersionRepository.effectiveYs() }
    val currentSr = settings.cloudSrVersion.ifBlank { CloudVersionRepository.effectiveSr() }

    LaunchedEffect(settings.cloudVersionAutoFetch) { autoFetch = settings.cloudVersionAutoFetch }
    LaunchedEffect(settings.cloudYsVersion) { ysVersion = settings.cloudYsVersion }
    LaunchedEffect(settings.cloudSrVersion) { srVersion = settings.cloudSrVersion }

    GalaxyBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(settingsPageInsets())) {
            DetailTopBar("云游戏版本", onBack)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "cloud_auto_fetch") {
                    SettingsSwitchCard(
                        title = "版本自动获取",
                        subtitle = "仅在签到进行时每 3 天获取一次",
                        checked = autoFetch,
                        onCheckedChange = { enabled ->
                            autoFetch = enabled
                            onSave(settings.copy(cloudVersionAutoFetch = enabled))
                        },
                    )
                }
                item(key = "cloud_custom") {
                    PanelCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            VersionSectionHeader(fetchState) {
                                fetchState = VersionFetchState.FETCHING
                                val startedAt = System.nanoTime()
                                onFetchCloudVersion { versions ->
                                    feedbackScope.launch {
                                        delay(remainingFetchingMillis(startedAt))
                                        if (versions != null) {
                                            ysVersion = versions.cloudYs
                                            srVersion = versions.cloudSr
                                            fetchState = VersionFetchState.COMPLETED
                                            delay(VERSION_FETCHED_VISIBLE_MILLIS)
                                        }
                                        fetchState = VersionFetchState.IDLE
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            SettingsFieldLabel("云原神版本")
                            FieldBox(ysVersion, { ysVersion = it }, CloudVersionRepository.effectiveYs(), Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                            SettingsFieldLabel("云崩铁版本")
                            FieldBox(srVersion, { srVersion = it }, CloudVersionRepository.effectiveSr(), Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            Text("当前：云原神 $currentYs / 云崩铁 $currentSr", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
                item(key = "cloud_save") {
                    PrimaryButton("保存") {
                        val trimmedYs = ysVersion.trim()
                        val trimmedSr = srVersion.trim()
                        CloudVersionRepository.applyOverrides(trimmedYs, trimmedSr)
                        onSave(settings.copy(cloudVersionAutoFetch = autoFetch, cloudYsVersion = trimmedYs, cloudSrVersion = trimmedSr))
                        onBack()
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
@Suppress("detekt:FunctionNaming")
private fun VersionSectionHeader(
    state: VersionFetchState,
    onFetch: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        SettingsSectionTitle("自定义版本")
        SettingsFetchButton(
            fetching = state == VersionFetchState.FETCHING,
            completed = state == VersionFetchState.COMPLETED,
            onClick = onFetch,
        )
    }
}

private fun remainingFetchingMillis(startedAtNanos: Long): Long {
    // 快速请求也保留最短获取状态，随后完成提示再单独展示固定时长。
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
    return (VERSION_FETCHING_MIN_MILLIS - elapsedMillis).coerceAtLeast(0L)
}

@Composable
private fun settingsPageInsets() = WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
