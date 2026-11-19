package com.questtick.sign

import com.questtick.data.Account
import com.questtick.data.Games
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInTaskPlanningTest {
    private val cloudYsBinding =
        CloudGameBinding(
            game = CloudSignIn.GAMES.getValue("CloudYS"),
            tokenOf = { it.genshinToken },
            webCookieOf = { it.genshinWebCookie },
            hasKeepLogin = { it.hasGenshinCloudKeepLogin },
            updateToken = { account, token -> account.copy(genshinToken = token) },
        )

    private val cloudSrBinding =
        CloudGameBinding(
            game = CloudSignIn.GAMES.getValue("CloudSR"),
            tokenOf = { it.starrailToken },
            webCookieOf = { it.starrailWebCookie },
            hasKeepLogin = { it.hasStarrailCloudKeepLogin },
            updateToken = { account, token -> account.copy(starrailToken = token) },
        )

    private val cloudZzzBinding =
        CloudGameBinding(
            game = CloudSignIn.GAMES.getValue("CloudZZZ"),
            tokenOf = { it.zzzToken },
            webCookieOf = { "" },
            hasKeepLogin = { false },
            updateToken = { account, token -> account.copy(zzzToken = token) },
        )

    @Test
    fun `coin task is planned only when the feature flag is on`() {
        val account =
            Account(
                id = "1",
                label = "test",
                mysCookie = "cookie_token=abc; account_id=123",
                selectedGames = setOf("Genshin"),
                mysCoinEnabled = true,
            )

        val withoutFeature = buildRunTaskPlan(listOf(account), emptyList(), mysCoinEnabled = false)
        val withFeature = buildRunTaskPlan(listOf(account), emptyList(), mysCoinEnabled = true)

        assertFalse(withoutFeature.any { it.targetName == MysCoinCheckIn.DISPLAY_NAME })
        assertTrue(withFeature.any { it.targetName == MysCoinCheckIn.DISPLAY_NAME })
        assertEquals(withoutFeature.size + 1, withFeature.size)
    }

    @Test
    fun `coin task is skipped when the account did not opt in`() {
        val account =
            Account(
                id = "1",
                label = "test",
                mysCookie = "cookie_token=abc; account_id=123",
                selectedGames = setOf("Genshin"),
                mysCoinEnabled = false,
            )

        val tasks = buildRunTaskPlan(listOf(account), emptyList(), mysCoinEnabled = true)

        assertFalse(tasks.any { it.targetName == MysCoinCheckIn.DISPLAY_NAME })
    }

    @Test
    fun `cloud zzz is planned like other cloud games`() {
        val account =
            Account(
                id = "1",
                label = "test",
                zzzToken = "zzz_token",
                selectedGames = setOf("CloudZZZ"),
            )

        assertTrue(account.hasCloudTask(cloudZzzBinding))

        val bindings = account.selectedCloudBindings(listOf(cloudYsBinding, cloudSrBinding, cloudZzzBinding))

        assertEquals(1, bindings.size)
        assertEquals("CloudZZZ", bindings.single().game.key)
        assertEquals("云绝区零", bindings.single().game.name)
    }

    @Test
    fun `selected mys games returns all supported mys games when cookie exists and no explicit selection`() {
        val account = Account(id = "1", label = "test", mysCookie = "cookie_token=abc; account_id=123")

        val games = account.selectedMysGames()

        // 预支持游戏已登记签到参数，但仍不属于账号可选集合。
        assertEquals(
            MysSignIn.GAMES.keys
                .filterNot { it in Games.PREVIEW_KEYS }
                .toSet(),
            games.map { it.key }.toSet(),
        )
        assertTrue(games.any { it.key == "Genshin" })
        assertTrue(games.any { it.key == "StarRail" })
    }

    @Test
    fun `selected mys games respects explicit selection`() {
        val account =
            Account(
                id = "1",
                label = "test",
                mysCookie = "cookie_token=abc; account_id=123",
                selectedGames = setOf("Genshin", "ZZZ"),
            )

        val games = account.selectedMysGames()

        assertEquals(listOf("Genshin", "ZZZ"), games.map { it.key })
    }

    @Test
    fun `selected mys games is enabled by keep login even without cookie`() {
        val account =
            Account(
                id = "1",
                label = "test",
                mysUid = "10001",
                stoken = "stoken",
                qrLoginBound = true,
                selectedGames = setOf("Genshin"),
            )

        val games = account.selectedMysGames()

        assertEquals(listOf("Genshin"), games.map { it.key })
    }

    @Test
    fun `cloud task is enabled when token exists and game is selected`() {
        val account =
            Account(
                id = "1",
                label = "test",
                genshinToken = "token",
                selectedGames = setOf("CloudYS"),
            )

        assertTrue(account.hasCloudTask(cloudYsBinding))
        assertFalse(account.hasCloudTask(cloudSrBinding))
    }

    @Test
    fun `cloud task is enabled by keep login even without token`() {
        val account =
            Account(
                id = "1",
                label = "test",
                genshinWebCookie = "login_cookie",
                genshinCloudQrLoginBound = true,
                selectedGames = setOf("CloudYS"),
            )

        assertTrue(account.hasCloudTask(cloudYsBinding))
    }

    @Test
    fun `selected cloud bindings filters unselected and unavailable games`() {
        val account =
            Account(
                id = "1",
                label = "test",
                genshinToken = "ys_token",
                starrailToken = "sr_token",
                selectedGames = setOf("CloudSR"),
            )

        val bindings = account.selectedCloudBindings(listOf(cloudYsBinding, cloudSrBinding))

        assertEquals(1, bindings.size)
        assertEquals("CloudSR", bindings.single().game.key)
    }
}
