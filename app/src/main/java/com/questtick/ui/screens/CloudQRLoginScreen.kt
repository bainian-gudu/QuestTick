package com.questtick.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.questtick.sign.CloudQRLogin
import com.questtick.ui.LocalHttpTransport
import com.questtick.ui.components.qr.QrKeepLoginChoiceCard
import com.questtick.ui.components.qr.QrLoginScaffold
import com.questtick.ui.components.qr.QrStatusMessage
import com.questtick.ui.components.qr.QrVisualState
import com.questtick.ui.components.qr.generateQrBitmap
import com.questtick.ui.theme.DangerRed
import com.questtick.ui.theme.SuccessGreen
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** 云游戏扫码登录结果。 */
data class CloudQRLoginResult(
    val comboToken: String,
    /** 保持登录时保存网页登录 Cookie，用于后续续期 combo_token。 */
    val cookieHeader: String,
    /** true 表示保存网页登录态并支持后续续期；false 表示仅保存当前 token。 */
    val keepLogin: Boolean,
)

/**
 * 云游戏扫码登录界面。
 *
 * 使用 Dialog 独立窗口显示，确保覆盖账号编辑 BottomSheet。
 *
 * @param gameKey "CloudYS" 表示云原神，"CloudSR" 表示云崩铁。
 * @param deviceId 云游戏接口使用的设备 ID。
 * @param onResult 登录成功后返回 token、Cookie 与保持登录选择。
 * @param onCancel 用户取消扫码。
 */
