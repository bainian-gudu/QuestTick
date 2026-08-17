package com.questtick.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/** 本地 DS 配置来源：Assets → 内置默认值。历史远程缓存不会再参与配置选择。 */
class LocalConfigProvider(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun selectedChannel(): DsConfigChannel =
        DsConfigChannel.parse(prefs.getString(KEY_CHANNEL, DsConfigChannel.STABLE.key))

    fun setSelectedChannel(channel: DsConfigChannel) {
        prefs.edit().putString(KEY_CHANNEL, channel.key).apply()
    }

    fun load(channel: DsConfigChannel = selectedChannel()): DsConfig {
        loadAssets(channel)?.let { return it }
        return DsConfig.Default
    }

    fun loadAssets(channel: DsConfigChannel = selectedChannel()): DsConfig? =
        try {
            appContext.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                DsConfig.parse(
                    raw = reader.readText(),
                    requireSignature = true,
                    selectedChannel = channel,
                    allowStableFallback = true,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load DS config from assets failed: ${e.message}")
            null
        }

    companion object {
        private const val TAG = "LocalConfigProvider"
        private const val PREFS_FILE = "signin_ds_config"
        private const val KEY_CHANNEL = "ds_config_channel"
        private const val ASSET_NAME = "ds_config.json"
    }
}
