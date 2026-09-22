package com.questtick.ui.screens

/** 云游戏扫码登录结果。 */
data class CloudQRLoginResult(
    val comboToken: String,
    /** 保持登录时保存网页登录 Cookie，用于后续续期 combo_token。 */
    val cookieHeader: String,
    /** true 表示保存网页登录态并支持后续续期；false 表示仅保存当前 token。 */
    val keepLogin: Boolean,
)
