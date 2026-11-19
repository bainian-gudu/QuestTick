package com.questtick.sign

import com.questtick.BuildConfig
import com.questtick.data.AppSettings

/**
 * 米游币打卡功能是否生效。
 *
 * Debug 版始终开放便于调试；正式版只看用户在「实验功能」里的开关，避免正式包默认
 * 对每个人多发一次社区打卡请求。取值必须来自读到的设置快照，不能是进程级缓存：
 * 计划生成与执行往往跨进程、跨入口，缓存漏同步会让当天静默漏掉（或错误发出）打卡请求。
 */
fun mysCoinFeatureEnabled(settings: AppSettings): Boolean = coinFeatureEnabled(BuildConfig.DEBUG, settings.mysCoinEnabled)

/** 纯函数形式，便于在单元测试里覆盖 Debug / 正式版两种取值。 */
internal fun coinFeatureEnabled(
    debugBuild: Boolean,
    enabledBySetting: Boolean,
): Boolean = debugBuild || enabledBySetting
