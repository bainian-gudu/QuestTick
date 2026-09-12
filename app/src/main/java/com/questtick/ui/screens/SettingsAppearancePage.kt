package com.questtick.ui.screens

/*
 * 外观与语言设置：主题模式、动态取色、预设主题与自定义主题色（HSL 编辑器），以及界面语言切换。
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
import com.questtick.i18n.Text
import com.questtick.i18n.localizedText
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
import com.questtick.i18n.AppLanguage
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.consumeHorizontalScrollOverflow
import com.questtick.ui.theme.TextSecondary
import kotlin.math.roundToInt

/** 外观与主题设置页面；语言切换位于独立的二级页面。 */
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
                item(key = "custom_theme_color") {
                    SettingsCard {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        var customHex by remember(settings.customThemeColor) { mutableStateOf(settings.customThemeColor) }
                        val customEnabled = !settings.dynamicColorEnabled
                        val customTextColor = if (customEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        Row(
                            Modifier.fillMaxWidth().clickableNoRipple { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsSectionTitle("自定义颜色")
                            Spacer(Modifier.weight(1f))
                            CustomColorOption(settings.customThemeColor, settings.customThemeColor, customEnabled, onSelect = {}, compact = true)
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "收起自定义配色" else "展开自定义配色",
                                tint = customTextColor,
                                modifier = Modifier.size(24.dp),
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
                                Text("动态配色开启时暂时停用，关闭后自动恢复已保存的自定义颜色。", fontSize = 12.sp, color = if (customEnabled) TextSecondary else TextSecondary.copy(alpha = 0.55f))
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .consumeHorizontalScrollOverflow()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
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
                                                        onSave(
                                                            settings.copy(
                                                                customThemePresets = remaining,
                                                                customThemeName = if (deletingCurrent) "" else settings.customThemeName,
                                                                customThemeColor = if (deletingCurrent) "" else settings.customThemeColor,
                                                                customThemeSecondaryColor = if (deletingCurrent) "" else settings.customThemeSecondaryColor,
                                                                customThemeTertiaryColor = if (deletingCurrent) "" else settings.customThemeTertiaryColor,
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
                                        onValueChange = { customHex = it.take(7) },
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
                                            if (normalized.matches(Regex("#[0-9A-F]{6}"))) onSave(settings.copy(customThemeColor = normalized))
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

/** 独立的语言设置页；语言卡片默认折叠，避免占用设置主页空间。 */
@Composable
@Suppress("detekt:LongMethod", "detekt:FunctionNaming")
internal fun LanguageDetailPage(
    settings: AppSettings,
    onSaveLanguage: (AppSettings, String) -> Unit,
    onBack: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    fun selectLanguage(language: AppLanguage) {
        if (language.settingValue == settings.appLanguage) return
        onSaveLanguage(settings, language.settingValue)
    }

    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("界面语言", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SettingsCard {
                        Row(
                            Modifier.fillMaxWidth().clickableNoRipple { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                SettingsSectionTitle("界面语言")
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    currentLanguageLabel(settings.appLanguage),
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                )
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "收起语言选项" else "展开语言选项",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            // 语言选项保持折叠，切换后由 settings StateFlow 立即刷新当前语言。
                            Column {
                                Spacer(Modifier.height(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    languageOptions.forEach { (language, label) ->
                                        ThemeModeOptionChip(
                                            title = label,
                                            mode = language.settingValue,
                                            currentMode = settings.appLanguage,
                                            modifier = Modifier.fillMaxWidth(),
                                            onSelect = { selectLanguage(language) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomColorOption(
    colorHex: String,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    compact: Boolean = false,
) {
    val color = if (colorHex.isBlank()) MaterialTheme.colorScheme.outline else runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(MaterialTheme.colorScheme.outline)
    val isSelected = selected.equals(colorHex, ignoreCase = true)
    val displayColor = if (enabled) color else Color.Gray.copy(alpha = 0.45f)
    Box(
        Modifier
            .size(if (compact) 24.dp else 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(displayColor)
            .border(if (isSelected) 3.dp else 1.dp, if (enabled && isSelected) MaterialTheme.colorScheme.onSurface else Color.Gray, RoundedCornerShape(17.dp))
            .then(if (enabled) Modifier.clickableNoRipple { onSelect(colorHex) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (colorHex.isBlank()) Text("自", fontSize = 10.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.DarkGray)
    }
}

/** 三级调色页面：用 HSL 调节三种主题色，并提供即时预览。 */
@Composable
private fun CustomThemeColorPage(
    settings: AppSettings,
    onApply: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var themeName by remember(settings.customThemeName) { mutableStateOf(settings.customThemeName) }
    var primary by remember(settings.customThemeColor) { mutableStateOf(hexToHsl(settings.customThemeColor)) }
    var secondary by remember(settings.customThemeSecondaryColor, settings.customThemeColor) {
        mutableStateOf(hexToHsl(settings.customThemeSecondaryColor.ifBlank { settings.customThemeColor }))
    }
    var tertiary by remember(settings.customThemeTertiaryColor, settings.customThemeColor) {
        mutableStateOf(hexToHsl(settings.customThemeTertiaryColor.ifBlank { settings.customThemeColor }))
    }
    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("自定义颜色", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsCard {
                        OutlinedTextField(
                            value = themeName,
                            onValueChange = { themeName = it.take(24) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("主题名称") },
                            placeholder = { Text("例如：我的蓝色主题") },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("调整色相、饱和度和明度", fontWeight = FontWeight.SemiBold)
                        Text("拖动滑块即可预览，颜色会在应用后保存。", fontSize = 12.sp, color = TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("P" to hslToHex(primary), "S" to hslToHex(secondary), "T" to hslToHex(tertiary)).forEach { (label, hex) ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    CustomColorOption(hex, hex, true, onSelect = {}, compact = false)
                                    Text(label, fontSize = 12.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
                item { SettingsCard { HslEditor("主色", primary) { primary = it } } }
                item { SettingsCard { HslEditor("辅色", secondary) { secondary = it } } }
                item { SettingsCard { HslEditor("第三色", tertiary) { tertiary = it } } }
                item {
                    Row(
                        Modifier.fillMaxWidth().height(50.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onBack,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                        ) { Text("取消", color = MaterialTheme.colorScheme.onPrimary) }
                        Button(
                            onClick = {
                                val name = themeName.trim().ifBlank { "自定义" }
                                val primaryHex = hslToHex(primary)
                                val secondaryHex = hslToHex(secondary)
                                val tertiaryHex = hslToHex(tertiary)
                                val savedPreset = com.questtick.data.SavedThemePreset(name, primaryHex, secondaryHex, tertiaryHex)
                                onApply(
                                    settings.copy(
                                        customThemeName = name,
                                        customThemeColor = primaryHex,
                                        customThemeSecondaryColor = secondaryHex,
                                        customThemeTertiaryColor = tertiaryHex,
                                        customThemePresets =
                                            settings.customThemePresets
                                                .filterNot { it.name.equals(name, true) }
                                                .plus(savedPreset),
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("保存", color = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private data class ThemePreset(
    val name: String,
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val isCustom: Boolean = false,
)

/** 预设保存完整三色组合，避免只替换主色后辅色仍停留在旧主题。 */
private fun buildThemePresets(): List<ThemePreset> =
    listOf(
        ThemePreset("默认", "#2563EB", "#60A5FA", "#3B82F6"),
        ThemePreset("海蓝青绿", "#0284C7", "#14B8A6", "#38BDF8"),
        ThemePreset("紫罗兰", "#7C3AED", "#A78BFA", "#EC4899"),
        ThemePreset("珊瑚暖橙", "#EA580C", "#F59E0B", "#F43F5E"),
        ThemePreset("森林青柠", "#15803D", "#22C55E", "#84CC16"),
        ThemePreset("玫瑰莓红", "#BE123C", "#F43F5E", "#FB7185"),
    )

@Composable
private fun ThemePresetOption(
    preset: ThemePreset,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val primary = preset.primary.toComposeColor()
    val secondary = preset.secondary.toComposeColor()
    val tertiary = preset.tertiary.toComposeColor()
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickableNoRipple(onClick = onSelect) else Modifier)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(42.dp)) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected && enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        shape = CircleShape,
                    ),
            ) {
                val alpha = if (enabled) 1f else 0.45f
                drawRect(secondary.copy(alpha = alpha), size = size)
                drawRect(
                    tertiary.copy(alpha = alpha),
                    topLeft = Offset(size.width / 2f, size.height / 2f),
                    size =
                        androidx.compose.ui.geometry
                            .Size(size.width / 2f, size.height / 2f),
                )
                drawCircle(
                    primary.copy(alpha = alpha),
                    radius = if (selected) 11.dp.toPx() else 8.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
            if (onDelete != null && enabled) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "删除${preset.name}",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .clickableNoRipple(onClick = onDelete)
                            .padding(2.dp),
                )
            }
        }
        Text(preset.name, fontSize = 9.sp, color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.45f), maxLines = 1)
    }
}

private fun String.toComposeColor(): Color = runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrDefault(Color.Gray)

private data class HslColor(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

@Composable
private fun HslEditor(
    title: String,
    value: HslColor,
    onValueChange: (HslColor) -> Unit,
) {
    val preview = hslToComposeColor(value)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(preview))
            Spacer(Modifier.width(8.dp))
            Text(
                hslToHex(value),
                fontSize = 11.sp,
                color = TextSecondary,
            )
        }
        HslSlider("色相", "${value.hue.roundToInt()}°", value.hue, 0f..360f) { onValueChange(value.copy(hue = it)) }
        HslSlider("饱和度", "${percent(value.saturation)}%", value.saturation, 0f..1f) { onValueChange(value.copy(saturation = it)) }
        HslSlider("明度", "${percent(value.lightness)}%", value.lightness, 0f..1f) { onValueChange(value.copy(lightness = it)) }
    }
}

@Composable
private fun HslSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(52.dp), fontSize = 12.sp, color = TextSecondary)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(valueText, modifier = Modifier.width(42.dp), fontSize = 12.sp, color = TextSecondary)
    }
}

private fun percent(value: Float): Int = (value * 100f).roundToInt()

/** 使用标准 HSL 公式转换，保证保存后的 HEX 与预览颜色一致。 */
private fun hexToHsl(hex: String): HslColor {
    if (hex.isBlank()) return HslColor(210f, 0.65f, 0.5f)
    val color = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() ?: return HslColor(210f, 0.65f, 0.5f)
    val r = android.graphics.Color.red(color) / 255f
    val g = android.graphics.Color.green(color) / 255f
    val b = android.graphics.Color.blue(color) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    if (max == min) return HslColor(0f, 0f, lightness)
    val delta = max - min
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue =
        when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
    return HslColor(hue, saturation, lightness)
}

private fun hslToHex(value: HslColor): String {
    val h = (value.hue % 360f) / 360f
    val s = value.saturation.coerceIn(0f, 1f)
    val l = value.lightness.coerceIn(0f, 1f)
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q

    fun hueToRgb(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    val (r, g, b) = if (s == 0f) Triple(l, l, l) else Triple(hueToRgb(h + 1f / 3f), hueToRgb(h), hueToRgb(h - 1f / 3f))
    return "#%02X%02X%02X".format((r * 255f).roundToInt(), (g * 255f).roundToInt(), (b * 255f).roundToInt())
}

private fun hslToComposeColor(value: HslColor): Color = Color(android.graphics.Color.parseColor(hslToHex(value)))

private val languageOptions =
    listOf(
        AppLanguage.SYSTEM to "跟随系统",
        AppLanguage.SIMPLIFIED_CHINESE to "简体中文",
        AppLanguage.TRADITIONAL_CHINESE to "繁体中文",
        AppLanguage.ENGLISH to "English",
        AppLanguage.JAPANESE to "日本語",
        AppLanguage.KOREAN to "한국어",
    )

internal fun currentLanguageLabel(setting: String): String =
    when (AppLanguage.fromSetting(setting)) {
        AppLanguage.SYSTEM -> "跟随系统"
        AppLanguage.SIMPLIFIED_CHINESE -> "简体中文"
        AppLanguage.TRADITIONAL_CHINESE -> "繁体中文"
        AppLanguage.ENGLISH -> "English"
        AppLanguage.JAPANESE -> "日本語"
        AppLanguage.KOREAN -> "한국어"
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
                contentDescription = localizedText("已选中"),
                tint = contentColor,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp).size(15.dp),
            )
        }
    }
}
