# 手机行为监控 App 实现计划

> **For agentic workers:** 使用 subagent-driven-development 或 executing-plans 按任务逐步实现。步骤使用 checkbox (`- [ ]`) 语法追踪进度。

**目标:** 从零构建 Android 端行为监控 App，通过无障碍服务 + 系统广播采集 37 种事件类型，实时上报到自建后端。

**架构:** Kotlin + Jetpack Compose + Room + WorkManager。无前台服务（零通知），以 AccessibilityService 为核心后台组件，使用 AlarmManager 保活。

**技术栈:** Kotlin, Jetpack Compose, Navigation Compose, Room, OkHttp/Retrofit, Kotlinx Serialization, WorkManager, AlarmManager

## 全局约束

| 约束 | 值 |
|------|-----|
| 包名 | `com.bsfeng.monitor` |
| compileSdk | 36 |
| minSdk | 36 |
| targetSdk | 36 |
| 零通知 | 不启动 ForegroundService，不出现任何通知栏图标 |
| 后端地址 | 编译时硬编码 `BuildConfig.SERVER_BASE_URL` |
| 通知内容上报 | 编译配置 `NOTIFICATION_CONTENT_ENABLED=true` |
| UI 框架 | Jetpack Compose + Navigation Compose |
| 依赖注入 | 手动（不引入 Hilt/Dagger） |
| 签名 | Release 签名打包，交付 APK |
| 测试 | 每阶段真机验证，通过后再打包 |

---

## 文件结构

```
app/src/main/java/com/bsfeng/monitor/
├── MonitorApp.kt                          # Application 入口
├── BuildConfig.kt                         # 编译时配置常量
├── MainActivity.kt                        # 单 Activity 入口
├── service/
│   ├── MonitorAccessibilityService.kt      # 无障碍服务（核心）
│   ├── BootReceiver.kt                    # 开机自启
│   └── AlarmReceiver.kt                   # AlarmManager 保活接收器
├── event/
│   ├── EventType.kt                       # 37 种事件类型枚举
│   ├── EventModels.kt                     # 所有事件的 data class
│   ├── EventCollector.kt                  # 事件收集器（去重+富化+写入）
│   └── ScreenClassifier.kt               # App 界面内容分类器
├── receiver/
│   ├── ScreenStateReceiver.kt             # 屏幕亮/灭/解锁
│   ├── BatteryReceiver.kt                 # 充电/电量变化
│   ├── WifiReceiver.kt                    # WiFi 连接/断开
│   ├── BluetoothReceiver.kt              # 蓝牙连接/断开
│   ├── HeadsetReceiver.kt                 # 耳机插拔
│   ├── PackageReceiver.kt                 # 应用安装/卸载
│   ├── AirplaneReceiver.kt               # 飞行模式
│   ├── PowerSaveReceiver.kt              # 省电模式
│   ├── StorageReceiver.kt                # 存储空间
│   ├── UsbReceiver.kt                     # USB 连接电脑
│   └── CallStateReceiver.kt               # 通话状态
├── data/
│   ├── AppDatabase.kt                     # Room 数据库
│   ├── EventEntity.kt                     # 事件 Entity
│   └── EventDao.kt                        # 事件 DAO
├── network/
│   ├── ApiService.kt                      # Retrofit API 接口
│   ├── DeviceRegisterRequest.kt           # 设备注册请求体
│   └── EventReporter.kt                   # 上报引擎（WorkManager 调度）
├── ui/
│   ├── MainScreen.kt                      # 主页面（实时状态）
│   ├── StatsScreen.kt                     # 统计页面
│   ├── SettingsScreen.kt                  # 设置页面
│   ├── components/
│   │   ├── EventCard.kt                   # 事件卡片组件
│   │   └── StatCard.kt                    # 统计卡片组件
│   └── MainViewModel.kt                   # 主 ViewModel
├── util/
│   └── AppUtils.kt                        # 工具函数（App名获取/网络判断等）

res/
├── xml/
│   └── accessibility_service_config.xml   # 无障碍服务配置
├── drawable/
│   ├── ic_monitor_foreground.xml          # App 图标
│   └── ic_monitor_background.xml
├── values/
│   ├── strings.xml
│   └── themes.xml
```

---

## 阶段一：项目骨架与基础设施

### Task 1.1: 创建 Gradle 项目结构

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (project level)
- Create: `app/build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/bsfeng/monitor/MonitorApp.kt`
- Create: `app/proguard-rules.pro`

**Key configurations in app/build.gradle.kts:**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bsfeng.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bsfeng.monitor"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "SERVER_BASE_URL", "\"https://your-server.com/api/v1\"")
        buildConfigField("int", "UPLOAD_INTERVAL_MS", "5000")
        buildConfigField("int", "BATCH_MAX_SIZE", "50")
        buildConfigField("int", "CACHE_MAX_DAYS", "3")
        buildConfigField("int", "RETRY_MAX_ATTEMPTS", "5")
        buildConfigField("boolean", "NOTIFICATION_CONTENT_ENABLED", "true")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
```

**AndroidManifest.xml 声明所有权限:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
    <uses-permission android:name="android.permission.BATTERY_STATS" />
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.USB_PERMISSION" />
    <uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />

    <application
        android:name=".MonitorApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="行为记录"
        android:supportsRtl="true"
        android:theme="@style/Theme.Monitor">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Monitor">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.MonitorAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <receiver
            android:name=".service.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.USER_PRESENT" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".service.AlarmReceiver"
            android:exported="false" />

    </application>
</manifest>
```

- [ ] **Step 1:** 创建 build.gradle.kts（project）—— Gradle 版本 catalog + Kotlin/Android 插件
- [ ] **Step 2:** 创建 app/build.gradle.kts —— 所有依赖和构建配置
- [ ] **Step 3:** 创建 AndroidManifest.xml —— 权限 + 组件声明
- [ ] **Step 4:** 创建 无障碍服务元数据 `res/xml/accessibility_service_config.xml`
- [ ] **Step 5:** 创建 MonitorApp.kt —— 空 Application 类 + 全局 Context 引用
- [ ] **Step 6:** 创建 gradle.properties + proguard-rules.pro
- [ ] **Step 7:** `./gradlew assembleDebug` 验证编译通过

---

### Task 1.2: 基础数据模型 + Room 数据库

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/event/EventType.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/event/EventModels.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/data/EventEntity.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/data/EventDao.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/data/AppDatabase.kt`

**EventType.kt:**

```kotlin
package com.bsfeng.monitor.event

import kotlinx.serialization.Serializable

@Serializable
enum class EventType(val description: String) {
    APP_FOREGROUND("前台 App 切换"),
    ACTIVITY_CHANGE("Activity 级界面切换"),
    SCREEN_ON("屏幕亮起"),
    SCREEN_OFF("屏幕熄灭"),
    UNLOCK_SUCCESS("解锁成功"),
    UNLOCK_FAILED("解锁失败"),
    CHARGING_STARTED("开始充电"),
    CHARGING_STOPPED("停止充电"),
    WIFI_CONNECTED("WiFi 连接"),
    WIFI_DISCONNECTED("WiFi 断开"),
    HEADSET_PLUGGED("耳机插入"),
    HEADSET_UNPLUGGED("耳机拔出"),
    APP_INSTALLED("应用安装"),
    APP_UNINSTALLED("应用卸载"),
    NOTIFICATION_RECEIVED("通知到达"),
    NOTIFICATION_REMOVED("通知移除"),
    BATTERY_CHANGED("电量变化"),
    RINGER_MODE_CHANGED("情景模式变化"),
    CONNECTED_TO_PC("连接电脑"),
    DISCONNECTED_FROM_PC("断开电脑"),
    BLUETOOTH_CONNECTED("蓝牙设备连接"),
    BLUETOOTH_DISCONNECTED("蓝牙设备断开"),
    AIRPLANE_MODE_CHANGED("飞行模式切换"),
    MOBILE_DATA_CHANGED("移动数据切换"),
    POWER_SAVE_MODE_CHANGED("省电模式切换"),
    SCREENSHOT_TAKEN("截屏事件"),
    VPN_CONNECTED("VPN 连接"),
    VPN_DISCONNECTED("VPN 断开"),
    APP_CRASHED("App 崩溃"),
    STORAGE_LOW("存储不足"),
    STORAGE_OK("存储恢复"),
    HOTSPOT_CHANGED("热点切换"),
    VOLUME_CHANGED("音量变化"),
    TIMEZONE_CHANGED("时区变化"),
    LOCALE_CHANGED("语言区域变化"),
    CALL_STATE_CHANGED("通话状态变化"),
    SCREEN_CONTENT_DETECTED("界面内容识别");
}
```

**EventModels.kt（统一基类 + 各事件 extra 数据类）:**

```kotlin
package com.bsfeng.monitor.event

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UnifiedEvent(
    val event_id: String,
    val event_type: String,
    val timestamp: Long,
    val device_id: String,
    val extra: JsonObject = JsonObject(emptyMap())
)

// 每个事件类型对应的 Extra 数据类
// 为简化，第一阶段直接用 Map<String, String> 构建 extra
// 后续阶段逐步替换为类型安全的 data class
```

**EventEntity.kt:**

```kotlin
package com.bsfeng.monitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val timestampMs: Long,
    val deviceId: String,
    val extraJson: String,       // JSON 字符串
    val isUploaded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
```

**EventDao.kt:**

```kotlin
package com.bsfeng.monitor.data

import androidx.room.*

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity)

    @Query("SELECT * FROM events WHERE isUploaded = 0 ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun getUnsentEvents(limit: Int = 50): List<EventEntity>

    @Query("UPDATE events SET isUploaded = 1 WHERE eventId IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("DELETE FROM events WHERE isUploaded = 1 AND createdAt < :before")
    suspend fun cleanOldEvents(before: Long)

    @Query("SELECT COUNT(*) FROM events WHERE isUploaded = 0")
    suspend fun countUnsent(): Int
}
```

**AppDatabase.kt:**

```kotlin
package com.bsfeng.monitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "monitor.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

- [ ] **Step 1:** 创建 EventType.kt —— 37 种事件类型枚举
- [ ] **Step 2:** 创建 EventModels.kt —— UnifiedEvent 数据类
- [ ] **Step 3:** 创建 EventEntity.kt —— Room Entity
- [ ] **Step 4:** 创建 EventDao.kt —— 增删查方法
- [ ] **Step 5:** 创建 AppDatabase.kt —— 数据库单例
- [ ] **Step 6:** `./gradlew assembleDebug` 验证编译通过

---

### Task 1.3: 编译时配置 + 设备 ID

**Files:**
- Modify: `app/build.gradle.kts`（buildConfigField 已在上一步添加）
- Create: `app/src/main/java/com/bsfeng/monitor/BuildConfig.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/util/AppUtils.kt`

**BuildConfig.kt（运行时配置常量，与 BuildConfig 字段对应）:**

```kotlin
package com.bsfeng.monitor

object AppConfig {
    const val SERVER_BASE_URL = BuildConfig.SERVER_BASE_URL
    const val UPLOAD_INTERVAL_MS = BuildConfig.UPLOAD_INTERVAL_MS
    const val BATCH_MAX_SIZE = BuildConfig.BATCH_MAX_SIZE
    const val CACHE_MAX_DAYS = BuildConfig.CACHE_MAX_DAYS
    const val RETRY_MAX_ATTEMPTS = BuildConfig.RETRY_MAX_ATTEMPTS
    const val NOTIFICATION_CONTENT_ENABLED = BuildConfig.NOTIFICATION_CONTENT_ENABLED
}
```

**AppUtils.kt（生成持久化设备 ID + 获取 App 名）:**

```kotlin
package com.bsfeng.monitor.util

import android.content.Context
import android.content.pm.PackageManager
import java.util.UUID

object AppUtils {
    private const val PREFS_NAME = "monitor_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
    }

    fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
```

- [ ] **Step 1:** 创建 AppConfig.kt
- [ ] **Step 2:** 创建 AppUtils.kt（设备 ID 持久化 + App 名查询）
- [ ] **Step 3:** `./gradlew assembleDebug` 编译通过

---

### Task 1.4: 基础网络 + 设备注册

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/network/ApiService.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/network/DeviceRegisterRequest.kt`

**ApiService.kt:**

```kotlin
package com.bsfeng.monitor.network

import com.bsfeng.monitor.event.UnifiedEvent
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.http.*

interface ApiService {

    @POST("events")
    suspend fun uploadEvents(
        @Body batch: EventsBatch,
        @Header("Authorization") token: String
    ): Response<UploadResponse>

    @POST("devices")
    suspend fun registerDevice(
        @Body request: DeviceRegisterRequest
    ): Response<DeviceRegisterResponse>

    @GET("heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") token: String
    ): Response<HeartbeatResponse>

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val contentType = "application/json".toMediaType()

        fun create(baseUrl: String, token: String): ApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(request)
                }
                .addInterceptor(logging)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(ApiService::class.java)
        }
    }
}

