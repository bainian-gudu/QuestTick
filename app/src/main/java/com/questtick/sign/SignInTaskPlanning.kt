package com.questtick.sign

// 生成米游社和云游戏签到任务绑定关系的辅助模型。

import com.questtick.data.Account

internal data class CloudGameBinding(
    val game: CloudSignIn.GameConfig,
    val tokenOf: (Account) -> String,
    val webCookieOf: (Account) -> String,
    val hasKeepLogin: (Account) -> Boolean,
    val updateToken: (Account, String) -> Account,
)

internal fun Account.hasCloudTask(binding: CloudGameBinding): Boolean =
    (binding.tokenOf(this).isNotBlank() || binding.hasKeepLogin(this)) &&
        isGameSelected(binding.game.key)

internal fun Account.hasMysTaskCredential(): Boolean = mysCookie.isNotBlank() || hasMysKeepLogin

internal fun Account.selectedMysGames(): List<MysSignIn.GameConfig> =
    if (hasMysTaskCredential()) {
        MysSignIn.GAMES.values.filter { isGameSelected(it.key) }
    } else {
        emptyList()
    }

internal fun Account.selectedCloudBindings(bindings: List<CloudGameBinding>): List<CloudGameBinding> = bindings.filter { hasCloudTask(it) }
