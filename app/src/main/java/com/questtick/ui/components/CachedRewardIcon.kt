package com.questtick.ui.components

/*
 * 奖励图标：带磁盘缓存的异步图标加载，加载中与失败时降级为占位图形，避免重复网络请求。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.questtick.data.RewardIconCache
import com.questtick.net.TrustedUrlPolicy
import com.questtick.ui.LocalHttpTransport
import com.questtick.ui.LocalAppErrorReporter
import java.io.File

/** 从本地缓存加载奖励图标；缓存缺失时下载一次并写入本地。 */
@Composable
fun CachedRewardIcon(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val httpTransport = LocalHttpTransport.current
    val reportError = LocalAppErrorReporter.current
    val trustedUrl = remember(url) { url.takeIf(TrustedUrlPolicy::isRewardIconUrl).orEmpty() }
    var localFile by remember(trustedUrl) { mutableStateOf<File?>(null) }

    // 后台读取/预取到本地持久缓存，不在 Composable 组合阶段直接访问文件系统。
    LaunchedEffect(trustedUrl) {
        localFile = null
        if (trustedUrl.isBlank()) {
            if (url.isNotBlank()) reportError("奖励图片加载", "奖励图片地址不受信任或为空: $url")
        } else {
            localFile = RewardIconCache.cachedFile(context, trustedUrl)
                ?: RewardIconCache.getOrDownload(context, trustedUrl, httpTransport) { detail ->
                    reportError("奖励图片加载", detail)
                }
            if (localFile == null) reportError("奖励图片加载", "奖励图片下载失败: $trustedUrl")
        }
    }

    if (localFile == null) {
        Box(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.CardGiftcard,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    } else {
        AsyncImage(
            model = localFile,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}
