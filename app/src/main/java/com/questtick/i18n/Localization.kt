package com.questtick.i18n

import com.questtick.i18n.languages.enTranslations
import com.questtick.i18n.languages.jaTranslations
import com.questtick.i18n.languages.koTranslations
import com.questtick.i18n.languages.zhHansTranslations
import com.questtick.i18n.languages.zhHantTranslations

/**
 * 统一从语言词典读取静态译文和动态模板。
 * 用户自定义名称、奖励名称及服务端原文无法确认语义时保持原样。
 */
fun localizeText(
    text: String,
    language: AppLanguage,
): String {
    if (language == AppLanguage.SIMPLIFIED_CHINESE || language == AppLanguage.SYSTEM) return text
    dictionaryValue(text, language)?.let { return it }
    localizeDynamicText(text, language)?.let { return it }
    return text
}

/** 语言文件是唯一译文来源；这里仅按语言选择对应词典。 */
private fun dictionaryValue(
    key: String,
    language: AppLanguage,
): String? =
    when (language) {
        AppLanguage.TRADITIONAL_CHINESE -> zhHantTranslations[key]
        AppLanguage.ENGLISH -> enTranslations[key]
        AppLanguage.JAPANESE -> jaTranslations[key]
        AppLanguage.KOREAN -> koTranslations[key]
        AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM -> zhHansTranslations[key]
    }

private fun formatDictionary(
    key: String,
    language: AppLanguage,
    vararg arguments: Any?,
): String? = dictionaryValue(key, language)?.let { template -> runCatching { template.format(*arguments) }.getOrNull() }

