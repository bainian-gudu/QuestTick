package com.questtick.sign

import com.questtick.net.FakeHttpTransport
import com.questtick.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class QRLoginManagerTest {
    @Test
    fun `create QR code returns ticket and url`() =
        runTest {
            val transport =
                FakeHttpTransport { request ->
                    assertEquals(
                        "https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin",
                        request.url,
                    )
                    HttpResponse.text(200, """{"retcode":0,"data":{"ticket":"ticket","url":"https://qr.example"}}""")
                }

            val qrCode = QRLoginManager.createQRCode("device", transport).getOrThrow()

            assertEquals("ticket", qrCode.ticket)
            assertEquals("https://qr.example", qrCode.url)
        }

    @Test
    fun `exchange stoken by login ticket returns tokens`() =
        runTest {
            val transport =
                FakeHttpTransport { request ->
                    assertEquals(
                        "https://api-takumi.mihoyo.com/auth/api/getMultiTokenByLoginTicket" +
                            "?login_ticket=login-ticket&token_types=3&uid=10001",
                        request.url,
                    )
                    HttpResponse.text(
                        200,
                        """{"retcode":0,"data":{"list":[""" +
                            """{"token_type":1,"token":"stoken_value"},""" +
                            """{"token_type":2,"token":"ltoken_value"}]}}""",
                    )
                }

            val token = QRLoginManager.exchangeStokenByLoginTicket("10001", "login-ticket", transport).getOrThrow()

            assertEquals("stoken_value", token.stoken)
            assertEquals("ltoken_value", token.ltoken)
        }
}
