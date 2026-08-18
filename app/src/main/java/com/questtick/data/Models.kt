package com.questtick.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** 用户配置的单个米游社账号。 */
@Immutable
data class Account(
    val id: String, // 本地 UUID
    val label: String, // 用户自定义名称
    val mysCookie: String = "", // 米游社 Cookie
    val genshinToken: String = "", // 云原神 x-rpc-combo_token
    val starrailToken: String = "", // 云崩铁 x-rpc-combo_token
    /** 云原神网页登录 Cookie；保持登录时用于续期 combo_token。 */
    val genshinWebCookie: String = "",
    /** 云崩铁网页登录 Cookie；保持登录时用于续期 combo_token。 */
    val starrailWebCookie: String = "",
    /** 云原神是否保存了扫码保持登录凭证。 */
    val genshinCloudQrLoginBound: Boolean = false,
    /** 云崩铁是否保存了扫码保持登录凭证。 */
    val starrailCloudQrLoginBound: Boolean = false,
    val enabled: Boolean = true,
    // 当前账号选择的可执行游戏键；传入外部可变集合时应先 toSet()，保持不可变快照。
    val selectedGames: Set<String> = Games.ALL_KEYS,
    // 扫码登录凭证：用于后续自动刷新 Cookie。
    /** 米游社 UID（account_id / stuid）。 */
    val mysUid: String = "",
    /** SToken V2；用于刷新 cookie_token 与 ltoken。 */
    val stoken: String = "",
    /** SToken 对应的 MiHoYo ID（stmid / mid）。 */
    val stmid: String = "",
    /** LToken V2。 */
    val ltoken: String = "",
    /** 米游社是否保存了扫码保持登录凭证；为 true 时支持自动刷新。 */
    val qrLoginBound: Boolean = false,
    /** 是否在签到运行中执行米游社社区打卡以获取米游币。 */
    val mysCoinEnabled: Boolean = false,
) {
    /** 判断指定游戏是否对当前账号启用。 */
    fun isGameSelected(key: String): Boolean {
        if (Games.byKey(key)?.preview == true) return false
        return selectedGames.contains(key)
    }

    /** 米游社是否处于可自动刷新 Cookie 的保持登录状态。 */
    val hasMysKeepLogin: Boolean
        get() = qrLoginBound && mysUid.isNotBlank() && stoken.isNotBlank()

    /** 云原神是否处于可自动续期 Token 的保持登录状态。 */
    val hasGenshinCloudKeepLogin: Boolean
        get() = genshinCloudQrLoginBound && genshinWebCookie.isNotBlank()

    /** 云崩铁是否处于可自动续期 Token 的保持登录状态。 */
    val hasStarrailCloudKeepLogin: Boolean
        get() = starrailCloudQrLoginBound && starrailWebCookie.isNotBlank()

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("label", label)
            put("mysCookie", mysCookie)
            put("genshinToken", genshinToken)
            put("starrailToken", starrailToken)
            put("genshinWebCookie", genshinWebCookie)
            put("starrailWebCookie", starrailWebCookie)
            put("genshinCloudQrLoginBound", genshinCloudQrLoginBound)
            put("starrailCloudQrLoginBound", starrailCloudQrLoginBound)
            put("enabled", enabled)
            put("selectedGames", JSONArray().apply { selectedGames.forEach { put(it) } })
            put("mysUid", mysUid)
            put("stoken", stoken)
            put("stmid", stmid)
            put("ltoken", ltoken)
            put("qrLoginBound", qrLoginBound)
            put("mysCoinEnabled", mysCoinEnabled)
        }

    companion object {
        fun fromJson(o: JSONObject): Account {
            val arr = o.getJSONArray("selectedGames")
            val games = LinkedHashSet<String>()
            for (i in 0 until arr.length()) games.add(arr.getString(i))
            val genshinWebCookie = o.getString("genshinWebCookie")
            val starrailWebCookie = o.getString("starrailWebCookie")
            val mysUid = o.getString("mysUid")
            val stoken = o.getString("stoken")
            val id = o.getString("id")
            require(id.isNotBlank()) { "Account id must not be blank" }
            require(games.all { it in Games.ALL_KEYS }) { "Account contains unsupported game key" }
            return Account(
                id = id,
                label = o.getString("label"),
                mysCookie = o.getString("mysCookie"),
                genshinToken = o.getString("genshinToken"),
                starrailToken = o.getString("starrailToken"),
                genshinWebCookie = genshinWebCookie,
                starrailWebCookie = starrailWebCookie,
                genshinCloudQrLoginBound = genshinWebCookie.isNotBlank() && o.getBoolean("genshinCloudQrLoginBound"),
                starrailCloudQrLoginBound = starrailWebCookie.isNotBlank() && o.getBoolean("starrailCloudQrLoginBound"),
                enabled = o.getBoolean("enabled"),
                selectedGames = games.toSet(),
                mysUid = mysUid,
                stoken = stoken,
                stmid = o.getString("stmid"),
                ltoken = o.getString("ltoken"),
                qrLoginBound = o.getBoolean("qrLoginBound") && mysUid.isNotBlank() && stoken.isNotBlank(),
                mysCoinEnabled = o.getBoolean("mysCoinEnabled"),
            )
        }
    }
}

