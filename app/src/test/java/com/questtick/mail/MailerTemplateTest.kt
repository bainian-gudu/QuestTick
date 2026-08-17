package com.questtick.mail

import com.questtick.data.RunRecord
import com.questtick.data.TaskResult
import com.questtick.i18n.AppLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailerTemplateTest {
    @Test
    fun htmlUsesUpstreamGroupedTitleAndBlueWhiteTheme() {
        val html = Mailer.buildHtml(
            RunRecord(
                timestamp = 1L,
                results = listOf(
                    TaskResult(
                        game = "原神-米游社",
                        gameKey = "Genshin",
                        accountLabel = "主号",
                        success = true,
                        skipped = false,
                        message = "签到成功",
                        rewardName = "原石",
                        rewardCount = "20",
                        totalSignDay = 1,
                    ),
                    TaskResult(
                        game = "云原神",
                        gameKey = "CloudYS",
                        accountLabel = "主号",
                        success = true,
                        skipped = false,
                        message = "领取完成 · 本次+15分钟 · 当前1小时15分钟",
                    ),
                    TaskResult(
                        game = "原神社区米游币打卡",
                        gameKey = "MysCoin",
                        accountLabel = "主号",
                        success = true,
                        skipped = false,
                        message = "原神社区打卡成功",
                        coinBalance = 130,
                        coinGained = 30,
                    ),
                ),
            ),
        )

        assertTrue(html.contains("米游社和云游戏签到结果总结"))
        assertTrue(html.contains("签到明细"))
        assertTrue(html.contains("主号"))
        assertTrue(html.contains("米游社结果"))
        assertTrue(html.contains("云游戏结果"))
        assertTrue(html.contains("#2563EB"))
        assertTrue(html.contains("#EFF6FF"))
        assertTrue(html.contains("原石"))
        assertTrue(html.contains("15分钟"))
        assertTrue(html.contains("打卡后米游币：130"))
        assertTrue(html.contains("本次获取：30"))
    }

    @Test
    fun htmlUsesSelectedLanguageAndKeepsAccountLabel() {
        val html = Mailer.buildHtml(
            RunRecord(
                timestamp = 1L,
                results = listOf(
                    TaskResult(
                        game = "原神-米游社",
                        gameKey = "Genshin",
                        accountLabel = "开发号",
                        success = true,
                        skipped = false,
                        message = "签到成功",
                    ),
                ),
            ),
            AppLanguage.ENGLISH,
        )

        assertTrue(html.contains("Miyoushe sign-in summary"))
        assertTrue(html.contains("Genshin Impact"))
        assertTrue(html.contains("开发号"))
        assertFalse(html.contains("签到结果总结"))
    }

    @Test
    fun htmlOnlyEmbedsTrustedRewardIconUrl() {
        val trusted =
            TaskResult(
                game = "原神-米游社",
                gameKey = "Genshin",
                accountLabel = "主号",
                success = true,
                skipped = false,
                message = "签到成功",
                rewardName = "原石",
                rewardIcon = "https://upload-bbs.miyoushe.com/upload/trusted.png",
            )
        val untrusted = trusted.copy(
            rewardName = "伪造奖励",
            rewardIcon = "https://evil.example/tracker.png",
        )

        val html = Mailer.buildHtml(RunRecord(timestamp = 1L, results = listOf(trusted, untrusted)))

        assertTrue(html.contains("https://upload-bbs.miyoushe.com/upload/trusted.png"))
        assertFalse(html.contains("https://evil.example/tracker.png"))
    }

    @Test
    fun htmlEscapesSensitiveLookingHtml() {
        val html = Mailer.buildHtml(
            RunRecord(
                timestamp = 1L,
                results = listOf(
                    TaskResult(
                        game = "原神<script>",
                        gameKey = "Genshin",
                        accountLabel = "<主号>",
                        success = false,
                        skipped = false,
                        message = "失败<script>",
                    ),
                ),
            ),
        )

        assertTrue(html.contains("&lt;主号&gt;"))
        assertTrue(html.contains("原神&lt;script&gt;"))
        assertFalse(html.contains("<script>"))
    }
}
