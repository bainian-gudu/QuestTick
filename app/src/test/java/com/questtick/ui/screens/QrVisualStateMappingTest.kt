package com.questtick.ui.screens

import com.questtick.sign.CloudQRLogin
import com.questtick.sign.QRLoginManager
import com.questtick.ui.components.qr.QrVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QrVisualStateMappingTest {
    @Test
    fun mysVisualStateMapsLoadingAndErrorsFirst() {
        assertSame(
            QrVisualState.Loading,
            mysVisualState(
                loading = true,
                errorMsg = "",
                confirmed = null,
                status = QRLoginManager.ScanStatus.Created,
            ),
        )
        assertEquals(
            QrVisualState.Error("生成失败"),
            mysVisualState(
                loading = false,
                errorMsg = "生成失败",
                confirmed = null,
                status = QRLoginManager.ScanStatus.Created,
            ),
        )
    }

    @Test
    fun mysVisualStateMapsScanLifecycle() {
        assertSame(
            QrVisualState.Waiting,
            mysVisualState(false, "", null, QRLoginManager.ScanStatus.Created),
        )
        assertSame(
            QrVisualState.Scanned,
            mysVisualState(false, "", null, QRLoginManager.ScanStatus.Scanned),
        )
        val confirmed = mysConfirmed()
        assertSame(
            QrVisualState.Success,
            mysVisualState(false, "", confirmed, confirmed),
        )
    }

    @Test
    fun mysVisualStateMapsTerminalStates() {
        assertSame(
            QrVisualState.Expired,
            mysVisualState(false, "", null, QRLoginManager.ScanStatus.Expired),
        )
        assertSame(
            QrVisualState.Cancelled,
            mysVisualState(false, "", null, QRLoginManager.ScanStatus.Cancelled),
        )
        assertEquals(
            QrVisualState.Error("轮询失败"),
            mysVisualState(false, "", null, QRLoginManager.ScanStatus.Error("轮询失败")),
        )
    }

    @Test
    fun cloudVisualStateMapsLoadingAndErrorsFirst() {
        assertSame(
            QrVisualState.Loading,
            cloudVisualState(
                loading = true,
                errorMsg = "",
                comboToken = "",
                exchangeError = "",
                exchanging = false,
                status = CloudQRLogin.ScanStatus.Created,
            ),
        )
        assertEquals(
            QrVisualState.Error("生成失败"),
            cloudVisualState(
                loading = false,
                errorMsg = "生成失败",
                comboToken = "",
                exchangeError = "",
                exchanging = false,
                status = CloudQRLogin.ScanStatus.Created,
            ),
        )
    }

    @Test
    fun cloudVisualStateMapsExchangeLifecycle() {
        assertSame(
            QrVisualState.Waiting,
            cloudVisualState(false, "", "", "", false, CloudQRLogin.ScanStatus.Created),
        )
        assertSame(
            QrVisualState.Scanned,
            cloudVisualState(false, "", "", "", false, CloudQRLogin.ScanStatus.Scanned),
        )
        assertEquals(
            QrVisualState.Busy("正在获取云游戏 Token…"),
            cloudVisualState(false, "", "", "", true, CloudQRLogin.ScanStatus.Scanned),
        )
        assertEquals(
            QrVisualState.Busy("正在获取云游戏 Token…"),
            cloudVisualState(false, "", "", "", false, cloudConfirmed()),
        )
        assertSame(
            QrVisualState.Success,
            cloudVisualState(false, "", "combo", "", false, cloudConfirmed()),
        )
    }

    @Test
    fun cloudVisualStateMapsTerminalStates() {
        assertEquals(
            QrVisualState.Error("兑换失败", title = "获取 Token 失败"),
            cloudVisualState(false, "", "", "兑换失败", false, cloudConfirmed()),
        )
        assertSame(
            QrVisualState.Expired,
            cloudVisualState(false, "", "", "", false, CloudQRLogin.ScanStatus.Expired),
        )
        assertSame(
            QrVisualState.Cancelled,
            cloudVisualState(false, "", "", "", false, CloudQRLogin.ScanStatus.Cancelled),
        )
        assertEquals(
            QrVisualState.Error("轮询失败"),
            cloudVisualState(false, "", "", "", false, CloudQRLogin.ScanStatus.Error("轮询失败")),
        )
    }

    private fun mysConfirmed(): QRLoginManager.ScanStatus.Confirmed =
        QRLoginManager.ScanStatus.Confirmed(
            uid = "10001",
            cookieToken = "cookie_token",
            ltoken = "ltoken",
            stoken = "stoken",
            mid = "mid",
        )

    private fun cloudConfirmed(): CloudQRLogin.ScanStatus.Confirmed =
        CloudQRLogin.ScanStatus.Confirmed(
            uid = "10001",
            mid = "mid",
            cookieHeader = "cookie=value",
        )
}
