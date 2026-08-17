package com.questtick

/** 应用更新提示、下载进度和安装流程的页面承载逻辑。 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.questtick.i18n.Text
import com.questtick.ui.components.AppAlertDialog
import com.questtick.ui.components.AppDialogActionButton
import com.questtick.ui.components.AppDialogActionStyle
import com.questtick.ui.components.appDialogColors
import com.questtick.ui.vm.AppUpdateDownloadProgress
import com.questtick.ui.vm.AppUpdatePrompt
import com.questtick.ui.vm.SettingsViewModel

@Composable
internal fun UpdateOverlayHost(
    updateDownloadProgress: AppUpdateDownloadProgress?,
    updatePrompt: AppUpdatePrompt?,
    settingsVm: SettingsViewModel,
) {
    val dialogColors = appDialogColors()
    updateDownloadProgress?.let { progress ->
        UpdateDownloadDialog(progress = progress)
    }

    updatePrompt?.let { prompt ->
        val releaseNotes = prompt.info.releaseNotes.ifBlank { "暂无更新公告。" }
        val updateQuestion =
            if (prompt.apkReady) {
                "安装包已下载，是否现在安装更新？"
            } else {
                "是否现在下载并安装更新？"
            }
        val confirmText = if (prompt.apkReady) "立即安装" else "立即更新"
        AppAlertDialog(
            title = "发现新版本 ${prompt.info.latestVersion}",
            onDismissRequest = settingsVm::dismissUpdatePrompt,
            content = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "当前版本：${prompt.info.currentVersion}\n新版本：${prompt.info.latestVersion}",
                        color = dialogColors.body,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "更新公告",
                        fontWeight = FontWeight.Bold,
                        color = dialogColors.title,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        releaseNotes,
                        color = dialogColors.body,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        updateQuestion,
                        color = dialogColors.title,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            actions = {
                if (prompt.automatic) {
                    AppDialogActionButton(
                        label = "关闭自动更新",
                        onClick = settingsVm::disableAutoUpdateFromPrompt,
                        style = AppDialogActionStyle.NEUTRAL,
                    )
                }
                AppDialogActionButton(
                    label = "稍后提醒",
                    onClick = settingsVm::dismissUpdatePrompt,
                    style = AppDialogActionStyle.NEUTRAL,
                )
                AppDialogActionButton(
                    label = confirmText,
                    onClick = { settingsVm.startUpdateFromPrompt(prompt.info) },
                )
            },
        )
    }
}

@Composable
internal fun UpdateDownloadDialog(progress: AppUpdateDownloadProgress) {
    val dialogColors = appDialogColors()
    val knownTotal = progress.totalBytes > 0L
    val ratio =
        if (knownTotal) {
            (progress.downloadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
    val sizeText =
        if (knownTotal) {
            "${formatReadableBytes(progress.downloadedBytes)} / ${formatReadableBytes(progress.totalBytes)}"
        } else {
            formatReadableBytes(progress.downloadedBytes)
        }
    val speedText = "${formatReadableBytes(progress.speedBytesPerSecond)}/s"

    AppAlertDialog(
        title = "正在下载更新 ${progress.version}",
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        content = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (knownTotal) "下载进度" else "正在连接…",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = dialogColors.title,
                )
                Text(
                    if (knownTotal) "${progress.percent}%" else "--",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = dialogColors.primaryAction,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(dialogColors.progressTrack),
            ) {
                if (knownTotal && ratio > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(ratio)
                            .fillMaxSize()
                            .background(dialogColors.primaryAction),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已下载 $sizeText",
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    color = dialogColors.body,
                )
                Text(
                    speedText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = dialogColors.body,
                )
            }
        },
    )
}
