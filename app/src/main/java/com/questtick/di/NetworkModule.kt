package com.questtick.di

/** 提供网络传输、流式传输及相关应用级网络依赖。 */

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import com.questtick.net.HttpTransport
import com.questtick.net.OkHttpTransport
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideHttpCache(
        @ApplicationContext context: Context,
    ): Cache = Cache(File(context.cacheDir, HTTP_CACHE_DIR), HTTP_CACHE_SIZE_BYTES)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cache: Cache,
        certificatePinner: CertificatePinner,
    ): OkHttpClient = com.questtick.net.OkHttpClientFactory.provideOkHttpClient(cache, certificatePinner)

    @Provides
    @Singleton
    fun provideOkHttpTransport(okHttpClient: OkHttpClient): OkHttpTransport = OkHttpTransport(okHttpClient)

    @Provides
    @Singleton
    fun provideHttpTransport(transport: OkHttpTransport): HttpTransport = transport

    @Provides
    @Singleton
    fun provideStreamingHttpTransport(transport: OkHttpTransport): com.questtick.net.StreamingHttpTransport = transport

    private const val HTTP_CACHE_DIR = "http_cache"
    private const val HTTP_CACHE_SIZE_BYTES = 20L * 1024 * 1024
}
