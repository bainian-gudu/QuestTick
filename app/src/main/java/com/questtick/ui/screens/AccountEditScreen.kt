package com.questtick.ui.screens

import com.questtick.i18n.localizedText
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
import com.questtick.i18n.Text
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

private enum class EditTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Basic("米游社", Icons.Filled.Cookie),
    Cloud("云游戏", Icons.Filled.Cloud),
    Games("游戏选择", Icons.Filled.Security),
}

@Composable
@Suppress(
    "detekt:LongMethod",
    "detekt:CyclomaticComplexMethod",
    "detekt:FunctionNaming",
    "detekt:LongParameterList",
)
fun AccountEditScreen(
    initial: Account,
    isNew: Boolean,
    editorSessionKey: Long = 0L,
    discardBaseline: Account = initial,
    onCancel: () -> Unit,
    onSave: (Account) -> Unit,
    /** 打开米游社扫码登录界面；回调参数是当前表单草稿，避免扫码结果覆盖未保存改动。 */
    onStartQRLogin: ((Account) -> Unit)? = null,
    /** 手动刷新米游社 Cookie；回调参数是当前表单草稿，完成回调用于展示完成态并收起界面刷新态。 */
    onRefreshCookie: ((Account, (Boolean) -> Unit) -> Unit)? = null,
    /** 退出米游社扫码登录；回调参数是当前表单草稿，保留当前 Cookie。 */
    onLogout: ((Account) -> Unit)? = null,
    /** 打开云游戏扫码登录；gameKey 为 "CloudYS" 或 "CloudSR"，Account 为当前表单草稿。 */
    onStartCloudQR: ((String, Account) -> Unit)? = null,
    /** 手动刷新云游戏 Token；gameKey 为 "CloudYS" 或 "CloudSR"，Account 为当前表单草稿，完成回调用于展示完成态并收起界面刷新态。 */
    onRefreshCloudToken: ((String, Account, (Boolean) -> Unit) -> Unit)? = null,
    /** 退出云游戏扫码登录；回调参数是当前表单草稿，保留当前 Token。 */
    onLogoutCloud: ((String, Account) -> Unit)? = null,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    DisposableEffect(editorSessionKey) {
        onDispose { viewModel.resetEditorState(editorSessionKey) }
    }

    LaunchedEffect(editorSessionKey, initial) {
        viewModel.syncInitial(initial, editorSessionKey)
    }

    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshScope = rememberCoroutineScope()

    // 只覆盖表单字段，扫码写入 initial 的刷新凭证继续随草稿传递到最终保存。
    fun currentDraftAccount(): Account =
        initial.copy(
            label = ui.label.trim().ifEmpty { "未命名账号" },
            mysCookie = ui.cookie.trim(),
            genshinToken = ui.genshin.trim(),
            starrailToken = ui.starrail.trim(),
            selectedGames = ui.selectedGames,
            mysCoinEnabled = ui.mysCoinEnabled,
        )

    var visualTabPosition by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(ui.tab.toFloat()) }
    var showDiscardDialog by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var refreshingCookie by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var cookieRefreshDone by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var cookieRefreshFailed by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var refreshingGenshinToken by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var genshinTokenRefreshDone by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var genshinTokenRefreshFailed by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var refreshingStarrailToken by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var starrailTokenRefreshDone by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var starrailTokenRefreshFailed by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }
    var showMysCoinCredentialWarning by rememberSaveable(editorSessionKey, initial.id) { mutableStateOf(false) }

    fun requestCancel() {
        if (ui.hasFormChangesFrom(discardBaseline)) {
            showDiscardDialog = true
        } else {
            onCancel()
        }
    }

    BackHandler(enabled = true) {
        when {
            ui.saveError != null -> viewModel.dismissSaveError()
            showDiscardDialog -> showDiscardDialog = false
            else -> requestCancel()
        }
    }

    // 切换编辑对象时立即显示真实表单，避免弹出后出现加载占位动画。
    LaunchedEffect(editorSessionKey, initial.id) {
        visualTabPosition = 0f
        viewModel.setHydrated()
    }

    LaunchedEffect(cookieRefreshDone, cookieRefreshFailed) {
        if (cookieRefreshDone || cookieRefreshFailed) {
            delay(1200)
            cookieRefreshDone = false
            cookieRefreshFailed = false
        }
    }

    LaunchedEffect(genshinTokenRefreshDone, genshinTokenRefreshFailed) {
        if (genshinTokenRefreshDone || genshinTokenRefreshFailed) {
            delay(1200)
            genshinTokenRefreshDone = false
            genshinTokenRefreshFailed = false
        }
    }

    LaunchedEffect(starrailTokenRefreshDone, starrailTokenRefreshFailed) {
        if (starrailTokenRefreshDone || starrailTokenRefreshFailed) {
            delay(1200)
            starrailTokenRefreshDone = false
            starrailTokenRefreshFailed = false
        }
    }

    fun save() {
        viewModel.saveAccount(onSave)
    }

    ui.saveError?.let { error ->
        AppMessageDialog(
            title = "无法保存",
            message = error,
            confirmLabel = "知道了",
            onConfirm = viewModel::dismissSaveError,
            onDismissRequest = viewModel::dismissSaveError,
        )
    }

    if (showDiscardDialog) {
        AppMessageDialog(
            title = "放弃修改？",
            message = "退出编辑将丢失尚未保存的账号配置。",
            confirmLabel = "放弃",
            onConfirm = {
                showDiscardDialog = false
                onCancel()
            },
            confirmStyle = AppDialogActionStyle.DESTRUCTIVE,
            dismissLabel = "继续编辑",
            onDismissAction = { showDiscardDialog = false },
            onDismissRequest = { showDiscardDialog = false },
        )
    }

    if (showMysCoinCredentialWarning) {
        AppMessageDialog(
            title = "米游币打卡凭证不完整",
            message = "当前 Cookie 未发现米游币打卡所需的 SToken 及对应身份字段（mid 或 stuid）。开启后可能返回“登录失效”，建议先扫码登录或填写完整 Cookie。",
            confirmLabel = "仍然开启",
            onConfirm = {
                showMysCoinCredentialWarning = false
                viewModel.setMysCoinEnabled(true)
            },
            dismissLabel = "取消",
            onDismissAction = { showMysCoinCredentialWarning = false },
            onDismissRequest = { showMysCoinCredentialWarning = false },
        )
    }

    GalaxyBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(
                        WindowInsets.displayCutout.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                        ),
                    ),
                ),
        ) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickableNoRipple { requestCancel() }
                        .align(Alignment.CenterStart),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, contentDescription = localizedText("关闭"), tint = MaterialTheme.colorScheme.onBackground) }
                Text(
                    if (isNew) "添加账号" else "编辑账号",
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Row(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickableNoRipple { save() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("保存", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            EditTabRow(
                visualPosition = visualTabPosition,
                onSelect = viewModel::setTab,
            )
            Spacer(Modifier.height(6.dp))

            SyncedHorizontalPager(
                selectedPage = ui.tab,
                pageCount = EditTab.entries.size,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                onVisualPagePositionChange = { visualTabPosition = it },
                onPageSettled = viewModel::setTab,
            ) { page, _ ->
                when (EditTab.entries[page]) {
                    EditTab.Basic ->
                        BasicTab(
                            ui.label,
                            viewModel::updateLabel,
                            ui.cookie,
                            viewModel::updateCookie,
                            cookieWarning = ui.cookieWarning,
                            keepLogin = initial.hasMysKeepLogin,
                            refreshingCookie = refreshingCookie,
                            cookieRefreshDone = cookieRefreshDone,
                            cookieRefreshFailed = cookieRefreshFailed,
                            onStartQRLogin = onStartQRLogin?.let { action -> { action(currentDraftAccount()) } },
                            onRefreshCookie =
                                onRefreshCookie?.let { action ->
                                    {
                                        if (!refreshingCookie && !cookieRefreshDone && !cookieRefreshFailed) {
                                            cookieRefreshDone = false
                                            cookieRefreshFailed = false
                                            refreshingCookie = true
                                            action(currentDraftAccount()) { success ->
                                                refreshScope.launch {
                                                    delay(300)
                                                    refreshingCookie = false
                                                    cookieRefreshDone = success
                                                    cookieRefreshFailed = !success
                                                }
                                            }
                                        }
                                    }
                                },
                            onLogout = onLogout?.let { action -> { action(currentDraftAccount()) } },
                        )
                    EditTab.Cloud ->
                        CloudTab(
                            ui.genshin,
                            viewModel::updateGenshinToken,
                            ui.starrail,
                            viewModel::updateStarrailToken,
                            genshinKeepLogin = initial.hasGenshinCloudKeepLogin,
                            starrailKeepLogin = initial.hasStarrailCloudKeepLogin,
                            refreshingGenshinToken = refreshingGenshinToken,
                            genshinTokenRefreshDone = genshinTokenRefreshDone,
                            genshinTokenRefreshFailed = genshinTokenRefreshFailed,
                            refreshingStarrailToken = refreshingStarrailToken,
                            starrailTokenRefreshDone = starrailTokenRefreshDone,
                            starrailTokenRefreshFailed = starrailTokenRefreshFailed,
                            onStartCloudQR =
                                onStartCloudQR?.let { action ->
                                    { gameKey -> action(gameKey, currentDraftAccount()) }
                                },
                            onRefreshCloudToken =
                                onRefreshCloudToken?.let { action ->
                                    { gameKey ->
                                        when (gameKey) {
                                            "CloudYS" -> {
                                                if (!refreshingGenshinToken &&
                                                    !genshinTokenRefreshDone &&
                                                    !genshinTokenRefreshFailed
                                                ) {
                                                    genshinTokenRefreshDone = false
                                                    genshinTokenRefreshFailed = false
                                                    refreshingGenshinToken = true
                                                    action(gameKey, currentDraftAccount()) { success ->
                                                        refreshScope.launch {
                                                            delay(300)
                                                            refreshingGenshinToken = false
                                                            genshinTokenRefreshDone = success
                                                            genshinTokenRefreshFailed = !success
                                                        }
                                                    }
                                                }
                                            }
                                            "CloudSR" -> {
                                                if (!refreshingStarrailToken &&
                                                    !starrailTokenRefreshDone &&
                                                    !starrailTokenRefreshFailed
                                                ) {
                                                    starrailTokenRefreshDone = false
                                                    starrailTokenRefreshFailed = false
                                                    refreshingStarrailToken = true
                                                    action(gameKey, currentDraftAccount()) { success ->
                                                        refreshScope.launch {
                                                            delay(300)
                                                            refreshingStarrailToken = false
                                                            starrailTokenRefreshDone = success
                                                            starrailTokenRefreshFailed = !success
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            onLogoutCloud =
                                onLogoutCloud?.let { action ->
                                    { gameKey -> action(gameKey, currentDraftAccount()) }
                                },
                        )
                    EditTab.Games ->
                        GamesTab(
                            selected = ui.selectedGames,
                            onToggle = viewModel::toggleGame,
                            mysCoinEnabled = ui.mysCoinEnabled,
                            onMysCoinEnabledChange = { enabled ->
                                if (enabled && !hasMysCoinCredential(ui.cookie, initial.stoken, initial.stmid, initial.mysUid)) {
                                    showMysCoinCredentialWarning = true
                                } else {
                                    viewModel.setMysCoinEnabled(enabled)
                                }
                            },
                        )
                }
            }
        }
    }
}

/** 编辑浮层的 Tab 行；每个 Tab 内置与底部导航一致的指示条动画。 */
@Composable
private fun EditTabRow(
    visualPosition: Float,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        EditTab.entries.forEachIndexed { index, t ->
            val selectionFraction = pagerSelectionFraction(index, visualPosition)
            EditTabButton(
                tab = t,
                selected = selectionFraction > 0.5f,
                selectionFraction = selectionFraction,
                modifier = Modifier.weight(1f),
            ) { onSelect(index) }
        }
    }
}

/** 单个编辑 Tab 按钮，内部包含选中指示条。 */
@Composable
private fun EditTabButton(
    tab: EditTab,
    selected: Boolean,
    selectionFraction: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint = lerp(TextSecondary, MaterialTheme.colorScheme.primary, selectionFraction)
    val indicatorWidth = 72.dp * selectionFraction
    Column(
        modifier = modifier.clickableNoRipple(onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            tab.label,
            color = tint,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .size(width = indicatorWidth, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun LoginActionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    done: Boolean = false,
    failed: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val stateColor = if (failed) com.questtick.ui.theme.DangerRed else color
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, stateColor),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = stateColor,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(text, color = stateColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LogoutActionButton(
    text: String = "退出登录",
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    LoginActionButton(
        text = text,
        color = com.questtick.ui.theme.DangerRed,
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    )
}

@Composable
private fun BasicTab(
    label: String,
    onLabel: (String) -> Unit,
    cookie: String,
    onCookie: (String) -> Unit,
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

        // Cookie 校验提示，展示 ViewModel 异步校验结果。
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
private fun CloudTab(
    genshin: String,
    onGenshin: (String) -> Unit,
    starrail: String,
    onStarrail: (String) -> Unit,
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

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
internal fun GroupLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary, modifier = Modifier.padding(start = 2.dp))
}

@Composable
private fun HintText(text: String) {
    Text(text, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
}

@Composable
private fun NoticeBox(
    color: Color,
    text: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(text, color = color, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun IconField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leading: androidx.compose.ui.graphics.vector.ImageVector,
    secret: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    val transform = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None

    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.7f)) },
        leadingIcon = { Icon(leading, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp)) },
        trailingIcon =
            if (secret) {
                {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = localizedText("显示/隐藏"),
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp).clickableNoRipple { reveal = !reveal },
                    )
                }
            } else {
                null
            },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 6,
        visualTransformation = transform,
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors =
            androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}
