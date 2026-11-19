package com.questtick.sign

import com.questtick.net.HttpResponse
import com.questtick.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MysSignInTest {
    private val game = MysSignIn.GAMES.getValue("Genshin")
    private val role = MysSignIn.Role(gameUid = "100000001", region = "cn_gf01", nickname = "tester")

    @Test
    fun `official already-signed retcode is treated as already signed`() =
        runBlocking {
            val transport =
                HttpTransport {
                    HttpResponse.text(200, "{\"retcode\":-5003,\"message\":\"今日已签到\"}")
                }

            val result = MysSignIn("device", transport).signIn("cookie", game, role, retryOnActIdInvalid = false)

            assertTrue(result.ok)
            assertTrue(result.already)
        }

    @Test
    fun `risk control wording is not treated as already signed`() =
        runBlocking {
            // “今日签到过于频繁”包含旧正则的“签到过”子串；retcode 非 -5003 时必须按失败处理，
            // 否则风控限流会被误判为已签到并写入日历，导致当天后续定时签到被跳过。
            val transport =
                HttpTransport {
                    HttpResponse.text(200, "{\"retcode\":-10001,\"message\":\"今日签到过于频繁，请稍后再试\"}")
                }

            val result = MysSignIn("device", transport).signIn("cookie", game, role, retryOnActIdInvalid = false)

            assertFalse(result.ok)
            assertFalse(result.already)
        }
}
