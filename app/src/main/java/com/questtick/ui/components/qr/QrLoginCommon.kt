package com.questtick.ui.components.qr

/** QR 登录页面共用的状态提示和操作组件。 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.ui.components.AppDialogActionStyle
import com.questtick.ui.components.AppMessageDialog
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber

@Composable
internal fun QrPendingConfirmOverlay() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "请在手机上确认",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun TerminalQrStateCard(title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = WarnAmber,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, color = WarnAmber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("请点击下方按钮刷新", color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
internal fun QrLoginInstruction(targetName: String = "") {
    val suffix = if (targetName.isBlank()) "" else " $targetName"
    Text(
        "请打开「米游社」App → 点击左上角扫码图标\n" +
            "扫描上方二维码并在手机端确认登录$suffix",
        fontSize = 13.sp,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        lineHeight = 20.sp,
    )
}

@Composable
internal fun PendingQrCancelDialog(
    onDismiss: () -> Unit,
    onConfirmExit: () -> Unit,
) {
    AppMessageDialog(
        title = "已扫码但尚未确认",
        message = "请先在手机米游社中点击确认登录。确定要退出当前扫码流程吗？",
        confirmLabel = "确认退出",
        onConfirm = onConfirmExit,
        confirmStyle = AppDialogActionStyle.DESTRUCTIVE,
        dismissLabel = "继续等待",
        onDismissAction = onDismiss,
        onDismissRequest = onDismiss,
    )
}
