package com.questtick.ui.screens

/** 二维码登录结果。 */
data class QRLoginResult(
    val cookie: String,
    val uid: String,
    val stoken: String,
    val stmid: String,
    val ltoken: String,
    val keepLogin: Boolean,
    val nickname: String = "",
)
