package com.questtick.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Worker 相关依赖模块；SignInRunnerFactory 已使用 @Inject constructor 直接绑定。 */
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule
