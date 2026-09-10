package com.questtick.ui.screens

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.questtick.sign.CookieRefresher
import com.questtick.sign.QRLoginManager
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

/** 二维码登录结果。 */
data class QRLoginResult(
    val cookie: String,
    val uid: String,
    val stoken: String,
    val stmid: String,
    val ltoken: String,
    val keepLogin: Boolean,
    val nickname: String = "",
)

/**
 * 米游社二维码扫码登录界面。
 *
 * 使用 Dialog 独立窗口显示，确保覆盖账号编辑 BottomSheet。
 *
 * @param deviceId 米游社接口使用的设备 ID。
 * @param onResult 登录成功后返回 Cookie 与刷新凭证。
 * @param onCancel 用户取消扫码。
 */
@Composable
fun QRLoginScreen(
    deviceId: String,
    visible: Boolean = true,
    onResult: (QRLoginResult) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit = {},
) {
    val httpTransport = LocalHttpTransport.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var ticket by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<QRLoginManager.ScanStatus>(QRLoginManager.ScanStatus.Created) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }
    // 扫码成功后的保持登录选择弹窗。
    var confirmedData by remember { mutableStateOf<QRLoginManager.ScanStatus.Confirmed?>(null) }
    // 刷新二维码时递增的 key。
    var refreshKey by remember { mutableStateOf(0) }
    var showPendingCancelDialog by remember { mutableStateOf(false) }

    fun requestCancel() {
        if (status is QRLoginManager.ScanStatus.Scanned && confirmedData == null) {
            showPendingCancelDialog = true
        } else {
            onCancel()
        }
    }

    // 退出动画仍保留组合；不可见时不创建二维码，也不启动后续轮询。
    LaunchedEffect(visible, refreshKey) {
        if (!visible) return@LaunchedEffect
        loading = true
        errorMsg = ""
        status = QRLoginManager.ScanStatus.Created
        ticket = ""
        qrBitmap = null
        confirmedData = null
        showPendingCancelDialog = false

        val result =
            withContext(Dispatchers.IO) {
                QRLoginManager.createQRCode(deviceId, httpTransport, onError)
            }
        val qr =
            result.getOrElse { e ->
                errorMsg = e.message ?: "生成二维码失败"
                onError("createQRLogin result failure: ${e.message.orEmpty()}")
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
        QRLoginManager
            .pollStatus(ticket, deviceId, httpTransport, recordError = onError)
            .flowOn(Dispatchers.IO)
            .collect { s ->
                status = s
                if (s is QRLoginManager.ScanStatus.Error) onError("扫码轮询终态: ${s.msg}")
                if (s !is QRLoginManager.ScanStatus.Scanned) {
                    showPendingCancelDialog = false
                }
                if (s is QRLoginManager.ScanStatus.Confirmed) {
                    confirmedData = s
                }
            }
    }

    QrLoginScaffold(
        title = "米游社扫码登录",
        visualState = mysVisualState(loading, errorMsg, confirmedData, status),
        qrBitmap = qrBitmap,
        statusMessage = mysStatusMessage(status, confirmedData),
        refreshEnabled = !loading,
        showSuccessContent = confirmedData != null,
        showPendingCancelDialog = showPendingCancelDialog,
        visible = visible,
        onDismissPendingCancel = { showPendingCancelDialog = false },
        onConfirmPendingCancel = onCancel,
        onRefresh = { refreshKey++ },
        onCancelRequest = { requestCancel() },
    ) {
        confirmedData?.let { data ->
            MysQrSuccessChoiceCard(data = data, onResult = onResult)
        }
    }
}

internal fun mysVisualState(
    loading: Boolean,
    errorMsg: String,
    confirmed: QRLoginManager.ScanStatus.Confirmed?,
    status: QRLoginManager.ScanStatus,
): QrVisualState =
    when {
        loading -> QrVisualState.Loading
        errorMsg.isNotBlank() -> QrVisualState.Error(message = errorMsg)
        confirmed != null -> QrVisualState.Success
        status is QRLoginManager.ScanStatus.Expired -> QrVisualState.Expired
        status is QRLoginManager.ScanStatus.Cancelled -> QrVisualState.Cancelled
        status is QRLoginManager.ScanStatus.Scanned -> QrVisualState.Scanned
        status is QRLoginManager.ScanStatus.TransientError -> QrVisualState.Waiting
        status is QRLoginManager.ScanStatus.Error -> QrVisualState.Error(message = status.msg)
        else -> QrVisualState.Waiting
    }

@Composable
private fun mysStatusMessage(
    status: QRLoginManager.ScanStatus,
    confirmed: QRLoginManager.ScanStatus.Confirmed?,
): QrStatusMessage =
    when {
        confirmed != null -> QrStatusMessage("登录成功！请选择是否保持登录状态", SuccessGreen)
        status is QRLoginManager.ScanStatus.Created -> QrStatusMessage("等待扫码…", TextSecondary)
        status is QRLoginManager.ScanStatus.Scanned -> {
            QrStatusMessage("已扫码，请在手机上确认", MaterialTheme.colorScheme.primary)
        }
        status is QRLoginManager.ScanStatus.TransientError -> {
            QrStatusMessage("网络波动，正在继续重试…", WarnAmber)
        }
        status is QRLoginManager.ScanStatus.Expired -> QrStatusMessage("二维码已过期，请点击刷新", WarnAmber)
        status is QRLoginManager.ScanStatus.Cancelled -> QrStatusMessage("用户已取消扫码", WarnAmber)
        status is QRLoginManager.ScanStatus.Error -> QrStatusMessage(status.msg, DangerRed)
        else -> QrStatusMessage("", TextSecondary)
    }

internal fun buildMysQrLoginResult(
    data: QRLoginManager.ScanStatus.Confirmed,
    keepLogin: Boolean,
): QRLoginResult {
    val fullCookie = CookieRefresher.buildCookie(data.uid, data.cookieToken, data.ltoken, data.mid)
    val canKeepLogin = keepLogin && data.uid.isNotBlank() && data.stoken.isNotBlank()
    return if (canKeepLogin) {
        QRLoginResult(
            cookie = fullCookie,
            uid = data.uid,
            stoken = data.stoken,
            stmid = data.mid,
            ltoken = data.ltoken,
            keepLogin = true,
            nickname = data.nickname,
        )
    } else {
        QRLoginResult(
            cookie = fullCookie,
            uid = "",
            stoken = "",
            stmid = "",
            ltoken = "",
            keepLogin = false,
            nickname = data.nickname,
        )
    }
}

@Composable
private fun MysQrSuccessChoiceCard(
    data: QRLoginManager.ScanStatus.Confirmed,
    onResult: (QRLoginResult) -> Unit,
) {
    QrKeepLoginChoiceCard(
        keepDescription = "Cookie 失效后可自动刷新，无需重新扫码",
        onceDescription = "仅使用本次 Cookie，失效后需重新扫码",
        onKeep = { onResult(buildMysQrLoginResult(data, keepLogin = true)) },
        onOnce = { onResult(buildMysQrLoginResult(data, keepLogin = false)) },
        keepEnabled = data.stoken.isNotBlank(),
        keepUnavailableDescription = "本次扫码未返回自动刷新凭证，只能保存普通 Cookie。",
    )
}
