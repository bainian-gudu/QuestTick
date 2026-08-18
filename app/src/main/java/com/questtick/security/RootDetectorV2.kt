package com.questtick.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.scottyab.rootbeer.RootBeer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/** 多重证据 Root 检测器；明确的 Root 证据会阻断签到，弱风险仅用于记录。 */
object RootDetectorV2 {
    data class RootCheckResult(
        val isRooted: Boolean,
        val score: Int, // 0-100 风险分
        val triggers: List<String>, // 触发的检测项
        val level: RiskLevel,
        val rootEvidenceTriggers: List<String> = emptyList(), // 明确 Root 证据，命中即阻断
        val isEmulator: Boolean = false, // 模拟器仅记录，不作为 Root 阻断依据
        val completeness: CheckCompleteness = CheckCompleteness.COMPLETE,
        val checkedProbeCount: Int = TOTAL_PROBE_GROUPS,
        val unavailableProbes: List<String> = emptyList(),
    )

    enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    enum class CheckCompleteness { COMPLETE, PARTIAL, FAILED }

    internal fun isRootEvidenceTrigger(trigger: String): Boolean =
        // RootBeer.isRooted 是聚合结论，可能由 test-keys / 危险属性等弱风险触发，不能直接作为阻断证据。
        trigger == "root_management_apps_rb" ||
            trigger == "root_native" ||
            trigger == "su_binary_rb" ||
            trigger == "magisk_binary_rb" ||
            trigger == "su_binary_fs" ||
            trigger == "rw_paths" ||
            trigger == "which_su" ||
            trigger == "su_exec_uid0" ||
            trigger == "writable_system" ||
            trigger == "magisk_hide_traces" ||
            trigger.startsWith("root_apps:") ||
            trigger.startsWith("magisk_files:")

    private const val COMMAND_TIMEOUT_MS = 1_500L
    private const val SU_EXEC_TIMEOUT_MS = 900L
    internal const val TOTAL_PROBE_GROUPS = 12
    private const val MIN_REQUIRED_PROBE_GROUPS = 11

    /** 快速返回完整检测得到的明确 Root 结论。 */
    fun isLikelyRooted(context: Context? = null): Boolean = check(context).isRooted