@Serializable
data class EventsBatch(
    val events: List<UnifiedEvent>,
    val batch_id: String,
    val device_id: String,
    val batch_created_at_ms: Long,
    val batch_size: Int
)

@Serializable
data class UploadResponse(
    val code: Int,
    val message: String,
    val data: UploadData? = null
)

@Serializable
data class UploadData(
    val accepted_count: Int,
    val rejected_count: Int,
    val rejected_events: List<RejectedEvent>? = null,
    val server_time_ms: Long? = null
)

@Serializable
data class RejectedEvent(val event_id: String, val reason: String)

@Serializable
data class DeviceRegisterRequest(
    val device_id: String,
    val device_name: String,
    val manufacturer: String,
    val model: String,
    val android_version: String,
    val app_version: String,
    val app_build: Int
)

@Serializable
data class DeviceRegisterResponse(
    val code: Int,
    val message: String,
    val data: DeviceRegisterData? = null
)

@Serializable
data class DeviceRegisterData(
    val device_id: String,
    val token: String,
    val token_expires_at_ms: Long,
    val config: ServerConfig? = null
)

@Serializable
data class ServerConfig(
    val upload_interval_ms: Int = 5000,
    val batch_max_size: Int = 50,
    val retry_max_attempts: Int = 5,
    val retry_backoff_base_ms: Int = 1000,
    val cache_max_days: Int = 3,
    val enable_notification_content: Boolean = true
)

@Serializable
data class HeartbeatResponse(
    val code: Int,
    val message: String,
    val data: HeartbeatData? = null
)

@Serializable
data class HeartbeatData(
    val server_time_ms: Long,
    val config: ServerConfig? = null
)
```

- [ ] **Step 1:** 创建 ApiService.kt —— Retrofit 接口 + 数据类
- [ ] **Step 2:** 创建 DeviceRegisterRequest.kt
- [ ] **Step 3:** `./gradlew assembleDebug` 编译通过

---

## 阶段二：AccessibilityService 核心监控

### Task 2.1: 实现 MonitorAccessibilityService

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/service/MonitorAccessibilityService.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Create: `app/src/main/java/com/bsfeng/monitor/event/EventCollector.kt`

**核心逻辑:**

1. `onAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)` 时：
   - 提取 `packageName` + `className` + `event.text`
   - 识别前台 App 切换 → 发出 `app_foreground`
   - 识别同 App 内 Activity 切换 → 发出 `activity_change`
   - 提取界面内容 → 发出 `screen_content_detected`
   - 计算上一个 App 的停留时长

2. `onAccessibilityEvent(TYPE_NOTIFICATION_STATE_CHANGED)` 时：
   - 提取包名 + 通知内容
   - 如果 `NOTIFICATION_CONTENT_ENABLED=true` 则含摘要
   - 发出 `notification_received`

- [ ] **Step 1:** 创建 accessibility_service_config.xml（配置监听事件类型）
- [ ] **Step 2:** 创建 EventCollector.kt（统一接收事件 → 生成 EventEntity → 写入 Room）
- [ ] **Step 3:** 实现 MonitorAccessibilityService 的 `onAccessibilityEvent` 方法
- [ ] **Step 4:** 实现前台 App 切换 + Activity 切换识别逻辑
- [ ] **Step 5:** 实现通知监听逻辑
- [ ] **Step 6:** `./gradlew assembleDebug` 编译通过

---

### Task 2.2: 界面内容提取（全应用 screen_content_detected）

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/event/ScreenClassifier.kt`
- Modify: `app/src/main/java/com/bsfeng/monitor/service/MonitorAccessibilityService.kt`

**ScreenClassifier.kt:**

