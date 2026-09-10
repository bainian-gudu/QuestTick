package com.questtick.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.questtick.MainActivity
import com.questtick.R
import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord
import com.questtick.i18n.AppLocaleController
import com.questtick.i18n.localizeText

/**
 * 本地通知工具。
 *
 * 负责创建通知渠道、检查通知权限并展示签到结果摘要；相关热路径已纳入 Baseline Profile，
 * 修改后请同步检查规则。
 */
object Notifier {
    private const val CHANNEL_ID = "signin_results"
    private const val NOTIFY_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val language = AppLocaleController.resolved(context)
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    localizeText("签到结果", language),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = localizeText("米游社/云游戏签到完成后的结果通知", language)
                }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun canShowResultNotifications(context: Context): Boolean {
        ensureChannel(context)
        return hasPermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            isResultChannelEnabled(context)
    }

    fun openNotificationSettings(context: Context) {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun isResultChannelEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun showResult(
        context: Context,
        record: RunRecord,
    ): Boolean {
        if (!canShowResultNotifications(context)) return false

        val language = AppLocaleController.resolved(context)
        val allSkipped = record.total > 0 && record.skipped == record.total && record.failed == 0
        val title =
            localizeText(
                when {
                    record.total == 0 -> "未配置账号，已跳过"
                    record.resultUnknown == record.total -> "签到结果待确认 ⚠️"
                    allSkipped -> "签到已跳过 ⏭"
                    record.failed == 0 && record.resultUnknown == 0 ->
                        "签到完成 ✅ 成功${record.succeeded} 已签${record.alreadySigned}"
                    else ->
                        "签到完成 ⚠️ 失败${record.failed} 待确认${record.resultUnknown} 成功${record.succeeded}"
                },
                language,
            )

        val lines =
            record.results.take(8).joinToString("\n") {
                val flag =
                    when {
                        it.failureCategory == FailureCategory.RESULT_UNKNOWN -> "⚠️"
                        it.skipped -> "⏭"
                        it.success && it.alreadySigned -> "☑"
                        it.success -> "✅"
                        else -> "❌"
                    }
                // 账号名、奖励名和服务端原因可能是用户/服务端原文，只翻译已知游戏和模板。
                val game = localizeText(it.game, language)
                val reward = if (it.rewardName.isNotBlank()) " · ${it.rewardName}×${it.rewardCount}" else ""
                val reason =
                    if ((it.skipped || !it.success) && it.message.isNotBlank()) {
                        " · ${localizeText(it.message.take(36), language)}"
                    } else {
                        ""
                    }
                "$flag ${it.accountLabel} · $game$reward$reason"
            }

        // 点击通知时：未配置账号跳转账号页，有结果则跳转记录页。
        val targetTab = if (record.total == 0) MainActivity.TAB_ACCOUNTS else MainActivity.TAB_RECORDS
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, targetTab)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                targetTab, // 不同 Tab 使用不同 requestCode，避免 PendingIntent 复用旧 extras。
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_signin)
                .setContentTitle(title)
                .setContentText(
                    localizeText(
                        when {
                            record.total == 0 -> "点击前往添加账号"
                            allSkipped -> record.results.firstOrNull()?.message ?: "点击查看跳过原因"
                            else -> "点击查看签到记录"
                        },
                        language,
                    ),
                ).setStyle(NotificationCompat.BigTextStyle().bigText(lines.ifEmpty { title }))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, builder.build())
            true
        } catch (_: SecurityException) {
            // 检查后权限可能被撤销，由 Outbox 记录为不可投递终态。
            false
        }
    }
}
