package com.questtick.sign

/*
 * 米游社签到奖励解析：从 info / home 两个接口的返回里取出当天奖励与图片地址。
 * 解析失败只影响奖励展示，不改变签到本身的成败判定。
 */
import com.questtick.log.AppLog
import org.json.JSONObject

private const val TAG = "MysRewardParser"
private const val DEFAULT_RETCODE = -999

internal object MysRewardParser {
    fun parse(
        game: MysSignIn.GameConfig,
        infoData: JSONObject,
        homeData: JSONObject,
        log: (level: String, message: String) -> Unit,
    ): MysSignIn.Reward? {
        val infoRetcode = infoData.optInt("retcode", DEFAULT_RETCODE)
        val homeRetcode = homeData.optInt("retcode", DEFAULT_RETCODE)
        val infoObject = infoData.optJSONObject("data")
        val homeObject = homeData.optJSONObject("data")
        val totalSignDay =
            firstPositiveInt(infoObject, "total_sign_day", "totalSignDay", "sign_day", "signDay")
                ?: firstPositiveInt(homeObject, "total_sign_day", "totalSignDay", "sign_day", "signDay")
                ?: 0
        val awards =
            homeObject?.optJSONArray("awards")
                ?: homeObject?.optJSONArray("award_list")
                ?: infoObject?.optJSONArray("awards")
        val award = awards?.optJSONObject(totalSignDay - 1)
        val awardsLength = awards?.length() ?: 0
        val dataMissing = totalSignDay <= 0 || awardsLength == 0
        val snapshot =
            RewardSnapshot(
                infoRetcode = infoRetcode,
                homeRetcode = homeRetcode,
                totalSignDay = totalSignDay,
                awardsLength = awardsLength,
                awardMissing = award == null,
                dataMissing = dataMissing,
            )
        val failureMessage =
            rewardFailureMessage(
                game = game,
                infoData = infoData,
                homeData = homeData,
                snapshot = snapshot,
            )
        val currentAward = award
        if (failureMessage != null || currentAward == null) {
            if (failureMessage != null) log("ERROR", failureMessage)
            return null
        }
        val icon = normalizeRewardIcon(extractRewardIcon(currentAward))
        if (icon.isBlank()) {
            log("ERROR", "[${game.name}] 奖励图片地址缺失：$currentAward")
        }
        return MysSignIn.Reward(
            day = totalSignDay,
            name = currentAward.optString("name"),
            cnt = currentAward.optString("cnt"),
            icon = icon,
        )
    }

    private data class RewardSnapshot(
        val infoRetcode: Int,
        val homeRetcode: Int,
        val totalSignDay: Int,
        val awardsLength: Int,
        val awardMissing: Boolean,
        val dataMissing: Boolean,
    )

    private fun rewardFailureMessage(
        game: MysSignIn.GameConfig,
        infoData: JSONObject,
        homeData: JSONObject,
        snapshot: RewardSnapshot,
    ): String? {
        val infoFailed = snapshot.infoRetcode != 0 || snapshot.homeRetcode != 0
        return when {
            infoFailed -> {
                AppLog.w(
                    TAG,
                    "getReward failed: ${game.key}, infoRetcode=${snapshot.infoRetcode}, homeRetcode=${snapshot.homeRetcode}",
                )
                "[${game.name}] 奖励查询失败：infoRetcode=${snapshot.infoRetcode}, " +
                    "infoMessage=${infoData.optString("message")}, homeRetcode=${snapshot.homeRetcode}, " +
                    "homeMessage=${homeData.optString("message")}"
            }

            snapshot.dataMissing -> {
                "[${game.name}] 奖励数据缺失：totalSignDay=${snapshot.totalSignDay}, awards=${snapshot.awardsLength}"
            }

            snapshot.awardMissing -> {
                "[${game.name}] 奖励数据缺失：无法读取第${snapshot.totalSignDay}天奖励"
            }

            else -> {
                null
            }
        }
    }

    private fun firstPositiveInt(
        json: JSONObject?,
        vararg keys: String,
    ): Int? =
        json?.let { source ->
            keys.firstNotNullOfOrNull { key -> source.optInt(key, 0).takeIf { it > 0 } }
        }

    private fun normalizeRewardIcon(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://webstatic.mihoyo.com$value"
            else -> value
        }
    }

    private fun extractRewardIcon(json: JSONObject): String {
        val directKeys = listOf("icon", "icon_url", "iconUrl", "reward_icon", "rewardIcon", "image", "image_url", "imageUrl")
        return directKeys.firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf { it.isNotBlank() } }
            ?: listOf("award", "reward", "item")
                .firstNotNullOfOrNull { key ->
                    json.optJSONObject(key)?.let { nested ->
                        directKeys.firstNotNullOfOrNull { name -> nested.optString(name).trim().takeIf { it.isNotBlank() } }
                    }
                }.orEmpty()
    }
}
