package com.questtick.ui.screens

/*
 * 外观设置：主题模式、动态取色、预设主题与自定义主题色（HSL 编辑器）。
 */
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import com.questtick.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.data.AppSettings
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.consumeHorizontalScrollOverflow
import com.questtick.ui.theme.TextSecondary
import kotlin.math.roundToInt

private const val THEME_HEADER_ICON_SIZE_DP = 24
private const val SELECTED_ICON_END_PADDING_DP = 7

/** 外观与主题设置页面。 */
@Composable
@Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod", "detekt:FunctionNaming")
internal fun AppearanceDetailPage(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var showCustomThemePage by rememberSaveable { mutableStateOf(false) }
    if (showCustomThemePage) {
        CustomThemeColorPage(
            settings = settings,
            onApply = { updated ->
                onSave(updated)
                showCustomThemePage = false
            },
            onBack = { showCustomThemePage = false },
        )
        return
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
                            ThemeModeOptionChip(
                                "跟随系统",
                                "SYSTEM",
                                settings.appThemeMode,
                                Modifier.weight(1f),
                            ) { onSave(settings.copy(appThemeMode = it)) }
                            ThemeModeOptionChip(
                                "浅色",
                                "LIGHT",
                                settings.appThemeMode,
                                Modifier.weight(1f),
                            ) { onSave(settings.copy(appThemeMode = it)) }
                            ThemeModeOptionChip(
                                "深色",
                                "DARK",
                                settings.appThemeMode,
                                Modifier.weight(1f),
                            ) { onSave(settings.copy(appThemeMode = it)) }
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
                item(key = "custom_theme_color") {
                    SettingsCard {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        var customHex by remember(settings.customThemeColor) { mutableStateOf(settings.customThemeColor) }
                        val customEnabled = !settings.dynamicColorEnabled
                        val customTextColor =
                            if (customEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        Row(
                            Modifier.fillMaxWidth().clickableNoRipple { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsSectionTitle("自定义颜色")
                            Spacer(Modifier.weight(1f))
                            CustomColorOption(
                                settings.customThemeColor,
                                settings.customThemeColor,
                                customEnabled,
                                onSelect = {},
                                compact = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "收起自定义配色" else "展开自定义配色",
                                tint = customTextColor,
                                modifier = Modifier.size(THEME_HEADER_ICON_SIZE_DP.dp),
                            )
                        }
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            // 把内容放入 Column，确保展开动画同时驱动卡片高度和内部间距。
                            Column {
                                val presets =
                                    remember(
                                        settings.customThemePresets,
                                        settings.customThemeColor,
                                        settings.customThemeSecondaryColor,
                                        settings.customThemeTertiaryColor,
                                        settings.customThemeName,
                                    ) {
                                        val builtIn = buildThemePresets()
                                        val saved =
                                            settings.customThemePresets.map { preset ->
                                                ThemePreset(preset.name, preset.primary, preset.secondary, preset.tertiary, isCustom = true)
                                            }
                                        val current =
                                            ThemePreset(
                                                settings.customThemeName.ifBlank { "自定义" },
                                                settings.customThemeColor,
                                                settings.customThemeSecondaryColor.ifBlank {
                                                    settings.customThemeColor
                                                },
                                                settings.customThemeTertiaryColor.ifBlank {
                                                    settings.customThemeColor
                                                },
                                                isCustom = false,
                                            )
                                        val existing = builtIn + saved
                                        existing +
                                            listOfNotNull(
                                                current.takeIf {
                                                    it.primary.isNotBlank() &&
                                                        existing.none { preset ->
                                                            preset.primary.equals(it.primary, true) &&
                                                                preset.secondary.equals(it.secondary, true) &&
                                                                preset.tertiary.equals(it.tertiary, true)
                                                        }
                                                },
                                            )
                                    }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "动态配色开启时暂时停用，关闭后自动恢复已保存的自定义颜色。",
                                    fontSize = 12.sp,
                                    color =
                                        if (customEnabled) {
                                            TextSecondary
                                        } else {
                                            TextSecondary.copy(alpha = 0.55f)
                                        },
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .consumeHorizontalScrollOverflow()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(THEME_PRESET_SPACING_DP.dp),
                                ) {
                                    presets.forEach { preset ->
                                        ThemePresetOption(
                                            preset = preset,
                                            selected =
                                                settings.customThemeColor.equals(preset.primary, true) &&
                                                    settings.customThemeSecondaryColor.equals(preset.secondary, true) &&
                                                    settings.customThemeTertiaryColor.equals(preset.tertiary, true),
                                            enabled = customEnabled,
                                            onSelect = {
                                                onSave(
                                                    settings.copy(
                                                        customThemeColor = preset.primary,
                                                        customThemeSecondaryColor = preset.secondary,
                                                        customThemeTertiaryColor = preset.tertiary,
                                                        customThemeName = preset.name,
                                                    ),
                                                )
                                            },
                                            onDelete =
                                                if (preset.isCustom) {
                                                    {
                                                        val remaining =
                                                            settings.customThemePresets.filterNot {
                                                                it.name.equals(preset.name, true) &&
                                                                    it.primary.equals(preset.primary, true) &&
                                                                    it.secondary.equals(preset.secondary, true) &&
                                                                    it.tertiary.equals(preset.tertiary, true)
                                                            }
                                                        val deletingCurrent =
                                                            settings.customThemeName.equals(preset.name, true) &&
                                                                settings.customThemeColor.equals(
                                                                    preset.primary,
                                                                    true,
                                                                ) &&
                                                                settings.customThemeSecondaryColor.equals(preset.secondary, true) &&
                                                                settings.customThemeTertiaryColor.equals(preset.tertiary, true)
                                                        val nextName = if (deletingCurrent) "" else settings.customThemeName
                                                        val nextColor = if (deletingCurrent) "" else settings.customThemeColor
                                                        val nextSecondary = if (deletingCurrent) "" else settings.customThemeSecondaryColor
                                                        val nextTertiary = if (deletingCurrent) "" else settings.customThemeTertiaryColor
                                                        onSave(
                                                            settings.copy(
                                                                customThemePresets = remaining,
                                                                customThemeName = nextName,
                                                                customThemeColor = nextColor,
                                                                customThemeSecondaryColor = nextSecondary,
                                                                customThemeTertiaryColor = nextTertiary,
                                                            ),
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                        )
                                    }
                                }
                                TextButton(
                                    enabled = customEnabled,
                                    onClick = { showCustomThemePage = true },
                                    modifier = Modifier.align(Alignment.End),
                                ) { Text("调色板", color = customTextColor) }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = customHex,
                                        onValueChange = { customHex = it.take(HEX_COLOR_LENGTH) },
                                        modifier = Modifier.weight(1f),
                                        enabled = customEnabled,
                                        singleLine = true,
                                        label = { Text("HEX 颜色") },
                                        placeholder = { Text("#RRGGBB") },
                                    )
                                    TextButton(
                                        enabled = customEnabled,
                                        onClick = {
                                            val normalized = customHex.trim().uppercase().let { if (it.startsWith("#")) it else "#$it" }
                                            if (normalized.matches(Regex("#[0-9A-F]{6}"))) {
                                                onSave(settings.copy(customThemeColor = normalized))
                                            }
                                        },
                                    ) { Text("应用", color = customTextColor) }
                                }
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
internal fun CustomColorOption(
    colorHex: String,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    compact: Boolean = false,
) {
    val color =
        if (colorHex.isBlank()) {
            MaterialTheme.colorScheme.outline
        } else {
            runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
                .getOrDefault(MaterialTheme.colorScheme.outline)
        }
    val isSelected = selected.equals(colorHex, ignoreCase = true)
    val displayColor = if (enabled) color else Color.Gray.copy(alpha = DISABLED_THEME_ALPHA)
    val borderColor =
        if (enabled && isSelected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.Gray
        }
    Box(
        Modifier
            .size(if (compact) COMPACT_PRESET_SWATCH_SIZE_DP.dp else 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(displayColor)
            .border(if (isSelected) 3.dp else 1.dp, borderColor, RoundedCornerShape(17.dp))
            .then(if (enabled) Modifier.clickableNoRipple { onSelect(colorHex) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (colorHex.isBlank()) Text("自", fontSize = 10.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.DarkGray)
    }
}

@Composable
private fun ThemeModeOptionChip(
    title: String,
    mode: String,
    currentMode: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val selected = currentMode.equals(mode, ignoreCase = true)
    val shape = RoundedCornerShape(12.dp)
    val background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickableNoRipple { onSelect(mode) },
        contentAlignment = Alignment.Center,
    ) {
        // 文字区域独立居中，勾选图标固定在右侧，避免英文长标题被图标推偏。
        Text(
            title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = contentColor,
            maxLines = 2,
            softWrap = true,
        )
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "已选中",
                tint = contentColor,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = SELECTED_ICON_END_PADDING_DP.dp).size(15.dp),
            )
        }
    }
}
