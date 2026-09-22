package com.questtick.sign

import com.questtick.net.FakeHttpTransport
import com.questtick.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActIdTest {
    @Test
    fun `valid act_id format`() {
        assertTrue(ActId.isValid("e202311201442471"))
        assertTrue(ActId.isValid("e202406242138391"))
        assertFalse(ActId.isValid(""))
        assertFalse(ActId.isValid(null))
        assertFalse(ActId.isValid("202311201442471")) // 缺少 e 前缀。
        assertFalse(ActId.isValid("e12345")) // 数字位数不足。
        assertFalse(ActId.isValid("eabc202311201442471")) // 包含非数字字符。
    }

    @Test
    fun `parse from url query`() {
        assertEquals(
            "e202311201442471",
            ActId.parseFromText("https://act.mihoyo.com/bbs/event/signin/hk4e/index.html?act_id=e202311201442471&lang=zh-cn"),
        )
        assertEquals(
            "e202304121516551",
            ActId.parseFromText("...&act_id=e202304121516551"),
        )
    }

    @Test
    fun `parse from json and js assignments`() {
        assertEquals("e202311201442471", ActId.parseFromText("""{"act_id":"e202311201442471"}"""))
        assertEquals("e202311201442471", ActId.parseFromText("""{'actId':'e202311201442471'}"""))
        assertEquals("e202311201442471", ActId.parseFromText("""var act_id = "e202311201442471";"""))
    }

    @Test
    fun `invalid candidates rejected`() {
        assertNull(ActId.parseFromText("act_id=hello_world"))
        assertNull(ActId.parseFromText(""))
        assertNull(ActId.parseFromText(null))
    }

    @Test
    fun `extract script urls resolves relative paths`() {
        val html =
            """
            <html><head>
            <script src="/js/main.abc123.js"></script>
            <script src="https://cdn.example.com/vendor.js"></script>
            <script src="bad url with spaces"></script>
            </head></html>
            """.trimIndent()
        val urls = ActId.extractScriptUrls(html, "https://act.mihoyo.com/bbs/event/signin/hk4e/index.html")
        assertTrue(urls.contains("https://act.mihoyo.com/js/main.abc123.js"))
        assertFalse(urls.contains("https://cdn.example.com/vendor.js"))
    }

    @Test
    fun `parse trusted home navigation act id`() {
        val body =
            """{"retcode":0,"data":{"navigator":[{"app_path":""" +
                """"https://act.mihoyo.com/bbs/event/signin/hk4e/index.html?act_id=e202311201442471"}]}}"""
        assertEquals("e202311201442471", ActId.parseFromNavigation(body, "Genshin"))
    }

    @Test
    fun `navigation rejects duplicate or mismatched candidates`() {
        val duplicate =
            """{"retcode":0,"data":{"navigator":[{"app_path":""" +
                """"https://act.mihoyo.com/bbs/event/signin/hk4e/?act_id=e202311201442471"},""" +
                """{"app_path":"https://act.mihoyo.com/bbs/event/signin/hk4e/?act_id=e202304121516551"}]}}"""
        assertNull(ActId.parseFromNavigation(duplicate, "Genshin"))
        val mismatch =
            """{"retcode":0,"data":{"navigator":[{"app_path":""" +
                """"https://evil.example/bbs/event/signin/hk4e/?act_id=e202311201442471"}]}}"""
        assertNull(ActId.parseFromNavigation(mismatch, "Genshin"))
    }

    @Test
    fun `fetch latest prefers navigation act id`() =
        runTest {
            val game = testGame()
            val navigationUrl = "https://bbs-api.miyoushe.com/apihub/api/home/new?gids=2"
            val body =
                """{"retcode":0,"data":{"navigator":[{"app_path":""" +
                    """"https://act.mihoyo.com/bbs/event/signin/hk4e/index.html?act_id=e202311201442471"}]}}"""
            val transport =
                FakeHttpTransport { request ->
                    assertEquals(navigationUrl, request.url)
                    HttpResponse.text(200, body, finalUrl = navigationUrl)
                }

            assertEquals("e202311201442471", ActId.fetchLatest(game, transport))
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun `fetch latest falls back to activity scripts`() =
        runTest {
            val game = testGame()
            val navigationUrl = "https://bbs-api.miyoushe.com/apihub/api/home/new?gids=2"
            val scriptUrl = "https://act.mihoyo.com/js/signin.js"
            val html = """<html><script src="/js/signin.js"></script></html>"""
            val transport =
                FakeHttpTransport { request ->
                    when (request.url) {
                        navigationUrl -> {
                            HttpResponse.text(200, """{"retcode":0,"data":{"navigator":[]}}""", finalUrl = navigationUrl)
                        }

                        game.actPage -> {
                            HttpResponse.text(200, html, finalUrl = game.actPage)
                        }

                        scriptUrl -> {
                            assertEquals(game.actPage, request.headers["Referer"])
                            HttpResponse.text(200, """var act_id = "e202406242138391";""", finalUrl = scriptUrl)
                        }

                        else -> {
                            error("unexpected request: ${request.url}")
                        }
                    }
                }

            assertEquals("e202406242138391", ActId.fetchLatest(game, transport))
            assertEquals(listOf(navigationUrl, game.actPage, scriptUrl), transport.requests.map { it.url })
        }

    private fun testGame(): MysSignIn.GameConfig =
        MysSignIn.GameConfig(
            key = "Genshin",
            name = "原神",
            gameBiz = "hk4e_cn",
            actId = "e202311201442471",
            signgame = "hk4e_cn",
            defaultRegion = "cn_gf01",
            actPage = "https://act.mihoyo.com/bbs/event/signin/hk4e/index.html",
        )
}

class ActIdInvalidTest {
    @Test
    fun `invalid retcodes trigger refresh`() {
        assertTrue(ActIdInvalid.isInvalid(-500001, ""))
        assertTrue(ActIdInvalid.isInvalid(1001, ""))
        assertTrue(ActIdInvalid.isInvalid(1002, ""))
    }

    @Test
    fun `login and risk retcodes never trigger refresh`() {
        assertFalse(ActIdInvalid.isInvalid(0, "OK"))
        assertFalse(ActIdInvalid.isInvalid(-100, "登录失效"))
        assertFalse(ActIdInvalid.isInvalid(1034, "需要验证"))
        assertFalse(ActIdInvalid.isInvalid(-1, ""))
    }

    @Test
    fun `activity-related messages trigger refresh`() {
        assertTrue(ActIdInvalid.isInvalid(-999, "活动不存在"))
        assertTrue(ActIdInvalid.isInvalid(-999, "活动已结束"))
        assertTrue(ActIdInvalid.isInvalid(-999, "invalid act_id"))
        assertTrue(ActIdInvalid.isInvalid(-999, "activity not found"))
    }

    @Test
    fun `clearly unrelated messages do not trigger refresh`() {
        assertFalse(ActIdInvalid.isInvalid(-999, "请重新登录"))
        assertFalse(ActIdInvalid.isInvalid(-999, "cookie 已过期"))
        assertFalse(ActIdInvalid.isInvalid(-999, "操作频繁，请稍后再试"))
        assertFalse(ActIdInvalid.isInvalid(-999, "角色不存在"))
        assertFalse(ActIdInvalid.isInvalid(-999, "今日已签到"))
        assertFalse(ActIdInvalid.isInvalid(null, ""))
    }
}
