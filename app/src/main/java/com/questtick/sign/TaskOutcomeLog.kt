package com.questtick.sign

internal data class TaskOutcomeLog(
    val account: String,
    val game: String,
    val success: Boolean,
    val skipped: Boolean,
    val message: String,
    val detail: String = "",
    val elapsedMs: Long = 0,
)
