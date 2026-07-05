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
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-core")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.13")
}