@Suppress("detekt:MagicNumber", "detekt:LongMethod", "detekt:CyclomaticComplexMethod", "detekt:ReturnCount")
private fun localizeDynamicText(
    text: String,
    language: AppLanguage,
): String? {
    Regex("^(\\d+) 项任务$").matchEntire(text)?.let { return formatDictionary("%s 项任务", language, it.groupValues[1]) }
    Regex("^总占用 (.+)$").matchEntire(text)?.let { return formatDictionary("总占用 %s", language, it.groupValues[1]) }
    Regex("^(\\d+) 条日志$").matchEntire(text)?.let { return formatDictionary("%s 条日志", language, it.groupValues[1]) }
    Regex("^累计签到 (\\d+) 天$").matchEntire(text)?.let {
        return formatDictionary("累计签到 %s 天", language, it.groupValues[1])
    }
    Regex("^本月 (\\d+) 天$").matchEntire(text)?.let { return formatDictionary("本月 %s 天", language, it.groupValues[1]) }
    Regex("^连续 (\\d+) 天$").matchEntire(text)?.let { return formatDictionary("连续 %s 天", language, it.groupValues[1]) }
    Regex("^(\\d+) 天$").matchEntire(text)?.let { return formatDictionary("%s 天", language, it.groupValues[1]) }
    Regex("^(\\d+)项待确认$").matchEntire(text)?.let { return formatDictionary("%s项待确认", language, it.groupValues[1]) }
    Regex("^(\\d+)项失败 (\\d+)项待确认$").matchEntire(text)?.let {
        return formatDictionary("%s项失败 %s项待确认", language, it.groupValues[1], it.groupValues[2])
    }
    Regex("^版本 (.+)$").matchEntire(text)?.let { return formatDictionary("版本 %s", language, it.groupValues[1]) }
    Regex("^发现新版本 (.+)$").matchEntire(text)?.let { return formatDictionary("发现新版本 %s", language, it.groupValues[1]) }
    Regex("^正在下载更新 (.+)$").matchEntire(text)?.let { return formatDictionary("正在下载更新 %s", language, it.groupValues[1]) }
    Regex("^每天 (\\d{2}:\\d{2})$").matchEntire(text)?.let {
        return formatDictionary("每天 %s", language, it.groupValues[1])
    }
    Regex("^已签(\\d+)$").matchEntire(text)?.let { return formatDictionary("已签%s", language, it.groupValues[1]) }
    Regex("^待确认(\\d+)$").matchEntire(text)?.let { return formatDictionary("待确认%s", language, it.groupValues[1]) }
    Regex("^(成功|失败|已签到|待确认|跳过) (\\d+)$").matchEntire(text)?.let {
        return formatDictionary("${it.groupValues[1]} %s", language, it.groupValues[2])
    }
    Regex("^已启用 (\\d+)/(\\d+)$").matchEntire(text)?.let {
        return formatDictionary("已启用 %s/%s", language, it.groupValues[1], it.groupValues[2])
    }
    Regex("^已完成 (\\d+) / (\\d+)$").matchEntire(text)?.let {
        return formatDictionary("已完成 %s / %s", language, it.groupValues[1], it.groupValues[2])
    }
    Regex("^还有 (\\d+) 条，前往「记录」查看全部$").matchEntire(text)?.let {
        return formatDictionary("还有 %s 条，前往「记录」查看全部", language, it.groupValues[1])
    }
    Regex("^可用 (\\d+) 个，需处理 (\\d+) 个$").matchEntire(text)?.let {
        return formatDictionary("可用 %s 个，需处理 %s 个", language, it.groupValues[1], it.groupValues[2])
    }
    Regex("^(.+) · 已启用 (\\d+)/(\\d+) 个账号 · (\\d+) 款游戏$").matchEntire(text)?.let {
        return formatDictionary(
            "%s · 已启用 %s/%s 个账号 · %s 款游戏",
            language,
            it.groupValues[1],
            it.groupValues[2],
            it.groupValues[3],
            it.groupValues[4],
        )
    }
    Regex("^(\\d+) 款游戏可配置$").matchEntire(text)?.let {
        return formatDictionary("%s 款游戏可配置", language, it.groupValues[1])
    }
    Regex("^共 (\\d+) 条日志$").matchEntire(text)?.let { return formatDictionary("共 %s 条日志", language, it.groupValues[1]) }
    Regex("^(\\d+)/(\\d+) 条日志$").matchEntire(text)?.let {
        return formatDictionary("%s/%s 条日志", language, it.groupValues[1], it.groupValues[2])
    }
    Regex("^共 (\\d+) 条 · ✓(\\d+)  ⚠(\\d+)  ✗(\\d+)  ℹ(\\d+)  DBG(\\d+)$").matchEntire(text)?.let {
        return formatDictionary(
            "共 %s 条 · ✓%s  ⚠%s  ✗%s  ℹ%s  DBG%s",
            language,
            it.groupValues[1],
            it.groupValues[2],
            it.groupValues[3],
            it.groupValues[4],
            it.groupValues[5],
            it.groupValues[6],
        )
    }
    Regex("^共 (\\d+) 条记录$").matchEntire(text)?.let { return formatDictionary("共 %s 条记录", language, it.groupValues[1]) }
    Regex("^全部 (\\d+)$").matchEntire(text)?.let { return formatDictionary("全部 %s", language, it.groupValues[1]) }
    Regex("^共 (.+)$").matchEntire(text)?.let { return formatDictionary("共 %s", language, it.groupValues[1]) }
    Regex("^已自定义：(.+)$").matchEntire(text)?.let { return formatDictionary("已自定义：%s", language, it.groupValues[1]) }
    Regex("^已启用：(.+)$").matchEntire(text)?.let { return formatDictionary("已启用：%s", language, it.groupValues[1]) }
    Regex("^(云原神|云崩铁) (.+)$").matchEntire(text)?.let {
        return formatDictionary("${it.groupValues[1]} %s", language, it.groupValues[2])
    }
    Regex("^当前：云原神 (.+) / 云崩铁 (.+)$").matchEntire(text)?.let {
        return formatDictionary(
            "当前：云原神 %s / 云崩铁 %s",
            language,
            it.groupValues[1],
            it.groupValues[2],
        )
    }
    Regex("^暂无(.+)日志$").matchEntire(text)?.let {
        return formatDictionary("暂无%s日志", language, localizeText(it.groupValues[1], language))
    }
    Regex("^(.+)：(\\d+)/(\\d+) 条 · (\\d+) 组$").matchEntire(text)?.let {
        return formatDictionary(
            "%s：%s/%s 条 · %s 组",
            language,
            localizeText(it.groupValues[1], language),
            it.groupValues[2],
            it.groupValues[3],
            it.groupValues[4],
        )
    }
    Regex("^(.+)中搜索“(.+)”：(\\d+)/(\\d+) 条 · (\\d+) 组$").matchEntire(text)?.let {
        return formatDictionary(
            "%s中搜索“%s”：%s/%s 条 · %s 组",
            language,
            localizeText(it.groupValues[1], language),
            it.groupValues[2],
            it.groupValues[3],
            it.groupValues[4],
            it.groupValues[5],
        )
    }
    Regex("^删除(.+)$").matchEntire(text)?.let { return formatDictionary("删除%s", language, it.groupValues[1]) }
    Regex("^打卡后米游币：(.*?) · 本次获取：(.*)$").matchEntire(text)?.let {
        return formatDictionary("打卡后米游币：%s · 本次获取：%s", language, it.groupValues[1], it.groupValues[2])
    }
    Regex("^打卡后米游币：(.*)$").matchEntire(text)?.let {
        return formatDictionary("打卡后米游币：%s", language, it.groupValues[1])
    }
    Regex("^本次获取：(.*)$").matchEntire(text)?.let {
        return formatDictionary("本次获取：%s", language, it.groupValues[1])
    }
    Regex("^请打开「米游社」App → 点击左上角扫码图标\\n扫描上方二维码并在手机端确认登录(.*)$", RegexOption.DOT_MATCHES_ALL)
        .matchEntire(text)
        ?.let {
            return formatDictionary(
                "请打开「米游社」App → 点击左上角扫码图标\\n扫描上方二维码并在手机端确认登录%s",
                language,
                it.groupValues[1],
            )
        }
    Regex("^📱 检测到 (.*?) 设备，建议同时开启自启动权限：\\n(.*)$", RegexOption.DOT_MATCHES_ALL)
        .matchEntire(text)
        ?.let {
            return formatDictionary(
                "📱 检测到 %s 设备，建议同时开启自启动权限：\\n%s",
                language,
                it.groupValues[1],
                localizeText(it.groupValues[2], language),
            )
        }
    Regex("^当前版本：(.*?)\\n新版本：(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(text)?.let {
        return formatDictionary("当前版本：%s\\n新版本：%s", language, it.groupValues[1], it.groupValues[2])
    }
    Regex("^签到完成：成功(\\d+) 已签(\\d+) 失败(\\d+) 待确认(\\d+) 跳过(\\d+)$").matchEntire(text)?.let {
        return formatDictionary(
            "签到完成：成功%s 已签%s 失败%s 待确认%s 跳过%s",
            language,
            it.groupValues[1],
            it.groupValues[2],
            it.groupValues[3],
            it.groupValues[4],
            it.groupValues[5],
        )
    }
    Regex("^签到已跳过：(.*)$").matchEntire(text)?.let {
        return formatDictionary("签到已跳过：%s", language, it.groupValues[1])
    }
    Regex("^(签到进行中|运行日志|错误日志|其它日志) · (.+)$").matchEntire(text)?.let {
        return formatDictionary("${it.groupValues[1]} · %s", language, it.groupValues[2])
    }
    Regex("^当前阶段：(.+)$").matchEntire(text)?.let {
        return formatDictionary("当前阶段：%s", language, localizeText(it.groupValues[1], language))
    }
    Regex("^当前：(.+)$").matchEntire(text)?.let { return formatDictionary("当前：%s", language, it.groupValues[1]) }
    Regex("^自定义 (.+)$").matchEntire(text)?.let { return formatDictionary("自定义 %s", language, it.groupValues[1]) }
    Regex("^默认 (.+)$").matchEntire(text)?.let { return formatDictionary("默认 %s", language, it.groupValues[1]) }
    Regex("^已下载 (.+)$").matchEntire(text)?.let { return formatDictionary("已下载 %s", language, it.groupValues[1]) }
    Regex("^(.+)扫码登录$").matchEntire(text)?.let {
        return formatDictionary("%s扫码登录", language, localizeText(it.groupValues[1], language))
    }
    Regex("^(.+) · (原神|崩坏：星穹铁道|崩坏3|崩坏学园2|未定事件簿|绝区零|云原神|云崩铁)$")
        .matchEntire(text)
        ?.let { return "${it.groupValues[1]} · ${localizeText(it.groupValues[2], language)}" }
    Regex("^确定删除「(.+)」吗？该操作不可恢复。$").matchEntire(text)?.let {
        return formatDictionary("确定删除「%s」吗？该操作不可恢复。", language, it.groupValues[1])
    }
    Regex("^「(.+)」的本地凭证无法解密或格式已损坏。(.+)$").matchEntire(text)?.let {
        return formatDictionary(
            "「%s」的本地凭证无法解密或格式已损坏。%s",
            language,
            it.groupValues[1],
            localizeText(it.groupValues[2], language),
        )
    }
    Regex("^（等待第(\\d+)次尝试）$").matchEntire(text)?.let {
        return formatDictionary("（等待第%s次尝试）", language, it.groupValues[1])
    }
    Regex("^邮件待发送(.*)$").matchEntire(text)?.let {
        val suffix = it.groupValues[1].let { value -> localizeDynamicText(value, language) ?: value }
        return formatDictionary("邮件待发送%s", language, suffix)
    }
    Regex("^(保存失败|发送失败|获取失败|检查更新失败|打开安装器失败|下载更新失败|签到失败|清空失败)[:：]\\s*(.+)$")
        .matchEntire(text)
        ?.let {
            val sourceKey = "${it.groupValues[1]}：%s"
            return formatDictionary(sourceKey, language, it.groupValues[2])
                ?: "${localizeText(it.groupValues[1], language)}: ${it.groupValues[2]}"
        }
    localizeDictionaryTemplate(text, language)?.let { return it }
    return null
}

