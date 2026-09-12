package com.questtick

// 账号编辑、登录和账号相关页面的导航承载逻辑。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.questtick.data.Account
import com.questtick.ui.screens.AccountEditScreen
import com.questtick.ui.screens.CloudQRLoginResult
import com.questtick.ui.screens.CloudQRLoginScreen
import com.questtick.ui.screens.QRLoginResult
import com.questtick.ui.screens.QRLoginScreen
import com.questtick.ui.theme.AppMotion
import com.questtick.ui.vm.MainViewModel
import kotlinx.coroutines.delay
import java.util.UUID

@Composable
internal fun AccountEditorSheetHost(
    editingAccount: Account?,
    editingIsNew: Boolean,
    editingSessionKey: Long,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onEditingAccountChange: (Account?) -> Unit,
    onOpenQrLogin: (Account) -> Unit,
    onOpenCloudQr: (String, Account) -> Unit,
) {
    var displayedAccount by remember { mutableStateOf<Account?>(null) }
    var displayedIsNew by remember { mutableStateOf(editingIsNew) }
    var displayedSessionKey by remember { mutableStateOf(editingSessionKey) }
    val editorAccounts = remember { mutableStateMapOf<Long, Account>() }
    // 刷新或退出登录会立即持久化；同步更新基准可避免退出编辑时误报未保存修改。
    val editorBaselines = remember { mutableStateMapOf<Long, Account>() }

    LaunchedEffect(editingAccount, editingIsNew, editingSessionKey) {
        editingAccount?.let {
            editorAccounts[editingSessionKey] = it
            editorBaselines.putIfAbsent(editingSessionKey, it)
            editorAccounts.keys.filter { key -> key < editingSessionKey - 1 }.forEach(editorAccounts::remove)
            editorBaselines.keys.filter { key -> key < editingSessionKey - 1 }.forEach(editorBaselines::remove)
            displayedAccount = it
            displayedIsNew = editingIsNew
            displayedSessionKey = editingSessionKey
        }
    }

    val account = editingAccount ?: displayedAccount ?: return
    val activeIsNew = if (editingAccount != null) editingIsNew else displayedIsNew
    val activeSessionKey = if (editingAccount != null) editingSessionKey else displayedSessionKey
    val closeEditorSheet: () -> Unit = onDismiss
    val sheetVisibleState =
        remember {
            MutableTransitionState(false)
        }
    sheetVisibleState.targetState = editingAccount != null

    AnimatedVisibility(
        visibleState = sheetVisibleState,
        enter = AppMotion.screenEnter(),
        exit = AppMotion.screenExit(),
    ) {
        AccountEditScreen(
            initial = editorAccounts[activeSessionKey] ?: account,
            isNew = activeIsNew,
            editorSessionKey = activeSessionKey,
            discardBaseline = editorBaselines[activeSessionKey] ?: account,
            onCancel = closeEditorSheet,
            onSave = {
                vm.saveAccount(it)
                closeEditorSheet()
            },
            onStartQRLogin = { draft -> onOpenQrLogin(draft) },
            onRefreshCookie = { draft, onFinished ->
                vm.refreshCookie(
                    draft,
                    onUpdated = { updated ->
                        editorBaselines[activeSessionKey] = updated
                        onEditingAccountChange(updated)
                    },
                    onFinished = onFinished,
                )
            },
            onLogout = { draft ->
                vm.logoutAccount(draft, false) { updated ->
                    editorBaselines[activeSessionKey] = updated
                    onEditingAccountChange(updated)
                }
            },
            onStartCloudQR = { gameKey, draft -> onOpenCloudQr(gameKey, draft) },
            onRefreshCloudToken = { gameKey, draft, onFinished ->
                vm.refreshCloudToken(
                    draft,
                    gameKey,
                    onUpdated = { updated ->
                        editorBaselines[activeSessionKey] = updated
                        onEditingAccountChange(updated)
                    },
                    onFinished = onFinished,
                )
            },
            onLogoutCloud = { gameKey, draft ->
                vm.logoutCloudAccount(draft, gameKey) { updated ->
                    editorBaselines[activeSessionKey] = updated
                    onEditingAccountChange(updated)
                }
            },
        )
    }
}

@Composable
internal fun MysQrLoginOverlayHost(
    visible: Boolean,
    targetAccount: Account?,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onAccountUpdated: (Account) -> Unit,
) {
    var displayedTargetAccount by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(visible, targetAccount) {
        if (visible && targetAccount != null) {
            displayedTargetAccount = targetAccount
        } else if (!visible) {
            delay(AppMotion.SCREEN_DURATION_MILLIS.toLong())
            displayedTargetAccount = null
        }
    }
    val activeTargetAccount = targetAccount ?: displayedTargetAccount ?: return
    val deviceId =
        remember {
            vm
                .effectiveMysDeviceId(forceCreate = false)
                .ifBlank { UUID.randomUUID().toString() }
        }
    QRLoginScreen(
        deviceId = deviceId,
        visible = visible,
        onResult = { result: QRLoginResult ->
            onDismiss()
            vm.persistGeneratedMysDeviceId(deviceId)
            // 扫码凭证只回写当前编辑草稿，账号仓库仍由编辑页“保存”统一提交。
            onAccountUpdated(
                vm.buildMysQrLoginDraft(
                    account = activeTargetAccount,
                    cookie = result.cookie,
                    uid = result.uid,
                    stoken = result.stoken,
                    stmid = result.stmid,
                    ltoken = result.ltoken,
                    keepLogin = result.keepLogin,
                    nickname = result.nickname,
                ),
            )
        },
        onCancel = onDismiss,
        onError = { detail -> vm.recordApplicationError("米游社二维码登录", detail) },
    )
}

@Composable
internal fun CloudQrLoginOverlayHost(
    visible: Boolean,
    gameKey: String,
    targetAccount: Account?,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onAccountUpdated: (Account) -> Unit,
) {
    var displayedTargetAccount by remember { mutableStateOf<Account?>(null) }
    var displayedGameKey by remember { mutableStateOf(gameKey) }
    LaunchedEffect(visible, gameKey, targetAccount) {
        if (visible && targetAccount != null) {
            displayedTargetAccount = targetAccount
            displayedGameKey = gameKey
        } else if (!visible) {
            delay(AppMotion.SCREEN_DURATION_MILLIS.toLong())
            displayedTargetAccount = null
        }
    }
    val activeTargetAccount = targetAccount ?: displayedTargetAccount ?: return
    val activeGameKey = if (visible) gameKey else displayedGameKey
    val cloudDeviceId =
        remember {
            vm
                .effectiveCloudDeviceId(forceCreate = false)
                .ifBlank { UUID.randomUUID().toString() }
        }
    CloudQRLoginScreen(
        gameKey = activeGameKey,
        deviceId = cloudDeviceId,
        visible = visible,
        onResult = { result: CloudQRLoginResult ->
            onDismiss()
            vm.persistGeneratedCloudDeviceId(cloudDeviceId)
            // 扫码凭证只回写当前编辑草稿，账号仓库仍由编辑页“保存”统一提交。
            onAccountUpdated(
                vm.buildCloudQrLoginDraft(
                    account = activeTargetAccount,
                    gameKey = activeGameKey,
                    comboToken = result.comboToken,
                    webCookie = result.cookieHeader,
                    keepLogin = result.keepLogin,
                ),
            )
        },
        onCancel = onDismiss,
        onError = { detail -> vm.recordApplicationError("云游戏二维码登录", detail) },
    )
}
