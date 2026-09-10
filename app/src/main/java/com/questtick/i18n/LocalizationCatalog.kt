package com.questtick.i18n

import com.questtick.i18n.languages.enTranslations
import com.questtick.i18n.languages.jaTranslations
import com.questtick.i18n.languages.koTranslations
import com.questtick.i18n.languages.zhHansTranslations

internal data class Translation(
    val en: String,
    val ja: String,
    val ko: String,
)

/**
 * 中文 key 是稳定的业务文案标识；各语言文件必须保持相同 key 集合。
 * 缺失翻译回退到 key，测试会阻止语言文件静默缺项。
 */
internal val exactTranslations =
    zhHansTranslations.keys.associateWith { key ->
        Translation(
            en = enTranslations[key] ?: key,
            ja = jaTranslations[key] ?: key,
            ko = koTranslations[key] ?: key,
        )
    }
