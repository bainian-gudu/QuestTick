package com.questtick.di

/** 提供证书固定和 Root 环境检测依赖。 */

import com.questtick.core.security.PinningManager
import com.questtick.security.AndroidRootEnvironmentChecker
import com.questtick.security.RootEnvironmentChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.CertificatePinner
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner = PinningManager.provideCertificatePinner()

    @Provides
    @Singleton
    fun provideRootEnvironmentChecker(implementation: AndroidRootEnvironmentChecker): RootEnvironmentChecker = implementation
}
