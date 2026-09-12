package com.questtick.ui.screens

// 设置页面复用的卡片、输入框、按钮和状态行组件。

import com.questtick.i18n.localizedText
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.questtick.log.AppLog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.ui.components.AnimatedSwitch
import com.questtick.ui.components.PanelCard
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.TextSecondary
import java.util.UUID

internal fun formatCacheSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    return when {
        safeBytes == 0L -> "0 B"
        safeBytes < 1024L -> "$safeBytes B"
        safeBytes < 1024L * 1024L -> "%.1f KB".format(safeBytes / 1024.0)
        safeBytes < 1024L * 1024L * 1024L -> "%.2f MB".format(safeBytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(safeBytes / 1024.0 / 1024.0 / 1024.0)
    }
}

@Composable
internal fun AboutLinkRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun HairlineSpacer() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 12.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    )
}

internal fun openExternalUrl(
    context: Context,
    url: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLog.w("SettingsScreen", "无法打开外部链接: $url", e)
    }
}

internal val SettingsEntryMinHeight = 72.dp

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    PanelCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
    }
}

@Composable
internal fun SettingsEntryCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val cardModifier =
        if (onClick != null) {
            modifier.fillMaxWidth().clickableNoRipple(onClick)
        } else {
            modifier.fillMaxWidth()
        }
    PanelCard(modifier = cardModifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsEntryMinHeight)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                softWrap = true,
            )
            when {
                trailing != null -> {
                    trailing()
                }

                onClick != null -> {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    supportingContent: @Composable ColumnScope.() -> Unit = {},
) {
    SettingsCard(modifier = modifier) {
        ToggleRow(title, subtitle, checked, onCheckedChange, enabled)
        supportingContent()
    }
}

@Composable
internal fun SettingsActionCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cardModifier =
        if (enabled) {
            modifier.clickableNoRipple(onClick)
        } else {
            modifier
        }
    SettingsCard(modifier = cardModifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun SettingsTextActionCard(
    title: String,
    actionText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onActionClick: () -> Unit,
) {
    SettingsCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val actionColor = if (enabled) MaterialTheme.colorScheme.primary else TextSecondary
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, actionColor, RoundedCornerShape(12.dp))
                        .then(if (enabled) Modifier.clickableNoRipple(onActionClick) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionText, fontSize = 13.sp, color = actionColor)
            }
        }
    }
}

@Composable
internal fun SettingsInfoCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    SettingsCard(modifier = modifier) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 13.sp, color = TextSecondary, lineHeight = 21.sp)
    }
}

@Composable
internal fun SettingsSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun SettingsCompactOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Text(text, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
    }
}

@Composable
@Suppress("detekt:FunctionNaming")
internal fun SettingsFetchButton(
    fetching: Boolean,
    completed: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SettingsCompactOutlinedButton(
        text =
            when {
                fetching -> "获取中"
                completed -> "获取完成"
                else -> "获取"
            },
        enabled = !fetching && !completed,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
internal fun HintBox(
    color: Color,
    text: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Text(text, fontSize = 12.sp, color = color, lineHeight = 18.sp)
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = TextSecondary)
            }
        }
        AnimatedSwitch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            offColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        )
    }
}

@Composable
internal fun SettingsFieldLabel(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
internal fun FieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.7f)) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
    )
}

@Composable
@Suppress("detekt:FunctionNaming")
internal fun ScheduleTimeField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(2)) },
        placeholder = {
            Text(
                text = placeholder,
                modifier = Modifier.fillMaxWidth(),
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
        modifier = Modifier.width(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
    )
}

@Composable
internal fun SecretFieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.7f)) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Icon(
                if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = localizedText("显示/隐藏"),
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).clickableNoRipple { reveal = !reveal },
            )
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
    )
}

@Composable
internal fun ProviderChip(
    text: String,
    brush: Brush,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bgAlpha = if (selected) 1f else 0.45f
    val borderColor = if (selected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.15f)
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(brush, alpha = bgAlpha)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = Color.White,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
internal fun DeviceIdField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .clickableNoRipple { onValueChange(UUID.randomUUID().toString()) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = localizedText("随机生成"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "随机生成",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        FieldBox(value, onValueChange, placeholder, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun fieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    )

@Composable
@Suppress("detekt:FunctionNaming")
internal fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        contentPadding = contentPadding,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}
