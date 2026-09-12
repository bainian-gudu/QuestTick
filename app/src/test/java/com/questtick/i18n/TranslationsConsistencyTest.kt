package com.questtick.i18n

import com.questtick.i18n.languages.enTranslations
import com.questtick.i18n.languages.jaTranslations
import com.questtick.i18n.languages.koTranslations
import com.questtick.i18n.languages.zhHansTranslations
import com.questtick.i18n.languages.zhHantTranslations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多语言词典一致性回归测试。
 *
 * 当前 i18n 采用「UI 内写中文 → 运行时查词典替换」的实现（见 [localizeText]），
 * 这意味着新增或修改一句 UI 文案时，如果忘记同步 5 份词典，不会有任何编译错误，
 * 只会静默回退显示中文。本测试把这类错误提前到 CI 阶段。
 *
 * 注意：这是缓解措施而非根治。彻底方案是迁移到 Android 标准资源体系
 * （stringResource + values-zh-rTW / values-en / ...），届时本测试可删除。
 */
class TranslationsConsistencyTest {
    private val dictionaries =
        mapOf(
            "zhHans" to zhHansTranslations,
            "zhHant" to zhHantTranslations,
            "en" to enTranslations,
            "ja" to jaTranslations,
            "ko" to koTranslations,
        )

    @Test
    fun allDictionariesAreLoaded() {
        dictionaries.forEach { (name, dict) ->
            assertTrue("$name 词典不应为空", dict.isNotEmpty())
        }
    }

    @Test
    fun allDictionariesShareTheSameKeySet() {
        val (baseName, base) = dictionaries.entries.first()

        dictionaries.forEach { (name, dict) ->
            val missing = base.keys - dict.keys
            val extra = dict.keys - base.keys

            assertTrue(
                "$name 缺少 $baseName 中的词条: ${missing.take(MAX_REPORTED_KEYS)}",
                missing.isEmpty(),
            )
            assertTrue(
                "$name 含有 $baseName 中不存在的词条: ${extra.take(MAX_REPORTED_KEYS)}",
                extra.isEmpty(),
            )
            assertEquals("$name 词条数量应与 $baseName 一致", base.size, dict.size)
        }
    }

    @Test
    fun dictionariesContainNoBlankKeysOrValues() {
        dictionaries.forEach { (name, dict) ->
            dict.forEach { (key, value) ->
                assertTrue("$name 词条 key 不应为空白: '$key'", key.isNotBlank())
                assertTrue("$name 词条 '$key' 的译文不应为空白", value.isNotBlank())
            }
        }
    }

    /**
     * 占位符数量必须跨语言一致。
     *
     * [localizeText] 的动态分支使用 `template.format(*arguments)` 且外层包了 runCatching，
     * 一旦某个语言的模板少写了 %s，format 会抛异常并被吞掉，最终静默回退成中文。
     */
    @Test
    fun formatSpecifierCountsMatchAcrossLanguages() {
        val (baseName, base) = dictionaries.entries.first()
        val mismatches = mutableListOf<String>()

        base.keys.forEach { key ->
            val expected = FORMAT_SPECIFIER.findAll(base.getValue(key)).count()
            dictionaries.forEach { (name, dict) ->
                val actual = FORMAT_SPECIFIER.findAll(dict.getValue(key)).count()
                if (actual != expected) {
                    mismatches += "$name['$key'] 占位符 $actual 个，$baseName 为 $expected 个"
                }
            }
        }

        assertTrue(
            "占位符数量不一致（共 ${mismatches.size} 处）:\n" + mismatches.take(MAX_REPORTED_KEYS).joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    private companion object {
        const val MAX_REPORTED_KEYS = 10

        /** 匹配 %s / %d / %f，以及 %1$s 这样的位置占位符。 */
        val FORMAT_SPECIFIER = Regex("%(?:\\d+\\$)?[sdf]")
    }
}
