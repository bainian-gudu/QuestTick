package com.questtick.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.MailSettings
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.PanelCard
import com.questtick.ui.theme.AppMotion

/** 邮件推送设置页面。 */
@Composable
internal fun MailDetailPage(
    mail: MailSettings,
    onSave: (MailSettings) -> Unit,
    onTest: (MailSettings) -> Unit,
    onBack: () -> Unit,
) {
    var enabled by remember { mutableStateOf(mail.enabled) }
    var server by remember { mutableStateOf(mail.smtpServer) }
    var port by remember { mutableStateOf(mail.smtpPort.toString()) }
    var username by remember { mutableStateOf(mail.username) }
    var password by remember { mutableStateOf(mail.password) }
    var mailTo by remember { mutableStateOf(mail.mailTo) }

    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("邮件推送", onBack)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SettingsSwitchCard(
                        title = "启用邮件推送",
                        subtitle = "使用 SMTP 发送签到结果",
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }
                item {
                    AnimatedVisibility(visible = enabled, enter = AppMotion.expandEnter(), exit = AppMotion.expandExit()) {
                        PanelCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SettingsFieldLabel("SMTP 服务器")
                                FieldBox(server, { server = it }, "smtp.qq.com", Modifier.fillMaxWidth())
                                SettingsFieldLabel("SMTP 端口")
                                FieldBox(port, { port = it.filter(Char::isDigit).take(5) }, "465", Modifier.fillMaxWidth(), number = true)
                                SettingsFieldLabel("发件邮箱")
                                FieldBox(username, { username = it }, "your@email.com", Modifier.fillMaxWidth())
                                SettingsFieldLabel("授权码 / 密码")
                                SecretFieldBox(password, { password = it }, "邮箱授权码")
                                SettingsFieldLabel("收件邮箱")
                                FieldBox(mailTo, { mailTo = it }, "to@email.com", Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (enabled) {
                    item {
                        SettingsCard {
                            SettingsSectionTitle("快速选择 SMTP 服务商")
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "QQ 邮箱" to ("smtp.qq.com" to 465),
                                    "163 邮箱" to ("smtp.163.com" to 465),
                                    "Gmail" to ("smtp.gmail.com" to 465),
                                    "Outlook" to ("smtp.office365.com" to 587),
                                ).forEach { (label, preset) ->
                                    OutlinedButton(
                                        onClick = { server = preset.first; port = preset.second.toString() },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) { Text(label, fontSize = 11.sp, maxLines = 1) }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) {
                                PrimaryButton("保存") {
                                    onSave(mail.copy(enabled = true, smtpServer = server.trim(), smtpPort = port.toIntOrNull() ?: 465, username = username.trim(), password = password, mailTo = mailTo.trim()))
                                    onBack()
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                PrimaryButton("发送测试") {
                                    onTest(mail.copy(enabled = true, smtpServer = server.trim(), smtpPort = port.toIntOrNull() ?: 465, username = username.trim(), password = password, mailTo = mailTo.trim()))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
