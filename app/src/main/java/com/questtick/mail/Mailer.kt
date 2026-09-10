package com.questtick.mail

import com.questtick.net.TrustedUrlPolicy

import com.questtick.data.MailSettings
import com.questtick.data.RunRecord
import com.questtick.data.TaskResult
import com.questtick.i18n.AppLanguage
import com.questtick.i18n.localizeText
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * 可选 SMTP 邮件推送。
 *
 * 邮件模板按账号分组，区分米游社与云游戏，生成动态标题与状态徽章。模板固定使用应用默认
 * 浅色调色板，不读取动态配色或用户自定义主题；正文不写入 Cookie / Token 等敏感值。
 */
object Mailer {
    private object DefaultMailPalette {
        const val PAGE_BACKGROUND = "#F8FBFF"
        const val SURFACE = "#FFFFFF"
        const val TEXT = "#0F172A"
        const val PRIMARY = "#2563EB"
        const val PRIMARY_LIGHT = "#60A5FA"
        const val PRIMARY_TINT = "#EFF6FF"
        const val PRIMARY_BORDER = "#BFDBFE"
    }

    private data class TitleInfo(
        val pageTitle: String,
        val mainTitle: String,
        val sectionTitle: String,
        val mailTitle: String,
    )

    private data class CloudTime(
        val after: String,
        val claimed: String,
    )

    suspend fun send(
        settings: MailSettings,
        record: RunRecord,
        language: AppLanguage = AppLanguage.SIMPLIFIED_CHINESE,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!settings.enabled) return@withContext Result.success(Unit)
            if (settings.mailTo.isBlank() ||
                settings.smtpServer.isBlank() ||
                settings.username.isBlank() ||
                settings.password.isBlank()
            ) {
                return@withContext Result.failure(IllegalStateException("SMTP 配置不完整"))
            }

