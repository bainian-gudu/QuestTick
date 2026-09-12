// 根项目构建脚本：集中声明各模块共用的插件版本与依赖解析策略。
plugins {
    // Android Gradle Plugin
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false

    // Baseline Profile 生成插件
    alias(libs.plugins.baselineprofile) apply false

    // Compose / Kotlin / KSP
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false

    // DI 与覆盖率
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kover) apply false

    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
                useVersion(libs.versions.kotlin.get())
                because("确保 Hilt 在 Kotlin 2.4 下读取到兼容的 metadata 版本")
            }
        }
    }
}
