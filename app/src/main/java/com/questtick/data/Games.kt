package com.questtick.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.questtick.R

/** 支持游戏的静态目录，供图标网格、账号选择与签到任务复用。 */
@Immutable
data class GameInfo(
    val key: String,
    val name: String, // 短显示名
    val symbol: String, // 文本场景下的备用符号
    val iconRes: Int, // 应用内展示的游戏图标资源
    val color: Color, // 图标加载失败时的兜底色
    val reward: String, // 游戏选择页说明
    val cloud: Boolean = false,
    val preview: Boolean = false,
)

object Games {
    val ALL: List<GameInfo> =
        listOf(
            GameInfo(
                "Honkai2",
                "崩坏学园2",
                "◈",
                R.drawable.game_honkai2,
                Color(0xFF3B82F6),
                "签到奖励：水晶、吃货啾啾、超钻石喵王等",
            ),
            GameInfo(
                "Honkai3rd",
                "崩坏3",
                "⬡",
                R.drawable.game_honkai3rd,
                Color(0xFFEF4444),
                "签到奖励：水晶、星石、体力药水等",
            ),
            GameInfo(
                "TearsOfThemis",
                "未定事件簿",
                "♦",
                R.drawable.game_tears_of_themis,
                Color(0xFFEC4899),
                "签到奖励：未名晶片、未名币、法理之谕等",
            ),
            GameInfo(
                "Genshin",
                "原神",
                "✦",
                R.drawable.game_genshin,
                Color(0xFFF5A623),
                "签到奖励：原石、摩拉、冒险家的经验等",
            ),
            GameInfo(
                "StarRail",
                "崩坏：星穹铁道",
                "★",
                R.drawable.game_starrail,
                Color(0xFF8B5CF6),
                "签到奖励：星琼、信用点、冒险记录等",
            ),
            GameInfo(
                "ZZZ",
                "绝区零",
                "⚡",
                R.drawable.game_zzz,
                Color(0xFFF97316),
                "签到奖励：菲林、丁尼、正式调查员记录等",
            ),
            GameInfo(
                "HNA",
                "崩坏：因缘精灵",
                "✿",
                R.drawable.game_hna,
                Color(0xFF0891B2),
                "预支持 · 签到参数待确认",
                preview = true,
            ),
            GameInfo(
                "StarNest",
                "星布谷地",
                "✧",
                R.drawable.game_starnest,
                Color(0xFF38BDF8),
                "预支持 · 签到参数待确认",
                preview = true,
            ),
            GameInfo(
                "CloudYS",
                "云原神",
                "☁",
                R.drawable.game_cloud_genshin,
                Color(0xFF10B981),
                "领取云游戏免费时长",
                cloud = true,
            ),
            GameInfo(
                "CloudSR",
                "云崩铁",
                "☁",
                R.drawable.game_cloud_starrail,
                Color(0xFF8B7CF6),
                "领取云游戏免费时长",
                cloud = true,
            ),
        )

    /** 首页游戏数量统计不把云游戏入口单独计入。 */
    val DISPLAY_GAME_COUNT: Int = ALL.count { !it.cloud }

    val PREVIEW_KEYS: Set<String> = ALL.filter { it.preview }.map { it.key }.toSet()

    /** 需要米游社 Cookie 的游戏键。 */
    val MYS_KEYS: List<String> = ALL.filterNot { it.cloud || it.preview }.map { it.key }

    /** 可实际执行签到的全部游戏键；用于默认全选。 */
    val ALL_KEYS: Set<String> = ALL.filterNot { it.preview }.map { it.key }.toSet()

    private val byKey = ALL.associateBy { it.key }

    fun byKey(key: String): GameInfo? = byKey[key]
}
