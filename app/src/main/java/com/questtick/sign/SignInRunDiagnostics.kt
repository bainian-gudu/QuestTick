package com.questtick.sign

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.questtick.config.DsConfigRepository
import com.questtick.data.Account
import com.questtick.data.AppSettings
import com.questtick.data.Games
import com.questtick.data.SecureStore
import com.questtick.security.RootBlockingPolicy
import com.questtick.security.RootDetectorV2

/**
 * 签到运行诊断日志服务。
 *
 * 集中输出系统环境、账号凭证概况、Root 检测结论与运行模式等诊断信息；
 * 只负责"记录"，不参与编排器的调度、状态归并与落盘决策。
 */
// 诊断代码从编排器原样迁入，以下抑制项与迁入前在 SignInRunner.kt 中的 detekt 结论保持一致
//（存量项原本由 baseline.xml 冻结，按文件归属随代码迁移，避免 baseline 签名失配）。
@Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooGenericExceptionCaught",
)
internal class SignInRunDiagnostics(
    private val context: Context,
    private val store: SecureStore,
    private val cloudGameBindings: List<CloudGameBinding>,
    private val log: (level: String, message: String, detail: String) -> Unit,
) {
    /** 记录系统与运行环境信息，供详细日志排查使用。 */
    fun logSystemInfo(
        settings: AppSettings,
        accounts: List<Account>,
        rootResultForLog: RootDetectorV2.RootCheckResult?,
    ) {
        val appVersion =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (_: Exception) {
                "unknown"
            }

        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val model = Build.MODEL ?: "unknown"
        val androidVersion = Build.VERSION.RELEASE ?: "unknown"
        val sdk = Build.VERSION.SDK_INT
        // Root 检测为强制安全基线；使用本次签到前的实时检测结果写入日志。
        val rootState =
            rootResultForLog?.let {
                when {
                    it.isRooted -> {
                        "检测到Root [${it.level} score=${it.score} ${it.rootEvidenceTriggers.take(3).joinToString()}]"
                    }
                    it.completeness == RootDetectorV2.CheckCompleteness.FAILED -> {
                        "Root检测失败 [checked=${it.checkedProbeCount}/${RootDetectorV2.TOTAL_PROBE_GROUPS} unavailable=${it.unavailableProbes.take(3).joinToString()}]"
                    }
                    it.completeness == RootDetectorV2.CheckCompleteness.PARTIAL -> {
                        "Root检测部分降级（不单独阻断） [checked=${it.checkedProbeCount}/${RootDetectorV2.TOTAL_PROBE_GROUPS} unavailable=${it.unavailableProbes.take(3).joinToString()}]"
                    }
                    it.isEmulator -> {
                        "模拟器环境（不阻断） [score=${it.score} ${it.triggers.take(3).joinToString()}]"
                    }
                    it.triggers.isNotEmpty() -> {
                        "检测到非Root风险项（不阻断） [${it.level} score=${it.score} ${it.triggers.take(3).joinToString()}]"
                    }
                    else -> "未检测到Root"
                }
            } ?: "检测失败"

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        val notificationGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        val enabledCount = accounts.count { it.enabled }
        val withMysCookie = accounts.count { it.mysCookie.isNotBlank() }
        val withMysKeepLogin = accounts.count { it.hasMysKeepLogin }
        val withMysCredential = accounts.count { it.mysCookie.isNotBlank() || it.hasMysKeepLogin }
        val withCloudYs = accounts.count { it.genshinToken.isNotBlank() || it.hasGenshinCloudKeepLogin }
        val withCloudSr = accounts.count { it.starrailToken.isNotBlank() || it.hasStarrailCloudKeepLogin }
        val withCloudToken = withCloudYs + withCloudSr

        val schedule =
            if (settings.scheduleEnabled) {
                "${settings.scheduleHour.toString().padStart(2, '0')}:${settings.scheduleMinute.toString().padStart(2, '0')}"
            } else {
                "未启用"
            }

        val experiments =
            buildList {
                if (settings.actIdAutoRefresh) add("act_id自动刷新")
                if (settings.parallelEnabled) add("并行签到")
                if (settings.mysAppVersionAutoFetch) add("米游社版本自动获取")
                if (settings.mysAppVersion.isNotBlank()) add("米游社自定义版本=${settings.mysAppVersion}")
                if (settings.cloudVersionAutoFetch) {
                    add(
                        "云游戏版本自动获取(" +
                            "ys=${CloudVersionRepository.effectiveYs()}, " +
                            "sr=${CloudVersionRepository.effectiveSr()})",
                    )
                }
                if (settings.cloudYsVersion.isNotBlank()) add("云原神自定义版本=${settings.cloudYsVersion}")
                if (settings.cloudSrVersion.isNotBlank()) add("云崩铁自定义版本=${settings.cloudSrVersion}")
                if (settings.debugLoggingEnabled) add("调试日志")
                add("root检测=强制开启，检测到Root后阻断")
            }.joinToString(", ").ifEmpty { "无" }

        val mail = store.getMailSettings()
        val mysDeviceId = store.effectiveMysDeviceId(forceCreate = false)
        val cloudDeviceId = store.effectiveCloudDeviceId(forceCreate = false)
        val dsConfig = DsConfigRepository.current

        log(
            "INFO",
            "系统/运行环境信息",
            buildString {
                append("appVersion=$appVersion, ")
                append("android=$androidVersion (API $sdk), ")
                append("device=$manufacturer $model, ")
                append("rootState=$rootState, ")
                append("batteryOptimizationIgnored=$batteryOptIgnored, ")
                append("notificationPermissionGranted=$notificationGranted, ")
                append(
                    "accounts={total=${accounts.size}, enabled=$enabledCount, " +
                        "withMysCookie=$withMysCookie, withMysKeepLogin=$withMysKeepLogin, " +
                        "withMysCredential=$withMysCredential, withCloudYS=$withCloudYs, " +
                        "withCloudSR=$withCloudSr, withCloudToken=$withCloudToken}, ",
                )
                append("schedule={enabled=${settings.scheduleEnabled}, time=$schedule}, ")
                append("notifyEnabled=${settings.notifyEnabled}, ")
                append("mailEnabled=${mail.enabled}, ")
                append("experiments={$experiments}, ")
                append("dsConfig={version=${dsConfig.version}, algorithm=${dsConfig.algorithm}, updateTime=${dsConfig.updateTime}}, ")
                append("mysDeviceId=${if (mysDeviceId.isNotBlank()) "***${mysDeviceId.takeLast(4)}" else "未生成"}, ")
                append("cloudDeviceId=${if (cloudDeviceId.isNotBlank()) "***${cloudDeviceId.takeLast(4)}" else "未生成"}")
            },
        )
    }

    fun logPlannedTasksForAccount(account: Account) {
        val mysGames = account.selectedMysGames()
        val cloudGames = account.selectedCloudBindings(cloudGameBindings).map { it.game.name }
        val gameNames = (mysGames.map { Games.byKey(it.key)?.name ?: it.name } + cloudGames).joinToString("、")
        log(
            "INFO",
            "[${account.label}] 待签游戏: $gameNames（共 ${mysGames.size + cloudGames.size} 款）",
            "hasCookie=${account.mysCookie.isNotBlank()}, hasMysKeepLogin=${account.hasMysKeepLogin}, " +
                "hasGenshinToken=${account.genshinToken.isNotBlank()}, hasStarrailToken=${account.starrailToken.isNotBlank()}, " +
                "hasGenshinWebCookie=${account.genshinWebCookie.isNotBlank()}, " +
                "hasStarrailWebCookie=${account.starrailWebCookie.isNotBlank()}, qrLoginBound=${account.qrLoginBound}",
        )
    }

    fun rootBlockResultMessage(decision: RootBlockingPolicy.Decision): String =
        if (decision == RootBlockingPolicy.Decision.BLOCK_ROOT_EVIDENCE) {
            RootBlockMessages.DETECTED_CONTENT
        } else {
            RootBlockMessages.CHECK_FAILED_CONTENT
        }
}
