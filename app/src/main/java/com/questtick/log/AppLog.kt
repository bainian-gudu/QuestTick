package com.questtick.log

import android.util.Log
import com.questtick.BuildConfig
import com.questtick.sign.Mask

/**
 * 统一日志入口：Debug 输出完整日志，Release 仅保留 warn/error，禁止业务代码直接调用 android.util.Log。
 *
 * warn / error 在 Release 构建中也会写入 logcat，因此这两个级别统一经过 [Mask.sensitive] 脱敏，
 * 避免异常消息里夹带的 Cookie / Token / 授权码片段被完整输出。
 * v / d / i 仅在 Debug 构建输出，保留原文以便排查问题。
 */
object AppLog {
    private const val MAX_TAG_LENGTH = 23

    private fun normalizeTag(tag: String): String = tag.take(MAX_TAG_LENGTH)

    fun v(
        tag: String,
        message: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.v(normalizeTag(tag), message)
        }
    }

    fun d(
        tag: String,
        message: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(normalizeTag(tag), message)
        }
    }

    fun i(
        tag: String,
        message: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.i(normalizeTag(tag), message)
        }
    }

    fun w(
        tag: String,
        message: String,
    ) {
        Log.w(normalizeTag(tag), Mask.sensitive(message))
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        Log.w(normalizeTag(tag), Mask.sensitive(message), throwable)
    }

    fun e(
        tag: String,
        message: String,
    ) {
        Log.e(normalizeTag(tag), Mask.sensitive(message))
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        Log.e(normalizeTag(tag), Mask.sensitive(message), throwable)
    }
}
