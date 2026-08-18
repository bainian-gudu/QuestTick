package com.questtick.ui.screens

import com.questtick.i18n.localizedText
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.BuildConfig
import com.questtick.R
import com.questtick.data.AppSettings
import com.questtick.data.MailSettings
import com.questtick.i18n.AppLanguage
import com.questtick.notify.Notifier
import com.questtick.sign.AppUpdateChecker.AppUpdateInfo
import com.questtick.sign.CloudVersionRepository
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.PageTitle
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.SkeletonBlock
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.consumeHorizontalPageSwipe
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.theme.InfoBlue
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber
import com.questtick.ui.vm.SettingsViewModel
import com.questtick.ui.vm.CacheCategory
import com.questtick.ui.vm.CacheStorageState

// 设置子页面枚举。

private enum class SettingsPage { None, Schedule, Mail, Device, ActId, Experiment, MysVersion, CloudVersion, Security, Appearance, Cache, About }

// 设置主页面。

@Composable
fun SettingsScreen(
    visibleKey: Int = 0,
    isVisible: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mail by viewModel.mail.collectAsStateWithLifecycle()
    val mailLoaded by viewModel.mailLoaded.collectAsStateWithLifecycle()
    val cacheStorageState by viewModel.cacheStorageState.collectAsStateWithLifecycle()

    val onSaveSchedule = viewModel::saveSchedule
    val onSaveLanguage = viewModel::saveLanguage
    val onSaveMail = viewModel::saveMail
    val onTestMail = viewModel::sendTestMail
    val onFetchMysVersion = viewModel::fetchLatestMysVersion
    val onFetchCloudVersion = viewModel::fetchLatestCloudVersion
    val onCheckAppUpdate: ((AppUpdateInfo?) -> Unit) -> Unit = viewModel::checkAppUpdate

    // 签到后台可能刚生成设备 ID 或刷新版本，切回设置页时重新读取持久化快照。
    LaunchedEffect(visibleKey) {
        viewModel.reloadSettings()
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
        // 主设置列表概览。
        GalaxyBackground {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // 子页面显示或退出动画期间禁止主列表滚动，防止触摸穿透。
                userScrollEnabled = !overlayActive,
            ) {
                item(key = "spacer_top") { Spacer(Modifier.height(8.dp)) }
                item(key = "page_title") { PageTitle("设置", "应用配置") }
                item(key = "spacer_after_title") { Spacer(Modifier.height(4.dp)) }
                // 定时任务入口。
                item(key = "row_schedule") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Schedule,
                        iconColor = Color(0xFF2F6BDB),
                        title = "定时任务",
                        subtitle =
                            if (settings.scheduleEnabled) {
                                "每天 ${settings.scheduleHour.toString().padStart(
                                    2,
                                    '0',
                                )}:${settings.scheduleMinute.toString().padStart(2, '0')}"
                            } else {
                                "关闭"
                            },
                    ) { openSettingsPage(SettingsPage.Schedule) }
                }

                // 外观入口。
                item(key = "row_appearance") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Palette,
                        iconColor = Color(0xFF20B8D8),
                        title = "外观",
                        subtitle = themeModeLabel(settings.appThemeMode),
                    ) { openSettingsPage(SettingsPage.Appearance) }
                }

                item(key = "row_mail") {
                    val complete = mail.smtpServer.isNotBlank() && mail.username.isNotBlank() &&
                        mail.password.isNotBlank() && mail.mailTo.isNotBlank()
                    SettingsEntryCard(
                        icon = Icons.Outlined.Email,
                        iconColor = Color(0xFF5BA8DF),
                        title = "邮件推送",
                        subtitle = when {
                            !mailLoaded || !mail.enabled -> "关闭"
                            complete -> "已配置"
                            else -> "配置不完整"
                        },
                    ) { openSettingsPage(SettingsPage.Mail) }
                }

                item(key = "row_mys_version") {
                    val mysSubtitle =
                        buildList {
                            if (settings.mysAppVersionAutoFetch) add("自动获取")
                            if (settings.mysAppVersion.isNotBlank()) add("自定义 ${settings.mysAppVersion}")
                        }.joinToString(" · ").ifEmpty { "默认 ${com.questtick.sign.MysAppVersionRepository.currentVersion}" }
                    SettingsEntryCard(
                        icon = Icons.Filled.Description,
                        iconColor = Color(0xFF8650C6),
                        title = "米游社版本",
                        subtitle = mysSubtitle,
                    ) { openSettingsPage(SettingsPage.MysVersion) }
                }

                item(key = "row_cloud_version") {
                    val cloudSubtitle =
                        buildList {
                            if (settings.cloudVersionAutoFetch) add("自动获取")
                            if (settings.cloudYsVersion.isNotBlank()) add("云原神 ${settings.cloudYsVersion}")
                            if (settings.cloudSrVersion.isNotBlank()) add("云崩铁 ${settings.cloudSrVersion}")
                        }.joinToString(" · ").ifEmpty {
                            "默认 ${CloudVersionRepository.effectiveYs()} / " +
                                CloudVersionRepository.effectiveSr()
                        }
                    SettingsEntryCard(
                        icon = Icons.Filled.Cloud,
                        iconColor = Color(0xFF0AAFC1),
                        title = "云游戏版本",
                        subtitle = cloudSubtitle,
                    ) { openSettingsPage(SettingsPage.CloudVersion) }
                }

                item(key = "row_security") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Lock,
                        iconColor = Color(0xFFEF5B63),
                        title = "安全检测",
                        subtitle = "Root 检测与阻断已强制开启",
                    ) { openSettingsPage(SettingsPage.Security) }
                }

                // 设备 ID 入口。
                item(key = "row_device") {
                    val customDeviceIdLabels =
                        buildList {
                            if (settings.mysDeviceId.isNotBlank()) add("米游社")
                            if (settings.cloudDeviceId.isNotBlank()) add("云游戏")
                            if (settings.cloudBackupDeviceId.isNotBlank()) add("云备用")
                        }
                    val deviceSubtitle =
                        if (customDeviceIdLabels.isNotEmpty()) {
                            "已自定义：${customDeviceIdLabels.joinToString("、")}"
                        } else {
                            "自动生成"
                        }
                    SettingsEntryCard(
                        icon = Icons.Filled.Memory,
                        iconColor = Color(0xFFE6A832),
                        title = "设备 ID",
                        subtitle = deviceSubtitle,
                    ) { openSettingsPage(SettingsPage.Device) }
                }

                // 聚合展示较高级的可选开关，减少首页设置项噪音。
                item(key = "row_experiment") {
                    val enabledExperiments =
                        buildList {
                            if (settings.parallelEnabled) add("并行签到")
                        }
                    val experimentSubtitle =
                        if (enabledExperiments.isNotEmpty()) {
                            "已启用：${enabledExperiments.joinToString("、")}"
                        } else {
                            "关闭"
                        }
                    SettingsEntryCard(
                        icon = Icons.Filled.Science,
                        iconColor = Color(0xFFB24BC5),
                        title = "实验功能",
                        subtitle = experimentSubtitle,
                    ) { openSettingsPage(SettingsPage.Experiment) }
                }

                item(key = "row_act_id") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Autorenew,
                        iconColor = Color(0xFFD66D2B),
                        title = "ACT_ID 自动刷新",
                        subtitle = if (settings.actIdAutoRefresh) "已启用" else "关闭",
                    ) { openSettingsPage(SettingsPage.ActId) }
                }

                item(key = "row_cache") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Storage,
                        iconColor = Color(0xFF2A9D8F),
                        title = "缓存管理",
                        subtitle = if (cacheStorageState.loading) "正在计算占用" else "共 ${formatCacheSize(cacheStorageState.totalBytes)}",
                    ) { openSettingsPage(SettingsPage.Cache) }
                }

                // 关于入口。
                item(key = "row_about") {
                    SettingsEntryCard(
                        icon = Icons.Filled.Info,
                        iconColor = Color(0xFF64748B),
                        title = "关于",
                        subtitle = "版本与项目信息",
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

        // 子页面浮层。
        SettingsPageOverlay(
            visible = currentPage != SettingsPage.None,
            consumeHorizontalGestures = displayedPage != SettingsPage.Appearance,
            onExitFinished = {
                overlayActive = false
                displayedPage = SettingsPage.None
            },
        ) {
            // 子页面内容直接按 displayedPage 渲染…
            Box(modifier = Modifier.fillMaxSize()) {
                when (displayedPage) {
                    SettingsPage.Schedule ->
                        ScheduleDetailPage(settings, onSaveSchedule, visibleKey) { closeSettingsPage() }
                    SettingsPage.Mail ->
                        MailDetailPage(mail, onSaveMail, onTestMail) { closeSettingsPage() }
                    SettingsPage.Device ->
                        DeviceDetailPage(settings, onSaveSchedule) { closeSettingsPage() }
                    SettingsPage.ActId ->
                        ActIdDetailPage(settings, onSaveSchedule) { closeSettingsPage() }
                    SettingsPage.Experiment ->
                        ExperimentDetailPage(settings, onSaveSchedule) { closeSettingsPage() }
                    SettingsPage.MysVersion ->
                        MysVersionDetailPage(
                            settings = settings,
                            onSave = onSaveSchedule,
                            onFetchMysVersion = onFetchMysVersion,
                        ) { closeSettingsPage() }
                    SettingsPage.CloudVersion ->
                        CloudVersionDetailPage(
                            settings = settings,
                            onSave = onSaveSchedule,
                            onFetchCloudVersion = viewModel::fetchLatestCloudVersion,
                        ) { closeSettingsPage() }
                    SettingsPage.Security ->
                        SecurityDetailPage { closeSettingsPage() }
                    SettingsPage.Appearance ->
                        AppearanceDetailPage(settings, onSaveSchedule, onSaveLanguage) { closeSettingsPage() }
                    SettingsPage.Cache ->
                        CacheDetailPage(
                            state = cacheStorageState,
                            onRefresh = viewModel::refreshCacheStorage,
                            onClearCategory = viewModel::clearCacheCategory,
                            onClearAll = viewModel::clearAllCaches,
                            onBack = { closeSettingsPage() },
                        )
                    SettingsPage.About ->
                        AboutDetailPage(
                            settings = settings,
                            onSaveSettings = onSaveSchedule,
                            onBack = { closeSettingsPage() },
                            onCheckUpdate = onCheckAppUpdate,
                        )
                    SettingsPage.None ->
                        Spacer(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ScheduleDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    visibleKey: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var scheduleEnabled by remember { mutableStateOf(settings.scheduleEnabled) }
    var notifyEnabled by remember { mutableStateOf(settings.notifyEnabled) }
    var notificationAvailable by remember { mutableStateOf(Notifier.canShowResultNotifications(context)) }
    var pendingNotificationEnable by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf(settings.scheduleHour.toString()) }
    var minute by remember { mutableStateOf(settings.scheduleMinute.toString()) }

    fun refreshNotificationAvailable(): Boolean {
        notificationAvailable = Notifier.canShowResultNotifications(context)
        return notificationAvailable
    }

    fun persistNotifyEnabled(enabled: Boolean) {
        notifyEnabled = enabled
        onSave(settings.copy(scheduleEnabled = scheduleEnabled, notifyEnabled = enabled))
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val available = refreshNotificationAvailable()
            if (granted && available) {
                pendingNotificationEnable = false
                persistNotifyEnabled(true)
            } else {
                pendingNotificationEnable = true
                persistNotifyEnabled(false)
                Notifier.openNotificationSettings(context)
            }
        }

    fun requestOrOpenNotificationPermission() {
        pendingNotificationEnable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.hasPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Notifier.openNotificationSettings(context)
        }
    }

    LaunchedEffect(settings.scheduleEnabled) { if (scheduleEnabled != settings.scheduleEnabled) scheduleEnabled = settings.scheduleEnabled }
    LaunchedEffect(settings.notifyEnabled) { if (notifyEnabled != settings.notifyEnabled) notifyEnabled = settings.notifyEnabled }
    LaunchedEffect(visibleKey) {
        val available = refreshNotificationAvailable()
        if (notifyEnabled && !available) persistNotifyEnabled(false)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, notifyEnabled, pendingNotificationEnable) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME ||
                    event == androidx.lifecycle.Lifecycle.Event.ON_START
                ) {
                    val available = refreshNotificationAvailable()
                    when {
                        pendingNotificationEnable && available -> {
                            pendingNotificationEnable = false
                            persistNotifyEnabled(true)
                        }
                        notifyEnabled && !available -> persistNotifyEnabled(false)
                        available -> pendingNotificationEnable = false
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settings.scheduleHour) {
        val s = settings.scheduleHour.toString()
        if (hour != s) hour = s
    }
    LaunchedEffect(settings.scheduleMinute) {
        val s = settings.scheduleMinute.toString()
        if (minute != s) minute = s
    }

    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("定时任务", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "schedule_card") {
                    SettingsSwitchCard(
                        title = "定时签到",
                        subtitle = "",
                        checked = scheduleEnabled,
                        onCheckedChange = { enabled ->
                            scheduleEnabled = enabled
                        },
                    ) {
                        AnimatedVisibility(
                            visible = scheduleEnabled,
                            enter = AppMotion.expandEnter(),
                            exit = AppMotion.expandExit(),
                        ) {
                            Column {
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FieldBox(hour, { hour = it.filter { c -> c.isDigit() }.take(2) }, "时", Modifier.width(90.dp))
                                    Text(
                                        ":",
                                        modifier = Modifier.width(12.dp),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    FieldBox(minute, { minute = it.filter { c -> c.isDigit() }.take(2) }, "分", Modifier.width(90.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("24小时制", fontSize = 12.sp, color = TextSecondary)
                                    Spacer(Modifier.weight(1f))
                                    Box(Modifier.width(80.dp)) {
                                        PrimaryButton("保存") {
                                            onSave(
                                                settings.copy(
                                                    scheduleEnabled = true,
                                                    notifyEnabled = notifyEnabled,
                                                    scheduleHour = hour.toIntOrNull()?.coerceIn(0, 23) ?: 8,
                                                    scheduleMinute = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                                                ),
                                            )
                                            onBack()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "schedule_notify") {
                    SettingsSwitchCard(
                        title = "签到结果通知",
                        subtitle = if (notifyEnabled && !notificationAvailable) "系统通知权限已关闭" else "",
                        checked = notifyEnabled && notificationAvailable,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (refreshNotificationAvailable()) {
                                    persistNotifyEnabled(true)
                                } else {
                                    requestOrOpenNotificationPermission()
                                }
                            } else {
                                persistNotifyEnabled(false)
                            }
                        },
                    )
                }
                item(key = "keepalive_card") { KeepAliveCard(visibleKey) }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

// 子页面：设备 ID。

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

    LaunchedEffect(settings.parallelEnabled) { if (parallelEnabled != settings.parallelEnabled) parallelEnabled = settings.parallelEnabled }

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
                // 并行签到。
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


// 内联小卡片。

// 后台保活状态检测共享逻辑。

/** 统一检测后台保活状态的 Compose Hook。 */
@Composable
private fun rememberBatteryExempted(
    visibleKey: Int,
    refreshTrigger: Int = 0,
): Boolean {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val pkgName = remember { context.packageName }
    var exempted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(pkgName)) }

    // 首次组合与设置页重新可见时触发检测。
    LaunchedEffect(visibleKey) {
        exempted = pm.isIgnoringBatteryOptimizations(pkgName)
    }

    // 从系统设置返回时通过 ON_RESUME 再次检测。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME ||
                    event == androidx.lifecycle.Lifecycle.Event.ON_START
                ) {
                    exempted = pm.isIgnoringBatteryOptimizations(pkgName)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 点击“开启”后短暂轮询，捕捉系统设置变化。
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            repeat(6) {
                kotlinx.coroutines.delay(500)
                val current = pm.isIgnoringBatteryOptimizations(pkgName)
                if (current != exempted) {
                    exempted = current
                    return@LaunchedEffect
                }
            }
        }
    }

    return exempted
}

/** 跳转到系统电池优化设置。 */
private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}

/** 后台保活状态行，用于设置概览页。 */
@Composable
private fun KeepAliveRow(visibleKey: Int) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val exempted = rememberBatteryExempted(visibleKey, refreshTrigger)

    SettingsEntryCard(
        icon = if (exempted) Icons.Filled.CheckCircle else Icons.Filled.BatteryAlert,
        iconColor = if (exempted) SuccessGreen else WarnAmber,
        title = "后台保活",
        subtitle = if (exempted) "已加入电池优化白名单" else "建议加入白名单",
        trailing = {
            if (!exempted) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(WarnAmber.copy(alpha = 0.15f))
                        .clickableNoRipple {
                            requestBatteryExemption(context)
                            refreshTrigger++
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("开启", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WarnAmber)
                }
            }
        },
    )
}

/** 后台保活完整卡片，用于定时任务子页面展示更多说明。 */
@Composable
private fun KeepAliveCard(visibleKey: Int) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val exempted = rememberBatteryExempted(visibleKey, refreshTrigger)
    val manufacturer = remember { android.os.Build.MANUFACTURER.lowercase() }
    val romHint =
        remember(manufacturer) {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                    "小米 / Redmi：设置 → 应用设置 → 应用管理 → 找到本应用 → 自启动（开启）"
                manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                    "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 找到本应用 → " +
                        "关闭「自动管理」→ 手动打开全部三项开关"
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
                    "OPPO / realme / 一加：设置 → 应用管理 → 应用列表 → " +
                        "找到本应用 → 耗电保护 → 允许后台运行"
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                    "vivo / iQOO：设置 → 电池 → 后台耗电管理 → 找到本应用 → 允许后台高耗电"
                manufacturer.contains("samsung") ->
                    "三星：设置 → 电池和设备维护 → 电池 → 后台使用限制 → 将本应用移至「不受限」"
                manufacturer.contains("meizu") ->
                    "魅族：设置 → 应用管理 → 找到本应用 → 权限管理 → 后台管理 → 允许后台运行"
                else -> null
            }
        }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // 电池优化状态。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (exempted) Icons.Filled.CheckCircle else Icons.Filled.BatteryAlert,
                    contentDescription = null,
                    tint = if (exempted) SuccessGreen else WarnAmber,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (exempted) "已加入电池优化白名单" else "建议加入电池优化白名单",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (exempted) {
                    "定时签到在后台被系统杀掉的概率已大幅降低。"
                } else {
                    "将应用加入电池优化白名单后，定时签到更准点、更不易被系统清理。"
                },
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 18.sp,
            )
            if (!exempted) {
                Spacer(Modifier.height(14.dp))
                PrimaryButton("申请忽略电池优化") {
                    requestBatteryExemption(context)
                    refreshTrigger++
                }
            }

            // ROM 自启动指引。
            if (romHint != null) {
                Spacer(Modifier.height(14.dp))
                HintBox(
                    InfoBlue,
                    "📱 检测到 ${android.os.Build.MANUFACTURER} 设备，建议同时开启自启动权限：\n$romHint",
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    "部分国产 ROM 还需在系统设置中允许「自启动 / 后台运行」权限。",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