/** 任务失败的结构化分类；NONE 表示成功或已签到。 */
enum class FailureCategory {
    NONE,
    AUTH_EXPIRED,
    CAPTCHA_REQUIRED,
    SMS_REQUIRED,
    RATE_LIMITED,
    SECURITY_BLOCKED,
    NO_ROLE,
    NETWORK_UNAVAILABLE,
    NETWORK_TIMEOUT,
    HTTP_ERROR,
    SERVER_ERROR,
    CONFIGURATION_ERROR,
    DATA_DECRYPTION_ERROR,
    CANCELLED,
    RESULT_UNKNOWN,
    INTERNAL_ERROR,
    ;

    companion object {
        fun parse(value: String): FailureCategory = valueOf(value)
    }
}

/** 单个账号、单个游戏任务的执行结果。 */
@Immutable
data class TaskResult(
    val game: String, // 展示名称，例如：原神-米游社
    val gameKey: String = "", // 游戏目录键，例如：Genshin
    val accountLabel: String,
    val success: Boolean,
    val skipped: Boolean,
    val alreadySigned: Boolean = false,
    val message: String, // 已脱敏的用户可读消息
    val rewardName: String = "",
    val rewardCount: String = "",
    val rewardIcon: String = "", // 签到接口返回的奖励图标 URL
    val totalSignDay: Int = 0,
    /** 米游币打卡后的账号余额；非米游币任务或无法读取时为 -1。 */
    val coinBalance: Int = -1,
    /** 本次打卡新增米游币；无法计算时为 -1。 */
    val coinGained: Int = -1,
    /** 本次运行内的稳定任务标识。 */
    val taskId: String = "",
    /** 账号本地 UUID；展示仍使用 accountLabel，业务关联不得依赖可重名标签。 */
    val accountId: String = "",
    val failureCategory: FailureCategory = FailureCategory.NONE,
    val errorCode: String = "",
    val retryable: Boolean = false,
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("game", game)
            put("gameKey", gameKey)
            put("accountLabel", accountLabel)
            put("success", success)
            put("skipped", skipped)
            put("alreadySigned", alreadySigned)
            put("message", message)
            put("rewardName", rewardName)
            put("rewardCount", rewardCount)
            put("rewardIcon", rewardIcon)
            put("totalSignDay", totalSignDay)
            put("coinBalance", coinBalance)
            put("coinGained", coinGained)
            put("taskId", taskId)
            put("accountId", accountId)
            put("failureCategory", failureCategory.name)
            put("errorCode", errorCode)
            put("retryable", retryable)
        }

    companion object {
        fun fromJson(o: JSONObject): TaskResult {
            val taskId = o.getString("taskId")
            val accountId = o.getString("accountId")
            require(taskId.isNotBlank()) { "Task result taskId must not be blank" }
            require(accountId.isNotBlank()) { "Task result accountId must not be blank" }
            return TaskResult(
                game = o.getString("game"),
                gameKey = o.getString("gameKey"),
                accountLabel = o.getString("accountLabel"),
                success = o.getBoolean("success"),
                skipped = o.getBoolean("skipped"),
                alreadySigned = o.getBoolean("alreadySigned"),
                message = o.getString("message"),
                rewardName = o.getString("rewardName"),
                rewardCount = o.getString("rewardCount"),
                rewardIcon = o.getString("rewardIcon"),
                totalSignDay = o.getInt("totalSignDay"),
                coinBalance = o.getInt("coinBalance"),
                coinGained = o.getInt("coinGained"),
                taskId = taskId,
                accountId = accountId,
                failureCategory = FailureCategory.parse(o.getString("failureCategory")),
                errorCode = o.getString("errorCode"),
                retryable = o.getBoolean("retryable"),
            )
        }
    }
}

