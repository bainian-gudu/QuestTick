package com.questtick.repository.run

/** 签到完成后异步通知和邮件投递使用的数据模型。 */

enum class PostRunActionType {
    NOTIFICATION,
    EMAIL,
}

data class PostRunActionRequest(
    val type: PostRunActionType,
)