    /** 完整检测并返回证据报告。 */
    fun check(context: Context? = null): RootCheckResult {
        val triggers = mutableListOf<String>()
        val unavailableProbes = mutableListOf<String>()
        var score = 0

        // Layer 1: RootBeer 官方库细分检查。避免直接使用 isRooted 聚合结论，防止 test-keys / 危险属性等弱风险误阻断。
        if (context != null) {
            try {
                val rb = RootBeer(context.applicationContext)
                if (rb.detectRootManagementApps()) {
                    triggers.add("root_management_apps_rb")
                    score += 15
                }
                if (rb.checkForRootNative()) {
                    triggers.add("root_native")
                    score += 15
                }
                if (rb.checkForSuBinary()) {
                    triggers.add("su_binary_rb")
                    score += 15
                }
                if (rb.checkForMagiskBinary()) {
                    triggers.add("magisk_binary_rb")
                    score += 20
                }
            } catch (e: Throwable) {
                unavailableProbes += "rootbeer"
                if (com.questtick.BuildConfig.DEBUG) {
                    android.util.Log.d("RootDetectorV2", "RootBeer检测异常: ${e.javaClass.simpleName}")
                }
            }
        } else {
            unavailableProbes += "rootbeer"
            unavailableProbes += "package_scan"
        }

        // Layer 2: su 二进制全路径扫描 (权重 15)
        if (checkSuBinary()) {
            triggers.add("su_binary_fs")
            score += 15
        }

        // Layer 3: 危险属性 ro.debuggable / ro.secure (权重 10)
        if (checkDangerousProps()) {
            triggers.add("dangerous_system_props")
            score += 10
        }

        // Layer 4: test-keys 检测 (权重 10)
        if (Build.TAGS?.contains("test-keys") == true) {
            triggers.add("test_keys")
            score += 10
        }

        // Layer 5: Root 管理应用包名扫描。只把明确 Root 管理器作为阻断证据，辅助/改机工具仅记录风险。
        val rootApps = detectRootManagementApps(context)
        if (rootApps.isNotEmpty()) {
            triggers.add("root_apps:${rootApps.take(3).joinToString(",")}")
            score += 15
        }
        val riskApps = detectRootRiskApps(context)
        if (riskApps.isNotEmpty()) {
            triggers.add("risk_apps:${riskApps.take(3).joinToString(",")}")
            score += 5
        }

        // Layer 6: Magisk / KernelSU / APatch 特定文件 (权重 20)
        val magiskFiles = checkMagiskSpecificPaths()
        if (magiskFiles.isNotEmpty()) {
            triggers.add("magisk_files:${magiskFiles.first()}")
            score += 20
        }

        // Layer 7: BusyBox 检测 (权重 8)
        if (checkBusyBox()) {
            triggers.add("busybox")
            score += 8
        }

        // Layer 8: which / command -v su 命令 (权重 12)
        if (checkWhichSu()) {
            triggers.add("which_su")
            score += 12
        }

        // Layer 8.5: 直接尝试执行 su -c id，命中时说明当前环境可提权，必须强信号阻断。
        if (checkSuCommandExecution()) {
            triggers.add("su_exec_uid0")
            score += 35
        }

        // Layer 9: SELinux 状态 (权重 5)
        if (isSelinuxPermissive()) {
            triggers.add("selinux_permissive")
            score += 5
        }

        // Layer 10: 可写系统路径 (权重 10)
        if (checkWritableSystemPaths()) {
            triggers.add("writable_system")
            score += 10
        }

        // Layer 11: 模拟器检测。模拟器本身不等于 Root，仅记录环境信息，不参与 Root 证据阻断。
        val emulator = isEmulator()
        if (emulator) {
            triggers.add("emulator")
            score += 5
        }

        // Layer 12: Magisk Hide / Shamiko / Zygisk 痕迹 (权重 10)
        if (detectMagiskHide()) {
            triggers.add("magisk_hide_traces")
            score += 10
        }

        score = score.coerceIn(0, 100)
        val distinctTriggers = triggers.distinct()
        val rootEvidenceTriggers = distinctTriggers.filter(::isRootEvidenceTrigger)
        val level =
            when {
                score >= 70 -> RiskLevel.CRITICAL
                score >= 50 -> RiskLevel.HIGH
                score >= 30 -> RiskLevel.MEDIUM
                score >= 10 -> RiskLevel.LOW
                else -> RiskLevel.SAFE
            }

        val distinctUnavailableProbes = unavailableProbes.distinct()
        val checkedProbeCount = (TOTAL_PROBE_GROUPS - distinctUnavailableProbes.size).coerceIn(0, TOTAL_PROBE_GROUPS)
        val completeness =
            when {
                checkedProbeCount < MIN_REQUIRED_PROBE_GROUPS -> CheckCompleteness.FAILED
                distinctUnavailableProbes.isNotEmpty() -> CheckCompleteness.PARTIAL
                else -> CheckCompleteness.COMPLETE
            }
        return RootCheckResult(
            isRooted = rootEvidenceTriggers.isNotEmpty(),
            score = score,
            triggers = distinctTriggers,
            level = level,
            rootEvidenceTriggers = rootEvidenceTriggers,
            isEmulator = emulator,
            completeness = completeness,
            checkedProbeCount = checkedProbeCount,
            unavailableProbes = distinctUnavailableProbes,
        )
    }

    // === 具体检测实现 ===

