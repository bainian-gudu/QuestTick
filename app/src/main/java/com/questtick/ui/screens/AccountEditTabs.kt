@file:Suppress(
    "detekt:CyclomaticComplexMethod",
    "detekt:LongMethod",
    "detekt:LongParameterList",
)

package com.questtick.ui.screens

/*
 * 账号编辑页的两个标签页：基础信息（Cookie / SToken）与云游戏（Token / 扫码）。
 */
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.ui.LocalMysCoinFeatureEnabled
import com.questtick.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.data.Account
import com.questtick.data.Games
import com.questtick.ui.components.AppDialogActionStyle
import com.questtick.ui.components.AppMessageDialog
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.pager.SyncedHorizontalPager
import com.questtick.ui.components.pager.pagerSelectionFraction
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.theme.WarnAmber
import com.questtick.ui.vm.AddAccountViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun BasicTab(
    label: String,
    onLabel: (String) -> Unit,
    cookie: String,
    onCookie: (String) -> Unit,
    stoken: String,
    onStoken: (String) -> Unit,
    cookieWarning: String? = null,
    keepLogin: Boolean = false,
    refreshingCookie: Boolean = false,
    cookieRefreshDone: Boolean = false,
    cookieRefreshFailed: Boolean = false,
    onStartQRLogin: (() -> Unit)? = null,
    onRefreshCookie: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { FieldLabel("账号名称") }
        item {
            IconField(
                value = label,
                onValueChange = onLabel,
                placeholder = "例如：主号、小号",
                leading = Icons.Filled.Person,
                singleLine = true,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { FieldLabel("米游社 Cookie") }
        item {
            IconField(
                value = cookie,
                onValueChange = onCookie,
                placeholder = "粘贴 Cookie，或使用扫码登录",
                leading = Icons.Filled.Security,
                secret = true,
                singleLine = true,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { FieldLabel("SToken（米游币打卡需要）") }
        item {
            IconField(
                value = stoken,
                onValueChange = onStoken,
                placeholder = "粘贴 v2_ 开头的 SToken",
                leading = Icons.Filled.Security,
                secret = true,
                singleLine = true,
            )
        }

        if (cookieWarning != null) {
            item {
                NoticeBox(
                    color = WarnAmber,
                    text = cookieWarning,
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 已保持登录时隐藏扫码登录入口，避免误以为还未登录。
                if (!keepLogin && onStartQRLogin != null) {
                    LoginActionButton(
                        text = "扫码登录",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = onStartQRLogin,
                    )
                }
                // 只有选择“保持登录”且保存了刷新凭证时才显示刷新 Cookie 按钮。
                if (keepLogin && onRefreshCookie != null) {
                    LoginActionButton(
                        text =
                            when {
                                refreshingCookie -> "刷新中…"
                                cookieRefreshDone -> "刷新完成"
                                cookieRefreshFailed -> "刷新失败"
                                else -> "刷新 Cookie"
                            },
                        color = com.questtick.ui.theme.SuccessGreen,
                        modifier = Modifier.weight(1f),
                        loading = refreshingCookie,
                        done = cookieRefreshDone,
                        failed = cookieRefreshFailed,
                        onClick = onRefreshCookie,
                    )
                }
            }
        }

        // 保持登录时显示退出登录入口与确认弹窗。
        if (keepLogin && onLogout != null) {
            item {
                var showLogoutDialog by remember { mutableStateOf(false) }

                LogoutActionButton { showLogoutDialog = true }

                if (showLogoutDialog) {
                    AppMessageDialog(
                        title = "退出登录",
                        message = "退出后会保留当前 Cookie，但无法自动刷新，失效后需要重新扫码登录。",
                        confirmLabel = "确认退出",
                        onConfirm = {
                            showLogoutDialog = false
                            onLogout()
                        },
                        confirmStyle = AppDialogActionStyle.DESTRUCTIVE,
                        dismissLabel = "取消",
                        onDismissAction = { showLogoutDialog = false },
                        onDismissRequest = { showLogoutDialog = false },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun CloudTab(
    genshin: String,
    onGenshin: (String) -> Unit,
    starrail: String,
    onStarrail: (String) -> Unit,
    zzz: String,
    onZzz: (String) -> Unit,
    genshinKeepLogin: Boolean = false,
    starrailKeepLogin: Boolean = false,
    refreshingGenshinToken: Boolean = false,
    genshinTokenRefreshDone: Boolean = false,
    genshinTokenRefreshFailed: Boolean = false,
    refreshingStarrailToken: Boolean = false,
    starrailTokenRefreshDone: Boolean = false,
    starrailTokenRefreshFailed: Boolean = false,
    /** 扫码获取云游戏 Token；gameKey 为 "CloudYS" 或 "CloudSR"。 */
    onStartCloudQR: ((gameKey: String) -> Unit)? = null,
    /** 手动刷新云游戏 Token；gameKey 为 "CloudYS" 或 "CloudSR"。 */
    onRefreshCloudToken: ((gameKey: String) -> Unit)? = null,
    /** 退出云游戏扫码登录；保留当前 Token。 */
    onLogoutCloud: ((gameKey: String) -> Unit)? = null,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { FieldLabel("云原神 Token") }
        item {
            IconField(
                value = genshin,
                onValueChange = onGenshin,
                placeholder = "粘贴 Token，或使用扫码登录",
                leading = Icons.Filled.Cloud,
                secret = true,
                singleLine = true,
            )
        }
        if (onStartCloudQR != null || (genshinKeepLogin && onRefreshCloudToken != null)) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!genshinKeepLogin && onStartCloudQR != null) {
                        LoginActionButton(
                            text = "扫码登录",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        ) { onStartCloudQR("CloudYS") }
                    }
                    if (genshinKeepLogin && onRefreshCloudToken != null) {
                        LoginActionButton(
                            text =
                                when {
                                    refreshingGenshinToken -> "刷新中…"
                                    genshinTokenRefreshDone -> "刷新完成"
                                    genshinTokenRefreshFailed -> "刷新失败"
                                    else -> "刷新 Token"
                                },
                            color = com.questtick.ui.theme.SuccessGreen,
                            modifier = Modifier.weight(1f),
                            loading = refreshingGenshinToken,
                            done = genshinTokenRefreshDone,
                            failed = genshinTokenRefreshFailed,
                        ) { onRefreshCloudToken("CloudYS") }
                    }
                }
            }
        }
        if (genshinKeepLogin && onLogoutCloud != null) {
            item {
                CloudLogoutButton { onLogoutCloud("CloudYS") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { FieldLabel("云崩铁 Token") }
        item {
            IconField(
                value = starrail,
                onValueChange = onStarrail,
                placeholder = "粘贴 Token，或使用扫码登录",
                leading = Icons.Filled.Cloud,
                secret = true,
                singleLine = true,
            )
        }
        if (onStartCloudQR != null || (starrailKeepLogin && onRefreshCloudToken != null)) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!starrailKeepLogin && onStartCloudQR != null) {
                        LoginActionButton(
                            text = "扫码登录",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        ) { onStartCloudQR("CloudSR") }
                    }
                    if (starrailKeepLogin && onRefreshCloudToken != null) {
                        LoginActionButton(
                            text =
                                when {
                                    refreshingStarrailToken -> "刷新中…"
                                    starrailTokenRefreshDone -> "刷新完成"
                                    starrailTokenRefreshFailed -> "刷新失败"
                                    else -> "刷新 Token"
                                },
                            color = com.questtick.ui.theme.SuccessGreen,
                            modifier = Modifier.weight(1f),
                            loading = refreshingStarrailToken,
                            done = starrailTokenRefreshDone,
                            failed = starrailTokenRefreshFailed,
                        ) { onRefreshCloudToken("CloudSR") }
                    }
                }
            }
        }
        if (starrailKeepLogin && onLogoutCloud != null) {
            item {
                CloudLogoutButton { onLogoutCloud("CloudSR") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { FieldLabel("云绝区零 Token") }
        item {
            IconField(
                value = zzz,
                onValueChange = onZzz,
                placeholder = "粘贴 x-rpc-combo_token",
                leading = Icons.Filled.Cloud,
                secret = true,
                singleLine = true,
            )
        }
        item {
            HintBox(
                WarnAmber,
                "云绝区零暂不支持扫码登录：请从云·绝区零客户端抓取 x-rpc-combo_token 后粘贴，" +
                    "Token 过期需重新获取。",
            )
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun CloudLogoutButton(
    text: String = "退出登录",
    onConfirm: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    LogoutActionButton(text = text) { showDialog = true }

    if (showDialog) {
        AppMessageDialog(
            title = "退出登录",
            message = "退出后会保留当前 Token，但无法自动续期，失效后需要重新扫码登录。",
            confirmLabel = "确认退出",
            onConfirm = {
                showDialog = false
                onConfirm()
            },
            confirmStyle = AppDialogActionStyle.DESTRUCTIVE,
            dismissLabel = "取消",
            onDismissAction = { showDialog = false },
            onDismissRequest = { showDialog = false },
        )
    }
}
