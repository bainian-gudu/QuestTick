package com.questtick.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRecordFormatTest {
    @Test
    fun `current run metadata and task identity survive json round trip`() {
        val record =
            RunRecord(
                timestamp = 1234L,
                results =
                    listOf(
                        TaskResult(
                            game = "原神",
                            gameKey = "Genshin",
                            accountLabel = "主账号",
                            success = true,
                            skipped = false,
                            message = "签到成功",
                            taskId = "account-1|MYS|Genshin",
                            accountId = "account-1",
                            failureCategory = FailureCategory.NETWORK_TIMEOUT,
                            errorCode = "SocketTimeoutException",
                            retryable = true,
                            coinBalance = 130,
                            coinGained = 30,
                        ),
                    ),
                runId = "run-1",
                trigger = RunTrigger.MANUAL,
                terminationReason = RunTerminationReason.ROOT_CHECK_FAILED,
            )

        val restored = RunRecord.fromJson(record.toJson())

        assertEquals(record, restored)
    }

    @Test
    fun `unknown enum values are rejected instead of silently converted`() {
        val json = completeRecordJson()
        json.put("terminationReason", "FUTURE_REASON")

        assertThrows(IllegalArgumentException::class.java) {
            RunRecord.fromJson(json)
        }

        val taskEnumJson = completeRecordJson()
        taskEnumJson.getJSONArray("results").getJSONObject(0).put("failureCategory", "FUTURE_CATEGORY")
        assertThrows(IllegalArgumentException::class.java) {
            RunRecord.fromJson(taskEnumJson)
        }
    }

    @Test
    fun `pre release json without current metadata is rejected`() {
        val preRelease =
            JSONObject(
                """
                {
                  "timestamp": 1000,
                  "results": [
                    {
                      "game": "原神",
                      "gameKey": "Genshin",
                      "accountLabel": "旧账号",
                      "success": true,
                      "skipped": false,
                      "message": "OK"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertThrows(Exception::class.java) {
            RunRecord.fromJson(preRelease)
        }
    }

    @Test
    fun `result unknown is counted separately from ordinary skip and failure`() {
        val unknown =
            TaskResult(
                game = "原神",
                gameKey = "Genshin",
                accountLabel = "账号",
                accountId = "account-1",
                taskId = "account-1|MYS|Genshin",
                success = false,
                skipped = true,
                message = "结果待确认",
                failureCategory = FailureCategory.RESULT_UNKNOWN,
            )
        val skipped =
            unknown.copy(
                game = "星铁",
                gameKey = "StarRail",
                taskId = "account-1|MYS|StarRail",
                message = "普通跳过",
                failureCategory = FailureCategory.NO_ROLE,
            )
        val record = RunRecord(timestamp = 1L, runId = "run-1", results = listOf(unknown, skipped))

        assertEquals(1, record.resultUnknown)
        assertEquals(1, record.skipped)
        assertEquals(0, record.failed)
        assertTrue(record.results.all { it.taskId.isNotBlank() && it.accountId.isNotBlank() })
    }

    private fun completeRecordJson(): JSONObject =
        RunRecord(
            timestamp = 1L,
            runId = "run-1",
            results =
                listOf(
                    TaskResult(
                        game = "原神",
                        gameKey = "Genshin",
                        accountLabel = "账号",
                        accountId = "account-1",
                        taskId = "account-1|MYS|Genshin",
                        success = false,
                        skipped = false,
                        message = "失败",
                        failureCategory = FailureCategory.INTERNAL_ERROR,
                    ),
                ),
        ).toJson()
}
