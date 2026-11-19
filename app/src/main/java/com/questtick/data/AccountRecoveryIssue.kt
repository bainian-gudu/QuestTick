package com.questtick.data

/**
 * 无法解密或解析的账号恢复条目。
 *
 * 仅暴露 Room 明文字段中的稳定 ID 与显示名，不包含密文、Cookie、Token 或异常详情。
 */
data class AccountRecoveryIssue(
    val accountId: String,
    val label: String,
    val reason: Reason,
) {
    enum class Reason {
        DECRYPTION_FAILED,
        INVALID_FORMAT,
    }
}
