package com.questtick.ui.screens

import com.questtick.i18n.localizedText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.components.consumeHorizontalPageSwipe
import com.questtick.ui.theme.AppMotion

/** 设置页导航复用组件：统一子页面转场、返回处理和标题布局。 */

@Composable
internal fun SettingsPageOverlay(
    visible: Boolean,
    consumeHorizontalGestures: Boolean = true,
    onExitFinished: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = AppMotion.screenEnter(),
        exit = AppMotion.screenExit(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (consumeHorizontalGestures) Modifier.consumeHorizontalPageSwipe() else Modifier),
        ) {
            DisposableEffect(Unit) {
                onDispose { onExitFinished() }
            }
            content()
        }
    }
}

@Composable
internal fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onBack)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickableNoRipple(onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = localizedText("返回"), tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            title,
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.size(40.dp))
    }
}
