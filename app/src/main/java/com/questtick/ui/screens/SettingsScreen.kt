@file:Suppress(
    "detekt:CyclomaticComplexMethod",
    "detekt:LongMethod",
)

package com.questtick.ui.screens

/*
 * 设置页总入口：左侧分类列表 + 右侧单层详情栈，同一时刻只展开一页，各子页实现见对应的 *DetailPage。
 */
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.questtick.data.AppSettings
import com.questtick.notify.Notifier
import com.questtick.sign.AppUpdateChecker.AppUpdateInfo
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.PageTitle
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.collectAsStateWhenVisible
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.theme.InfoBlue
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber
import com.questtick.ui.vm.SettingsViewModel

private const val SCHEDULE_ICON_COLOR = 0xFF2F6BDB
private const val APPEARANCE_ICON_COLOR = 0xFF20B8D8
private const val MAIL_ICON_COLOR = 0xFF5BA8DF
private const val MYS_VERSION_ICON_COLOR = 0xFF8650C6
private const val CLOUD_VERSION_ICON_COLOR = 0xFF0AAFC1
private const val SECURITY_ICON_COLOR = 0xFFEF5B63
private const val DEVICE_ICON_COLOR = 0xFFE6A832
private const val EXPERIMENT_ICON_COLOR = 0xFFB24BC5
private const val ACT_ID_ICON_COLOR = 0xFFD66D2B
private const val CACHE_ICON_COLOR = 0xFF2A9D8F
private const val ABOUT_ICON_COLOR = 0xFF64748B

private enum class SettingsPage {
    None,
    Schedule,
    Mail,
    Device,
    ActId,
    Experiment,
    MysVersion,
    CloudVersion,
    Security,
    Appearance,
    Cache,
    About,
}

