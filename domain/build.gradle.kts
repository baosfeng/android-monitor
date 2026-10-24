plugins {
    id("org.jetbrains.kotlin.jvm")
    // domain 是纯 Kotlin/JVM 模块，没有 Robolectric 的沙箱 ClassLoader，
    // 因此 JaCoCo 的覆盖数据是**真实可信**的（app 模块被 Robolectric 绕过的那些类在这里不存在）。
    // 用标准 jacoco 插件：agent 挂在 Test JVM 上，报告由 jacocoTestReport 生成。
    jacoco
}

java {
    toolchain {
        // domain 是纯 Kotlin/JVM 模块，不经 Android desugar，可直接吃 JDK 25 字节码（major 69）；
        // app 侧由 AGP 内置 D8/R8 消化，无需同步抬高 app 的 jvmTarget
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// ------------------------------------------------------------
// 覆盖率报告
// ------------------------------------------------------------
// 排除清单与 app 模块同口径：只排「测试碰不到 / 不该用单测衡量」的代码。
//   - *$WhenMappings：Kotlin 编译器为 when 枚举生成的映射表，随源码自动生成、无法单独测试
// domain 没有 Compose、没有 R/BuildConfig、没有代码生成器产物，所以排除项只有这一类。
// （报告里偶尔出现的 Comparisons.kt 是 kotlin stdlib comparator 的内联源码映射，0 行，
//   不对应任何 domain 的 class 文件，无需也无法排除。）
val jacocoExcludes = listOf(
    "**/*\$WhenMappings.class",
)

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
            exclude(jacocoExcludes)
        }
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))

    doFirst {
        // 排除规则自检：pattern 命中 0 个 class 说明排除已静默失效（app 模块踩过同类坑：
        // 用 .kt 路径匹配 .class fileset，数字一点没变却毫无提示）。命中 0 直接失败。
        val classRoot = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val hits = jacocoExcludes.associateWith { pattern ->
            fileTree(classRoot) { include(pattern) }.files.size
        }
        val misses = hits.filterValues { it == 0 }.keys
        if (misses.isNotEmpty()) {
            throw GradleException(
                "domain 覆盖率排除规则未命中任何 class 文件，排除已失效：$misses"
            )
        }
        println("domain 覆盖率排除自检通过：${jacocoExcludes.size} 条规则全部命中")
    }
}
