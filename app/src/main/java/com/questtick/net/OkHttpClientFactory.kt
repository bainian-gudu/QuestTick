package com.questtick.net

import com.questtick.core.security.PinningManager
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/** 网络层工厂，集中提供 CertificatePinner 与 OkHttpClient。 */
object OkHttpClientFactory {
    private const val MAX_CPU_PARALLELISM = 4
    private const val MAX_REQUESTS_PER_HOST = 3
    private const val CONNECTION_POOL_SIZE = MAX_REQUESTS_PER_HOST
    private const val CONNECTION_KEEP_ALIVE_SECONDS = 90L
    private const val DEFAULT_TIMEOUT_SECONDS = 10L
    private const val DEFAULT_CALL_TIMEOUT_SECONDS = 25L

    private val cpuCores = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_CPU_PARALLELISM)

    private val connectionPool =
        ConnectionPool(
            CONNECTION_POOL_SIZE,
            CONNECTION_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
        )

    private val dispatcher =
        Dispatcher().apply {
            // 控制并发上限，避免后台任务在弱网或风控场景下过度堆积请求。
            maxRequests = cpuCores * 2
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }

    fun provideCertificatePinner(): CertificatePinner = PinningManager.provideCertificatePinner()

    fun provideOkHttpClient(
        cache: Cache? = null,
        certificatePinner: CertificatePinner = provideCertificatePinner(),
    ): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // 重定向由 TrustedRedirects 逐跳校验，禁止 OkHttp 在校验前自动发出下一跳请求。
                .followRedirects(false)
                .followSslRedirects(false)
                // 传输重试由 TransportRetryPolicy 显式控制，禁止 OkHttp 隐式重发请求。
                .retryOnConnectionFailure(false)
                .fastFallback(true)
                .connectionPool(connectionPool)
                .dispatcher(dispatcher)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                // 仅对配置中的米游社核心 API 域名启用 Pinning；其它域名不受影响。
                .certificatePinner(certificatePinner)
        if (cache != null) builder.cache(cache)
        return builder.build()
    }
}
