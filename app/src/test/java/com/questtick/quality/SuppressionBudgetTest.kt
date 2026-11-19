package com.questtick.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * detekt 抑制预算。
 *
 * 这套代码的 detekt 基线为空、`maxIssues = 0`，于是「让门禁通过」最省力的做法就是就地加一条
 * `detekt:` 抑制。单条抑制看不出问题，累积起来等于把规则关掉：规则名义开启，实际只在没被
 * 豁免的地方生效。这里把总量钉成预算，新增抑制必须显式上调并说明理由，而不是顺手加一行。
 */
class SuppressionBudgetTest {
    @Test
    fun `detekt suppressions stay within budget`() {
        val sources = locateSources()
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val suppressions =
            files.sumOf { file ->
                Regex("detekt:[A-Za-z]+").findAll(file.readText()).count()
            }
        val filesWithSuppression =
            files.count { file -> file.readText().contains("detekt:") }

        assertTrue(
            "detekt 抑制已达 $suppressions 条 / $filesWithSuppression 个文件，" +
                "超出预算 ${SUPPRESSION_BUDGET} 条 / ${FILE_BUDGET} 个文件。" +
                "优先修问题；确需新增请同步上调预算并在提交说明里写清原因。",
            suppressions <= SUPPRESSION_BUDGET && filesWithSuppression <= FILE_BUDGET,
        )
    }

    private fun locateSources(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，单元测试的工作目录可能不是模块根目录")
    }

    private companion object {
        /** 2026-11-16 拆分巨型类后的实际值；只允许随「修掉抑制」而下调。 */
        const val SUPPRESSION_BUDGET = 242
        const val FILE_BUDGET = 109
    }
}
