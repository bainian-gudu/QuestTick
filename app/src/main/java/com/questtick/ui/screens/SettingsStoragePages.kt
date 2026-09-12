package com.questtick.ui.screens

/*
 * 存储设置页：分类统计缓存（奖励图、网络响应、更新包、导出文件等）占用并支持清理，附开源许可声明。
 */
import com.questtick.i18n.localizedText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import com.questtick.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questtick.ui.components.GalaxyBackground
import com.questtick.ui.components.clickableNoRipple
import com.questtick.ui.theme.TextSecondary
import com.questtick.ui.vm.CacheCategory
import com.questtick.ui.vm.CacheStorageState

/** 设置页中与缓存和许可证相关的独立页面。 */

@Composable
@Suppress("detekt:LongMethod", "detekt:FunctionNaming")
internal fun CacheDetailPage(
    state: CacheStorageState,
    onRefresh: () -> Unit,
    onClearCategory: (CacheCategory) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
) {
    val busy = state.busy
    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            DetailTopBar("缓存管理", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "cache_summary") {
                    SettingsActionCard(
                        title = "总占用 ${formatCacheSize(state.totalBytes)}",
                        value =
                            when {
                                state.loading -> "扫描中…"
                                state.scanCompleted -> "扫描完成"
                                else -> "重新扫描"
                            },
                        enabled = !busy,
                    ) { onRefresh() }
                }
                items(
                    count = state.items.size,
                    key = { index -> state.items[index].category.name },
                ) { index ->
                    val item = state.items[index]
                    val clearingThis = state.clearing == item.category || state.clearingAll
                    val clearedThis = state.clearedCategory == item.category
                    val canClear = item.sizeBytes > 0L && !busy
                    SettingsCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cacheCategoryTitle(item.category), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(4.dp))
                                Text(cacheCategoryDescription(item.category), fontSize = 12.sp, lineHeight = 18.sp, color = TextSecondary)
                                Spacer(Modifier.height(4.dp))
                                Text(formatCacheSize(item.sizeBytes), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onClearCategory(item.category) }, enabled = canClear) {
                                Text(
                                    when {
                                        clearingThis -> "清理中…"
                                        clearedThis -> "清理完成"
                                        else -> "清理"
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "cache_safety_note") {
                    SettingsInfoCard(title = "保留的数据", text = "不会删除账号、Cookie、设置、签到记录、运行日志、ACT_ID 和设备 ID。")
                }
                item(key = "cache_clear_all") {
                    PrimaryButton(
                        text =
                            when {
                                state.clearingAll -> "清理中…"
                                state.clearAllCompleted -> "清理完成"
                                else -> "清理全部缓存"
                            },
                        enabled = state.totalBytes > 0L && !busy,
                        onClick = onClearAll,
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

private fun cacheCategoryTitle(category: CacheCategory): String =
    when (category) {
        CacheCategory.REWARD_IMAGES -> "奖励图片"
        CacheCategory.NETWORK_RESPONSES -> "网络响应"
        CacheCategory.APP_UPDATES -> "应用更新"
        CacheCategory.LOG_EXPORTS -> "日志导出文件"
        CacheCategory.OTHER_TEMP -> "其他临时文件"
    }

private fun cacheCategoryDescription(category: CacheCategory): String =
    when (category) {
        CacheCategory.REWARD_IMAGES -> "签到奖励图标及图片加载缓存"
        CacheCategory.NETWORK_RESPONSES -> "接口响应缓存，清理后会按需重新下载"
        CacheCategory.APP_UPDATES -> "已下载的安装包和更新检查缓存"
        CacheCategory.LOG_EXPORTS -> "分享日志时生成的临时导出文件"
        CacheCategory.OTHER_TEMP -> "应用产生且不属于以上分类的临时文件"
    }

@Composable
internal fun LicenseDetailDialog(
    licenseText: String,
    onDismiss: () -> Unit,
) {
    val licenseBody = remember(licenseText) { licenseText.trimEnd() }
    BackHandler(enabled = true, onBack = onDismiss)
    GalaxyBackground {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            ),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    "GNU General Public License v3.0",
                    modifier = Modifier.padding(horizontal = 48.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                )
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.18f))
                        .clickableNoRipple(onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = localizedText("关闭"), tint = MaterialTheme.colorScheme.onBackground)
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                item(key = "license_body") {
                    SelectionContainer {
                        Text(
                            licenseBody,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}
