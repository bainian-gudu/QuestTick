package com.questtick.log

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.questtick.BuildConfig
import com.questtick.data.LogEntry
import com.questtick.data.SecureStore
import com.questtick.sign.Mask
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 统一崩溃诊断处理器：收集堆栈、设备信息和网络状态，并写入本地诊断文件与日志。 */
class CrashHandler private constructor(
    private val context: Context,
    private val store: SecureStore,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        runCatching {
            val report = buildReport(thread, throwable)
            writeCrashFile(report)
            appendCrashLogWithTimeout(report)
        }
        previous?.uncaughtException(thread, throwable) ?: run {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    /**
     * 在崩溃线程上只做有界等待地把崩溃摘要写入运行日志。
     *
     * 崩溃报告本身已经写入文件（[writeCrashFile]），日志只是为了让"运行日志"页能看到崩溃记录。
     * 这里绝不能无限期阻塞崩溃线程：崩溃往往发生在 IO 调度器繁忙时（例如签到正在执行），
     * 此时无限等待会把一次崩溃放大成 ANR，反而丢失现场。
     */
    private fun appendCrashLogWithTimeout(report: String) {
        val worker =
            Thread(
                {
                    runCatching {
                        store.appendLogs(
                            listOf(
                                LogEntry(
                                    timestamp = System.currentTimeMillis(),
                                    level = "ERROR",
                                    message = "应用发生崩溃，已生成诊断信息",
                                    detail = report,
                                ),
                            ),
                        )
                    }
                },
                CRASH_LOG_THREAD_NAME,
            ).apply { isDaemon = true }
        worker.start()
        worker.join(CRASH_LOG_JOIN_TIMEOUT_MS)
    }

    private fun buildReport(
        thread: Thread,
        throwable: Throwable,
    ): String {
        val stack = StringWriter().also { writer -> throwable.printStackTrace(PrintWriter(writer)) }.toString()
        return buildString {
            appendLine("==== QuestTick崩溃诊断 ====")
            appendLine("time=${timeFmt.format(Date())}")
            appendLine("thread=${thread.name}")
            appendLine("appVersion=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("network=${networkState()}")
            appendLine("exception=${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            appendLine("---- stacktrace ----")
            appendLine(stack)
        }.let(Mask::sensitive)
    }

    private fun networkState(): String =
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            val types =
                buildList {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
                }.ifEmpty { listOf("other") }
            "${types.joinToString(
                "+",
            )}, internet=${caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
            )}, validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
        } catch (e: Exception) {
            "unknown: ${e.javaClass.simpleName}"
        }

    private fun writeCrashFile(report: String) {
        val dir = File(context.filesDir, CRASH_DIR).apply { mkdirs() }
        dir
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_FILES - 1)
            ?.forEach { it.delete() }
        File(dir, "crash_${fileNameFmt.format(Date())}.txt").writeText(report)
    }

    companion object {
        private const val CRASH_DIR = "crash_diagnostics"
        private const val MAX_CRASH_FILES = 10
        private const val CRASH_LOG_THREAD_NAME = "questtick-crash-log"
        private const val CRASH_LOG_JOIN_TIMEOUT_MS = 800L
        private val timeFmt: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val fileNameFmt: SimpleDateFormat get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        fun install(
            context: Context,
            store: SecureStore,
        ) {
            val appContext = context.applicationContext
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(appContext, store, current))
        }
    }
}
