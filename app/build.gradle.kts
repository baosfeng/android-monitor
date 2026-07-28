plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bsfeng.monitor"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.bsfeng.monitor"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "SERVER_BASE_URL", "\"https://your-server.com/api/v1\"")
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
        named("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        "--add-opens=java.base/java.util=ALL-UNNAMED"
    )
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-extended")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
