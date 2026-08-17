package com.questtick.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 生成器。
 *
 * 主要覆盖三类场景：
 *  - startup：冷启动到首帧
 *  - mainJourney：Tab 切换、列表滚动、首页操作
 *  - criticalUserJourney：账号编辑、扫码登录、设置与日志等高频路径
 *
 * 执行：
 * ./gradlew :baselineprofile:connectedAndroidTest \
 *   -P android.testInstrumentationRunnerArguments.class=com.questtick.baselineprofile.BaselineProfileGenerator
 *
 * 输出：
 * app/src/main/baseline-prof.txt
 * app/src/main/startup-prof.txt
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    // === 1. 启动路径 ===
    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = "com.questtick",
        includeInStartupProfile = true
    ) {
        repeat(3) {
            pressHome()
            startActivityAndWait()
            waitForIdleAndContent()
            // 等待首页加载态结束，避免把过渡状态写入基线。
            device.wait(Until.gone(By.text("加载中")), 2000)
            device.waitForIdle()
        }
    }

    // === 2. 主流程路径 ===
    @Test
    fun mainJourney() = baselineProfileRule.collect(
        packageName = "com.questtick"
    ) {
        pressHome()
        startActivityAndWait()
        waitForIdleAndContent()

        // 多轮 Tab 切换，覆盖主页到设置页之间的常见重组路径。
        repeat(2) {
            listOf("账号", "记录", "日志", "设置", "主页").forEach { tab ->
                clickTextOrDesc(tab)
                device.waitForIdle()
                // 列表页上下滚动，覆盖列表复用与回收逻辑。
                flingDownUp()
            }
        }

        // 点击首页签到按钮，覆盖主页上的主要交互与 ViewModel 热路径。
        clickTextOrDesc("立即全部签到")
        device.waitForIdle()
        Thread.sleep(500)

        // 展开与收起最近签到卡片，覆盖主要动画路径。
        repeat(2) {
            clickTextOrDesc("最近签到")
            device.waitForIdle()
            Thread.sleep(200)
        }
    }

    // === 3. 关键用户路径 ===
    @Test
    fun criticalUserJourney() = baselineProfileRule.collect(
        packageName = "com.questtick"
    ) {
        pressHome()
        startActivityAndWait()
        waitForIdleAndContent()

        // --- 账号管理 ---
        clickTextOrDesc("账号")
        device.waitForIdle()
        flingDownUp()

        // 打开添加账号 BottomSheet
        clickTextOrDesc("添加账号")
        device.wait(Until.hasObject(By.text("基本信息")), 3000)
        device.waitForIdle()

        // AccountEditScreen 3 个 Tab 切换
        listOf("云游戏", "游戏选择", "基本信息").forEach { tab ->
            clickTextOrDesc(tab)
            device.waitForIdle()
            // 游戏选择网格滚动
            if (tab == "游戏选择") flingDownUp()
        }

        // 打开扫码登录，触发 QRLoginScreen / ZXing
        clickTextOrDesc("扫码登录") ?: clickTextOrDesc("米游社扫码")
        device.waitForIdle()
        Thread.sleep(400)
        device.pressBack()
        device.waitForIdle()

        // 关闭编辑页
        clickTextOrDesc("关闭") ?: device.pressBack()
        device.waitForIdle()

        // --- 设置页 ---
        clickTextOrDesc("设置")
        device.waitForIdle()

        // 4 个子页面切换 + 开关点击
        listOf("定时任务", "邮件推送", "设备 ID", "实验功能").forEach { tab ->
            clickTextOrDesc(tab)
            device.waitForIdle()
            // 点击开关，触发 AnimatedSwitch 重组
            repeat(2) {
                device.findObject(By.clickable(true))?.click()
                device.waitForIdle()
            }
        }
        device.pressBack()
        device.waitForIdle()

        // --- 日志页 ---
        clickTextOrDesc("日志")
        device.waitForIdle()
        flingDownUp()
        flingDownUp()

        // 日志导出
        clickTextOrDesc("导出")
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()

        // --- 记录页 ---
        clickTextOrDesc("记录")
        device.waitForIdle()
        flingDownUp()

        // 返回主页，触发一次签到
        clickTextOrDesc("主页")
        device.waitForIdle()
        clickTextOrDesc("立即全部签到")
        device.waitForIdle()
    }

    // === 辅助 ===

    private fun MacrobenchmarkScope.waitForIdleAndContent() {
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.clickTextOrDesc(text: String): UiObject2? {
        val node = device.findObject(By.desc(text))
            ?: device.findObject(By.text(text))
            ?: device.findObject(By.textContains(text))
        node?.click()
        return node
    }

    private fun MacrobenchmarkScope.flingDownUp() {
        try {
            // 向下 fling
            device.findObject(By.scrollable(true))?.let {
                it.setGestureMargin(device.displayWidth / 5)
                it.fling(Direction.DOWN)
                device.waitForIdle()
                Thread.sleep(150)
                it.fling(Direction.UP)
                device.waitForIdle()
            }
        } catch (_: Exception) { }
    }
}