/**
 * 兜底匹配词典中的 %s 模板，覆盖运行时日志和页面插值文案。
 * 只对完整匹配的模板进行替换，未知账号名、奖励名和服务端原文保持原样。
 */
private data class CompiledDictionaryTemplate(
    val pattern: Regex,
    val template: String,
)

private val compiledDictionaryTemplates: Map<AppLanguage, List<CompiledDictionaryTemplate>> by lazy {
    AppLanguage.entries.associateWith { language ->
        val dictionary =
            when (language) {
                AppLanguage.TRADITIONAL_CHINESE -> zhHantTranslations
                AppLanguage.ENGLISH -> enTranslations
                AppLanguage.JAPANESE -> jaTranslations
                AppLanguage.KOREAN -> koTranslations
                AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.SYSTEM -> zhHansTranslations
            }
        dictionary
            .asSequence()
            .filter { (key, _) -> key.contains("%s") }
            .sortedByDescending { (key, _) -> key.length }
            .map { (key, template) ->
                val parts = key.split("%s")
                val pattern =
                    buildString {
                        append('^')
                        parts.forEachIndexed { index, part ->
                            append(Regex.escape(part))
                            if (index < parts.lastIndex) append("([\\s\\S]*?)")
                        }
                        append('$')
                    }
                CompiledDictionaryTemplate(pattern.toRegex(), template)
            }.toList()
    }
}

@Suppress("detekt:SpreadOperator")
private fun localizeDictionaryTemplate(
    text: String,
    language: AppLanguage,
): String? =
    compiledDictionaryTemplates[language]
        ?.asSequence()
        ?.mapNotNull { compiled ->
            compiled.pattern.matchEntire(text)?.let { match ->
                runCatching {
                    compiled.template.format(*match.groupValues.drop(1).toTypedArray())
                }.getOrNull()
            }
        }?.firstOrNull()