    private val SU_PATHS =
        listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/xbin/daemonsu",
            // Magisk / KernelSU 新增路径 2024-2026
            "/system_ext/bin/su",
            "/vendor/bin/su",
            "/odm/bin/su",
            "/product/bin/su",
            "/apex/com.android.runtime/bin/su",
            "/data/adb/magisk",
            "/data/adb/magisk.db",
            "/data/adb/ksud",
            "/data/adb/kernelsu",
            "/data/adb/apd",
            "/data/adb/apatch",
            "/sbin/.magisk",
            "/cache/.disable_magisk",
            "/dev/.magisk.unblock",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su",
            "/data/adb/su",
            "/data/local/tmp/su",
            "/data/adb/modules",
            "/data/adb/post-fs-data.d",
            "/data/adb/service.d",
        )

    private fun checkSuBinary(): Boolean {
        return SU_PATHS.any { File(it).exists() }
    }

    private fun checkDangerousProps(): Boolean {
        return try {
            val debuggable = getProp("ro.debuggable") == "1"
            val secure = getProp("ro.secure") == "0"
            debuggable || secure
        } catch (_: Exception) {
            false
        }
    }

    private fun getProp(key: String): String? =
        try {
            execFirstLine(arrayOf("getprop", key))
        } catch (e: Exception) {
            if (com.questtick.BuildConfig.DEBUG) {
                android.util.Log.d("RootDetectorV2", "getProp异常: ${e.message}")
            }
            null
        }

    private fun execFirstLine(
        command: Array<String>,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): String? {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        return try {
            if (!waitForProcess(process, timeoutMs)) return null
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine()?.trim() }
        } finally {
            process.destroy()
        }
    }

    private fun execOutput(
        command: Array<String>,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        maxChars: Int = 4_096,
    ): String? {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        return try {
            if (!waitForProcess(process, timeoutMs)) return null
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val output = StringBuilder()
                var line = reader.readLine()
                while (line != null && output.length < maxChars) {
                    output.append(line).append('\n')
                    line = reader.readLine()
                }
                output.toString()
            }
        } finally {
            process.destroy()
        }
    }

    private fun execSucceeds(
        command: Array<String>,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): Boolean =
        try {
            val process = Runtime.getRuntime().exec(command)
            try {
                waitForProcess(process, timeoutMs) && process.exitValue() == 0
            } finally {
                process.destroy()
            }
        } catch (_: Exception) {
            false
        }

    private fun waitForProcess(
        process: Process,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): Boolean {
        val completed = AtomicBoolean(false)
        val waiter =
            Thread {
                try {
                    process.waitFor()
                    completed.set(true)
                } catch (_: InterruptedException) {
                }
            }
        waiter.isDaemon = true
        waiter.start()
        waiter.join(timeoutMs)
        if (!completed.get()) {
            process.destroy()
            waiter.interrupt()
        }
        return completed.get()
    }

    private val ROOT_MANAGEMENT_PACKAGES =
        listOf(
            // SuperSU / Superuser
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot",
            // Magisk / KernelSU / APatch
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
            "me.weishu.magisk",
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            // Root 隐藏工具通常只有 Root 环境才有意义，按明确 Root 证据处理。
            "com.amphoras.hidemyroot",
            "com.amphoras.hidemyrootadfree",
            "com.formyhm.hideroot",
            "com.formyhm.hiderootPremium",
        ).distinct()

    private val ROOT_RISK_PACKAGES =
        listOf(
            // Shizuku / AppOps / 模块管理等并不必然代表设备已 Root，仅作为环境风险记录。
            "com.rikka.appops",
            "moe.shizuku.privileged.api",
            "com.fox2code.mmm",
            // ROM / Patch / 市场类工具可辅助改机，但存在免 Root 或 ADB 模式，避免单独阻断。
            "com.koushikdutta.rommanager",
            "com.koushikdutta.rommanager.license",
            "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch",
            "com.chelpus.luckypatcher",
            "com.ramdroid.appquarantine",
            "com.ramdroid.appquarantinepro",
            "com.android.vending.billing.InAppBillingService.COIN",
            "com.android.vending.billing.InAppBillingService.LUCK",
            "com.blackmartalpha",
            "org.blackmart.market",
            "com.allinone.free",
            "com.repodroid.app",
            // Xposed / LSPosed / LSPatch / Substrate 等框架类工具仅记录风险，避免误伤非 Root 补丁场景。
            "me.weishu.exp",
            "com.saurik.substrate",
            "de.robv.android.xposed.installer",
            "io.va.exposed",
            "com.sollyu.xposed",
            "org.lsposed.manager",
            "org.lsposed.lspatch",
        ).distinct()

    private fun detectInstalledPackages(
        context: Context?,
        packages: List<String>,
    ): List<String> {
        if (context == null) return emptyList()
        val pm = context.packageManager
        return packages.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun detectRootManagementApps(context: Context?): List<String> =
        detectInstalledPackages(context, ROOT_MANAGEMENT_PACKAGES)

    private fun detectRootRiskApps(context: Context?): List<String> =
        detectInstalledPackages(context, ROOT_RISK_PACKAGES)

    private fun checkMagiskSpecificPaths(): List<String> {
        val magiskPaths =
            listOf(
                "/data/adb/magisk",
                "/data/adb/magisk.db",
                "/data/adb/magisk_bak",
                "/sbin/.magisk",
                "/cache/.disable_magisk",
                "/dev/.magisk.unblock",
                "/data/adb/ksud",
                "/data/adb/kernelsu",
                "/data/adb/apd",
                "/data/adb/modules",
                "/data/adb/modules_update",
                "/data/adb/post-fs-data.d",
                "/data/adb/service.d",
                "/data/adb/shamiko",
                "/data/misc/riru",
                "/data/misc/riru/modules",
                "/data/local/tmp/zygisk",
                "/system/xbin/ku.sud",
            )
        return magiskPaths.filter { File(it).exists() }
    }

    private fun checkBusyBox(): Boolean {
        val paths =
            listOf(
                "/system/xbin/busybox",
                "/system/bin/busybox",
                "/data/local/busybox",
                "/data/local/xbin/busybox",
            )
        if (paths.any { File(it).exists() }) return true
        return execSucceeds(arrayOf("which", "busybox"), SU_EXEC_TIMEOUT_MS)
    }

    private fun checkWhichSu(): Boolean {
        // 部分设备 which 不在 PATH 中，优先尝试常见绝对路径，再回退到 PATH 搜索与 command -v。
        val whichPaths =
            listOf(
                "/system/bin/which",
                "/system/xbin/which",
                "/vendor/bin/which",
            )
        for (which in whichPaths) {
            if (File(which).exists() && execSucceeds(arrayOf(which, "su"), SU_EXEC_TIMEOUT_MS)) return true
        }
        return execSucceeds(
            arrayOf(
                "sh",
                "-c",
                "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/su/bin:${'$'}PATH; " +
                    "command -v su >/dev/null 2>&1 || which su >/dev/null 2>&1",
            ),
            SU_EXEC_TIMEOUT_MS,
        )
    }

    private fun checkSuCommandExecution(): Boolean =
        try {
            val absoluteSuPaths = SU_PATHS.filter { it.endsWith("/su") && File(it).exists() }
            val script =
                buildString {
                    append("PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/su/bin:${'$'}PATH; ")
                    append("for s in su")
                    absoluteSuPaths.forEach { append(' ').append(it) }
                    append("; do ")
                    append("if command -v \"${'$'}s\" >/dev/null 2>&1 || [ -x \"${'$'}s\" ]; then ")
                    append("\"${'$'}s\" -c id 2>&1 && exit 0; ")
                    append("fi; ")
                    append("done; exit 1")
                }
            execOutput(arrayOf("sh", "-c", script), SU_EXEC_TIMEOUT_MS)?.contains("uid=0") == true
        } catch (_: Exception) {
            false
        }

    private fun isSelinuxPermissive(): Boolean {
        return try {
            getProp("ro.build.selinux") == "0" ||
                File("/sys/fs/selinux/enforce").takeIf { it.exists() }?.readText()?.trim() == "0"
        } catch (_: Exception) {
            false
        }
    }

    private fun checkWritableSystemPaths(): Boolean {
        val paths =
            arrayOf(
                "/system",
                "/system/bin",
                "/system/sbin",
                "/system/xbin",
                "/vendor/bin",
                "/sbin",
                "/etc",
            )
        return paths.any { path ->
            try {
                val f = File(path)
                f.exists() && f.canWrite()
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isEmulator(): Boolean {
        val fields =
            listOf(
                Build.FINGERPRINT,
                Build.MODEL,
                Build.MANUFACTURER,
                Build.BRAND,
                Build.DEVICE,
                Build.PRODUCT,
                Build.HARDWARE,
                Build.BOARD,
            ).map { it.orEmpty().lowercase() }
        val markers =
            listOf(
                "generic",
                "unknown",
                "google_sdk",
                "emulator",
                "android sdk built for x86",
                "genymotion",
                "goldfish",
                "ranchu",
                "vbox",
                "nox",
                "bluestacks",
                "ldplayer",
                "leidian",
                "mumu",
                "nemu",
                "netease",
            )
        return fields.any { field -> markers.any { marker -> marker in field } } ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
    }

    /** 检测 Magisk Hide / Shamiko 痕迹 - 反向检测 */
    private fun detectMagiskHide(): Boolean {
        // 检查一些 Magisk Hide 会留下的痕迹
        return try {
            val mounts = File("/proc/mounts").readText()
            mounts.contains("magisk") || mounts.contains("APatch") || mounts.contains("KSU")
        } catch (_: Exception) {
            false
        }
    }
}
