package com.questtick.ui.screens

/*
 * 精确闹钟权限入口：Android 12 起该权限单独授权，未授权时定时签到只能退化为宽松闹钟，
 * 触发时刻可能被系统推迟数分钟，因此设置页需要显式展示状态并提供跳转。
 */

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.questtick.ui.components.Text
import com.questtick.ui.components.PanelCard
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber

private const val EXACT_ALARM_HINT_SPACING_DP = 6

/** 精确闹钟权限卡片：显示当前状态，未授权时给出跳转系统设置的入口。 */
@Composable
internal fun ExactAlarmCard(visibleKey: Int) {
    val context = LocalContext.current
    val status = rememberExactAlarmStatus(visibleKey)

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (status == ExactAlarmStatus.GRANTED) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                    contentDescription = null,
                    tint =
                        when (status) {
                            ExactAlarmStatus.GRANTED -> SuccessGreen
                            ExactAlarmStatus.DENIED -> WarnAmber
                            ExactAlarmStatus.UNSUPPORTED -> TextSecondary
                        },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (status == ExactAlarmStatus.GRANTED) "精确闹钟已启用" else "精确闹钟权限",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(EXACT_ALARM_HINT_SPACING_DP.dp))
            Text(
                when (status) {
                    ExactAlarmStatus.GRANTED -> "允许精确闹钟后，定时签到可在设定时间准时触发。"
                    ExactAlarmStatus.DENIED -> "未授权精确闹钟时，系统可能把定时签到推迟数分钟后才触发。"
                    ExactAlarmStatus.UNSUPPORTED -> "当前系统版本不需要单独授权精确闹钟。"
                },
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 18.sp,
            )
            if (status == ExactAlarmStatus.DENIED) {
                Spacer(Modifier.height(14.dp))
                PrimaryButton("前往授权") { openExactAlarmSettings(context) }
            }
        }
    }
}

/** 读取精确闹钟权限；首次组合、页面重新可见以及从系统设置返回时都会重新检测。 */
@Composable
private fun rememberExactAlarmStatus(visibleKey: Int): ExactAlarmStatus {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AlarmManager::class.java) }

    fun current(): ExactAlarmStatus =
        exactAlarmStatus(
            sdkInt = Build.VERSION.SDK_INT,
            canScheduleExactAlarms =
                manager != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    manager.canScheduleExactAlarms(),
        )

    var status by remember { mutableStateOf(current()) }
    LaunchedEffect(visibleKey) { status = current() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME ||
                    event == androidx.lifecycle.Lifecycle.Event.ON_START
                ) {
                    status = current()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return status
}

/** 跳转到系统「闹钟和提醒」授权页；目标页不存在时退回应用详情页。 */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val request =
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(request) }.isSuccess) return
    }
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
