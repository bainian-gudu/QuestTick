package com.questtick.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.questtick.i18n.Text
import com.questtick.ui.theme.LocalAppUiTheme

/** 全应用警示、确认与进度弹窗共用的视觉语义，避免各页面在深浅主题下产生不同底色。 */
object AppDialogTokens {
    val Shape = RoundedCornerShape(24.dp)
    val ShadowElevation = 12.dp
}

data class AppDialogColors(
    val container: Color,
    val title: Color,
    val body: Color,
    val border: Color,
    val progressTrack: Color,
    val primaryAction: Color,
    val neutralAction: Color,
    val destructiveAction: Color,
)

@Composable
fun appDialogColors(): AppDialogColors {
    val theme = LocalAppUiTheme.current
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    return AppDialogColors(
        container = theme.cardElevated,
        title = theme.textPrimary,
        body = theme.textSecondary,
        border = theme.hairline,
        progressTrack = theme.field,
        primaryAction = theme.brand,
        neutralAction = theme.textSecondary,
        destructiveAction = scheme.error,
    )
}

enum class AppDialogActionStyle {
    PRIMARY,
    NEUTRAL,
    DESTRUCTIVE,
}

/** 统一弹窗操作按钮的文字层级与可访问颜色。 */
@Composable
fun AppDialogActionButton(
    label: String,
    onClick: () -> Unit,
    style: AppDialogActionStyle = AppDialogActionStyle.PRIMARY,
    enabled: Boolean = true,
) {
    val colors = appDialogColors()
    val contentColor =
        when (style) {
            AppDialogActionStyle.PRIMARY -> colors.primaryAction
            AppDialogActionStyle.NEUTRAL -> colors.neutralAction
            AppDialogActionStyle.DESTRUCTIVE -> colors.destructiveAction
        }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 统一的应用弹窗骨架。
 *
 * 弹窗固定使用白色内容面，确保 Root、更新、删除和退出等同类型弹窗不会随调用位置出现不同颜色；
 * 标题、正文、描边与按钮颜色均使用满足白底可读性的专用语义色，深色主题下也保持一致对比度。
 */
@Composable
fun AppAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = appDialogColors()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier =
            modifier
                .shadow(AppDialogTokens.ShadowElevation, AppDialogTokens.Shape, clip = false)
                .border(1.dp, colors.border, AppDialogTokens.Shape),
        title = {
            Text(
                title,
                color = colors.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            CompositionLocalProvider(LocalContentColor provides colors.body) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    content = content,
                )
            }
        },
        confirmButton = {
            if (actions != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actions()
                }
            }
        },
        containerColor = colors.container,
        iconContentColor = colors.primaryAction,
        titleContentColor = colors.title,
        textContentColor = colors.body,
        shape = AppDialogTokens.Shape,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/** 标准文本弹窗，确认、取消及危险操作共用相同结构和操作顺序。 */
@Composable
fun AppMessageDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    confirmStyle: AppDialogActionStyle = AppDialogActionStyle.PRIMARY,
    dismissLabel: String? = null,
    onDismissAction: (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val colors = appDialogColors()
    AppAlertDialog(
        title = title,
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = {
            Text(
                message,
                color = colors.body,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        },
        actions = {
            if (dismissLabel != null && onDismissAction != null) {
                AppDialogActionButton(
                    label = dismissLabel,
                    onClick = onDismissAction,
                    style = AppDialogActionStyle.NEUTRAL,
                )
            }
            AppDialogActionButton(
                label = confirmLabel,
                onClick = onConfirm,
                style = confirmStyle,
            )
        },
    )
}
