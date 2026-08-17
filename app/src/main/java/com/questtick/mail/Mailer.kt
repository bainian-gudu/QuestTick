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
 * 邮件模板按账号分组，区分米游社与云游戏，生成动态标题与状态徽章；配色保持当前 Android
 * 端一致的蓝白风格。正文不写入 Cookie / Token 等敏感值。
 */
object Mailer {
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
            if (settings.mailTo.isBlank() || settings.smtpServer.isBlank() ||
                settings.username.isBlank() || settings.password.isBlank()
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
                            settings.mailTo.split(",", "\n")
                                .map { it.trim() }.filter { it.isNotEmpty() }
                                .map { InternetAddress(it) }.toTypedArray()
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
                record.total == 0 -> mailText(language, "No tasks", "タスクなし", "작업 없음", "无任务")
                record.resultUnknown == record.total ->
                    mailText(language, "Result unconfirmed", "結果要確認", "결과 확인 필요", "结果待确认")
                record.failed == 0 && record.resultUnknown == 0 ->
                    mailText(language, "All successful", "すべて成功", "모두 성공", "全部成功")
                record.failed == 0 ->
                    mailText(
                        language,
                        "${record.resultUnknown} unconfirmed",
                        "${record.resultUnknown} 件要確認",
                        "${record.resultUnknown}개 확인 필요",
                        "${record.resultUnknown}项待确认",
                    )
                record.failed >= record.total ->
                    mailText(language, "All failed", "すべて失敗", "모두 실패", "全部失败")
                else ->
                    mailText(
                        language,
                        "${record.failed} failed, ${record.resultUnknown} unconfirmed",
                        "${record.failed} 件失敗、${record.resultUnknown} 件要確認",
                        "${record.failed}개 실패, ${record.resultUnknown}개 확인 필요",
                        "${record.failed}项失败 ${record.resultUnknown}项待确认",
                    )
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
                  style="padding:14px;background:#EFF6FF;border:1px solid #BFDBFE;
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
<body style="margin:0;padding:16px;background:#F8FBFF;font-family:Arial,'Microsoft YaHei','PingFang SC',sans-serif;color:#0F172A;">
  <div
    style="max-width:720px;margin:0 auto;background:#FFFFFF;
           border:1px solid #BFDBFE;border-radius:16px;padding:18px;
           box-shadow:0 4px 18px rgba(37,99,235,.10);">

    <div
      style="background:#2563EB;background:linear-gradient(135deg,#2563EB,#60A5FA);
             border-radius:13px;padding:16px 12px;text-align:center;color:#FFFFFF;">
      <div style="font-size:19px;font-weight:bold;line-height:1.4;letter-spacing:.5px;">
        ${escape(titleInfo.mainTitle)}
      </div>
      <div style="font-size:12px;margin-top:6px;color:#DBEAFE;">
        一日签到已毕 · 今日收获如下
      </div>
    </div>

    <div
      style="margin-top:14px;padding:10px 12px;background:#EFF6FF;
             border:1px solid #BFDBFE;border-radius:10px;text-align:center;
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
                  ${statCard("签到数", record.total.toString(), "#FFFFFF", "#BFDBFE", "#1E3A8A")}
                  ${statCard("成功", success.toString(), "#EFF6FF", "#93C5FD", "#2563EB")}
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
                }
                .toSortedMap()
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
        val cnt = row.rewardCount.takeIf { it.isNotBlank() }?.let { "×$it" }.orEmpty()
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
        val claimed = Regex("本次\\+([^·]+)").find(message)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val after = Regex("当前([^·，,。]+)").find(message)?.groupValues?.getOrNull(1)?.trim().orEmpty()
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
        val phrases =
            listOf(
                MailPhrase("米游社和云游戏签到结果总结", "Miyoushe and cloud gaming sign-in summary", "米游社・クラウドゲーム受取結果", "미요우서 및 클라우드 게임 출석 결과"),
                MailPhrase("云游戏签到结果总结", "Cloud gaming sign-in summary", "クラウドゲーム受取結果", "클라우드 게임 출석 결과"),
                MailPhrase("米游社签到结果总结", "Miyoushe sign-in summary", "米游社受取結果", "미요우서 출석 결과"),
                MailPhrase("签到结果总结", "Sign-in summary", "受取結果", "출석 결과"),
                MailPhrase("米游社和云游戏签到结果", "Miyoushe and cloud gaming sign-in results", "米游社・クラウドゲーム受取結果", "미요우서 및 클라우드 게임 출석 결과"),
                MailPhrase("云游戏签到结果", "Cloud gaming sign-in results", "クラウドゲーム受取結果", "클라우드 게임 출석 결과"),
                MailPhrase("米游社签到结果", "Miyoushe sign-in results", "米游社受取結果", "미요우서 출석 결과"),
                MailPhrase("云游戏签到明细", "Cloud gaming details", "クラウドゲーム詳細", "클라우드 게임 상세"),
                MailPhrase("米游社签到明细", "Miyoushe details", "米游社詳細", "미요우서 상세"),
                MailPhrase("签到明细", "Sign-in details", "受取詳細", "출석 상세"),
                MailPhrase("签到结果", "Sign-in result", "受取結果", "출석 결과"),
                MailPhrase("一日签到已毕 · 今日收获如下", "Today's sign-in is complete · Here are your rewards", "本日の受取が完了しました・獲得内容", "오늘 출석 완료 · 획득 내역"),
                MailPhrase("签到时间：", "Sign-in time:", "受取時刻：", "출석 시간:"),
                MailPhrase("本次没有可显示的签到任务。", "There are no sign-in tasks to display.", "表示できる受取タスクはありません。", "표시할 출석 작업이 없습니다."),
                MailPhrase("本次签到统计", "Sign-in statistics", "受取統計", "출석 통계"),
                MailPhrase("本邮件由「米游社签到」App 自动发送，不包含任何 Cookie / Token 等敏感信息。", "This email was sent automatically by the Miyoushe Sign-in app and contains no sensitive Cookie or Token data.", "このメールは「米游社受取」アプリから自動送信され、CookieやTokenなどの機密情報は含まれません。", "이 이메일은 미요우서 출석 앱에서 자동 전송했으며 Cookie 또는 Token과 같은 민감한 정보를 포함하지 않습니다."),
                MailPhrase("米游社成功：", "Miyoushe successful: ", "米游社成功：", "미요우서 성공: "),
                MailPhrase("云游戏成功：", "Cloud gaming successful: ", "クラウドゲーム成功：", "클라우드 게임 성공: "),
                MailPhrase("米游社结果", "Miyoushe results", "米游社結果", "미요우서 결과"),
                MailPhrase("云游戏结果", "Cloud gaming results", "クラウドゲーム結果", "클라우드 게임 결과"),
                MailPhrase("领取后免费时长", "Free time after claim", "受取後の無料時間", "수령 후 무료 시간"),
                MailPhrase("领取时长", "Claimed time", "受取時間", "수령 시간"),
                MailPhrase("累计天数", "Total days", "累計日数", "누적 일수"),
                MailPhrase("今日奖励", "Today's reward", "本日の報酬", "오늘의 보상"),
                MailPhrase("签到数", "Tasks", "タスク数", "작업 수"),
                MailPhrase("未知账号", "Unknown account", "不明なアカウント", "알 수 없는 계정"),
                MailPhrase("结果待确认", "Result unconfirmed", "結果要確認", "결과 확인 필요"),
                MailPhrase("已跳过", "Skipped", "スキップ済み", "건너뜀"),
                MailPhrase("已签", "Already claimed", "受取済み", "이미 수령"),
                MailPhrase("待确认", "Unconfirmed", "要確認", "확인 필요"),
                MailPhrase("成功", "Success", "成功", "성공"),
                MailPhrase("失败", "Failed", "失敗", "실패"),
                MailPhrase("游戏", "Game", "ゲーム", "게임"),
                MailPhrase("原神", "Genshin Impact", "原神", "원신"),
                MailPhrase("崩坏：星穹铁道", "Honkai: Star Rail", "崩壊：スターレイル", "붕괴: 스타레일"),
                MailPhrase("崩坏3", "Honkai Impact 3rd", "崩壊3rd", "붕괴3rd"),
                MailPhrase("崩坏学园2", "Guns GirlZ", "崩壊学園", "붕괴학원 2"),
                MailPhrase("未定事件簿", "Tears of Themis", "未定事件簿", "미해결사건부"),
                MailPhrase("绝区零", "Zenless Zone Zero", "ゼンレスゾーンゼロ", "젠레스 존 제로"),
                MailPhrase("云原神", "Cloud Genshin Impact", "クラウド原神", "클라우드 원신"),
                MailPhrase("云崩铁", "Cloud Honkai: Star Rail", "クラウド崩壊：スターレイル", "클라우드 붕괴: 스타레일"),
            )
        var result = html
        phrases.sortedByDescending { it.source.length }.forEach { phrase ->
            result = result.replace(phrase.source, phrase.value(language))
        }
        result = result.replace(Regex("(\\d+)天")) { match ->
            val days = match.groupValues[1]
            mailText(language, "$days days", "$days 日", "${days}일", "$days 天")
        }
        val languageTag = language.localeTag ?: "en"
        return result.replace("lang=\"zh-CN\"", "lang=\"$languageTag\"")
    }

    private data class MailPhrase(
        val source: String,
        val en: String,
        val ja: String,
        val ko: String,
        val zhHant: String = source,
    ) {
        fun value(language: AppLanguage): String =
            when (language) {
                AppLanguage.TRADITIONAL_CHINESE -> localizeText(zhHant, AppLanguage.TRADITIONAL_CHINESE)
                AppLanguage.JAPANESE -> ja
                AppLanguage.KOREAN -> ko
                else -> en
            }
    }

    private fun mailText(
        language: AppLanguage,
        en: String,
        ja: String,
        ko: String,
        zhHans: String,
    ): String =
        when (language) {
            AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM -> zhHans
            AppLanguage.TRADITIONAL_CHINESE -> localizeText(zhHans, AppLanguage.TRADITIONAL_CHINESE)
            AppLanguage.JAPANESE -> ja
            AppLanguage.KOREAN -> ko
            else -> en
        }

    private fun isCloud(result: TaskResult): Boolean =
        result.gameKey == "CloudYS" || result.gameKey == "CloudSR" || result.game.startsWith("云")

    private fun escape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
