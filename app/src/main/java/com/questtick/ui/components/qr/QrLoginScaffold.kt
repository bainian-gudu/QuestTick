package com.questtick.ui.components.qr

/** QR 登录流程的统一页面骨架和二维码展示容器。 */

import com.questtick.i18n.localizedText
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.questtick.ui.components.DialogSystemBars
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary

@Composable
internal fun QrLoginScaffold(
    title: String,
    visualState: QrVisualState,
    qrBitmap: Bitmap?,
    statusMessage: QrStatusMessage,
    instructionTargetName: String = "",
    refreshEnabled: Boolean,
    showSuccessContent: Boolean,
    showPendingCancelDialog: Boolean,
    visible: Boolean = true,
    onDismissPendingCancel: () -> Unit,
    onConfirmPendingCancel: () -> Unit,
    onRefresh: () -> Unit,
    onCancelRequest: () -> Unit,
    successContent: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancelRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false,
            ),
    ) {
        DialogSystemBars()
        AnimatedVisibility(
            visible = visible,
            enter = AppMotion.screenEnter(),
            exit = AppMotion.screenExit(),
        ) {
            GalaxyBackground {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.statusBars.union(
                                WindowInsets.displayCutout.only(
                                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                                ),
                            ),
                        ).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QrLoginTopBar(title, onCancelRequest)

                    Spacer(Modifier.height(32.dp))

                    QrCodeStatePanel(
                        visualState = visualState,
                        qrBitmap = qrBitmap,
                    )

                    Spacer(Modifier.height(20.dp))

                    QrStatusLine(statusMessage)

                    Spacer(Modifier.height(24.dp))

                    if (showSuccessContent) {
                        successContent()
                    } else {
                        QrActionButtons(
                            refreshEnabled = refreshEnabled,
                            onRefresh = onRefresh,
                            onCancelRequest = onCancelRequest,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    if (!showSuccessContent) {
                        QrLoginInstruction(targetName = instructionTargetName)
                    }
                }
            }
        }
    }

    if (showPendingCancelDialog) {
        PendingQrCancelDialog(
            onDismiss = onDismissPendingCancel,
            onConfirmExit = onConfirmPendingCancel,
        )
    }
}

@Composable
private fun QrLoginTopBar(
    title: String,
    onCancelRequest: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickableNoRipple(onCancelRequest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = localizedText("关闭"),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.size(40.dp))
    }
}

@Composable
internal fun QrCodeStatePanel(
    visualState: QrVisualState,
    qrBitmap: Bitmap?,
) {
    PanelCard(modifier = Modifier.size(280.dp)) {
        Box(
            Modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (visualState) {
                QrVisualState.Loading -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
                }
                QrVisualState.Success -> {
                    QrSuccessStateCard()
                }
                QrVisualState.Expired -> {
                    TerminalQrStateCard(title = "二维码已过期")
                }
                QrVisualState.Cancelled -> {
                    TerminalQrStateCard(title = "扫码已取消")
                }
                is QrVisualState.Error -> {
                    QrErrorStateCard(visualState)
                }
                is QrVisualState.Busy -> {
                    QrBusyStateCard(visualState.message)
                }
                QrVisualState.Waiting,
                QrVisualState.Scanned,
                -> {
                    QrBitmapCard(
                        qrBitmap = qrBitmap,
                        scanned = visualState is QrVisualState.Scanned,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrBitmapCard(
    qrBitmap: Bitmap?,
    scanned: Boolean,
) {
    qrBitmap?.let { bitmap ->
        Box(contentAlignment = Alignment.Center) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = localizedText("扫码登录"),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .alpha(if (scanned) 0.3f else 1f),
            )
            if (scanned) {
                QrPendingConfirmOverlay()
            }
        }
    }
}

@Composable
private fun QrSuccessStateCard() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("登录成功", color = SuccessGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QrErrorStateCard(error: QrVisualState.Error) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = DangerRed,
            modifier = Modifier.size(48.dp),
        )
        if (error.title.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(error.title, color = DangerRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
        Text(
            error.message,
            color = if (error.title.isNotBlank()) TextSecondary else DangerRed,
            fontSize = if (error.title.isNotBlank()) 12.sp else 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QrBusyStateCard(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QrStatusLine(message: QrStatusMessage) {
    if (message.text.isNotBlank()) {
        Text(
            message.text,
            color = message.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QrActionButtons(
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    onCancelRequest: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = refreshEnabled,
        ) {
            Text("刷新二维码")
        }
        OutlinedButton(
            onClick = onCancelRequest,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("取消")
        }
    }
}
