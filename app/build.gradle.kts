import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 后端地址从 local.properties 注入（该文件不入库），仓库内只保留占位。
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val serverBaseUrl = localProps.getProperty("serverBaseUrl") ?: "https://your-server.example.com"

// 版本号由仓库根目录 version.properties 维护，发布脚本负责递增。
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val appVersionCode = versionProps.getProperty("versionCode")?.toIntOrNull() ?: 1
val appVersionName = versionProps.getProperty("versionName") ?: "1.0.0"

// Release 签名：本地从 local.properties 读取，CI 从环境变量读取，密钥不入库。
val releaseStoreFile = localProps.getProperty("releaseStoreFile") ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword = localProps.getProperty("releaseStorePassword") ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localProps.getProperty("releaseKeyAlias") ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = localProps.getProperty("releaseKeyPassword") ?: System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.bsfeng.monitor"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.bsfeng.monitor"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
        // 更新检测：自建更新源优先，仓库信息用于 jsDelivr / GitHub 镜像兜底
        buildConfigField("String", "UPDATE_BASE_URL", "\"${localProps.getProperty("updateBaseUrl") ?: ""}\"")
        buildConfigField("String", "UPDATE_REPO", "\"baosfeng/android-monitor\"")
        buildConfigField("int", "UPLOAD_INTERVAL_MS", "5000")
        buildConfigField("int", "BATCH_MAX_SIZE", "50")
        buildConfigField("int", "CACHE_MAX_DAYS", "3")
        buildConfigField("int", "RETRY_MAX_ATTEMPTS", "5")
        buildConfigField("boolean", "NOTIFICATION_CONTENT_ENABLED", "true")

        androidResources.localeFilters += listOf("zh", "zh-rCN")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        // 本地存在 debug.keystore 时沿用（保证覆盖安装签名一致）；
        // 公开仓库不含该文件时回退到 AGP 默认 debug keystore，保证 clone 后可构建。
        val localDebugKeystore = file("debug.keystore")
        if (localDebugKeystore.exists()) {
            named("debug") {
                storeFile = localDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Release 签名：仅在配置齐全时创建（本地 local.properties / CI Secrets）
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            // 与 release 共用同一签名：debug 包可直接覆盖安装 release 包，更新链路不用卸载重装；
            // 没有签名材料（CI / 新 clone）时退回默认 debug keystore，保证仍可构建
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 单元测试覆盖率：AGP 会对 class 做离线插桩，
            // Robolectric 沙箱类加载器加载的正是插桩后的类，覆盖率才能被统计到
            enableUnitTestCoverage = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 性能验证包：与 release 相同的编译优化（R8 + shrink + 非 debuggable），
        // 签名跟随统一签名（有 release 材料时用 release，否则用 debug），
        // 可与日常包**覆盖安装**（同包名同签名，数据库与无障碍授权都保留）。
        //   ./gradlew :app:assemblePerf && adb install -r app/build/outputs/apk/perf/app-perf.apk
        create("perf") {
            initWith(getByName("release"))
            signingConfig =
                if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint {
        // 将 Lint 错误视为编译失败
        abortOnError = true
        // Release build 不额外检查（debug 已覆盖）
        checkReleaseBuilds = false
        // 自动生成基线文件
        baseline = file("lint-baseline.xml")
        // 警告视为错误
        warningsAsErrors = true
        // 不检查这些 Issue
        disable += setOf(
            // 系统级权限，我们的 Accessibility Service 必须使用
            "ProtectedPermissions",
            // 需要查询所有已安装应用名单
            "QueryAllPackagesPermission",
            // Android 12+ 新属性，minSdk 29 的旧版本设备忽略
            "UnusedAttribute",
            // 可升级的依赖版本（需人工评估，不阻塞编译）
            "GradleDependency",
            "NewerVersionAvailable",
            // mipmap v26 对 minSdk 29 冗余但无关紧要
            "ObsoleteSdkInt",
        )
    }
}

tasks.withType<Test> {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        // Robolectric 4.17 在 API 37 沙箱里建 ApplicationSharedMemory 时会反射 SharedSecrets
        // 改写 FileDescriptor，JDK 21 默认不导出该包（报 "Failed to interact with raw
        // FileDescriptor internals; perhaps JRE has changed?"）
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED"
    )

    // 测试并行执行以加快反馈（质量基线要求）。
    // 每个 fork 是独立 JVM：Robolectric 沙箱缓存、被 resetForTesting 清理的静态单例互不干扰。
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// =============================================
// UI 规范静态检查（编译后自动运行）
// 检测 theme 包之外是否引入了硬编码颜色/字号
// =============================================
val checkUiStyle by tasks.registering {
    val srcDir = file("src/main/java/com/bsfeng/monitor")
    val excluded = setOf("ui/theme") // theme 包允许定义 Token

    doLast {
        val errors = mutableListOf<String>()

        // 检查硬编码颜色（theme 包外）
        fileTree(srcDir).matching { exclude("**/theme/**") }.forEach { file ->
            val text = file.readText()
            val relPath = srcDir.toPath().relativize(file.toPath()).toString()

            // 检查 Color(0xFF 硬编码（允许 Color.White / Color.Transparent / IOSColors.*）
            val colorPattern = Regex("""Color\(0xFF[A-Fa-f0-9]{6}\)""")
            val colorMatches = colorPattern.findAll(text).toList()
            if (colorMatches.isNotEmpty()) {
                colorMatches.forEach { match ->
                    val line = text.substring(0..match.range.first).count { it == '\n' } + 1
                    errors.add("$relPath:$line  硬编码颜色: ${match.value}  → 使用 MaterialTheme.colorScheme 或 IOSColors")
                }
            }

            // 检查 fontSize = X.sp（排除 copy(fontSize = X.sp) 特例和已标记白名单）
            val fontSizePattern = Regex("""fontSize = (\d+)\.sp""")
            val skipPattern = Regex("""\.copy\(fontSize = \d+\.sp""")
            val filteredText = text.replace(skipPattern, "")
            val fontSizeMatches = fontSizePattern.findAll(filteredText).toList()
            if (fontSizeMatches.isNotEmpty()) {
                fontSizeMatches.forEach { match ->
                    val line = text.substring(0..match.range.first).count { it == '\n' } + 1
                    errors.add("$relPath:$line  硬编码字号: ${match.value}  → 使用 MaterialTheme.typography")
                }
            }
        }

        if (errors.isNotEmpty()) {
            println("\n⚠️  UI 规范检查发现 ${errors.size} 个问题:\n")
            errors.forEach { println("  • $it") }
            println("\n  请参考: docs/UI设计/设计规范.md\n")
            throw GradleException("UI 规范检查未通过")
        } else {
            println("✅ UI 规范检查通过")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkUiStyle)
}

// =============================================
// 代码健康检查：TODO/FIXME 不允许提交
// =============================================
val checkTodos by tasks.registering {
    val srcDir = file("src/main/java/com/bsfeng/monitor")

    doLast {
        val todos = mutableListOf<String>()
        fileTree(srcDir).forEach { file ->
            val text = file.readText()
            val relPath = srcDir.toPath().relativize(file.toPath()).toString()

            // 检查 FIXME（硬性要求必须修复）
            val fixmePattern = Regex("""FIXME""")
            fixmePattern.findAll(text).forEach { match ->
                val line = text.substring(0..match.range.first).count { it == '\n' } + 1
                todos.add("$relPath:$line  FIXME 未处理")
            }
        }

        if (todos.isNotEmpty()) {
            println("\n⚠️  代码健康检查发现 ${todos.size} 个问题:\n")
            todos.forEach { println("  • $it") }
            println()
            // TODO 为提醒，FIXME 为错误
            val fixmes = todos.count { it.contains("FIXME") }
            if (fixmes > 0) {
                throw GradleException("发现 $fixmes 个 FIXME，请修复后再提交")
            }
        } else {
            println("✅ 代码健康检查通过")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkTodos)
}

dependencies {
    // Domain 模块
    implementation(project(":domain"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-extended")

    // Core
    implementation("androidx.core:core-ktx:1.19.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
}

// =============================================
// 单元测试覆盖率
// ------------------------------------------------------------
// 使用 AGP 内置的覆盖率支持（见 buildTypes.debug.enableUnitTestCoverage）：
//
//   ./gradlew :app:createDebugUnitTestCoverageReport
//
// 报告：app/build/reports/coverage/test/debug/index.html
// 数据：app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec
//
// ⚠️ 已知限制：**Robolectric 用例的覆盖不会被计入**。
// Robolectric 用自带的沙箱 ClassLoader 加载被测类，绕过了 JaCoCo 的在线插桩 agent，
// 因此 exec 里只包含纯 JVM 用例（如 FinanceModelTest）触碰到的类。
// 由 Robolectric 覆盖的类（FinanceDao / FinanceManager / PandaImporter 等）在报告里显示 0%，
// **不代表没有测试**——它们以「用例断言 + 反向验证（回滚修复后测试变红）」作为证据。
// =============================================
