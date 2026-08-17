package com.questtick.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.questtick.data.LogEntry
import com.questtick.sign.Mask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志导出器。
 *
 * 导出前会再次调用 [Mask.sensitive] 做防御性脱敏，即使旧日志或导入日志遗漏处理，也不会泄露
 * Cookie / Token。支持 TXT / JSON / ZIP 三种格式，便于普通阅读与问题诊断。
 */
object LogExporter {
    enum class ExportFormat(
        val label: String,
        val extension: String,
        val mimeType: String,
    ) {
        TXT("TXT", "txt", "text/plain"),
        JSON("JSON", "json", "application/json"),
        ZIP("ZIP", "zip", "application/zip"),
    }

    // SimpleDateFormat 非线程安全，每次访问都创建新实例。
    private val fileNameFmt: SimpleDateFormat get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val lineTimeFmt: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 生成已脱敏的日志导出文本。 */
    fun buildText(
        logs: List<LogEntry>,
        verbose: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("==== QuestTick 运行日志 ====")
        sb.appendLine("导出时间：${lineTimeFmt.format(Date())}")
        sb.appendLine("日志条数：${logs.size}")
        sb.appendLine("模式：${if (verbose) "详细（含技术细节）" else "简洁"}")
        sb.appendLine("说明：所有 Cookie / Token / 账号 ID 等敏感信息均已自动打码。")
        sb.appendLine("--------------------------------")
        for (entry in logs) {
            val time = lineTimeFmt.format(Date(entry.timestamp))
            sb.appendLine("[$time][${entry.level}] ${Mask.sensitive(entry.message)}")
            if (verbose && entry.detail.isNotBlank()) {
                sb.appendLine("    └ 详情: ${Mask.sensitive(entry.detail)}")
            }
        }
        sb.appendLine("--------------------------------")
        sb.appendLine("由「QuestTick」导出")
        return sb.toString()
    }

    /** 生成已脱敏的 JSON 导出文本。 */
    fun buildJson(
        logs: List<LogEntry>,
        verbose: Boolean,
    ): String {
        val root = JSONObject()
        root.put("app", "QuestTick")
        root.put("exportTime", System.currentTimeMillis())
        root.put("verbose", verbose)
        root.put("count", logs.size)
        root.put(
            "logs",
            JSONArray().apply {
                logs.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("timestamp", entry.timestamp)
                            put("time", lineTimeFmt.format(Date(entry.timestamp)))
                            put("level", entry.level)
                            put("message", Mask.sensitive(entry.message))
                            if (verbose) put("detail", Mask.sensitive(entry.detail))
                        },
                    )
                }
            },
        )
        return root.toString(2)
    }

    fun buildBytes(
        logs: List<LogEntry>,
        verbose: Boolean,
        format: ExportFormat,
    ): ByteArray =
        when (format) {
            ExportFormat.TXT -> buildText(logs, verbose).toByteArray(Charsets.UTF_8)
            ExportFormat.JSON -> buildJson(logs, verbose).toByteArray(Charsets.UTF_8)
            ExportFormat.ZIP -> buildZip(logs, verbose, emptyList())
        }

    fun buildBytesWithDiagnostics(
        context: Context,
        logs: List<LogEntry>,
        verbose: Boolean,
        format: ExportFormat,
    ): ByteArray =
        when (format) {
            ExportFormat.ZIP -> buildZip(logs, verbose, readCrashReports(context))
            else -> buildBytes(logs, verbose, format)
        }

    private fun buildZip(
        logs: List<LogEntry>,
        verbose: Boolean,
        crashReports: List<File>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("signin_logs.txt"))
            zip.write(buildText(logs, verbose).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("signin_logs.json"))
            zip.write(buildJson(logs, verbose).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            crashReports.forEach { file ->
                zip.putNextEntry(ZipEntry("crash/${file.name}"))
                zip.write(Mask.sensitive(file.readText()).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(
                (
                    "本压缩包由QuestTick生成。" +
                        "日志与崩溃诊断均已进行二次脱敏处理。\n"
                ).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun readCrashReports(context: Context): List<File> =
        File(context.filesDir, "crash_diagnostics")
            .listFiles { file -> file.isFile && file.extension.equals("txt", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(10)
            .orEmpty()

    /** 生成日志导出的默认文件名，例如 `signin_logs_20260615_153012.txt`。 */
    fun suggestFileName(
        now: Date = Date(),
        format: ExportFormat = ExportFormat.TXT,
    ): String = "signin_logs_${fileNameFmt.format(now)}.${format.extension}"

    /** 将日志写入 cacheDir/exports 并打开系统分享面板；失败时返回中文错误信息。 */
    fun exportAndShare(
        context: Context,
        logs: List<LogEntry>,
        verbose: Boolean,
        format: ExportFormat = ExportFormat.TXT,
    ): Result<Unit> {
        if (logs.isEmpty()) return Result.failure(IllegalStateException("暂无日志可导出"))
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            // 清理 7 天前的导出缓存，避免 cacheDir 膨胀。
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < weekAgo) it.delete() }

            val file = File(dir, suggestFileName(format = format))
            file.writeBytes(buildBytesWithDiagnostics(context, logs, verbose, format))

            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "QuestTick日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            val chooser =
                Intent.createChooser(send, "导出日志").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("日志导出失败：${e.javaClass.simpleName}"))
        }
    }
}
