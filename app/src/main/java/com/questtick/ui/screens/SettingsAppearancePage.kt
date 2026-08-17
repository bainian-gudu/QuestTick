package com.questtick.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.BuildConfig
import com.questtick.data.AppSettings
import com.questtick.i18n.AppLanguage
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.TextSecondary

/** 外观、主题与调试版界面语言设置页面。 */
@Composable
internal fun AppearanceDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onSaveLanguage: (AppSettings, String, () -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    fun selectLanguage(language: AppLanguage) {
        if (language.settingValue == settings.appLanguage) return
        onSaveLanguage(settings, language.settingValue) { context.findActivity()?.recreate() }
    }

    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("外观", onBack)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "theme_mode") {
                    SettingsCard {
                        SettingsSectionTitle("主题模式")
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeModeOptionChip("跟随系统", "SYSTEM", settings.appThemeMode, Modifier.weight(1f)) { onSave(settings.copy(appThemeMode = it)) }
                            ThemeModeOptionChip("浅色", "LIGHT", settings.appThemeMode, Modifier.weight(1f)) { onSave(settings.copy(appThemeMode = it)) }
                            ThemeModeOptionChip("深色", "DARK", settings.appThemeMode, Modifier.weight(1f)) { onSave(settings.copy(appThemeMode = it)) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("选择后立即生效；跟随系统会随系统深色模式自动切换。", fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
                    }
                }
                item(key = "oled_pure_black") {
                    SettingsSwitchCard(
                        title = "OLED/AMOLED 优化",
                        subtitle = "深色模式下将大面积背景设为纯黑；LCD 屏幕建议关闭",
                        checked = settings.oledPureBlackEnabled,
                        onCheckedChange = { onSave(settings.copy(oledPureBlackEnabled = it)) },
                    )
                }
                item(key = "dynamic_color") {
                    SettingsSwitchCard(
                        title = "动态配色",
                        subtitle = "Android 12 及以上跟随系统壁纸和主题颜色",
                        checked = settings.dynamicColorEnabled,
                        onCheckedChange = { onSave(settings.copy(dynamicColorEnabled = it)) },
                    )
                }
                if (BuildConfig.DEBUG) {
                    item(key = "interface_language") {
                        SettingsCard {
                            SettingsSectionTitle("界面语言")
                            Spacer(Modifier.height(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LanguageOptionRow(listOf(AppLanguage.SYSTEM to "跟随系统", AppLanguage.SIMPLIFIED_CHINESE to "简体中文", AppLanguage.TRADITIONAL_CHINESE to "繁體中文"), settings.appLanguage, ::selectLanguage)
                                LanguageOptionRow(listOf(AppLanguage.ENGLISH to "English", AppLanguage.JAPANESE to "日本語", AppLanguage.KOREAN to "한국어"), settings.appLanguage, ::selectLanguage)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun LanguageOptionRow(options: List<Pair<AppLanguage, String>>, selected: String, onSelect: (AppLanguage) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (language, label) ->
            ThemeModeOptionChip(label, language.settingValue, selected, Modifier.weight(1f)) { onSelect(language) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun ThemeModeOptionChip(title: String, mode: String, currentMode: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val selected = currentMode.equals(mode, ignoreCase = true)
    val shape = RoundedCornerShape(12.dp)
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier.height(44.dp).clip(shape).background(background).border(1.dp, borderColor, shape).clickableNoRipple { onSelect(mode) },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, color = contentColor, maxLines = 1)
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
            }
        }
    }
}
