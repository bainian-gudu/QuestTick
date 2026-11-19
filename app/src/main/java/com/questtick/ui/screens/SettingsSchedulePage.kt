@file:Suppress(
    "detekt:CyclomaticComplexMethod",
    "detekt:LongMethod",
)

package com.questtick.ui.screens

/*
 * 定时任务设置页：签到时间、结果通知与准点闹钟状态。
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

private const val MAX_SCHEDULE_HOUR = 23
private const val MAX_SCHEDULE_MINUTE = 59

@Composable
internal fun ScheduleDetailPage(
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

                        notifyEnabled && !available -> {
                            persistNotifyEnabled(false)
                        }

                        available -> {
                            pendingNotificationEnable = false
                        }
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
                                    ScheduleTimeField(
                                        value = hour,
                                        placeholder = "时",
                                        onValueChange = { hour = it },
                                    )
                                    Text(
                                        ":",
                                        modifier = Modifier.width(12.dp),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    ScheduleTimeField(
                                        value = minute,
                                        placeholder = "分",
                                        onValueChange = { minute = it },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("24小时制", fontSize = 12.sp, color = TextSecondary)
                                    Spacer(Modifier.weight(1f))
                                    Box(Modifier.width(80.dp)) {
                                        PrimaryButton(
                                            text = "保存",
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        ) {
                                            onSave(
                                                settings.copy(
                                                    scheduleEnabled = true,
                                                    notifyEnabled = notifyEnabled,
                                                    scheduleHour = hour.toIntOrNull()?.coerceIn(0, MAX_SCHEDULE_HOUR) ?: 8,
                                                    scheduleMinute = minute.toIntOrNull()?.coerceIn(0, MAX_SCHEDULE_MINUTE) ?: 0,
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
                item(key = "exact_alarm_card") { ExactAlarmCard(visibleKey) }
                item(key = "keepalive_card") { KeepAliveCard(visibleKey) }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}