@Composable
fun SettingsScreen(
    visibleKey: Int = 0,
    isVisible: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    // 相邻 Pager 页面会被预组合；隐藏时保留 UI 快照，但不持续订阅状态流触发重组。
    val settings by viewModel.settings.collectAsStateWhenVisible(isVisible)
    val mail by viewModel.mail.collectAsStateWhenVisible(isVisible)
    val cacheStorageState by viewModel.cacheStorageState.collectAsStateWhenVisible(isVisible)

    val onSaveSchedule = viewModel::saveSchedule
    val onSaveMail = viewModel::saveMail
    val onTestMail = viewModel::sendTestMail
    val onFetchMysVersion = viewModel::fetchLatestMysVersion
    val onFetchCloudVersion = viewModel::fetchLatestCloudVersion
    val onCheckAppUpdate: ((AppUpdateInfo?) -> Unit) -> Unit = viewModel::checkAppUpdate

    // 签到后台可能刚生成设备 ID 或刷新版本，切回设置页时重新读取持久化快照。
    LaunchedEffect(visibleKey) {
        if (isVisible) {
            viewModel.reloadSettings()
            viewModel.ensureCacheStorageLoaded()
        }
    }

    var currentPage by remember { mutableStateOf(SettingsPage.None) }
    // 退出动画期间保留最后一个子页面，避免内容瞬间消失。
    var displayedPage by remember { mutableStateOf(SettingsPage.None) }
    // overlayActive 覆盖子页面显示与退出动画全过程，用于阻止触摸穿透。
    var overlayActive by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun openSettingsPage(page: SettingsPage) {
        if (page == SettingsPage.Cache) viewModel.refreshCacheStorage()
        displayedPage = page
        currentPage = page
        overlayActive = true
    }

    fun closeSettingsPage() {
        currentPage = SettingsPage.None
    }

    // currentPage 通过其它路径变化时，同步更新退出动画保留页面。
    LaunchedEffect(currentPage) {
        if (currentPage != SettingsPage.None) {
            displayedPage = currentPage
            overlayActive = true
        }
    }

    // 主 Tab 切走后页面仍会被保留在 Composition 中，需显式清理二级页面状态。
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            currentPage = SettingsPage.None
            displayedPage = SettingsPage.None
            overlayActive = false
            listState.scrollToItem(0)
        }
    }

    Box(Modifier.fillMaxSize()) {
        GalaxyBackground {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // 子页面显示或退出动画期间禁止主列表滚动，防止触摸穿透。
                userScrollEnabled = !overlayActive,
            ) {
                // 顶部占位 10.dp + LazyColumn item 间距 10.dp = 20.dp，与账号/记录/日志页标题高度保持一致。
                item(key = "spacer_top") { Spacer(Modifier.height(10.dp)) }
                item(key = "page_title") { PageTitle("设置", "应用配置") }
                item(key = "spacer_after_title") { Spacer(Modifier.height(4.dp)) }
                item(key = "row_schedule") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Schedule,
                        iconColor = Color(SCHEDULE_ICON_COLOR),
                        title = "定时任务",
                    ) { openSettingsPage(SettingsPage.Schedule) }
                }

                item(key = "row_appearance") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Palette,
                        iconColor = Color(APPEARANCE_ICON_COLOR),
                        title = "外观",
                    ) { openSettingsPage(SettingsPage.Appearance) }
                }

                item(key = "row_mail") {
                    SettingsEntryCard(
                        icon = Icons.Outlined.Email,
                        iconColor = Color(MAIL_ICON_COLOR),
                        title = "邮件推送",
                    ) { openSettingsPage(SettingsPage.Mail) }
                }

                item(key = "row_mys_version") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Description,
                        iconColor = Color(MYS_VERSION_ICON_COLOR),
                        title = "米游社版本",
                    ) { openSettingsPage(SettingsPage.MysVersion) }
                }

                item(key = "row_cloud_version") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Cloud,
                        iconColor = Color(CLOUD_VERSION_ICON_COLOR),
                        title = "云游戏版本",
                    ) { openSettingsPage(SettingsPage.CloudVersion) }
                }

                item(key = "row_security") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Lock,
                        iconColor = Color(SECURITY_ICON_COLOR),
                        title = "安全检测",
                    ) { openSettingsPage(SettingsPage.Security) }
                }

                item(key = "row_device") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Memory,
                        iconColor = Color(DEVICE_ICON_COLOR),
                        title = "设备 ID",
                    ) { openSettingsPage(SettingsPage.Device) }
                }

                item(key = "row_experiment") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Science,
                        iconColor = Color(EXPERIMENT_ICON_COLOR),
                        title = "实验功能",
                    ) { openSettingsPage(SettingsPage.Experiment) }
                }

                item(key = "row_act_id") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Autorenew,
                        iconColor = Color(ACT_ID_ICON_COLOR),
                        title = "ACT_ID 自动刷新",
                    ) { openSettingsPage(SettingsPage.ActId) }
                }

                item(key = "row_cache") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Storage,
                        iconColor = Color(CACHE_ICON_COLOR),
                        title = "缓存管理",
                    ) { openSettingsPage(SettingsPage.Cache) }
                }

                item(key = "row_about") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Info,
                        iconColor = Color(ABOUT_ICON_COLOR),
                        title = "关于",
                    ) { openSettingsPage(SettingsPage.About) }
                }

                item(key = "spacer_bottom") { Spacer(Modifier.height(28.dp)) }
            }
        }

        // 子页面可见或退出动画期间添加透明拦截层，并接管返回键，防止事件穿透。
        if (overlayActive) {
            BackHandler(enabled = true) { closeSettingsPage() }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickableNoRipple { /* 吞噬触摸事件，不做任何操作 */ },
            )
        }

        SettingsPageOverlay(
            visible = currentPage != SettingsPage.None,
            consumeHorizontalGestures = displayedPage != SettingsPage.Appearance,
            onExitFinished = {
                overlayActive = false
                displayedPage = SettingsPage.None
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (displayedPage) {
                    SettingsPage.Schedule -> {
                        ScheduleDetailPage(settings, onSaveSchedule, visibleKey) { closeSettingsPage() }
                    }

                    SettingsPage.Mail -> {
                        MailDetailPage(mail, onSaveMail, onTestMail) { closeSettingsPage() }
                    }

                    SettingsPage.Device -> {
                        DeviceDetailPage(settings, onSaveSchedule) { closeSettingsPage() }
                    }

                    SettingsPage.ActId -> {
                        ActIdDetailPage(settings, onSaveSchedule) { closeSettingsPage() }
                    }

                    SettingsPage.Experiment -> {
                        ExperimentDetailPage(settings, onSaveSchedule) { closeSettingsPage() }
                    }

                    SettingsPage.MysVersion -> {
                        MysVersionDetailPage(
                            settings = settings,
                            onSave = onSaveSchedule,
                            onFetchMysVersion = onFetchMysVersion,
                        ) { closeSettingsPage() }
                    }

                    SettingsPage.CloudVersion -> {
                        CloudVersionDetailPage(
                            settings = settings,
                            onSave = onSaveSchedule,
                            onFetchCloudVersion = viewModel::fetchLatestCloudVersion,
                        ) { closeSettingsPage() }
                    }

                    SettingsPage.Security -> {
                        SecurityDetailPage { closeSettingsPage() }
                    }

                    SettingsPage.Appearance -> {
                        AppearanceDetailPage(settings, onSaveSchedule) { closeSettingsPage() }
                    }

                    SettingsPage.Cache -> {
                        CacheDetailPage(
                            state = cacheStorageState,
                            onRefresh = viewModel::refreshCacheStorage,
                            onClearCategory = viewModel::clearCacheCategory,
                            onClearAll = viewModel::clearAllCaches,
                            onBack = { closeSettingsPage() },
                        )
                    }

                    SettingsPage.About -> {
                        AboutDetailPage(
                            settings = settings,
                            onSaveSettings = onSaveSchedule,
                            onBack = { closeSettingsPage() },
                            onCheckUpdate = onCheckAppUpdate,
                        )
                    }

                    SettingsPage.None -> {
                        Spacer(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var mysId by remember { mutableStateOf(settings.mysDeviceId) }
    var cloudId by remember { mutableStateOf(settings.cloudDeviceId) }
    var cloudBackup by remember { mutableStateOf(settings.cloudBackupDeviceId) }

    LaunchedEffect(settings.mysDeviceId) { if (mysId != settings.mysDeviceId) mysId = settings.mysDeviceId }
    LaunchedEffect(settings.cloudDeviceId) { if (cloudId != settings.cloudDeviceId) cloudId = settings.cloudDeviceId }
    LaunchedEffect(
        settings.cloudBackupDeviceId,
    ) { if (cloudBackup != settings.cloudBackupDeviceId) cloudBackup = settings.cloudBackupDeviceId }

    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("设备 ID", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "device_fields") {
                    SettingsCard {
                        DeviceIdField("米游社设备 ID", mysId, "MYS_DEVICE_ID（可选）") { mysId = it }
                        Spacer(Modifier.height(14.dp))
                        DeviceIdField("云游戏设备 ID", cloudId, "MIHOYO_DEVICE_ID（可选）") { cloudId = it }
                        Spacer(Modifier.height(14.dp))
                        DeviceIdField("云游戏备用 ID", cloudBackup, "MIHOYO_CLOUD_DEVICE_ID（可选）") { cloudBackup = it }
                    }
                }
                item(key = "device_save") {
                    PrimaryButton("保存") {
                        onSave(
                            settings.copy(
                                mysDeviceId = mysId.trim(),
                                cloudDeviceId = cloudId.trim(),
                                cloudBackupDeviceId = cloudBackup.trim(),
                            ),
                        )
                        onBack()
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun ExperimentDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var parallelEnabled by remember { mutableStateOf(settings.parallelEnabled) }
    var mysCoinEnabled by remember { mutableStateOf(settings.mysCoinEnabled) }

    LaunchedEffect(settings.parallelEnabled) { if (parallelEnabled != settings.parallelEnabled) parallelEnabled = settings.parallelEnabled }
    LaunchedEffect(settings.mysCoinEnabled) { if (mysCoinEnabled != settings.mysCoinEnabled) mysCoinEnabled = settings.mysCoinEnabled }

    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("实验功能", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "exp_mys_coin") {
                    SettingsSwitchCard(
                        title = "米游币打卡",
                        subtitle = "为勾选了米游币的账号额外执行社区打卡",
                        checked = mysCoinEnabled,
                        onCheckedChange = { enabled ->
                            mysCoinEnabled = enabled
                            onSave(settings.copy(mysCoinEnabled = enabled))
                        },
                    ) {
                        Spacer(Modifier.height(10.dp))
                        HintBox(
                            InfoBlue,
                            "需要米游社 App 登录态的 SToken（v2_ 开头）：在账号编辑的「SToken」里粘贴完整值，" +
                                "网页 Cookie 里的令牌不能代替。开启后还要在账号编辑里勾选米游币；" +
                                "接口返回登录失效时请重新获取并填写 SToken。",
                        )
                    }
                }
                if (mysCoinEnabled) {
                    item(key = "exp_mys_coin_forums") {
                        MysCoinForumsCard(
                            selected = settings.mysCoinForums,
                            onToggle = { id ->
                                val toggled =
                                    if (id in settings.mysCoinForums) {
                                        settings.mysCoinForums - id
                                    } else {
                                        settings.mysCoinForums + id
                                    }
                                // 至少保留一个社区。
                                if (toggled.isNotEmpty()) onSave(settings.copy(mysCoinForums = toggled))
                            },
                        )
                    }
                }
                item(key = "exp_parallel") {
                    SettingsSwitchCard(
                        title = "并行签到",
                        subtitle = "",
                        checked = parallelEnabled,
                        onCheckedChange = { enabled ->
                            parallelEnabled = enabled
                            onSave(
                                settings.copy(
                                    parallelEnabled = enabled,
                                ),
                            )
                        },
                    ) {
                        Spacer(Modifier.height(10.dp))
                        HintBox(
                            WarnAmber,
                            "⚠️ 会同时处理多个账号，短时间请求增多，" +
                                "可能导致账号风控，账号较多时请谨慎开启。",
                        )
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun ActIdDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("ACT_ID 自动刷新", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "act_id_toggle") {
                    SettingsSwitchCard(
                        title = "ACT_ID 自动刷新",
                        subtitle = "仅在签到响应提示对应 ACT_ID 失效时刷新并重试",
                        checked = settings.actIdAutoRefresh,
                        onCheckedChange = { enabled ->
                            onSave(settings.copy(actIdAutoRefresh = enabled))
                        },
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}
