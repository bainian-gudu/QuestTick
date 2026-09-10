package com.questtick.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrConfirmedCookieParsingTest {
    @Test
    fun parseConfirmedCookiesBuildsConfirmedFromV2CookiesAndUserInfo() {
        val status =
            QRLoginManager.parseConfirmedCookies(
                setCookieHeaders =
                    listOf(
                        "stuid=old_uid; Path=/",
                        "cookie_token=old_cookie; Path=/",
                        "cookie_token_v2=cookie_v2; Path=/",
                        "ltoken=old_ltoken; Path=/",
                        "ltoken_v2=ltoken_v2; Path=/",
                        "stoken=old_stoken; Path=/",
                        "stoken_v2=stoken_v2; Path=/; HttpOnly",
                        "account_mid_v2=cookie_mid; Path=/",
                    ),
                userUid = "user_uid",
                userMid = "user_mid",
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("user_uid", confirmed.uid)
        assertEquals("user_mid", confirmed.mid)
        assertEquals("cookie_v2", confirmed.cookieToken)
        assertEquals("ltoken_v2", confirmed.ltoken)
        assertEquals("stoken_v2", confirmed.stoken)
    }

    @Test
    fun parseConfirmedCookiesFallsBackToCookieUidAndMidFields() {
        val status =
            QRLoginManager.parseConfirmedCookies(
                setCookieHeaders =
                    listOf(
                        "account_id_v2=10001; Path=/",
                        "cookie_token=cookie_token; Path=/",
                        "ltoken=ltoken; Path=/",
                        "stoken=stoken; Path=/",
                        "mid=legacy_mid; Path=/",
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("10001", confirmed.uid)
        assertEquals("legacy_mid", confirmed.mid)
        assertEquals("cookie_token", confirmed.cookieToken)
        assertEquals("ltoken", confirmed.ltoken)
        assertEquals("stoken", confirmed.stoken)
    }

    @Test
    fun parseConfirmedCookiesKeepsLoginTicketWhenStokenIsMissing() {
        val status =
            QRLoginManager.parseConfirmedCookies(
                setCookieHeaders =
                    listOf(
                        "account_id=10001; Path=/",
                        "cookie_token=cookie_token; Path=/",
                        "login_ticket=login_ticket; Path=/; HttpOnly",
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("10001", confirmed.uid)
        assertEquals("", confirmed.stoken)
        assertEquals("login_ticket", confirmed.loginTicket)
    }

    @Test
    fun parseConfirmedDataMergesTokensFromResponseBody() {
        val status =
            QRLoginManager.parseConfirmedData(
                setCookieHeaders = listOf("cookie_token=cookie_from_header; Path=/"),
                data =
                    org.json.JSONObject(
                        """
                        {
                          "status": "Confirmed",
                          "user_info": {"aid": "10001", "mid": "mid_from_user", "nickname": "旅行者"},
                          "tokens": [
                            {"name": "stoken", "token": "stoken_from_body"},
                            {"name": "ltoken", "token": "ltoken_from_body"}
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("10001", confirmed.uid)
        assertEquals("mid_from_user", confirmed.mid)
        assertEquals("cookie_from_header", confirmed.cookieToken)
        assertEquals("ltoken_from_body", confirmed.ltoken)
        assertEquals("stoken_from_body", confirmed.stoken)
        assertEquals("旅行者", confirmed.nickname)
    }

    @Test
    fun parseConfirmedDataDoesNotUseAccountNameAsMysNickname() {
        val status =
            QRLoginManager.parseConfirmedData(
                setCookieHeaders = listOf("account_id=10001; Path=/", "cookie_token=cookie; Path=/"),
                data =
                    org.json.JSONObject(
                        """
                        {
                          "status": "Confirmed",
                          "user_info": {"aid": "10001", "mid": "mid", "account_name": "account_name"}
                        }
                        """.trimIndent(),
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("", confirmed.nickname)
    }

    @Test
    fun parseMysUserNicknameReadsBbsNickname() {
        val nickname =
            QRLoginManager.parseMysUserNickname(
                org.json.JSONObject(
                    """
                    {
                      "retcode": 0,
                      "data": {
                        "user_info": {
                          "uid": "10001",
                          "nickname": "米游社昵称",
                          "introduce": ""
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("米游社昵称", nickname)
    }

    @Test
    fun parseConfirmedDataPrefersTokenTypeOneForStoken() {
        val status =
            QRLoginManager.parseConfirmedData(
                setCookieHeaders =
                    listOf(
                        "stuid=10001; Path=/",
                        "cookie_token=cookie_from_header; Path=/",
                        "stoken=stoken_from_header; Path=/",
                    ),
                data =
                    org.json.JSONObject(
                        """
                        {
                          "status": "Confirmed",
                          "user_info": {"aid": "10001", "mid": "mid_from_user"},
                          "tokens": [
                            {"name": "stoken", "token": "stoken_from_name"},
                            {"token_type": 1, "token": "stoken_from_type_one"}
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("stoken_from_type_one", confirmed.stoken)
    }

    @Test
    fun parseConfirmedDataSupportsTokenTypeFields() {
        val status =
            QRLoginManager.parseConfirmedData(
                setCookieHeaders = emptyList(),
                data =
                    org.json.JSONObject(
                        """
                        {
                          "status": "Confirmed",
                          "user_info": {"uid": "10001", "account_mid": "mid_from_user"},
                          "tokens": [
                            {"token_type": 4, "token": "cookie_from_body"},
                            {"token_type": 1, "token": "stoken_from_body"}
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("10001", confirmed.uid)
        assertEquals("mid_from_user", confirmed.mid)
        assertEquals("cookie_from_body", confirmed.cookieToken)
        assertEquals("stoken_from_body", confirmed.stoken)
    }

    @Test
    fun parseConfirmedCookiesAllowsLoginTicketForStokenExchange() {
        val status =
            QRLoginManager.parseConfirmedCookies(
                setCookieHeaders =
                    listOf(
                        "account_id=10001; Path=/",
                        "login_ticket=login_ticket; Path=/; HttpOnly",
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("10001", confirmed.uid)
        assertEquals("", confirmed.cookieToken)
        assertEquals("", confirmed.stoken)
        assertEquals("login_ticket", confirmed.loginTicket)
    }

    @Test
    fun parseConfirmedDataAllowsStokenBeforeCookieRefresh() {
        val status =
            QRLoginManager.parseConfirmedData(
                setCookieHeaders = emptyList(),
                data =
                    org.json.JSONObject(
                        """
                        {
                          "status": "Confirmed",
                          "user_info": {"aid": "10001", "mid": "mid_from_user"},
                          "tokens": [
                            {"token_type": 1, "token": "stoken_from_body"}
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val confirmed = status as QRLoginManager.ScanStatus.Confirmed
        assertEquals("10001", confirmed.uid)
        assertEquals("mid_from_user", confirmed.mid)
        assertEquals("", confirmed.cookieToken)
        assertEquals("stoken_from_body", confirmed.stoken)
    }

    @Test
    fun parseMultiTokenResponseExtractsStokenAndLtoken() {
        val token =
            QRLoginManager.parseMultiTokenResponse(
                org.json.JSONObject(
                    """
                    {
                      "retcode": 0,
                      "data": {
                        "list": [
                          {"name":"ltoken","token":"ltoken_value"},
                          {"name":"stoken","token":"stoken_value"}
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("stoken_value", token.stoken)
        assertEquals("ltoken_value", token.ltoken)
    }

    @Test
    fun parseMultiTokenResponseSupportsStokenV2Name() {
        val token =
            QRLoginManager.parseMultiTokenResponse(
                org.json.JSONObject(
                    """
                    {
                      "retcode": 0,
                      "data": {
                        "list": [
                          {"name":"stoken_v2","token":"stoken_v2_value"},
                          {"name":"ltoken_v2","token":"ltoken_v2_value"}
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("stoken_v2_value", token.stoken)
        assertEquals("ltoken_v2_value", token.ltoken)
    }

    @Test
    fun parseMultiTokenResponsePrefersTokenTypeOneForStoken() {
        val token =
            QRLoginManager.parseMultiTokenResponse(
                org.json.JSONObject(
                    """
                    {
                      "retcode": 0,
                      "data": {
                        "list": [
                          {"name":"stoken","token":"stoken_from_name"},
                          {"token_type":1,"token":"stoken_from_type_one"}
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("stoken_from_type_one", token.stoken)
    }

    @Test
    fun parseConfirmedCookiesReturnsErrorWhenUidIsMissing() {
        val status =
            QRLoginManager.parseConfirmedCookies(
                setCookieHeaders = listOf("cookie_token=cookie_token; Path=/"),
            )

        assertTrue(status is QRLoginManager.ScanStatus.Error)
        assertEquals("扫码确认成功，但未能解析账号 UID", (status as QRLoginManager.ScanStatus.Error).msg)
    }

    @Test
    fun parseConfirmedCookiesReturnsErrorWhenCookieTokenIsMissing() {
        val status =
            QRLoginManager.parseConfirmedCookies(
                setCookieHeaders = listOf("stuid=10001; Path=/"),
            )

        assertTrue(status is QRLoginManager.ScanStatus.Error)
        assertEquals(
            "扫码确认成功，但响应未返回 cookie_token、stoken 或 login_ticket",
            (status as QRLoginManager.ScanStatus.Error).msg,
        )
    }
}