```kotlin
package com.bsfeng.monitor.event

import android.view.accessibility.AccessibilityNodeInfo

data class ScreenContent(
    val screenTitle: String?,
    val screenType: String,        // chat / article / feed / video / settings / unknown
    val confidence: Float          // 0.0 - 1.0
)

object ScreenClassifier {
    private val cache = mutableMapOf<String, Long>()    // key: "pkg:title" -> timestamp
    private const val CACHE_TTL_MS = 30_000L           // 30s 去重

    fun classify(
        packageName: String,
        className: String?,
        windowText: List<CharSequence>?,
        rootNode: AccessibilityNodeInfo?
    ): ScreenContent? {
        val title = windowText?.joinToString("")?.takeIf { it.isNotBlank() }

        // 去重检查
        val cacheKey = "$packageName:$title"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { lastTime ->
            if (now - lastTime < CACHE_TTL_MS) return null
        }
        cache[cacheKey] = now

        val texts = mutableListOf<String>()
        rootNode?.let { traverseNode(it, texts, depth = 0, maxDepth = 3) }

        val screenType = classifyByPackage(packageName, className, title)
        return ScreenContent(
            screenTitle = title ?: texts.firstOrNull(),
            screenType = screenType,
            confidence = if (screenType != "unknown") 0.85f else 0.5f
        )
    }

    private fun traverseNode(node: AccessibilityNodeInfo, texts: MutableList<String>,
                              depth: Int, maxDepth: Int) {
        if (depth > maxDepth || texts.size >= 3) return
        node.text?.toString()?.takeIf { it.isNotBlank() && it.length > 2 }?.let {
            texts.add(it)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseNode(it, texts, depth + 1, maxDepth) }
        }
    }

    private fun classifyByPackage(pkg: String, cls: String?, title: String?): String {
        return when {
            pkg == "com.tencent.mm" && cls?.contains("ChattingUI") == true -> "chat"
            pkg == "com.tencent.mm" && cls?.contains("SnsTimeLineUI") == true -> "feed"
            pkg == "com.tencent.mm" && cls?.contains("BizTimeLineUI") == true -> "article"
            pkg == "com.tencent.mm" && cls?.contains("LauncherUI") == true -> "chat_list"
            cls?.contains("video", true) == true -> "video"
            cls?.contains("article", true) == true -> "article"
            cls?.contains("search", true) == true -> "search"
            cls?.contains("settings", true) == true -> "settings"
            else -> "unknown"
        }
    }

    fun cleanCache() { cache.clear() }
}
```

- [ ] **Step 1:** 创建 ScreenClassifier.kt —— 全应用界面内容分类器
- [ ] **Step 2:** 在 AccessibilityService 中集成 ScreenClassifier，`TYPE_WINDOW_STATE_CHANGED` 时调用
- [ ] **Step 3:** `./gradlew assembleDebug` 编译通过

---

## 阶段三：BroadcastReceiver 事件源

每个 Receiver 按统一模式实现：
1. 继承 `BroadcastReceiver`
2. 在 `onReceive` 中构造事件 → 调用 `EventCollector.emit()`
3. 动态注册时加 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` 标志
4. 在 `MonitorAccessibilityService` 或 `MonitorApp` 中注册

### Task 3.1: ScreenStateReceiver + CallStateReceiver

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/ScreenStateReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/CallStateReceiver.kt`

**ScreenStateReceiver 监听:**
- `ACTION_SCREEN_ON` → `screen_on`
- `ACTION_SCREEN_OFF` → `screen_off`
- `ACTION_USER_PRESENT` → `unlock_success`
- 解锁失败通过 `KeyguardManager` 推断（无直接广播）

**CallStateReceiver 监听:**
- `TelephonyManager.ACTION_PHONE_STATE` 广播
- `EXTRA_STATE` 取 `IDLE / RINGING / OFFHOOK`
- **不上报电话号码**

- [ ] **Step 1:** 实现 ScreenStateReceiver
- [ ] **Step 2:** 实现 CallStateReceiver
- [ ] **Step 3:** 在 MonitorAccessibilityService.onCreate() 中动态注册
- [ ] **Step 4:** `./gradlew assembleDebug`

---

### Task 3.2: BatteryReceiver + WifiReceiver + BluetoothReceiver

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/BatteryReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/WifiReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/BluetoothReceiver.kt`

**BatteryReceiver:**
- `ACTION_POWER_CONNECTED` → `charging_started`
- `ACTION_POWER_DISCONNECTED` → `charging_stopped`
- `ACTION_BATTERY_CHANGED` → 粘性广播，获取电量% + 温度 + 电压 → `battery_changed`

**WifiReceiver:**
- `WifiManager.NETWORK_STATE_CHANGED_ACTION` → `wifi_connected` / `wifi_disconnected`
- `ConnectivityManager.NetworkCallback` 补充

**BluetoothReceiver:**
- `BluetoothDevice.ACTION_ACL_CONNECTED` → `bluetooth_connected`
- `BluetoothDevice.ACTION_ACL_DISCONNECTED` → `bluetooth_disconnected`
- 需要 `BLUETOOTH_CONNECT` 运行时权限

- [ ] **Step 1:** 实现 BatteryReceiver
- [ ] **Step 2:** 实现 WifiReceiver（含 NetworkCallback）
- [ ] **Step 3:** 实现 BluetoothReceiver
- [ ] **Step 4:** 注册所有 Receiver
- [ ] **Step 5:** `./gradlew assembleDebug`

---

### Task 3.3: 其余 Receiver（耳机/安装/飞行/省电/存储/USB/热点/音量/时区）

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/HeadsetReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/PackageReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/AirplaneReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/PowerSaveReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/StorageReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/UsbReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/receiver/VolumeObserver.kt`

监听内容:
- `ACTION_HEADSET_PLUG` → `headset_plugged/unplugged`
- `ACTION_PACKAGE_ADDED/REMOVED`（data scheme="package"）→ `app_installed/uninstalled`
- `ACTION_AIRPLANE_MODE_CHANGED` → `airplane_mode_changed`
- `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED` → `power_save_mode_changed`
- `ACTION_DEVICE_STORAGE_LOW/OK` → `storage_low/ok`
- `UsbManager.ACTION_USB_DEVICE_ATTACHED/DETACHED` → `connected_to_pc/disconnected_from_pc`
- `ContentObserver on Settings.System.VOLUME_*` → `volume_changed`
- `ACTION_TIMEZONE_CHANGED` / `ACTION_LOCALE_CHANGED` → `timezone_changed` / `locale_changed`

- [ ] **Step 1:** 实现 HeadsetReceiver
- [ ] **Step 2:** 实现 PackageReceiver
- [ ] **Step 3:** 实现 AirplaneReceiver + PowerSaveReceiver + StorageReceiver
- [ ] **Step 4:** 实现 UsbReceiver
- [ ] **Step 5:** 实现 VolumeObserver（ContentObserver）
- [ ] **Step 6:** 注册所有 Receiver + Observer
- [ ] **Step 7:** `./gradlew assembleDebug`

---

## 阶段四：上报引擎

### Task 4.1: EventReporter（WorkManager 调度）

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/network/EventReporter.kt`

**EventReporter 职责:**
1. WorkManager 周期性任务（每 5 分钟）
2. 从 Room 取出未上报事件（最多 50 条）
3. 批量打包 → HTTP POST
4. 成功则标记已上传，失败则下次重试
5. 指数退避：1s → 2s → 4s → 8s → 16s
6. 超过 3 天的旧数据自动清理
7. 上报同时触发事件，事件也触发上报（双触发机制）

```kotlin
class EventReporter(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val api = ApiService.create(
        AppConfig.SERVER_BASE_URL,
        getStoredToken()
    )

    suspend fun uploadBatch(): Result<Int> {
        val events = db.eventDao().getUnsentEvents(AppConfig.BATCH_MAX_SIZE)
        if (events.isEmpty()) return Result.success(0)

        val batch = EventsBatch(
            events = events.map { it.toUnifiedEvent() },
            batch_id = UUID.randomUUID().toString(),
            device_id = AppUtils.getDeviceId(context),
            batch_created_at_ms = System.currentTimeMillis(),
            batch_size = events.size
        )

        return try {
            val response = api.uploadEvents(batch, getStoredToken())
            if (response.isSuccessful && response.body()?.code == 0) {
                db.eventDao().markUploaded(events.map { it.eventId })
                cleanOldEvents()
                Result.success(events.size)
            } else {
                Result.failure(Exception("Upload failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

- [ ] **Step 1:** 创建 EventReporter.kt（含 uploadBatch 和重试逻辑）
- [ ] **Step 2:** 实现 WorkManager Worker 调度周期性上报
- [ ] **Step 3:** 实现设备注册流程（首次启动自动 POST /devices）
- [ ] **Step 4:** 实现 Token 持久化 + 过期处理
- [ ] **Step 5:** 实现心跳 `/heartbeat`
- [ ] **Step 6:** `./gradlew assembleDebug`

---

## 阶段五：保活与容错

### Task 5.1: AlarmManager 保活 + BootReceiver

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/service/AlarmReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/service/BootReceiver.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/service/HealthChecker.kt`

**HealthChecker 职责:**
1. 每 30 分钟通过 AlarmManager 触发
2. 检查 AccessibilityService 是否存活（通过访问静态状态标记）
3. 如果服务死亡 → 发故障通知 + 尝试重启
4. 如果事件积压超过 1000 条 → 发故障通知
5. 如果网络中断超过 30 分钟 → 发故障通知

**BootReceiver 逻辑:**
1. 收到 `BOOT_COMPLETED` + `USER_PRESENT` 后
2. 延迟 10 秒（等系统完全启动）
3. 打开 MainActivity 触发服务自启
4. 启动 AlarmManager 保活定时器

- [ ] **Step 1:** 创建 HealthChecker.kt —— 服务存活检测逻辑
- [ ] **Step 2:** 创建 AlarmReceiver.kt —— 接收 AlarmManager 广播
- [ ] **Step 3:** 实现 AlarmManager 定时注册（每 30 分钟）
- [ ] **Step 4:** 创建 BootReceiver.kt —— 开机自启
- [ ] **Step 5:** 实现故障通知（仅在异常时弹通知）
- [ ] **Step 6:** `./gradlew assembleDebug`

---

## 阶段六：UI 界面

### Task 6.1: MainActivity + 底部导航框架

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/MainActivity.kt`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values/strings.xml`

- [ ] **Step 1:** 创建 themes.xml + strings.xml
- [ ] **Step 2:** 创建 MainActivity.kt 带 Navigation Compose 底部三栏导航
- [ ] **Step 3:** `./gradlew assembleDebug`

---

### Task 6.2: 实时监控页面（MainScreen）

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/ui/MainScreen.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/ui/MainViewModel.kt`

**显示内容:**
- 状态栏：监控中/已暂停状态
- 今日统计：事件总数、解锁次数、亮屏总时长
- TOP 3 App 使用时长
- 最近事件实时滚动列表（EventCard）

- [ ] **Step 1:** 创建 MainViewModel.kt（从 Room 读取数据）
- [ ] **Step 2:** 创建 MainScreen.kt（Compose 组件）
- [ ] **Step 3:** `./gradlew assembleDebug`

---

### Task 6.3: 统计页面 + 设置页面

**Files:**
- Create: `app/src/main/java/com/bsfeng/monitor/ui/StatsScreen.kt`
- Create: `app/src/main/java/com/bsfeng/monitor/ui/SettingsScreen.kt`

**StatsScreen:**
- 日/周/月维度 App 使用时长排行
- 解锁频率图表（文本版）

**SettingsScreen:**
- 无障碍服务状态 + 引导跳转
- 监控暂停/恢复开关
- 通知内容上报开关（默认开启）
- 电池优化白名单引导
- 自启动管理引导
- 关于信息（版本号 + 设备 ID）

- [ ] **Step 1:** 创建 StatsScreen.kt
- [ ] **Step 2:** 创建 SettingsScreen.kt
- [ ] **Step 3:** 创建 EventCard.kt（事件列表卡片组件）
- [ ] **Step 4:** `./gradlew assembleDebug`

---

## 阶段七：全量真机测试

### Task 7.1: 按测试清单逐项验证

**测试清单（共 28 项，覆盖 37 种事件 + 保活 + UI）：**

| 编号 | 测试项 | 验证方法 | 预期 |
|------|--------|---------|------|
| T1 | 安装 APK → 开启无障碍服务 | 观察设置页状态 | 状态变为"已开启" |
| T2 | 打开微信→桌面→抖音 | Logcat | `app_foreground` 含正确包名 |
| T3 | 微信内：首页→聊天→朋友圈 | Logcat | `activity_change` + `screen_content_detected` |
| T4 | 按电源键关屏→亮屏→解锁 | Logcat | `screen_off` → `screen_on` → `unlock_success` |
| T5 | 解锁失败 3 次 | Logcat | 3 条 `unlock_failed` |
| T6 | 插拔充电器 | Logcat | `charging_started/stopped` |
| T7 | 电量变化 ≥ 1% | Logcat | `battery_changed` |
| T8 | 切换 WiFi 网络 | Logcat | `wifi_connected/disconnected` + SSID |
| T9 | 连接/断开蓝牙耳机 | Logcat | `bluetooth_connected/disconnected` |
| T10 | 插拔耳机 | Logcat | `headset_plugged/unplugged` |
| T11 | 安装/卸载 App | Logcat | `app_installed/uninstalled` |
| T12 | 收到微信通知 → 滑掉 | Logcat | `notification_received/removed` |
| T13 | 开启/关闭飞行模式 | Logcat | `airplane_mode_changed` |
| T14 | 开启/关闭移动数据 | Logcat | `mobile_data_changed` |
| T15 | 开启/关闭省电模式 | Logcat | `power_save_mode_changed` |
| T16 | 截图一次 | Logcat | `screenshot_taken` |
| T17 | 开启/关闭 VPN | Logcat | `vpn_connected/disconnected` |
| T18 | 开启/关闭热点 | Logcat | `hotspot_changed` |
| T19 | 调节媒体音量 | Logcat | `volume_changed` |
| T20 | 修改时区/语言 | Logcat | `timezone_changed` / `locale_changed` |
| T21 | 拨打电话 → 挂断 | Logcat | `call_state_changed` |
| T22 | USB 连接电脑 | Logcat | `connected_to_pc` |
| T23 | 检查通知栏 | 肉眼观察 | 无任何 App 图标或通知 |
| T24 | 杀掉 App 进程 | 等待 30 分钟 | AlarmManager 重新拉起 |
| T25 | 手机重启 | 开机后 | BootReceiver 自动启动 |
| T26 | 打开主页面 | 观察 | 实时事件列表滚动显示 |
| T27 | 设置页：暂停监控 | 观察 | 事件停止采集，恢复后继续 |
| T28 | 24 小时连续运行 | 观察 | 无崩溃，事件完整 |

- [ ] **Step 1:** 逐项执行 T1-T14
- [ ] **Step 2:** 修复测试中发现的问题
- [ ] **Step 3:** 逐项执行 T15-T28
- [ ] **Step 4:** 修复所有问题后确认全部通过

---

## 阶段八：打包交付

### Task 8.1: Release 签名打包

**过程:**

1. 生成 keystore（如果不存在）:
   ```bash
   keytool -genkey -v -keystore monitor.keystore -alias monitor -keyalg RSA \
     -keysize 2048 -validity 36500 -storepass <password> -keypass <password>
   ```

2. 在 `app/build.gradle.kts` 中配置 signingConfigs:
   ```kotlin
   signingConfigs {
       release {
           storeFile = file("../monitor.keystore")
           storePassword = "<password>"
           keyAlias = "monitor"
           keyPassword = "<password>"
       }
   }
   buildTypes {
       release {
           signingConfig = signingConfigs.release
           isMinifyEnabled = true
           proguardFiles(...)
       }
   }
   ```

3. 打包:
   ```bash
   ./gradlew assembleRelease
   ```

4. APK 路径: `app/build/outputs/apk/release/app-release.apk`

**交付物:**
- `app-release.apk`（签名安装包，侧载安装）
- `monitor.keystore`（保留，后续升级使用同一签名）

- [ ] **Step 1:** 生成 keystore 并配置 signingConfigs
- [ ] **Step 2:** 配置 ProGuard 规则（保留 Kotlin Serialization + Retrofit）
- [ ] **Step 3:** `./gradlew assembleRelease` 打包
- [ ] **Step 4:** 验证 APK 可正常安装 + 运行
- [ ] **Step 5:** 交付 APK 文件
