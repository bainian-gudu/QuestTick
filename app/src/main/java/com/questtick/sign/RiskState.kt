package com.questtick.sign

import com.questtick.data.FailureCategory
import com.questtick.data.RunRecord

/** 风控状态分类，用于日志标记与后台任务自动暂停判断。 */
enum class RiskState {
    NORMAL,
    CAPTCHA,
    SMS,
    BLOCKED,
    ;

    companion object {
        fun fromRecord(record: RunRecord): RiskState {
            var state = NORMAL
            for (result in record.results) {
                val next = fromFailureCategory(result.failureCategory)
                if (next.priority > state.priority) state = next
            }
            return state
        }

        private fun fromFailureCategory(category: FailureCategory): RiskState =
            when (category) {
                FailureCategory.CAPTCHA_REQUIRED -> CAPTCHA
                FailureCategory.SMS_REQUIRED -> SMS
                FailureCategory.RATE_LIMITED -> BLOCKED
                else -> NORMAL
            }

        private val RiskState.priority: Int
            get() =
                when (this) {
                    NORMAL -> 0
                    CAPTCHA -> 1
                    SMS -> 2
                    BLOCKED -> 3
                }
    }
}
