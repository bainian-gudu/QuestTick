package com.questtick.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questtick.ui.theme.LocalAppUiTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * 主分页器会预组合相邻页面；隐藏页面保留最后一次 UI 快照，
 * 但不持续订阅状态流，避免后台数据变化反复触发不可见页面重组。
 */
@Composable
fun <T> StateFlow<T>.collectAsStateWhenVisible(isVisible: Boolean): State<T> =
    if (isVisible) {
        collectAsStateWithLifecycle()
    } else {
        remember(this) { mutableStateOf(value) }
    }

/** 全屏纯白应用背景容器。 */
@Composable
fun GalaxyBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) { content() }
}

/** 全局通用卡片容器：白底、圆角、蓝色描边与轻阴影。 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    bg: Color? = null,
    content: @Composable () -> Unit,
) {
    val theme = LocalAppUiTheme.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(bg ?: theme.card)
            .border(1.dp, theme.hairline, shape),
    ) { content() }
}

/** 账号卡片共用的小型状态标签，保持主页与账号页相同的尺寸和颜色语义。 */
@Composable
fun AccountTagChip(
    text: String,
    color: Color? = null,
) {
    StatusBadge(text = text, color = color ?: MaterialTheme.colorScheme.primary)
}

/** 带蓝色图标徽章的分区标题。 */
@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    accent: Color? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = accent ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** 全屏页面顶部使用的居中大标题与副标题。 */
@Composable
fun PageTitle(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (trailing != null) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/** 空状态组件：圆形图标徽章 + 标题 + 说明。 */
@Composable
fun EmptyState(
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { badge() }
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** 细分割线。 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    val theme = LocalAppUiTheme.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.hairline),
    )
}

/** 本地数据预热期间使用的轻量骨架块。 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color),
    )
}

/** 通用骨架卡片，避免加载瞬间出现突兀空状态。 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    titleWidth: androidx.compose.ui.unit.Dp = 120.dp,
    lineCount: Int = 3,
) {
    PanelCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            SkeletonBlock(Modifier.width(titleWidth).height(16.dp))
            repeat(lineCount) { index ->
                Spacer(Modifier.height(if (index == 0) 14.dp else 10.dp))
                SkeletonBlock(
                    Modifier
                        .fillMaxWidth(if (index == lineCount - 1) 0.72f else 1f)
                        .height(12.dp),
                )
            }
        }
    }
}
