// 根项目构建脚本：集中声明各模块共用的插件版本与依赖解析策略。
plugins {
    // Android Gradle Plugin
    id("com.android.application") version "9.2.1" apply false
    id("com.android.test") version "9.2.1" apply false

    // Baseline Profile 生成插件
    id("androidx.baselineprofile") version "1.5.0-alpha05" apply false

    // Compose / Kotlin / KSP
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false

    // DI 与覆盖率
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8" apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
                useVersion("2.4.0")
                because("确保 Hilt 在 Kotlin 2.4 下读取到兼容的 metadata 版本")
            }
        }
    }
}
