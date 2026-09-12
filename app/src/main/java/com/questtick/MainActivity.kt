package com.questtick

/*
 * 应用唯一的界面宿主：设置 Compose 内容、主题与边到边布局，并接管系统栏适配；业务导航状态见 MainAppRoot。
 */
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.questtick.log.AppLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.questtick.notify.Notifier
import com.questtick.net.HttpTransport
import com.questtick.repository.log.AppErrorLogger
import javax.inject.Inject
import com.questtick.ui.theme.AppUiThemeCatalog
import com.questtick.ui.theme.ThemeModeController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var httpTransport: HttpTransport

    @Inject lateinit var appErrorLogger: AppErrorLogger

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            com.questtick.i18n.AppLocaleController
                .wrap(newBase),
        )
    }

    /** 通知点击请求跳转的底部 Tab，下游由 AppRoot 监听。 */
    private val requestedTab = MutableStateFlow(-1)

    @Volatile
    private var splashCanHide = false
    private var fullyDrawnReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchThemeMode = readThemeModePreference()
        ThemeModeController.syncApplicationNightMode(this, launchThemeMode)
        val darkLaunchTheme = shouldUseDarkLaunchTheme(launchThemeMode)
        val oledPureBlack = darkLaunchTheme && readOledPureBlackPreference()
        val dynamicColorEnabled = readDynamicColorPreference()
        val customThemeColor = readCustomThemeColorPreference()
        if (oledPureBlack) {
            setTheme(R.style.Theme_QuestTick_Starting_Oled)
        } else if (darkLaunchTheme) {
            setTheme(R.style.Theme_QuestTick_Starting_Dark)
        }
        // 冷启动窗口先填充目标主题色，避免 Compose 首帧前露出系统默认底色。
        val launchStart = SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            !splashCanHide && (SystemClock.uptimeMillis() - launchStart) < 1_500L
        }
        super.onCreate(savedInstanceState)
        applyLaunchThemeColors(darkLaunchTheme, oledPureBlack, dynamicColorEnabled, customThemeColor)
        enableEdgeToEdge()
        // Android 16+：接入预测性返回。
        if (Build.VERSION.SDK_INT >= 36) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                moveTaskToBack(false)
            }
        }
        applyDisplayTweaks()
        consumeTabIntent(intent)
        setContent {
            ThemedAppRoot(
                requestedTab = requestedTab,
                httpTransport = httpTransport,
                onCriticalUiReady = { splashCanHide = true },
                onFullyDrawn = {
                    if (!fullyDrawnReported) {
                        fullyDrawnReported = true
                        reportFullyDrawn()
                        // 通知渠道延后创建，避免阻塞首帧
                        lifecycleScope.launch {
                            try {
                                Notifier.ensureChannel(this@MainActivity)
                            } catch (e: Exception) {
                                AppLog.w(TAG, "通知渠道创建失败", e)
                                appErrorLogger.record("通知渠道创建", e)
                            }
                        }
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeTabIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 系统可能在省电模式、外接屏或旋转后重新协商刷新率。
        applyDisplayTweaks()
    }

    private fun consumeTabIntent(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        if (tab in 0 until TAB_COUNT) {
            requestedTab.value = tab
            intent?.removeExtra(EXTRA_OPEN_TAB)
        }
    }

    /** 在 Compose 首帧前同步窗口颜色，避免启动瞬间出现默认背景闪烁。 */
    @Suppress("DEPRECATION")
    private fun applyLaunchThemeColors(
        darkTheme: Boolean,
        oledPureBlack: Boolean,
        dynamicColorEnabled: Boolean,
        customThemeColor: String,
    ) {
        val palette =
            when {
                oledPureBlack -> AppUiThemeCatalog.OledDark
                darkTheme -> AppUiThemeCatalog.Dark
                else -> AppUiThemeCatalog.Light
            }
        val customColor =
            runCatching {
                if (customThemeColor.isBlank()) null else android.graphics.Color.parseColor(customThemeColor)
            }.getOrNull()
        val background =
            if (customColor != null) {
                customColor
            } else if (dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    val colorRes = if (darkTheme) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10
                    getColor(colorRes)
                }.getOrDefault(palette.backgroundBottom.toArgb())
            } else {
                palette.backgroundBottom.toArgb()
            }
        val resolvedBackground = if (oledPureBlack) Color.Black.toArgb() else background
        window.setBackgroundDrawable(ColorDrawable(resolvedBackground))
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = resolvedBackground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    private fun readThemeModePreference(): String =
        getSharedPreferences("signin_preferences", Context.MODE_PRIVATE)
            .getString("appThemeMode", "SYSTEM")
            .orEmpty()
            .uppercase()

    private fun readOledPureBlackPreference(): Boolean =
        getSharedPreferences("signin_preferences", Context.MODE_PRIVATE)
            .getBoolean("oledPureBlackEnabled", false)

    private fun readDynamicColorPreference(): Boolean =
        getSharedPreferences("signin_preferences", Context.MODE_PRIVATE)
            .getBoolean("dynamicColorEnabled", false)

    private fun readCustomThemeColorPreference(): String =
        getSharedPreferences("signin_preferences", Context.MODE_PRIVATE)
            .getString("customThemeColor", "")
            .orEmpty()

    private fun shouldUseDarkLaunchTheme(mode: String): Boolean =
        when (mode) {
            "LIGHT" -> {
                false
            }

            "DARK" -> {
                true
            }

            else -> {
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
        }

    /** 窗口重新获得焦点时刷新高刷与挖孔屏参数。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyDisplayTweaks()
        }
    }

    companion object {
        const val TAG = "MainActivity"
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_HOME = 0
        const val TAB_ACCOUNTS = 1
        const val TAB_RECORDS = 2
        const val TAB_LOGS = 3
        const val TAB_SETTINGS = 4
        const val TAB_COUNT = 5
    }

    /**
     * 同步显示参数：内容可延伸到挖孔区域，并尽量请求设备支持的最高刷新率。
     */
    private fun applyDisplayTweaks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        } else {
                            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                }
        }

        try {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
            val modes = display?.supportedModes.orEmpty()
            if (modes.isEmpty()) return
            val current = display?.mode
            // 不跨分辨率切换显示模式，避免部分设备因高刷模式分辨率不同而闪屏。
            val sameResolution =
                current
                    ?.let { mode ->
                        modes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                    }.orEmpty()
            val candidates = if (sameResolution.isNotEmpty()) sameResolution else modes.toList()
            val best =
                candidates
                    .filter { it.refreshRate.isFinite() && it.refreshRate > 0f }
                    .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                lp.preferredDisplayModeId = best.modeId
            }
            @Suppress("DEPRECATION")
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
        } catch (t: Throwable) {
            AppLog.w(TAG, "高刷/挖孔参数设置失败", t)
            appErrorLogger.record("显示参数设置", t)
        }
    }
}
