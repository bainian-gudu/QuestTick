package com.questtick.data

import android.content.SharedPreferences
import com.questtick.log.AppLog

/** SharedPreferences 同步提交工具；失败时统一记录日志。 */
internal fun SharedPreferences.Editor.commitSafely(
    tag: String,
    operation: String,
): Boolean =
    try {
        commit().also { success ->
            if (!success) AppLog.w(tag, "$operation commit returned false, data may not be persisted")
        }
    } catch (e: Exception) {
        AppLog.e(tag, "$operation commit failed", e)
        false
    }
