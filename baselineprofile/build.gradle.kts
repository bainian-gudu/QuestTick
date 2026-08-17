plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.questtick.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Macrobenchmark / Baseline Profile 生成任务最低要求 API 28。
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        // 迭代次数保持较小值，兼顾结果稳定性与 CI 耗时。
        testInstrumentationRunnerArguments["androidx.benchmark.iterations"] = "3"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    experimentalProperties["android.experimental.enableArtProfiles"] = true
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
