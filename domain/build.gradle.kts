plugins {
    id("org.jetbrains.kotlin.jvm")
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
