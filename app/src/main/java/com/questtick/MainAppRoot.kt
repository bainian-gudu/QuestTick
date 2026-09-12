package com.questtick

// 应用根 Composable：组装全局状态、主题、页面和底部导航。

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.data.Account
import com.questtick.net.HttpTransport
import com.questtick.ui.LocalHttpTransport
import com.questtick.ui.LocalAppErrorReporter
import com.questtick.i18n.LocalAppLanguageSetting
import com.questtick.ui.theme.LocalAppUiTheme
import com.questtick.ui.theme.QuestTickTheme
import com.questtick.ui.vm.AccountsViewModel
import com.questtick.ui.vm.HomeViewModel
import com.questtick.ui.vm.LogsViewModel
import com.questtick.ui.vm.MainViewModel
import com.questtick.ui.vm.RecordsViewModel
import com.questtick.ui.vm.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
internal fun ThemedAppRoot(
    requestedTab: MutableStateFlow<Int>,
    httpTransport: HttpTransport,
    onCriticalUiReady: () -> Unit,
    onFullyDrawn: () -> Unit,
    vm: MainViewModel = hiltViewModel(),
) {
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalAppLanguageSetting provides appSettings.appLanguage,
        LocalHttpTransport provides httpTransport,
        LocalAppErrorReporter provides { feature, detail -> vm.recordApplicationError(feature, detail) },
    ) {
        QuestTickTheme(
            themeMode = appSettings.appThemeMode,
            oledPureBlackEnabled = appSettings.oledPureBlackEnabled,
            dynamicColorEnabled = appSettings.dynamicColorEnabled,
            customThemeColor = appSettings.customThemeColor,
            customThemeSecondaryColor = appSettings.customThemeSecondaryColor,
            customThemeTertiaryColor = appSettings.customThemeTertiaryColor,
        ) {
            AppRoot(
                requestedTab = requestedTab,
                onCriticalUiReady = onCriticalUiReady,
                onFullyDrawn = onFullyDrawn,
                vm = vm,
            )
        }
    }
}

@Composable
private fun AppRoot(
    requestedTab: MutableStateFlow<Int>,
    onCriticalUiReady: () -> Unit,
    onFullyDrawn: () -> Unit,
    vm: MainViewModel = hiltViewModel(),
) {
    val ready by vm.ready.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()

    val homeVm: HomeViewModel = hiltViewModel()
    val accountsVm: AccountsViewModel = hiltViewModel()
    val recordsVm: RecordsViewModel = hiltViewModel()
    val logsVm: LogsViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val updatePrompt by settingsVm.updatePrompt.collectAsStateWithLifecycle()
    val updateDownloadProgress by settingsVm.updateDownloadProgress.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var visualTabPosition by remember { mutableFloatStateOf(tab.toFloat()) }
    var warmedTabs by remember { mutableStateOf(setOf(tab)) }
    var settingsVisibleCount by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var editingSessionKey by remember { mutableStateOf(0L) }

    var showQRLogin by remember { mutableStateOf(false) }
    var qrLoginForAccount by remember { mutableStateOf<Account?>(null) }

    var showCloudQR by remember { mutableStateOf(false) }
    var cloudQRGameKey by remember { mutableStateOf("CloudYS") }
    var cloudQRForAccount by remember { mutableStateOf<Account?>(null) }

    LaunchedEffect(ready) {
        if (ready) {
            onCriticalUiReady()
            withFrameNanos { }
            onFullyDrawn()
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (Tab.Accounts.ordinal !in warmedTabs) {
            warmedTabs = warmedTabs + Tab.Accounts.ordinal
        }
        if (Tab.Settings.ordinal !in warmedTabs) {
            warmedTabs = warmedTabs + Tab.Settings.ordinal
        }
    }

    LaunchedEffect(tab) {
        if (tab !in warmedTabs) warmedTabs = warmedTabs + tab
    }

    LaunchedEffect(tab) {
        if (tab == Tab.Settings.ordinal) settingsVisibleCount++
    }

    val pendingTab by requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTab) {
        if (pendingTab in Tab.entries.indices) {
            tab = pendingTab
            requestedTab.value = -1
        }
    }

    LaunchedEffect(ready, appSettings.appUpdateAutoCheck) {
        if (ready && appSettings.appUpdateAutoCheck) {
            settingsVm.checkAppUpdateSilently()
        }
    }

    val toastMsg by vm.toast.collectAsStateWithLifecycle()
    val localizedToast = com.questtick.i18n.localizedText(toastMsg.orEmpty())
    LaunchedEffect(toastMsg, localizedToast) {
        toastMsg?.let {
            scope.launch { snackbar.showSnackbar(localizedToast) }
            vm.consumeToast()
        }
    }

    val appTheme = LocalAppUiTheme.current

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = appTheme.backgroundTop,
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                GalaxyBottomBar(visualPosition = visualTabPosition) { tab = it }
            },
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(14.dp),
                        containerColor = appTheme.brand,
                        contentColor = Color.White,
                        actionColor = Color.White,
                        dismissActionContentColor = Color.White.copy(alpha = 0.85f),
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp)
                                .border(1.dp, appTheme.brandAlt.copy(alpha = 0.75f), RoundedCornerShape(14.dp)),
                    )
                }
            },
        ) { padding ->
            MainTabContent(
                tab = tab,
                warmedTabs = warmedTabs,
                contentPadding = padding,
                settingsVisibleCount = settingsVisibleCount,
                homeVm = homeVm,
                accountsVm = accountsVm,
                recordsVm = recordsVm,
                logsVm = logsVm,
                settingsVm = settingsVm,
                onTabChange = { tab = it },
                onVisualTabPositionChange = { visualTabPosition = it },
                onStartEdit = { account, isNew ->
                    editingSessionKey++
                    editingAccount = account
                    editingIsNew = isNew
                },
            )
        }

        AccountEditorSheetHost(
            editingAccount = editingAccount,
            editingIsNew = editingIsNew,
            editingSessionKey = editingSessionKey,
            vm = vm,
            onDismiss = { editingAccount = null },
            onEditingAccountChange = { editingAccount = it },
            onOpenQrLogin = { account ->
                qrLoginForAccount = account
                showQRLogin = true
            },
            onOpenCloudQr = { gameKey, draft ->
                cloudQRGameKey = gameKey
                cloudQRForAccount = draft
                showCloudQR = true
            },
        )

        MysQrLoginOverlayHost(
            visible = showQRLogin,
            targetAccount = qrLoginForAccount,
            vm = vm,
            onDismiss = {
                showQRLogin = false
                qrLoginForAccount = null
            },
            onAccountUpdated = { updated -> editingAccount = updated },
        )

        CloudQrLoginOverlayHost(
            visible = showCloudQR,
            gameKey = cloudQRGameKey,
            targetAccount = cloudQRForAccount,
            vm = vm,
            onDismiss = {
                showCloudQR = false
                cloudQRForAccount = null
            },
            onAccountUpdated = { updated -> editingAccount = updated },
        )

        UpdateOverlayHost(
            updateDownloadProgress = updateDownloadProgress,
            updatePrompt = updatePrompt,
            settingsVm = settingsVm,
        )
    }
}
