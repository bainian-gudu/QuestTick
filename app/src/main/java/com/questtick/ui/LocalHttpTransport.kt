package com.questtick.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.questtick.net.HttpTransport

/** 由 Hilt 在应用根节点提供；预览和 UI 测试可注入独立 Fake。 */
val LocalHttpTransport =
    staticCompositionLocalOf<HttpTransport> {
        error("HttpTransport is not provided")
    }

/** UI 异步资源流程使用的应用级错误入口。 */
val LocalAppErrorReporter = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }
