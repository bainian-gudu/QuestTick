package com.questtick.sign

import com.questtick.data.Account
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

    @Test
    fun `selected mys games returns all supported mys games when cookie exists and no explicit selection`() {
        val account = Account(id = "1", label = "test", mysCookie = "cookie_token=abc; account_id=123")

        val games = account.selectedMysGames()

        assertEquals(MysSignIn.GAMES.size, games.size)
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
