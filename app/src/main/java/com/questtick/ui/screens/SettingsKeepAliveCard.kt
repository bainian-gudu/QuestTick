@file:Suppress(
    "detekt:CyclomaticComplexMethod",
    "detekt:LongMethod",
)

package com.questtick.ui.screens

/*
 * 后台保活检测与电池优化引导卡片。
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

private const val BATTERY_POLL_ATTEMPTS = 6
private const val BATTERY_POLL_INTERVAL_MS = 500L
private const val BATTERY_HINT_SPACING_DP = 6

// 后台保活状态检测共享逻辑。

/** 统一检测后台保活状态的 Compose Hook。 */
@Composable
internal fun rememberBatteryExempted(
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
            repeat(BATTERY_POLL_ATTEMPTS) {
                kotlinx.coroutines.delay(BATTERY_POLL_INTERVAL_MS)
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

/** 后台保活完整卡片，用于定时任务子页面展示更多说明。 */
@Composable
internal fun KeepAliveCard(visibleKey: Int) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val exempted = rememberBatteryExempted(visibleKey, refreshTrigger)
    val manufacturer =
        remember {
            android.os.Build.MANUFACTURER
                .lowercase()
        }
    val romHint =
        remember(manufacturer) {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    "小米 / Redmi：设置 → 应用设置 → 应用管理 → 找到本应用 → 自启动（开启）"
                }

                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 找到本应用 → " +
                        "关闭「自动管理」→ 手动打开全部三项开关"
                }

                manufacturer.contains("oppo") ||
                    manufacturer.contains("realme") ||
                    manufacturer.contains("oneplus") -> {
                    "OPPO / realme / 一加：设置 → 应用管理 → 应用列表 → " +
                        "找到本应用 → 耗电保护 → 允许后台运行"
                }

                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    "vivo / iQOO：设置 → 电池 → 后台耗电管理 → 找到本应用 → 允许后台高耗电"
                }

                manufacturer.contains("samsung") -> {
                    "三星：设置 → 电池和设备维护 → 电池 → 后台使用限制 → 将本应用移至「不受限」"
                }

                manufacturer.contains("meizu") -> {
                    "魅族：设置 → 应用管理 → 找到本应用 → 权限管理 → 后台管理 → 允许后台运行"
                }

                else -> {
                    null
                }
            }
        }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
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
            Spacer(Modifier.height(BATTERY_HINT_SPACING_DP.dp))
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

            if (romHint != null) {
                Spacer(Modifier.height(14.dp))
                val romHintText =
                    "📱 检测到 ${android.os.Build.MANUFACTURER} 设备，建议同时开启自启动权限：\n" +
                        romHint
                HintBox(
                    InfoBlue,
                    // 外层模板和 ROM 指引分别解析，避免已知厂商指引在非中文界面残留中文。
                    romHintText,
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
