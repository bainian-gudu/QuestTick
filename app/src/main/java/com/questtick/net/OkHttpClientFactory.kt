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
    private val cpuCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private val connectionPool =
        ConnectionPool(
            3,
            90,
            TimeUnit.SECONDS,
        )

    private val dispatcher =
        Dispatcher().apply {
            // 控制并发上限，避免后台任务在弱网或风控场景下过度堆积请求。
            maxRequests = cpuCores * 2
            maxRequestsPerHost = 3
        }

    fun provideCertificatePinner(): CertificatePinner = PinningManager.provideCertificatePinner()

    fun provideOkHttpClient(
        cache: Cache? = null,
        certificatePinner: CertificatePinner = provideCertificatePinner(),
    ): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
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
