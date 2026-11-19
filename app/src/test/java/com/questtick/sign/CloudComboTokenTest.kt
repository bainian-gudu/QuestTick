package com.questtick.sign

import com.questtick.net.FakeHttpTransport
import com.questtick.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudComboTokenTest {
    @Test
    fun createQRCodeReturnsTicketAndUrl() =
        runTest {
            val transport =
                FakeHttpTransport { request ->
                    assertEquals(
                        "https://passport-api.mihoyo.com/account/ma-cn-passport/web/createQRLogin",
                        request.url,
                    )
                    HttpResponse.text(200, """{"retcode":0,"data":{"ticket":"ticket","url":"https://qr.example"}}""")
                }

            val qrCode = CloudQRLogin.createQRCode("CloudYS", "device", transport).getOrThrow()

            assertEquals("ticket", qrCode.ticket)
            assertEquals("https://qr.example", qrCode.url)
        }

    @Test
    fun exchangeComboTokenBuildsToken() =
        runTest {
            val transport =
                FakeHttpTransport { request ->
                    assertEquals(
                        "https://hk4e-sdk.mihoyo.com/hk4e_cn/combo/granter/login/webLogin",
                        request.url,
                    )
                    HttpResponse.text(
                        200,
                        """{"retcode":0,"data":{"app_id":"4","channel_id":"1","open_id":"open_id","combo_token":"combo_token"}}""",
                    )
                }

            val token = CloudQRLogin.exchangeComboToken("CloudYS", "cookie=value", "device", transport).getOrThrow()

            assertTrue(token.startsWith("ai=4;ci=1;oi=open_id;ct=combo_token;"))
            assertTrue(token.endsWith(";bi=hk4e_cn"))
        }

    @Test
    fun buildWebComboTokenSignsGenshinPayload() {
        val token =
            CloudQRLogin.buildWebComboToken(
                gameKey = "CloudYS",
                appId = CloudQRLogin.APP_ID_GENSHIN,
                channelId = "1",
                openId = "open_id",
                comboToken = "combo_token",
            )

        assertEquals(
            "ai=4;ci=1;oi=open_id;ct=combo_token;" +
                "si=0afea8f1465a93b433a6e0c56c287045f3dc6bfcc6e2afbbd1e76b42b16584d1;bi=hk4e_cn",
            token,
        )
    }

    @Test
    fun buildWebComboTokenSignsStarrailPayload() {
        val token =
            CloudQRLogin.buildWebComboToken(
                gameKey = "CloudSR",
                appId = CloudQRLogin.APP_ID_STARRAIL,
                channelId = "1",
                openId = "open_id",
                comboToken = "combo_token",
            )

        assertEquals(
            "ai=8;ci=1;oi=open_id;ct=combo_token;" +
                "si=ab54c55904ed314422ab3d1f5635336576979a2cdef804549d8699aef0dee8ff;bi=hkrpg_cn",
            token,
        )
    }

    @Test
    fun parseWebLoginComboTokenReadsNestedPayloadAndUsesConfigDefaults() {
        val json =
            JSONObject().put(
                "data",
                JSONObject().put(
                    "data",
                    JSONObject()
                        .put("open_id", "open_id")
                        .put("combo_token", "combo_token"),
                ),
            )

        val token = CloudQRLogin.parseWebLoginComboToken("CloudYS", json).getOrThrow()

        assertTrue(token.startsWith("ai=4;ci=1;oi=open_id;ct=combo_token;"))
        assertTrue(token.endsWith(";bi=hk4e_cn"))
    }

    @Test
    fun parseWebLoginComboTokenReadsStringNestedPayload() {
        val nested = JSONObject().put("open_id", "open_id").put("combo_token", "combo_token")
        val json = JSONObject().put("data", JSONObject().put("data", nested.toString()))

        val token = CloudQRLogin.parseWebLoginComboToken("CloudSR", json).getOrThrow()

        assertTrue(token.startsWith("ai=8;ci=1;oi=open_id;ct=combo_token;"))
        assertTrue(token.endsWith(";bi=hkrpg_cn"))
    }

    @Test
    fun parseWebLoginComboTokenReturnsFailureWhenRequiredFieldsMissing() {
        val missingData = CloudQRLogin.parseWebLoginComboToken("CloudYS", JSONObject())
        val missingOpenId =
            CloudQRLogin.parseWebLoginComboToken(
                "CloudYS",
                JSONObject().put(
                    "data",
                    JSONObject().put("combo_token", "combo_token"),
                ),
            )

        assertTrue(missingData.isFailure)
        assertEquals("combo webLogin: data is null", missingData.exceptionOrNull()?.message)
        assertTrue(missingOpenId.isFailure)
        assertEquals("combo webLogin: open_id 或 combo_token 为空", missingOpenId.exceptionOrNull()?.message)
    }

    @Test
    fun parsePassportConfirmedCookiesBuildsCookieHeaderAndPrefersUserInfo() {
        val status =
            CloudQRLogin.parsePassportConfirmedCookies(
                setCookieHeaders =
                    listOf(
                        "account_id=old_uid; Path=/; HttpOnly",
                        "account_mid_v2=cookie_mid; Domain=.miyoushe.com",
                        "login_ticket=ticket; Path=/",
                    ),
                userUid = "user_uid",
                userMid = "user_mid",
            )

        val confirmed = status as CloudQRLogin.ScanStatus.Confirmed
        assertEquals("user_uid", confirmed.uid)
        assertEquals("user_mid", confirmed.mid)
        assertEquals("account_id=old_uid; account_mid_v2=cookie_mid; login_ticket=ticket", confirmed.cookieHeader)
    }

    @Test
    fun parsePassportConfirmedCookiesFallsBackToCookieFields() {
        val status =
            CloudQRLogin.parsePassportConfirmedCookies(
                setCookieHeaders =
                    listOf(
                        "account_id_v2=10001; Path=/",
                        "ltmid_v2=mid_from_cookie; Path=/",
                    ),
            )

        val confirmed = status as CloudQRLogin.ScanStatus.Confirmed
        assertEquals("10001", confirmed.uid)
        assertEquals("mid_from_cookie", confirmed.mid)
    }

    @Test
    fun parsePassportConfirmedCookiesReturnsErrorWhenCookieHeaderIsMissing() {
        val status = CloudQRLogin.parsePassportConfirmedCookies(emptyList())

        assertTrue(status is CloudQRLogin.ScanStatus.Error)
        assertEquals("扫码确认成功，但响应未返回 Cookie", (status as CloudQRLogin.ScanStatus.Error).msg)
    }
}
