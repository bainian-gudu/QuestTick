package com.questtick.log

import android.util.Log
import com.questtick.BuildConfig

/** 统一日志入口：Debug 输出完整日志，Release 仅保留 warn/error，禁止业务代码直接调用 android.util.Log。 */
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
        Log.w(normalizeTag(tag), message)
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        Log.w(normalizeTag(tag), message, throwable)
    }

    fun e(
        tag: String,
        message: String,
    ) {
        Log.e(normalizeTag(tag), message)
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        Log.e(normalizeTag(tag), message, throwable)
    }
}
