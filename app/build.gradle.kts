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

tasks.register("verifyCodeRedLines") {
    group = "verification"
    description = "扫描源码红线：裸 android.util.Log / println / printStackTrace"
    val sourceDir = layout.projectDirectory.dir("src/main/java")
    inputs.dir(sourceDir)
    doLast {
        val violations = mutableListOf<String>()
        sourceDir.asFileTree.matching { include("**/*.kt") }.forEach { file ->
            if (file.path.endsWith("log/AppLog.kt")) return@forEach
            file.readLines().forEachIndexed { index, line ->
                when {
                    Regex("^\\s*import android\\.util\\.Log\b").containsMatchIn(line) ->
                        violations.add("${file.path}:${index + 1} 禁止直接 import android.util.Log，请使用 com.questtick.log.AppLog")
                    Regex("""\bprintln\s*\(""").containsMatchIn(line) ->
                        violations.add("${file.path}:${index + 1} 禁止使用 println，请使用 AppLog 或 Room 日志")
                    Regex("""\.printStackTrace\s*\(\s*\)""").containsMatchIn(line) ->
                        violations.add("${file.path}:${index + 1} 禁止使用 printStackTrace，请将异常传递给 AppLog 或上层处理")
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

        // DS 签名 salt 混淆：支持通过 gradle.properties / 环境变量注入，避免源码硬编码
        // 例：./gradlew assembleRelease -PdsSalt="your_salt"
        val dsSaltRaw = optionalBuildValue("dsSalt", "DS_SALT") ?: "yUZ3s0Sna1IrSNfk29Vo6vRapdOyqyhB"
        // 简单混淆：Base64 + 倒序，仅降低静态扫描命中率，不作为安全边界。
        // 注意：此处不能用 java.util.Base64，会和 Gradle 的 java 扩展冲突
        val dsSaltObf = Base64.getEncoder().encodeToString(dsSaltRaw.reversed().toByteArray(Charsets.UTF_8))
        buildConfigField("String", "DS_SALT_OBF", "\"$dsSaltObf\"")
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
    // --- Compose BOM ---
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX 核心能力
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.2.0")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose UI 与 Material 组件
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // --- 网络请求 ---
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // 项目当前未使用 Retrofit；如后续接入，可在此补充相关依赖。

    // Hilt 依赖注入与 WorkManager 集成
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-work:1.3.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Coil：加载签到奖励图标
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // ZXing：生成扫码登录二维码
    implementation("com.google.zxing:core:3.5.3")

    // JSON 使用 Android 内置 org.json，无需额外依赖

    // WorkManager：每日定时签到
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Room：日志 / 历史记录数据库存储
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // RootBeer：可选 Root 环境检测
    implementation("com.scottyab:rootbeer-lib:0.1.2")

    // ProfileInstaller：运行时安装 Baseline Profile
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // 项目已不再依赖 AndroidX Security 的旧加密存储实现。

    // --- 邮件推送：Jakarta Mail ---
    implementation("jakarta.mail:jakarta.mail-api:2.1.3")
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("org.eclipse.angus:angus-activation:2.0.2")

    // Baseline Profile 生成模块
    baselineProfile(project(":baselineprofile"))

    // 调试工具
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- 测试依赖 ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    // MockWebServer 版本与 OkHttp 保持一致。
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")

    // 测试框架
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
