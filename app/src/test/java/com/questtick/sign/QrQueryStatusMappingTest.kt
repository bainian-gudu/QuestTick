package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QrQueryStatusMappingTest {
    @Test
    fun mysMapQueryStatusMapsRetcodeTerminalStates() {
        assertSame(
            QRLoginManager.ScanStatus.Expired,
            QRLoginManager.mapQueryStatus(
                retcode = -3501,
                message = "expired",
                dataStatus = null,
                confirmed = mysConfirmed(),
            ),
        )
        assertSame(
            QRLoginManager.ScanStatus.Cancelled,
            QRLoginManager.mapQueryStatus(
                retcode = -3505,
                message = "cancelled",
                dataStatus = null,
                confirmed = mysConfirmed(),
            ),
        )
        assertEquals(
            QRLoginManager.ScanStatus.Error("retcode=-1"),
            QRLoginManager.mapQueryStatus(
                retcode = -1,
                message = "",
                dataStatus = null,
                confirmed = mysConfirmed(),
            ),
        )
    }

    @Test
    fun mysMapQueryStatusMapsDataStatus() {
        val confirmed = mysConfirmed()
        assertSame(
            QRLoginManager.ScanStatus.Created,
            QRLoginManager.mapQueryStatus(0, "", "Created", confirmed),
        )
        assertSame(
            QRLoginManager.ScanStatus.Scanned,
            QRLoginManager.mapQueryStatus(0, "", "Scanned", confirmed),
        )
        assertSame(
            confirmed,
            QRLoginManager.mapQueryStatus(0, "", "Confirmed", confirmed),
        )
        assertEquals(
            QRLoginManager.ScanStatus.Error("unknown status: Paused"),
            QRLoginManager.mapQueryStatus(0, "", "Paused", confirmed),
        )
    }

    @Test
    fun cloudMapQueryStatusMapsRetcodeTerminalStates() {
        assertSame(
            CloudQRLogin.ScanStatus.Expired,
            CloudQRLogin.mapQueryStatus(
                retcode = -3501,
                message = "expired",
                dataStatus = null,
                confirmed = cloudConfirmed(),
            ),
        )
        assertSame(
            CloudQRLogin.ScanStatus.Cancelled,
            CloudQRLogin.mapQueryStatus(
                retcode = -3505,
                message = "cancelled",
                dataStatus = null,
                confirmed = cloudConfirmed(),
            ),
        )
        assertEquals(
            CloudQRLogin.ScanStatus.Error("retcode=-2"),
            CloudQRLogin.mapQueryStatus(
                retcode = -2,
                message = "",
                dataStatus = null,
                confirmed = cloudConfirmed(),
            ),
        )
    }

    @Test
    fun cloudMapQueryStatusMapsDataStatus() {
        val confirmed = cloudConfirmed()
        assertSame(
            CloudQRLogin.ScanStatus.Created,
            CloudQRLogin.mapQueryStatus(0, "", "Created", confirmed),
        )
        assertSame(
            CloudQRLogin.ScanStatus.Scanned,
            CloudQRLogin.mapQueryStatus(0, "", "Scanned", confirmed),
        )
        assertSame(
            confirmed,
            CloudQRLogin.mapQueryStatus(0, "", "Confirmed", confirmed),
        )
        assertEquals(
            CloudQRLogin.ScanStatus.Error("unknown status: Paused"),
            CloudQRLogin.mapQueryStatus(0, "", "Paused", confirmed),
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
