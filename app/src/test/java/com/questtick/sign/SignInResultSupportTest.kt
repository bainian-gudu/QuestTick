package com.questtick.sign

import com.questtick.data.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInResultSupportTest {
    private val cloudYsBinding =
        CloudGameBinding(
            game = CloudSignIn.GAMES.getValue("CloudYS"),
            tokenOf = { it.genshinToken },
            webCookieOf = { it.genshinWebCookie },
            hasKeepLogin = { it.hasGenshinCloudKeepLogin },
            updateToken = { account, token -> account.copy(genshinToken = token) },
        )

    @Test
    fun `build mys task result carries reward fields`() {
        val reward = MysSignIn.Reward(day = 3, name = "原石", cnt = "20", icon = "icon_url")
        val outcome =
            MysSignIn.Outcome(
                success = true,
                skipped = false,
                message = "签到成功",
                alreadySigned = false,
                reward = reward,
            )

        val account = Account(id = "account-1", label = "主账号")
        val result = buildMysTaskResult("原神", "Genshin", account, outcome)

        assertEquals("原神", result.game)
        assertEquals("Genshin", result.gameKey)
        assertEquals("主账号", result.accountLabel)
        assertEquals("account-1|MYS|Genshin", result.taskId)
        assertEquals("account-1", result.accountId)
        assertEquals("原石", result.rewardName)
        assertEquals("20", result.rewardCount)
        assertEquals("icon_url", result.rewardIcon)
        assertEquals(3, result.totalSignDay)
    }

    @Test
    fun `build cloud task result keeps success and message`() {
        val outcome = CloudSignIn.Outcome(success = true, skipped = false, message = "领取完成")

        val account = Account(id = "account-2", label = "云账号")
        val result = buildCloudTaskResult("云原神", "CloudYS", account, outcome)

        assertEquals("云原神", result.game)
        assertEquals("CloudYS", result.gameKey)
        assertTrue(result.success)
        assertEquals("领取完成", result.message)
    }

    @Test
    fun `build mys outcome detail includes reward and flags`() {
        val outcome =
            MysSignIn.Outcome(
                success = true,
                skipped = true,
                message = "今日已签到",
                alreadySigned = true,
                reward = MysSignIn.Reward(day = 5, name = "摩拉", cnt = "8000"),
                detail = "retcode=0",
            )

        val detail = buildMysOutcomeDetail(outcome)

        assertTrue(detail.contains("retcode=0"))
        assertTrue(detail.contains("alreadySigned=true"))
        assertTrue(detail.contains("reward={day=5, name=摩拉, cnt=8000}"))
        assertTrue(detail.contains("skipped=true"))
    }

    @Test
    fun `format sign in elapsed covers ms seconds and minutes`() {
        assertEquals("999ms", formatSignInElapsed(999))
        assertEquals("1.5s", formatSignInElapsed(1500))
        assertEquals("1m5s", formatSignInElapsed(65_000))
    }

    @Test
    fun `build failed task results includes mys and selected cloud tasks`() {
        val account =
            Account(
                id = "1",
                label = "test",
                mysCookie = "cookie_token=abc; account_id=123",
                genshinToken = "cloud_token",
                selectedGames = setOf("Genshin", "CloudYS"),
            )

        val results = buildFailedTaskResults(account, "失败", listOf(cloudYsBinding))

        assertEquals(2, results.size)
        assertEquals(listOf("Genshin", "CloudYS"), results.map { it.gameKey })
    }

    @Test
    fun `compute sign in task total sums mys and cloud tasks`() {
        val first =
            Account(
                id = "1",
                label = "a",
                mysCookie = "cookie_token=abc; account_id=123",
                selectedGames = setOf("Genshin", "ZZZ"),
            )
        val second =
            Account(
                id = "2",
                label = "b",
                genshinToken = "cloud_token",
                selectedGames = setOf("CloudYS"),
            )
        val third =
            Account(
                id = "3",
                label = "c",
                mysUid = "10001",
                stoken = "stoken",
                qrLoginBound = true,
                selectedGames = setOf("StarRail"),
            )

        val total = computeSignInTaskTotal(listOf(first, second, third), listOf(cloudYsBinding))

        assertEquals(4, total)
    }

    @Test
    fun `compute sign in task total includes enabled mys coin check in`() {
        val account =
            Account(
                id = "1",
                label = "a",
                mysCookie = "cookie_token=abc; account_id=123",
                selectedGames = setOf("Genshin"),
                mysCoinEnabled = true,
            )

        assertEquals(2, computeSignInTaskTotal(listOf(account), emptyList()))
    }
}
