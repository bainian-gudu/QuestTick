import java.io.FileInputStream
import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("androidx.baselineprofile")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlinx.kover")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
}

// ---------------------------------------------------------------------------
// 代码红线：禁止裸 android.util.Log / println / printStackTrace 进入源码。
// ---------------------------------------------------------------------------

/**
 * 剥离 Kotlin 注释后再做红线扫描，避免“注释里写了 println(”被误判为违规。
 *
 * 保留换行以维持行号不变，便于直接定位；字符串字面量（如 "https://..."）原样保留，
 * 不会因为内部含有 // 或块注释起始符而被误当作注释截断。
 * 注意：Kotlin 的块注释可嵌套，因此本文件任何注释里都不要再写块注释起始符。
 */
fun stripKotlinComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        when {
            source[i] == '"' -> {
                out.append(source[i])
                i++
                while (i < source.length) {
                    if (source[i] == '\\') {
                        out.append(source[i])
                        if (i + 1 < source.length) out.append(source[i + 1])
                        i += 2
                        continue
                    }
                    out.append(source[i])
                    i++
                    if (source[i - 1] == '"') break
                }
            }

            source.startsWith("//", i) -> {
                while (i < source.length && source[i] != '\n') i++
            }

            source.startsWith("/*", i) -> {
                val found = source.indexOf("*/", i + 2)
                val stop = if (found < 0) source.length else found + 2
                for (k in i until stop) out.append(if (source[k] == '\n') '\n' else ' ')
                i = stop
            }

            else -> {
                out.append(source[i])
                i++
            }
        }
    }
    return out.toString()
}

val redLineRules =
    listOf(
        Regex("""^\s*import android\.util\.Log\b""") to
            "禁止直接 import android.util.Log，请使用 com.questtick.log.AppLog",
        Regex("""\bprintln\s*\(""") to
            "禁止使用 println，请使用 AppLog 或 Room 日志",
        Regex("""\.printStackTrace\s*\(\s*\)""") to
            "禁止使用 printStackTrace，请将异常传递给 AppLog 或上层处理",
    )

