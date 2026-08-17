package com.questtick.sign

import com.questtick.core.runCatchingCancellable
import com.questtick.net.HttpTransport
import com.questtick.net.getIdempotent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从 App Store 查询米游社最新版本号，用作 x-rpc-app_version 候选值。 */
object MysAppVersionFetcher {
    private const val LOOKUP_URL = "https://itunes.apple.com/cn/lookup?id=1470182559"
    private val VERSION_PATTERN = Regex("^\\d+(?:\\.\\d+){1,3}$")

    suspend fun fetchLatest(httpTransport: HttpTransport): Result<String> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val response =
                    httpTransport.getIdempotent(
                        LOOKUP_URL,
                        headers =
                            mapOf(
                                "User-Agent" to Endpoints.effectiveUserAgent(""),
                                "Accept" to "application/json, text/plain, */*",
                                "Accept-Language" to "zh-CN,zh;q=0.9",
                            ),
                    )
                if (response.code !in 200..299) {
                    error("HTTP ${response.code}")
                }

                val results = response.json().optJSONArray("results")
                val version =
                    results
                        ?.optJSONObject(0)
                        ?.optString("version")
                        .orEmpty()
                        .trim()

                if (!VERSION_PATTERN.matches(version)) {
                    error("未解析到有效版本号")
                }
                version
            }
        }
}
