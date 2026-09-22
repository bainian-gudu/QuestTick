package com.questtick.sign

import com.questtick.data.Account

internal data class CloudGameBinding(
    val game: CloudSignIn.GameConfig,
    val tokenOf: (Account) -> String,
    val webCookieOf: (Account) -> String,
    val hasKeepLogin: (Account) -> Boolean,
    val updateToken: (Account, String) -> Account,
)