            try {
                val useSsl = settings.smtpPort == 465
                val props =
                    Properties().apply {
                        put("mail.smtp.host", settings.smtpServer)
                        put("mail.smtp.port", settings.smtpPort.toString())
                        put("mail.smtp.auth", "true")
                        // 强制 TLS，防止明文回落
                        put("mail.smtp.ssl.checkserveridentity", "true")
                        if (useSsl) {
                            put("mail.smtp.ssl.enable", "true")
                        } else {
                            put("mail.smtp.starttls.enable", "true")
                            put("mail.smtp.starttls.required", "true")
                            // 禁止明文回落
                        }
                        put("mail.smtp.connectiontimeout", "15000")
                        put("mail.smtp.timeout", "15000")
                        put("mail.smtp.writetimeout", "15000")
                        // 禁用已被认为不安全的 SSL 协议
                        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }

                val session =
                    Session.getInstance(
                        props,
                        object : Authenticator() {
                            override fun getPasswordAuthentication() = PasswordAuthentication(settings.username, settings.password)
                        },
                    )

                val message =
                    MimeMessage(session).apply {
                        setFrom(InternetAddress(settings.username))
                        val recipients =
                            settings.mailTo
                                .split(",", "\n")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .map { InternetAddress(it) }
                                .toTypedArray()
                        setRecipients(Message.RecipientType.TO, recipients)
                        subject = buildSubject(record, language)
                        setContent(buildHtml(record, language), "text/html; charset=utf-8")
                    }

                Transport.send(message)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun buildSubject(
        record: RunRecord,
        language: AppLanguage,
    ): String {
        val title = localizeMailTemplate(getTitleInfo(record).mailTitle, language)
        val status =
            when {
                record.total == 0 -> localizeText("无任务", language)
                record.resultUnknown == record.total -> localizeText("结果待确认", language)
                record.failed == 0 && record.resultUnknown == 0 -> localizeText("全部成功", language)
                record.failed == 0 -> localizeText("${record.resultUnknown}项待确认", language)
                record.failed >= record.total -> localizeText("全部失败", language)
                else -> localizeText("${record.failed}项失败 ${record.resultUnknown}项待确认", language)
            }
        return "$title - $status"
    }

    fun buildHtml(
        record: RunRecord,
        language: AppLanguage = AppLanguage.SIMPLIFIED_CHINESE,
    ): String {
        val titleInfo = getTitleInfo(record)
        val statsSection = renderStatsSection(record)
        val groupedSection = renderGroupedAccountSection(record, titleInfo)
        val emptyMessage =
            if (record.results.isEmpty()) {
                """
                <div
                  style="padding:14px;background:${DefaultMailPalette.PRIMARY_TINT};border:1px solid ${DefaultMailPalette.PRIMARY_BORDER};
                         text-align:center;margin-top:16px;font-size:13px;
                         border-radius:10px;color:#1E40AF;">
                    本次没有可显示的签到任务。
                </div>
                """.trimIndent()
            } else {
                ""
            }

        return """
            <!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>${escape(titleInfo.pageTitle)}</title>
</head>
<body style="margin:0;padding:16px;background:${DefaultMailPalette.PAGE_BACKGROUND};font-family:Arial,'Microsoft YaHei','PingFang SC',sans-serif;color:${DefaultMailPalette.TEXT};">
  <div
    style="max-width:720px;margin:0 auto;background:${DefaultMailPalette.SURFACE};
           border:1px solid ${DefaultMailPalette.PRIMARY_BORDER};border-radius:16px;padding:18px;
           box-shadow:0 4px 18px rgba(37,99,235,.10);">

    <div
      style="background:${DefaultMailPalette.PRIMARY};background:linear-gradient(135deg,${DefaultMailPalette.PRIMARY},${DefaultMailPalette.PRIMARY_LIGHT});
             border-radius:13px;padding:16px 12px;text-align:center;color:${DefaultMailPalette.SURFACE};">
      <div style="font-size:19px;font-weight:bold;line-height:1.4;letter-spacing:.5px;">
        ${escape(titleInfo.mainTitle)}
      </div>
      <div style="font-size:12px;margin-top:6px;color:#DBEAFE;">
        一日签到已毕 · 今日收获如下
      </div>
    </div>

    <div
      style="margin-top:14px;padding:10px 12px;background:${DefaultMailPalette.PRIMARY_TINT};
             border:1px solid ${DefaultMailPalette.PRIMARY_BORDER};border-radius:10px;text-align:center;
             font-size:12px;color:#1E3A8A;">
      <strong>🕒 签到时间：</strong> ${escape(formatMailDate(record.timestamp, language))}
    </div>

    $statsSection
    $groupedSection
    $emptyMessage

    <p style="text-align:center;margin-top:18px;font-size:11px;color:#64748B;">
      本邮件由「米游社签到」App 自动发送，不包含任何 Cookie / Token 等敏感信息。
    </p>
  </div>
</body>
</html>
            """.trimIndent().let { localizeMailTemplate(it, language) }
    }

    private fun getTitleInfo(record: RunRecord): TitleInfo {
        val hasMys = record.results.any { !isCloud(it) }
        val hasCloud = record.results.any { isCloud(it) }
        return when {
            hasMys && hasCloud ->
                TitleInfo(
                    pageTitle = "米游社和云游戏签到结果总结",
                    mainTitle = "📜 米游社和云游戏签到结果总结",
                    sectionTitle = "📖 签到明细",
                    mailTitle = "米游社和云游戏签到结果",
                )
            hasCloud ->
                TitleInfo(
                    pageTitle = "云游戏签到结果总结",
                    mainTitle = "📜 云游戏签到结果总结",
                    sectionTitle = "📖 云游戏签到明细",
                    mailTitle = "云游戏签到结果",
                )
            hasMys ->
                TitleInfo(
                    pageTitle = "米游社签到结果总结",
                    mainTitle = "📜 米游社签到结果总结",
                    sectionTitle = "📖 米游社签到明细",
                    mailTitle = "米游社签到结果",
                )
            else ->
                TitleInfo(
                    pageTitle = "签到结果总结",
                    mainTitle = "📜 签到结果总结",
                    sectionTitle = "📖 签到结果",
                    mailTitle = "签到结果",
                )
        }
    }

    private fun renderStatsSection(record: RunRecord): String {
        if (record.total <= 0) return ""
        val success = (record.succeeded + record.alreadySigned).coerceAtLeast(0)
        val mysSuccess = record.results.count { !isCloud(it) && it.success && !it.skipped }
        val cloudSuccess = record.results.count { isCloud(it) && it.success && !it.skipped }
        return """
            <div style="margin-top:14px;padding:12px;background:#EFF6FF;border:1px solid #BFDBFE;border-radius:13px;">
              <div style="font-size:13px;font-weight:bold;color:#1E40AF;text-align:center;margin-bottom:10px;">
                📌 本次签到统计
              </div>

              <table cellpadding="0" cellspacing="0" style="width:100%;border-collapse:collapse;table-layout:fixed;">
                <tr>
                  ${statCard("签到数", record.total.toString(), DefaultMailPalette.SURFACE, DefaultMailPalette.PRIMARY_BORDER, "#1E3A8A")}
                  ${statCard("成功", success.toString(), DefaultMailPalette.PRIMARY_TINT, "#93C5FD", DefaultMailPalette.PRIMARY)}
                  ${statCard("失败", record.failed.toString(), "#FEF2F2", "#FECACA", "#DC2626")}
                  ${statCard("待确认", record.resultUnknown.toString(), "#FFFBEB", "#FDE68A", "#D97706")}
                </tr>
              </table>

              <table cellpadding="0" cellspacing="0" style="width:100%;border-collapse:collapse;table-layout:fixed;margin-top:8px;">
                <tr>
                  <td style="padding:5px;">
                    <div style="background:#FFFFFF;border:1px solid #BFDBFE;border-radius:10px;padding:8px 6px;text-align:center;">
                      <span style="font-size:12px;color:#1D4ED8;font-weight:bold;">🏠 米游社成功：$mysSuccess</span>
                    </div>
                  </td>

                  <td style="padding:5px;">
                    <div style="background:#FFFFFF;border:1px solid #BFDBFE;border-radius:10px;padding:8px 6px;text-align:center;">
                      <span style="font-size:12px;color:#1D4ED8;font-weight:bold;">☁️ 云游戏成功：$cloudSuccess</span>
                    </div>
                  </td>
                </tr>
              </table>
            </div>
            """.trimIndent()
    }

    private fun statCard(
        label: String,
        value: String,
        bg: String,
        border: String,
        color: String,
    ): String =
        """
        <td style="padding:5px;">
          <div style="background:$bg;border:1px solid $border;border-radius:10px;padding:10px 6px;text-align:center;">
            <div style="font-size:11px;color:$color;margin-bottom:4px;">${escape(label)}</div>
            <div style="font-size:20px;font-weight:bold;color:$color;">${escape(value)}</div>
          </div>
        </td>
        """.trimIndent()

    private fun renderGroupedAccountSection(
        record: RunRecord,
        titleInfo: TitleInfo,
    ): String {
        val groups =
            record.results
                .groupBy { result ->
                    result.accountId.ifBlank { "label:${result.accountLabel.ifBlank { "未知账号" }}" }
                }.toSortedMap()
        if (groups.isEmpty()) return ""
        val body =
            groups.values.joinToString("\n") { results ->
                renderAccountBlock(results.first().accountLabel.ifBlank { "未知账号" }, results)
            }
        return """
            <h3 style="color:#1D4ED8;text-align:center;margin:20px 0 10px;font-size:16px;">
              ${escape(titleInfo.sectionTitle)}
            </h3>
            $body
            """.trimIndent()
    }

    private fun renderAccountBlock(
        account: String,
        results: List<TaskResult>,
    ): String {
        val mys = results.filterNot(::isCloud)
        val cloud = results.filter(::isCloud)
        return """
            <div style="margin-top:16px;padding:12px;border:1px solid #BFDBFE;border-radius:14px;background:#FFFFFF;">
              <div style="padding:10px 12px;background:#EFF6FF;border:1px solid #BFDBFE;border-radius:11px;text-align:center;">
                <div style="font-size:16px;font-weight:bold;color:#1E3A8A;">
                  ${escape(account)}
                </div>
              </div>

              ${renderAccountMysTable(mys)}
              ${renderAccountCloudTable(cloud)}
            </div>
            """.trimIndent()
    }

    private fun renderAccountMysTable(rows: List<TaskResult>): String {
        if (rows.isEmpty()) return ""
        val body = rows.joinToString("\n") { row -> renderAccountMysRow(row, rows.indexOf(row)) }
        return """
            <div
              style="margin-top:12px;padding:8px 10px;background:#EFF6FF;
                     border:1px solid #BFDBFE;border-radius:9px;text-align:center;
                     font-size:13px;font-weight:bold;color:#1D4ED8;">
              🏠 米游社结果
            </div>

            <div style="margin-top:8px;background:#FFFFFF;border:1px solid #93C5FD;border-radius:9px;overflow:hidden;">
              <table cellpadding="0" cellspacing="0" style="border-collapse:collapse;width:100%;table-layout:fixed;">
                <thead>
                  <tr style="background:#2563EB;color:#FFFFFF;">
                    <th style="padding:8px 4px;text-align:center;width:22%;font-size:12px;">游戏</th>
                    <th style="padding:8px 4px;text-align:center;width:22%;font-size:12px;">签到结果</th>
                    <th style="padding:8px 4px;text-align:center;width:20%;font-size:12px;">累计天数</th>
                    <th style="padding:8px 4px;text-align:center;width:36%;font-size:12px;">今日奖励</th>
                  </tr>
                </thead>
                <tbody>$body</tbody>
              </table>
            </div>
            """.trimIndent()
    }

    private fun renderAccountMysRow(
        row: TaskResult,
        index: Int,
    ): String {
        val bg = if (index % 2 == 0) "#FFFFFF" else "#F8FBFF"
        val day = if (row.totalSignDay > 0) "${row.totalSignDay}天" else "—"
        return """
            <tr>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;font-size:12px;">
                ${escape(displayGameName(row.game))}
              </td>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;">
                ${renderStatusBadge(statusText(row))}
              </td>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;font-size:12px;">
                ${escape(day)}
              </td>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;font-size:12px;">
                ${renderMysRewardContent(row)}
              </td>
            </tr>
            """.trimIndent()
    }

    private fun renderMysRewardContent(row: TaskResult): String {
        val coinContent =
            if (row.coinBalance >= 0 || row.coinGained >= 0) {
                buildString {
                    if (row.coinBalance >= 0) append("打卡后米游币：${row.coinBalance}")
                    if (row.coinGained >= 0) {
                        if (isNotEmpty()) append("<br>")
                        append("本次获取：${row.coinGained}")
                    }
                }
            } else {
                ""
            }
        if (coinContent.isNotBlank()) {
            return "<div style=\"font-size:11px;line-height:1.5;color:#1E3A8A;\">$coinContent</div>"
        }
        if (row.rewardName.isBlank()) return "—"
        val name = row.rewardName
        val cnt =
            row.rewardCount
                .takeIf { it.isNotBlank() }
                ?.let { "×$it" }
                .orEmpty()
        val icon =
            if (TrustedUrlPolicy.isRewardIconUrl(row.rewardIcon)) {
                """<img src="${escape(
                    row.rewardIcon,
                )}" alt="${escape(name)}" style="width:30px;height:30px;display:block;margin:0 auto 4px auto;border-radius:6px;">"""
            } else {
                "<div style=\"font-size:24px;line-height:1;\">🎁</div>"
            }
        return """
            <div
              style="display:inline-block;min-width:58px;margin:3px 4px;padding:6px 5px;
                     background:#EFF6FF;border:1px solid #BFDBFE;border-radius:8px;
                     text-align:center;vertical-align:top;">
              $icon
              <div style="font-size:11px;margin-top:4px;line-height:1.25;word-break:keep-all;color:#1E3A8A;">${escape(name)}</div>
              <div style="font-size:11px;color:#2563EB;line-height:1.25;">${escape(cnt)}</div>
            </div>
            """.trimIndent()
    }

    private fun renderAccountCloudTable(rows: List<TaskResult>): String {
        if (rows.isEmpty()) return ""
        val body = rows.joinToString("\n") { row -> renderAccountCloudRow(row, rows.indexOf(row)) }
        return """
            <div
              style="margin-top:12px;padding:8px 10px;background:#EFF6FF;
                     border:1px solid #BFDBFE;border-radius:9px;text-align:center;
                     font-size:13px;font-weight:bold;color:#1D4ED8;">
              ☁️ 云游戏结果
            </div>

            <div style="margin-top:8px;background:#FFFFFF;border:1px solid #93C5FD;border-radius:9px;overflow:hidden;">
              <table cellpadding="0" cellspacing="0" style="border-collapse:collapse;width:100%;table-layout:fixed;">
                <thead>
                  <tr style="background:#2563EB;color:#FFFFFF;">
                    <th style="padding:8px 4px;text-align:center;width:22%;font-size:12px;">游戏</th>
                    <th style="padding:8px 4px;text-align:center;width:24%;font-size:12px;">签到结果</th>
                    <th style="padding:8px 4px;text-align:center;width:27%;font-size:12px;">领取后免费时长</th>
                    <th style="padding:8px 4px;text-align:center;width:27%;font-size:12px;">领取时长</th>
                  </tr>
                </thead>
                <tbody>$body</tbody>
              </table>
            </div>
            """.trimIndent()
    }

    private fun renderAccountCloudRow(
        row: TaskResult,
        index: Int,
    ): String {
        val bg = if (index % 2 == 0) "#FFFFFF" else "#F8FBFF"
        val time = parseCloudTime(row.message)
        return """
            <tr>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;font-size:12px;">
                ${escape(displayGameName(row.game))}
              </td>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;">
                ${renderStatusBadge(statusText(row))}
              </td>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;font-size:12px;">
                ${escape(time.after)}
              </td>
              <td style="padding:8px 5px;background:$bg;border-top:1px solid #DBEAFE;text-align:center;font-size:12px;">
                ${escape(time.claimed)}
              </td>
            </tr>
            """.trimIndent()
    }

    private fun renderStatusBadge(text: String): String {
        val style =
            when {
                text.contains("成功") || text.contains("已签") -> "background:#EFF6FF;color:#1D4ED8;border:1px solid #93C5FD;"
                text.contains("失败") -> "background:#FEF2F2;color:#DC2626;border:1px solid #FECACA;"
                text.contains("待确认") -> "background:#FFFBEB;color:#D97706;border:1px solid #FDE68A;"
                text.contains("跳过") -> "background:#F8FAFC;color:#64748B;border:1px solid #CBD5E1;"
                else -> "background:#F8FBFF;color:#1E40AF;border:1px solid #BFDBFE;"
            }
        return """
            <span
              style="display:inline-block;padding:3px 8px;border-radius:999px;
                     font-size:11px;font-weight:bold;white-space:nowrap;$style">
              ${escape(text)}
            </span>
            """.trimIndent()
    }

    private fun statusText(row: TaskResult): String =
        when {
            row.failureCategory == com.questtick.data.FailureCategory.RESULT_UNKNOWN -> "⚠️ 结果待确认"
            row.skipped -> "⏭️ 已跳过"
            row.success && row.alreadySigned -> "✅ 已签"
            row.success -> "✅ 成功"
            else -> "❌ 失败"
        }

    private fun parseCloudTime(message: String): CloudTime {
        val claimed =
            Regex("本次\\+([^·]+)")
                .find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
        val after =
            Regex("当前([^·，,。]+)")
                .find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
        return CloudTime(after = after.ifBlank { "—" }, claimed = claimed.ifBlank { "—" })
    }

    private fun displayGameName(game: String): String = game.removeSuffix("-米游社")

    private fun formatMailDate(
        timestamp: Long,
        language: AppLanguage,
    ): String {
        val locale =
            when (language) {
                AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
                AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
                AppLanguage.JAPANESE -> Locale.JAPANESE
                AppLanguage.KOREAN -> Locale.KOREAN
                else -> Locale.ENGLISH
            }
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(Date(timestamp))
    }

    private fun localizeMailTemplate(
        html: String,
        language: AppLanguage,
    ): String {
        if (language == AppLanguage.SIMPLIFIED_CHINESE || language == AppLanguage.SYSTEM) return html
        val sourceKeys =
            listOf(
                "米游社和云游戏签到结果总结",
                "云游戏签到结果总结",
                "米游社签到结果总结",
                "签到结果总结",
                "米游社和云游戏签到结果",
                "云游戏签到结果",
                "米游社签到结果",
                "云游戏签到明细",
                "米游社签到明细",
                "签到明细",
                "签到结果",
                "一日签到已毕 · 今日收获如下",
                "签到时间：",
                "本次没有可显示的签到任务。",
                "本次签到统计",
                "本邮件由「米游社签到」App 自动发送，不包含任何 Cookie / Token 等敏感信息。",
                "米游社成功：",
                "云游戏成功：",
                "米游社结果",
                "云游戏结果",
                "领取后免费时长",
                "领取时长",
                "累计天数",
                "今日奖励",
                "签到数",
                "未知账号",
                "结果待确认",
                "已跳过",
                "已签",
                "待确认",
                "成功",
                "失败",
                "游戏",
                "打卡后米游币：",
                "本次获取：",
                "原神",
                "崩坏：星穹铁道",
                "崩坏3",
                "崩坏学园2",
                "未定事件簿",
                "绝区零",
                "云原神",
                "云崩铁",
            )
        var result = html
        sourceKeys.sortedByDescending(String::length).forEach { source ->
            result = result.replace(source, localizeText(source, language))
        }
        result =
            result.replace(Regex("(\\d+)天")) { match ->
                localizeText("${match.groupValues[1]} 天", language)
            }
        val languageTag = language.localeTag ?: "en"
        return result.replace("lang=\"zh-CN\"", "lang=\"$languageTag\"")
    }

    private fun isCloud(result: TaskResult): Boolean = result.gameKey == "CloudYS" || result.gameKey == "CloudSR" || result.game.startsWith("云")

    private fun escape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
