package com.questtick.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Repository 绑定模块。
 *
 * 当前各 Repository 已使用 @Inject constructor + @Singleton 直接绑定；保留该模块作为仓库层统一入口，
 * 后续如需替换为接口实现或测试 fake，可在这里集中声明 @Binds / @Provides。
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule
