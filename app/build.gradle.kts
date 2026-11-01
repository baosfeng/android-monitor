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
    // 测试 JVM 取 25：:domain 用 25 toolchain 编译出 major 69 字节码，
    // 低于 25 的 JVM 加载它会抛 UnsupportedClassVersionError，与启动构建的 JDK 无关
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }

    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        // Robolectric 4.17 在 API 37 沙箱里建 ApplicationSharedMemory 时会反射 SharedSecrets
        // 改写 FileDescriptor，而该包默认未导出（报 "Failed to interact with raw
        // FileDescriptor internals; perhaps JRE has changed?"）
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        // conscrypt 用 System.load 加载 native 库；JDK 24 起这属于受限方法，
        // 显式放行以免默认告警在后续 JDK 版本里升级成硬失败
        "--enable-native-access=ALL-UNNAMED"
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

// =============================================
// 无障碍静态门禁：ERROR 阻断，WARN 只报不阻断
// 规则与豁免口径见 docs/UI设计/无障碍基线.md，脚本 scripts/check-a11y.sh
// 不挂 preBuild：存量 WARN 会拖慢每次构建且吵；挂在 check 里，本地手动跑
//   ./gradlew checkA11y      （等价 bash scripts/check-a11y.sh）
// =============================================
val checkA11y by tasks.registering {
    group = "verification"
    description = "无障碍静态门禁（ERROR：IconButton 无标签；WARN：锚点/触达区/状态语义）"

    // 与 checkUiStyle 一致：不声明 inputs，每次执行都重新扫描并打印实数
    val script = rootProject.file("scripts/check-a11y.sh").absolutePath
    val workDir = rootProject.rootDir

    doLast {
        if (!File(script).exists()) {
            throw GradleException("缺少无障碍门禁脚本：$script")
        }
        val process = ProcessBuilder("bash", script)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        println(output.trimEnd())
        if (exitCode != 0) {
            throw GradleException("无障碍静态门禁未通过（退出码 $exitCode）。规范见 docs/UI设计/无障碍基线.md")
        }
    }
}

dependencies {
    // Domain 模块
    implementation(project(":domain"))

    // 桌面小组件（Glance 1.2.0）：6 个 App Widget 全部由 Glance 生成 RemoteViews，
    // 与现有 Compose 同一心智（@Composable + 响应式尺寸 + 深浅色），不必回到 XML 布局。
    // 代价是 AAR 会带入 work-runtime / datastore（Glance 的 update 走 WorkManager session），
    // 选型与实测依赖树见 docs/小组件/调研.md；放在 BOM 之前只是阅读顺序，platform 约束对整个模块生效。
    implementation("androidx.glance:glance-appwidget:1.2.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.material:material-icons-extended")

    // Core
    implementation("androidx.core:core-ktx:1.19.1")

    // 验证器二维码解码（纯 Java、无 native、Apache 2.0，jar 约 607KB）。
    // 默认依赖红线是「零第三方业务依赖」，zxing core 是**算法库白名单**里唯一获批项，
    // 且只在 totp/TotpQrDecoder.kt 一个文件里被 import，便于审计。
    implementation("com.google.zxing:core:3.5.3")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
}

// =============================================
// 单元测试覆盖率
// ------------------------------------------------------------
// 使用 AGP 内置的覆盖率支持（见 buildTypes.debug.enableUnitTestCoverage）采集 exec 数据：
//
//   ./gradlew :app:testDebugUnitTest            # 产出 testDebugUnitTest.exec
//   ./gradlew :app:createDebugUnitTestCoverageReport
//   ./gradlew :app:coverageReport               # 带排除规则的报告 + XML，门禁用这个
//
// 报告：app/build/reports/coverage/test/debug/index.html（AGP 原始口径）
//       app/build/reports/coverage/app-debug/   （AGP 口径，已排除生成代码与纯 Composable）
//       app/build/reports/coverage/app-debug/report.xml（scripts/check-coverage.sh 读这个）
// 数据：app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec
//
// ⚠️ 已知限制：**Robolectric 用例的覆盖不会被计入**。
// AGP 只把 jacocoagent 以 -javaagent 方式挂到测试 JVM 上做在线插桩（不产出离线插桩类）；
// Robolectric 用自带沙箱 ClassLoader 直接从原始 .class 字节加载被测类，不经过该 agent，
// 因此 exec 里只包含纯 JVM 用例触碰到的类。
// 由 Robolectric 覆盖的类（FinanceDao / FinanceManager / PandaImporter 等）在报告里显示 0%，
// **不代表没有测试**——它们以「用例断言 + 反向验证（回滚修复后测试变红）」作为证据。
// 结论：门禁阈值只能用来防「纯 JVM 可测代码」的覆盖率下滑，不能当作整体质量分数。
// =============================================

// ------------------------------------------------------------
// 覆盖率排除清单
// ------------------------------------------------------------
// 原则：**只排除「测试根本碰不到、或不该用单测衡量」的代码**，不排除难测的核心逻辑。
// 每项都写明理由；报告里被排除的行数会打印出来，便于核对没有借排除凑数字。

// 1) 纯 Compose UI 文件：整个文件只有 @Composable 函数（含 private 的布局拆分函数），
//    没有可独立调用的业务逻辑。Compose 的渲染需要 UI 测试（androidTest + Compose Testing），
//    单元测试环境无法组合，强行计入会让分母膨胀且永远不涨。
//    判定标准：文件内所有声明都是 @Composable 函数；一旦文件里出现普通 fun/class，
//    就不排除它（那说明有可单测的逻辑）。
//    写法是相对 src/main/java 的路径（去掉 .kt），下面转成 class 名 pattern。
//    注意 Kotlin 顶层函数的 class 名是「文件名 + Kt」，排除时必须用它而不是文件名。
val pureComposableSources = listOf(
    "com/bsfeng/monitor/ui/AdScreen",
    "com/bsfeng/monitor/ui/WhitelistScreen",
    "com/bsfeng/monitor/ui/components/DebugGate",
    "com/bsfeng/monitor/ui/components/PlaceholderScreens",
    "com/bsfeng/monitor/ui/components/WindowOverlay",
    "com/bsfeng/monitor/ui/foundation/Backdrop",
    "com/bsfeng/monitor/ui/foundation/ImmersiveScrim",
    "com/bsfeng/monitor/ui/quota/QuotaAggregateList",
    "com/bsfeng/monitor/ui/quota/QuotaAggregateRow",
    "com/bsfeng/monitor/ui/quota/QuotaAtoms",
    "com/bsfeng/monitor/ui/quota/QuotaSettingsAlerts",
    "com/bsfeng/monitor/ui/quota/QuotaSettingsScreen",
    "com/bsfeng/monitor/ui/quota/QuotaStateViews",
    "com/bsfeng/monitor/ui/quota/QuotaUsageCard",
    "com/bsfeng/monitor/ui/quota/QuotaViewModeSwitch",
    "com/bsfeng/monitor/ui/theme/Components",
    "com/bsfeng/monitor/finance/ui/FinanceDialogs",
)

// 2) 代码生成产物：改了会被下一次生成覆盖，覆盖率高不代表逻辑可靠。
//    - GeneratedTokens.kt：tools/gen-design-tokens.mjs 由原型 HTML 生成，
//      产出 DesignTokens / GeneratedColorScheme / LegacyTokenMap 三个类（132 个 getter 纯转发）
//    - Compose 编译器产出的 ComposableSingletons$* 与 *$WhenMappings 合成类：
//      随源码自动生成、无法单独测试，随宿主文件一起统计才有意义
//    注：R / BuildConfig 的 class 不进本报告的 classfiles（AGP 只喂 kotlinc/javac 输出），
//    已实测报告内这两类计数为 0，故不写空 pattern 占位。
val generatedCodeClasses = listOf(
    "com/bsfeng/monitor/ui/theme/DesignTokens.class",
    "com/bsfeng/monitor/ui/theme/GeneratedColorScheme.class",
    "com/bsfeng/monitor/ui/theme/LegacyTokenMap.class",
    "com/bsfeng/monitor/ui/theme/LegacyTokenMap\$*.class",
    "**/ComposableSingletons\$*.class",
    "**/*\$WhenMappings.class",
)

// 合并成 JaCoCo Ant 报告能吃的 pattern；纯 Composable 转成「文件名Kt 及其内部类」
val coverageExcludes: List<String> = pureComposableSources.map { "$it" + "Kt*.class" } + generatedCodeClasses

// 报告任务：用 JaCoCo Ant 而非 AGP 内置报告，因为 AGP 的 JacocoReportTask 不接受自定义排除项
// （JacocoWorkParameters.exclusions 只供 on-the-fly 模式用）。Ant 报告与 AGP 报告在无排除时
// 数字完全一致（LINE 19.78% / BRANCH 20.50%，已实测对比），换的只是过滤能力。
val coverageReport by tasks.registering {
    group = "verification"
    description = "生成单元测试覆盖率报告（HTML + XML），排除生成代码与纯 Composable 文件"
    dependsOn("testDebugUnitTest")

    val execFile = layout.buildDirectory.file(
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
    )
    val classesDir = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    val javaClassesDir = layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
    val sourceDir = file("src/main/java")
    val reportDir = layout.buildDirectory.dir("reports/coverage/app-debug")
    val excludes = coverageExcludes

    inputs.file(execFile)
    inputs.files(classesDir, javaClassesDir)
    inputs.property("excludes", excludes)
    outputs.dir(reportDir)

    doLast {
        val classDirs = listOf(classesDir.get().asFile, javaClassesDir.get().asFile).filter { it.exists() }

        // 自检：每个排除 pattern 必须真命中 class 文件，否则是静默失效（曾用 .kt 路径踩过，
        // 报告数字一点没变却毫无提示）。命中 0 直接失败，逼着改 pattern。
        val hitsByPattern = excludes.associateWith { pattern ->
            classDirs.sumOf { dir ->
                fileTree(dir) { include(pattern) }.files.size
            }
        }
        val misses = hitsByPattern.filterValues { it == 0 }.keys
        if (misses.isNotEmpty()) {
            throw GradleException(
                "覆盖率排除规则未命中任何 class 文件，排除已失效：$misses\n" +
                    "请改成正确的 class 名 pattern（Kotlin 顶层函数在 <文件名>Kt 里）。"
            )
        }
        val excludedCount = classDirs.sumOf { dir -> fileTree(dir) { exclude(excludes) ; include("**/*.class") }.files.size }
        val totalClassFiles = classDirs.sumOf { dir -> fileTree(dir) { include("**/*.class") }.files.size }
        println("覆盖率排除自检通过：${excludes.size} 条规则全部命中；排除后保留 $excludedCount / $totalClassFiles 个 class")

        val antClasspath = configurations.getByName("androidJacocoAnt").files
            .joinToString(":") { it.absolutePath }
        val outDir = reportDir.get().asFile
        outDir.mkdirs()
        ant.withGroovyBuilder {
            "taskdef"("name" to "jacocoReport", "classname" to "org.jacoco.ant.ReportTask") {
                "classpath" { "pathelement"("path" to antClasspath) }
            }
            "jacocoReport" {
                "executiondata" {
                    "file"("file" to execFile.get().asFile)
                }
                "structure"("name" to "app-debug") {
                    "classfiles" {
                        classDirs.forEach { d ->
                            "fileset"("dir" to d) {
                                excludes.forEach { "exclude"("name" to it) }
                            }
                        }
                    }
                    "sourcefiles" {
                        "fileset"("dir" to sourceDir)
                    }
                }
                "html"("destdir" to File(outDir, "html"))
                "xml"("destfile" to File(outDir, "report.xml"))
            }
        }
        println("覆盖率报告（含排除）: file://${File(outDir, "html/index.html")}")
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(coverageReport, checkA11y)
}