@Composable
fun CloudQRLoginScreen(
    gameKey: String,
    deviceId: String,
    visible: Boolean = true,
    onResult: (CloudQRLoginResult) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit = {},
) {
    val httpTransport = LocalHttpTransport.current
    val gameName = if (gameKey == "CloudYS") "云原神" else "云崩铁"

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var ticket by remember { mutableStateOf("") }
    // 显式声明父类型，避免状态变量被推断为 Created 子类。
    var status by remember { mutableStateOf<CloudQRLogin.ScanStatus>(CloudQRLogin.ScanStatus.Created) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf<CloudQRLogin.ScanStatus.Confirmed?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    var exchanging by remember { mutableStateOf(false) }
    var exchangeError by remember { mutableStateOf("") }
    var comboToken by remember { mutableStateOf("") }
    var showPendingCancelDialog by remember { mutableStateOf(false) }

    fun requestCancel() {
        if (status is CloudQRLogin.ScanStatus.Scanned && confirmed == null && comboToken.isBlank()) {
            showPendingCancelDialog = true
        } else {
            onCancel()
        }
    }

    // 退出动画仍保留组合；不可见时不创建二维码，也不启动后续轮询与凭证兑换。
    LaunchedEffect(visible, refreshKey) {
        if (!visible) return@LaunchedEffect
        loading = true
        errorMsg = ""
        exchangeError = ""
        comboToken = ""
        exchanging = false
        status = CloudQRLogin.ScanStatus.Created
        ticket = ""
        qrBitmap = null
        confirmed = null
        showPendingCancelDialog = false

        val result = withContext(Dispatchers.IO) { CloudQRLogin.createQRCode(gameKey, deviceId, httpTransport, onError) }
        val qr =
            result.getOrElse { e ->
                errorMsg = e.message ?: "生成二维码失败"
                onError("$gameName createQRLogin result failure: ${e.message.orEmpty()}")
                loading = false
                return@LaunchedEffect
            }

        ticket = qr.ticket
        qrBitmap = withContext(Dispatchers.IO) { generateQrBitmap(qr.url) }
        if (qrBitmap == null) {
            ticket = ""
            errorMsg = "生成二维码失败"
        }
        loading = false
    }

    LaunchedEffect(visible, ticket) {
        if (!visible || ticket.isBlank()) return@LaunchedEffect
        CloudQRLogin
            .pollStatus(gameKey, ticket, deviceId, httpTransport, recordError = onError)
            .flowOn(Dispatchers.IO)
            .collect { s ->
                status = s
                if (s is CloudQRLogin.ScanStatus.Error) onError("$gameName 扫码轮询终态: ${s.msg}")
                if (s !is CloudQRLogin.ScanStatus.Scanned) {
                    showPendingCancelDialog = false
                }
                if (s is CloudQRLogin.ScanStatus.Confirmed) {
                    confirmed = s
                }
            }
    }

    // 扫码确认后使用网页登录态调用 SDK webLogin，换取 x-rpc-combo_token。
    LaunchedEffect(visible, confirmed) {
        if (!visible) return@LaunchedEffect
        val c = confirmed ?: return@LaunchedEffect
        exchanging = true
        exchangeError = ""
        val result =
            withContext(Dispatchers.IO) {
                CloudQRLogin.exchangeComboToken(gameKey, c.cookieHeader, deviceId, httpTransport, onError)
            }
        val token =
            result.getOrElse { e ->
                exchangeError = e.message ?: "获取云游戏 Token 失败"
                exchanging = false
                return@LaunchedEffect
            }

        comboToken = token
        exchanging = false
    }

    QrLoginScaffold(
        title = "${gameName}扫码登录",
        visualState =
            cloudVisualState(
                loading = loading,
                errorMsg = errorMsg,
                comboToken = comboToken,
                exchangeError = exchangeError,
                exchanging = exchanging,
                status = status,
            ),
        qrBitmap = qrBitmap,
        statusMessage =
            cloudStatusMessage(
                comboToken = comboToken,
                exchangeError = exchangeError,
                exchanging = exchanging,
                status = status,
            ),
        instructionTargetName = gameName,
        refreshEnabled = !loading && !exchanging,
        showSuccessContent = comboToken.isNotBlank(),
        showPendingCancelDialog = showPendingCancelDialog,
        visible = visible,
        onDismissPendingCancel = { showPendingCancelDialog = false },
        onConfirmPendingCancel = onCancel,
        onRefresh = { refreshKey++ },
        onCancelRequest = { requestCancel() },
    ) {
        CloudQrSuccessChoiceCard(
            comboToken = comboToken,
            cookieHeader = confirmed?.cookieHeader.orEmpty(),
            onResult = onResult,
        )
    }
}

internal fun cloudVisualState(
    loading: Boolean,
    errorMsg: String,
    comboToken: String,
    exchangeError: String,
    exchanging: Boolean,
    status: CloudQRLogin.ScanStatus,
): QrVisualState =
    when {
        loading -> {
            QrVisualState.Loading
        }

        errorMsg.isNotBlank() -> {
            QrVisualState.Error(message = errorMsg)
        }

        comboToken.isNotBlank() -> {
            QrVisualState.Success
        }

        exchangeError.isNotBlank() -> {
            QrVisualState.Error(message = exchangeError, title = "获取 Token 失败")
        }

        exchanging -> {
            QrVisualState.Busy("正在获取云游戏 Token…")
        }

        status is CloudQRLogin.ScanStatus.Confirmed -> {
            QrVisualState.Busy("正在获取云游戏 Token…")
        }

        status is CloudQRLogin.ScanStatus.Expired -> {
            QrVisualState.Expired
        }

        status is CloudQRLogin.ScanStatus.Cancelled -> {
            QrVisualState.Cancelled
        }

        status is CloudQRLogin.ScanStatus.Scanned -> {
            QrVisualState.Scanned
        }

        status is CloudQRLogin.ScanStatus.TransientError -> {
            QrVisualState.Waiting
        }

        status is CloudQRLogin.ScanStatus.Error -> {
            QrVisualState.Error(message = status.msg)
        }

        else -> {
            QrVisualState.Waiting
        }
    }

@Composable
private fun cloudStatusMessage(
    comboToken: String,
    exchangeError: String,
    exchanging: Boolean,
    status: CloudQRLogin.ScanStatus,
): QrStatusMessage =
    when {
        comboToken.isNotBlank() -> {
            QrStatusMessage("登录成功！请选择是否保持登录状态", SuccessGreen)
        }

        exchangeError.isNotBlank() -> {
            QrStatusMessage(exchangeError, DangerRed)
        }

        exchanging -> {
            QrStatusMessage("正在获取云游戏 Token…", MaterialTheme.colorScheme.primary)
        }

        status is CloudQRLogin.ScanStatus.Confirmed -> {
            QrStatusMessage("正在获取云游戏 Token…", MaterialTheme.colorScheme.primary)
        }

        status is CloudQRLogin.ScanStatus.Created -> {
            QrStatusMessage("等待扫码…", TextSecondary)
        }

        status is CloudQRLogin.ScanStatus.Scanned -> {
            QrStatusMessage("已扫码，请在手机上确认", MaterialTheme.colorScheme.primary)
        }

        status is CloudQRLogin.ScanStatus.TransientError -> {
            QrStatusMessage("网络波动，正在继续重试…", WarnAmber)
        }

        status is CloudQRLogin.ScanStatus.Expired -> {
            QrStatusMessage("二维码已过期", WarnAmber)
        }

        status is CloudQRLogin.ScanStatus.Cancelled -> {
            QrStatusMessage("用户已取消扫码", WarnAmber)
        }

        status is CloudQRLogin.ScanStatus.Error -> {
            QrStatusMessage(status.msg, DangerRed)
        }

        else -> {
            QrStatusMessage("", TextSecondary)
        }
    }

internal fun buildCloudQrLoginResult(
    comboToken: String,
    cookieHeader: String,
    keepLogin: Boolean,
): CloudQRLoginResult =
    CloudQRLoginResult(
        comboToken = comboToken,
        cookieHeader = if (keepLogin) cookieHeader else "",
        keepLogin = keepLogin,
    )

@Composable
private fun CloudQrSuccessChoiceCard(
    comboToken: String,
    cookieHeader: String,
    onResult: (CloudQRLoginResult) -> Unit,
) {
    QrKeepLoginChoiceCard(
        keepDescription = "Token 失效后可自动续期，无需重新扫码",
        onceDescription = "仅使用本次 Token，失效后需重新扫码",
        onKeep = { onResult(buildCloudQrLoginResult(comboToken, cookieHeader, keepLogin = true)) },
        onOnce = { onResult(buildCloudQrLoginResult(comboToken, cookieHeader, keepLogin = false)) },
    )
}
