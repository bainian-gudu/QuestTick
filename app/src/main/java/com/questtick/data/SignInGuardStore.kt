package com.questtick.data

import android.content.SharedPreferences
import android.util.Log

/** 后台签到保护状态：记录连续失败次数与自动暂停原因。 */
internal class SignInGuardStore(
    private val prefs: SharedPreferences,
) {
    fun getConsecutiveFailures(): Int = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)

    fun recordFailure(): Int {
        val next = (getConsecutiveFailures() + 1).coerceAtMost(999)
        val success =
            prefs.edit()
                .putInt(KEY_CONSECUTIVE_FAILURES, next)
                .commitSafely(TAG, "record sign-in failure")
        if (!success) {
            Log.w(TAG, "Failed to record sign-in failure count")
        }
        return next
    }

    fun resetFailures() {
        if (getConsecutiveFailures() == 0) return
        val success =
            prefs.edit()
                .putInt(KEY_CONSECUTIVE_FAILURES, 0)
                .remove(KEY_PAUSED_AT)
                .remove(KEY_PAUSE_REASON)
                .commitSafely(TAG, "reset sign-in failure count")
        if (!success) {
            Log.w(TAG, "Failed to reset sign-in failure count")
        }
    }

    fun markPaused(
        reason: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val success =
            prefs.edit()
                .putLong(KEY_PAUSED_AT, now)
                .putString(KEY_PAUSE_REASON, reason)
                .commitSafely(TAG, "mark sign-in paused")
        if (!success) {
            Log.w(TAG, "Failed to mark sign-in paused")
        }
    }

    companion object {
        private const val TAG = "SignInGuardStore"
        private const val KEY_CONSECUTIVE_FAILURES = "signin_guard_consecutive_failures"
        private const val KEY_PAUSED_AT = "signin_guard_paused_at"
        private const val KEY_PAUSE_REASON = "signin_guard_pause_reason"
    }
}
