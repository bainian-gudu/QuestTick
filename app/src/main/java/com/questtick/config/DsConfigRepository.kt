package com.questtick.config

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * DS 配置仓库。
 *
 * 启动时同步加载本地 assets/内置默认值。远程配置不参与运行时 DS 签名，避免启动或签到过程
 * 依赖远程配置服务。
 */
object DsConfigRepository {
    private val currentRef = AtomicReference(DsConfig.Default)

    val current: DsConfig
        get() = currentRef.get()

    fun initialize(context: Context) {
        val local = LocalConfigProvider(context)
        val config = local.load()
        currentRef.set(config)
    }

    fun selectedChannel(context: Context): DsConfigChannel = LocalConfigProvider(context).selectedChannel()

    fun setChannel(
        context: Context,
        channel: DsConfigChannel,
    ) {
        val local = LocalConfigProvider(context)
        local.setSelectedChannel(channel)
        currentRef.set(local.load(channel))
    }
}