/** 发起签到运行的来源。 */
enum class RunTrigger {
    MANUAL,
    SCHEDULED,
    RETRY,
    ;

    companion object {
        fun parse(value: String): RunTrigger = valueOf(value)
    }
}

/** 运行结束的结构化原因，业务判断不得依赖可变的中文提示文案。 */
enum class RunTerminationReason {
    COMPLETED,
    NO_ENABLED_ACCOUNT,
    NO_RUNNABLE_TASK,
    ROOT_DETECTED,
    ROOT_CHECK_FAILED,
    USER_CANCELLED,
    SYSTEM_CANCELLED,
    PROCESS_INTERRUPTED,
    INTERNAL_ERROR,
    ;

    companion object {
        fun parse(value: String): RunTerminationReason = valueOf(value)
    }
}

/** 一次完整签到运行：稳定运行 ID、触发来源、终止原因、时间戳与全部任务结果。 */
@Immutable
data class RunRecord(
    val timestamp: Long,
    val results: List<TaskResult>,
    val runId: String = "",
    val trigger: RunTrigger = RunTrigger.MANUAL,
    val terminationReason: RunTerminationReason = RunTerminationReason.COMPLETED,
) {
    val total: Int get() = results.size
    val failed: Int get() = results.count { !it.skipped && !it.success }

    /** 本次新签到成功的任务数，不包含“今日已签到”。 */
    val succeeded: Int get() = results.count { it.success && !it.skipped && !it.alreadySigned }

    /** 今日此前已经签到过的任务数。 */
    val alreadySigned: Int get() = results.count { it.success && it.alreadySigned }
    val resultUnknown: Int get() = results.count { it.failureCategory == FailureCategory.RESULT_UNKNOWN }
    val skipped: Int get() = results.count {
        it.skipped && it.failureCategory != FailureCategory.RESULT_UNKNOWN
    }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("timestamp", timestamp)
            put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })
            put("runId", runId)
            put("trigger", trigger.name)
            put("terminationReason", terminationReason.name)
        }

    companion object {
        fun fromJson(o: JSONObject): RunRecord {
            val arr = o.getJSONArray("results")
            val list = ArrayList<TaskResult>(arr.length())
            for (i in 0 until arr.length()) list.add(TaskResult.fromJson(arr.getJSONObject(i)))
            val runId = o.getString("runId")
            require(runId.isNotBlank()) { "Run record runId must not be blank" }
            return RunRecord(
                timestamp = o.getLong("timestamp"),
                results = list,
                runId = runId,
                trigger = RunTrigger.parse(o.getString("trigger")),
                terminationReason = RunTerminationReason.parse(o.getString("terminationReason")),
            )
        }
    }
}

/** 独立于运行记录的签到日历状态；清空签到记录不会影响该状态。 */
@Immutable
data class SignInCalendarDay(
    val dayKey: String,
    val status: String,
    val taskCount: Int = 0,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val STATUS_SIGNED = "SIGNED"
        const val STATUS_PARTIAL = "PARTIAL"
        const val STATUS_FAILED = "FAILED"

        fun normalizeStatus(status: String?): String? =
            when (status?.uppercase()) {
                STATUS_SIGNED -> STATUS_SIGNED
                STATUS_PARTIAL -> STATUS_PARTIAL
                STATUS_FAILED -> STATUS_FAILED
                else -> null
            }
    }
}

/** 可选的 SMTP 邮件推送配置。 */
@Immutable
data class MailSettings(
    val enabled: Boolean = false,
    val mailTo: String = "",
    val smtpServer: String = "",
    val smtpPort: Int = 465,
    val username: String = "",
    val password: String = "",
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("enabled", enabled)
            put("mailTo", mailTo)
            put("smtpServer", smtpServer)
            put("smtpPort", smtpPort)
            put("username", username)
            put("password", password)
        }

    companion object {
        fun fromJson(o: JSONObject): MailSettings =
            MailSettings(
                enabled = o.getBoolean("enabled"),
                mailTo = o.getString("mailTo"),
                smtpServer = o.getString("smtpServer"),
                smtpPort = o.getInt("smtpPort"),
                username = o.getString("username"),
                password = o.getString("password"),
            )
    }
}

/** 已保存的自定义主题预设。 */
@Immutable
data class SavedThemePreset(
    val name: String,
    val primary: String,
    val secondary: String,
    val tertiary: String,
)

/** 应用通用设置。 */
@Immutable
data class AppSettings(
    val scheduleEnabled: Boolean = false,
    val scheduleHour: Int = 8,
    val scheduleMinute: Int = 0,
    val notifyEnabled: Boolean = true,
    /** 外观模式：SYSTEM / LIGHT / DARK。 */
    val appThemeMode: String = "SYSTEM",
    /** 深色模式下使用 OLED 纯黑背景；LCD 屏幕保持分层深色背景。 */
    val oledPureBlackEnabled: Boolean = false,
    /** Android 12+ 使用系统动态配色。 */
    val dynamicColorEnabled: Boolean = false,
    /** 自定义主题主色，使用 #RRGGBB；为空时使用内置或系统动态配色。 */
    val customThemeColor: String = "",
    /** 自定义主题辅色，使用 #RRGGBB；为空时跟随主色。 */
    val customThemeSecondaryColor: String = "",
    /** 自定义主题第三色，使用 #RRGGBB；为空时跟随主色。 */
    val customThemeTertiaryColor: String = "",
    /** 当前自定义配色名称。 */
    val customThemeName: String = "",
    /** 用户手动保存的自定义主题预设。 */
    val customThemePresets: List<SavedThemePreset> = emptyList(),
    /** 界面语言：SYSTEM / ZH_CN / ZH_TW / EN / JA / KO。 */
    val appLanguage: String = "SYSTEM",
    val mysDeviceId: String = "",
    val cloudDeviceId: String = "",
    val cloudBackupDeviceId: String = "",
    /** act_id 疑似失效时自动抓取最新值并重试一次。 */
    val actIdAutoRefresh: Boolean = false,
    /** 自定义米游社 App 版本号；为空时使用内置默认值。 */
    val mysAppVersion: String = "",
    /** 仅在实际米游社签到任务中按 3 天间隔自动获取版本。 */
    val mysAppVersionAutoFetch: Boolean = false,
    /** 多账号并行签到开关；关闭时按账号串行执行。 */
    val parallelEnabled: Boolean = false,
    /** 启动应用后在后台静默检查应用更新；默认开启，首次打开即可提示新版本。 */
    val appUpdateAutoCheck: Boolean = true,
    /** 调试日志开关；开启后写入 DEBUG 级别诊断日志。 */
    val debugLoggingEnabled: Boolean = false,
    /** 云原神自定义版本；为空时自动获取 */
    val cloudYsVersion: String = "",
    /** 云崩铁自定义版本；为空时自动获取 */
    val cloudSrVersion: String = "",
    /** 云游戏版本自动获取：仅在实际签到流程中按 3 天间隔执行。 */
    val cloudVersionAutoFetch: Boolean = false,
)

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OK,
    ;

    companion object {
        fun normalize(level: String): String = entries.firstOrNull { it.name == level.uppercase() }?.name ?: INFO.name
    }
}

/** 签到运行期间产生的单条日志。 */
@Immutable
data class LogEntry(
    val timestamp: Long,
    val level: String, // DEBUG / INFO / WARN / ERROR / OK
    val message: String, // 已脱敏的中文提示
    val detail: String = "", // 已脱敏的技术细节（retcode / exception）
)