tasks.register("verifyCodeRedLines") {
    group = "verification"
    description = "扫描源码红线：裸 android.util.Log / println / printStackTrace（覆盖 main / test / androidTest）"
    // main / test / androidTest 三个源码集一并纳入，避免测试代码成为红线缺口。
    // 只登记实际存在的目录：inputs.dir 指向不存在的目录会让任务直接失败。
    val sourceDirs =
        listOf("src/main/java", "src/test/java", "src/androidTest/java")
            .map { layout.projectDirectory.dir(it) }
            .filter { it.asFile.exists() }
    sourceDirs.forEach { inputs.dir(it) }
    doLast {
        val violations = mutableListOf<String>()
        sourceDirs.forEach { dir ->
            dir.asFileTree.matching { include("**/*.kt") }.forEach { file ->
                if (file.path.endsWith("log/AppLog.kt")) return@forEach
                stripKotlinComments(file.readText()).lines().forEachIndexed { index, line ->
                    redLineRules.forEach { (regex, message) ->
                        if (regex.containsMatchIn(line)) {
                            violations.add("${file.path}:${index + 1} $message")
                        }
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("代码红线检查失败：\n" + violations.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn("verifyCodeRedLines") }

afterEvaluate {
    // AGP 9 下 detekt 变体任务未自动注册，手动接通编译类路径以启用类型解析类规则。
    tasks.matching { it.name == "detekt" }.configureEach {
        val detektTask = this as io.gitlab.arturbosch.detekt.Detekt
        configurations.findByName("debugCompileClasspath")?.let { detektTask.classpath.from(it) }
        detektTask.jvmTarget = "17"
    }
    tasks.matching { it.name == "detektBaseline" }.configureEach {
        val baselineTask = this as io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
        configurations.findByName("debugCompileClasspath")?.let { baselineTask.classpath.from(it) }
        baselineTask.jvmTarget = "17"
    }
}

// ---------------------------------------------------------------------------
// Release 签名配置：按“环境变量 → 本地配置文件 → 不签名”的顺序自动选择。
// ---------------------------------------------------------------------------

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            FileInputStream(keystorePropertiesFile).use { load(it) }
        }
    }

val envStoreBase64: String? = System.getenv("KEYSTORE_BASE64") ?: System.getenv("KEYSTORE_B64")
val envStoreFile: String? = System.getenv("KEYSTORE_FILE")
val envStorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val envKeyAlias: String? = System.getenv("KEY_ALIAS")
val envKeyPassword: String? = System.getenv("KEY_PASSWORD")

val storeFilePath: String =
    envStoreFile
        ?: keystoreProperties
            .getProperty("storeFile")
            ?.takeIf { it.isNotBlank() }
        ?: ""

val storeFileObj =
    if (storeFilePath.isNotBlank()) {
        rootProject.file(storeFilePath)
    } else {
        // 当没有 keystore 时，使用一个不存在的文件路径，避免 rootProject.file("") 报错
        rootProject.file("NO_KEYSTORE")
    }

if (!envStoreBase64.isNullOrBlank() && !storeFileObj.exists()) {
    try {
        val decoded = Base64.getDecoder().decode(envStoreBase64.trim())
        storeFileObj.parentFile?.mkdirs()
        storeFileObj.writeBytes(decoded)
        project.logger.lifecycle("✅ 自动从环境变量 KEYSTORE_BASE64 解码并创建了密钥文件: ${storeFileObj.absolutePath}")
    } catch (e: Exception) {
        project.logger.error("❌ 从环境变量 KEYSTORE_BASE64 解码密钥失败: ${e.message}")
    }
}

val finalStorePassword =
    envStorePassword
        ?: keystoreProperties.getProperty("storePassword")

val finalKeyAlias =
    envKeyAlias
        ?: keystoreProperties.getProperty("keyAlias")

val finalKeyPassword =
    envKeyPassword
        ?: keystoreProperties.getProperty("keyPassword")
        ?: finalStorePassword

val hasReleaseKeystore =
    storeFileObj.exists() &&
        !finalStorePassword.isNullOrBlank() &&
        !finalKeyAlias.isNullOrBlank()

fun optionalBuildValue(
    propertyName: String,
    envName: String,
): String? =
    (findProperty(propertyName) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }

val appVersionName = optionalBuildValue("appVersionName", "APP_VERSION_NAME") ?: "1.0.0"
val appVersionCode =
    optionalBuildValue("appVersionCode", "APP_VERSION_CODE")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: 2

android {
    namespace = "com.questtick"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.questtick"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
            generatedDensities?.clear()
        }
        // 仅保留当前需要的语言资源，减少 APK 体积。
        // 语言过滤统一在 androidResources.localeFilters 中配置。
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = storeFileObj
                storePassword = finalStorePassword!!
                keyAlias = finalKeyAlias!!
                keyPassword = finalKeyPassword!!

                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // R8 full mode 由 gradle.properties 中的 android.enableR8.fullMode 控制。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            signingConfig =
                if (hasReleaseKeystore) {
                    signingConfigs.getByName("release")
                } else {
                    null
                }
        }
        debug {
            applicationIdSuffix = ".debug"
            // Debug 构建关闭压缩，缩短本地迭代时间。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // 统一使用 Java 21，避免编译与工具链版本分裂。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // MigrationTestHelper 在 androidTest assets 中查找导出的 schema JSON，用于升级回归测试。
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    androidResources {
        // 资源收缩相关配置。
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = false
        localeFilters.addAll(listOf("zh", "zh-rCN", "zh-rTW", "zh-rHK", "zh-rMO", "en", "ja", "ko"))
    }

    // 仅发布完整 APK，不生成按语言拆分且需要 Play Core 动态下载的 App Bundle。
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 邮件相关库会带入多份说明文件，这里统一排除以避免打包冲突。
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/DEPENDENCIES"
            // 这些资源需要保留，否则邮件能力在运行时可能缺少默认配置。
            pickFirsts += "META-INF/javamail.default.providers"
            pickFirsts += "META-INF/javamail.default.address.map"
            pickFirsts += "META-INF/javamail.charset.map"
            pickFirsts += "META-INF/mailcap"
            pickFirsts += "META-INF/mailcap.default"
            pickFirsts += "META-INF/mimetypes.default"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // 保持与当前 Kotlin 编译参数一致，避免预发布校验影响构建。
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    // 生成 Kotlin 风格的 Room 代码，减少与 KSP 输出风格不一致的问题。
    arg("room.generateKotlin", "true")
}

hilt {
    enableAggregatingTask = true
}

baselineProfile {
    saveInSrc = true
}

dependencies {
    // --- Compose BOM：各 Compose 库的具体版本由平台统一约束 ---
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    // AndroidX 核心能力
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.activity.compose)

    // Compose UI 与 Material 组件
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Kotlin 协程（版本统一在 gradle/libs.versions.toml 中管理，测试库与运行库保持一致）
    implementation(libs.kotlinx.coroutines.android)

    // --- 网络请求 ---
    implementation(libs.okhttp)
    // 项目当前未使用 Retrofit；如后续接入，可在此补充相关依赖。

    // Hilt 依赖注入与 WorkManager 集成
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    // Coil：加载签到奖励图标
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ZXing：生成扫码登录二维码
    implementation(libs.zxing.core)

    // JSON 使用 Android 内置 org.json，无需额外依赖

    // WorkManager：每日定时签到
    implementation(libs.work.runtime.ktx)

    // Room：日志 / 历史记录数据库存储
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // RootBeer：可选 Root 环境检测
    implementation(libs.rootbeer.lib)

    // ProfileInstaller：运行时安装 Baseline Profile
    implementation(libs.profileinstaller)

    // 项目已不再依赖 AndroidX Security 的旧加密存储实现。

    // --- 邮件推送：Jakarta Mail ---
    implementation(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)

    // Baseline Profile 生成模块
    baselineProfile(project(":baselineprofile"))

    // 调试工具
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // --- 测试依赖 ---
    testImplementation(libs.junit)
    testImplementation(libs.json)
    // MockWebServer 与 OkHttp 共用同一版本号，避免测试与生产的协议行为不一致。
    testImplementation(libs.okhttp.mockwebserver)

    // 测试框架
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
