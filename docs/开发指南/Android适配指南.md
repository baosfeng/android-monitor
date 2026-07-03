# Android 多版本适配指南（API 29~36）

> **版本范围：** Android 10 (API 29) ~ Android 16 (API 36)
> **当前项目配置：** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 36`
> **最后更新：** 2025-07-03

---

## 快速导航

| 版本 | API | 代号 | 关键影响 |
|------|-----|------|---------|
| [16](#android-16-api-36) | 36 | — | 强制全面屏、16KB页面、Broadcast优先级 |
| [15](#android-15-api-35) | 35 | — | 前台服务超时、`BOOT_COMPLETED` 限制、隐私空间 |
| [14](#android-14-api-34) | 34 | — | FGS类型必填、Broadcast Export标志、精确闹钟 |
| [13](#android-13-api-33) | 33 | Tiramisu | 通知权限、细粒度媒体权限、WiFi权限分离 |
| [12](#android-12-api-31) | 31~32 | Snow Cone | `android:exported` 必填、`PendingIntent` 可变性、SplashScreen |
| [11](#android-11-api-30) | 30 | Red Velvet Cake | 包可见性、Scoped Storage、一次性权限 |
| [10](#android-10-api-29) | 29 | Queen Cake | 深色主题、手势导航、TLS 1.3 |

---

## Android 16 (API 36)

### 行为变更（仅影响 target 36 的应用）

#### 🔴 强制全面屏（Edge-to-Edge）
`R.attr#windowOptOutEdgeToEdgeEnforcement` 弃用并禁用，应用**无法选择退出全面屏模式**。

> 本项目使用 Material3 + `Scaffold` + `paddingValues`，已天然支持。

#### 🔴 预测性返回（Predictive Back）默认启用
- `onBackPressed()` 不再调用
- `KeyEvent.KEYCODE_BACK` 不再分发
- Navigation Compose 已内置支持，无需额外处理

#### 🔴 Ordered Broadcast 优先级仅同进程生效
- `android:priority` 和 `IntentFilter.setPriority()` 不再跨进程保证顺序
- 本项目所有 BroadcastReceiver 均为同进程注册，**不受影响**

#### 🔴 16 KB Page Size 兼容
- ✅ 已处理：`AndroidManifest.xml` 添加 `android:pageSizeCompat="enabled"`

#### 🟡 Intent 重定向安全保护
- 新的 `removeLaunchSecurityProtection()` API
- 本项目使用标准系统 Intent，无不受信的 sub-intent 传递

### 行为变更（影响所有应用）

#### 🟡 JobScheduler 配额优化
- 根据待机模式和应用可见性限制任务运行时间
- 本项目使用 AlarmManager，**不受影响**

#### 🟡 无障碍公告 API 弃用
- `announceForAccessibility()` 和 `TYPE_ANNOUNCEMENT` 弃用
- 本项目未直接使用

#### 🟡 自动主题化应用图标
- ✅ 已处理：已添加 `monochrome` 图层到 adaptive-icon

#### 🟡 ART 内部变更
- 依赖 ART 内部结构的库可能不兼容，需测试

---

## Android 15 (API 35)

### 行为变更（仅影响 target 35 的应用）

#### 🔴 前台服务（Foreground Service）超时行为
- `dataSync` 类型前台服务：**24小时内最多运行6小时**
- 超时后调用 `Service.onTimeout(int, int)`，需在数秒内调用 `stopSelf()`
- 新增 `mediaProcessing` 前台服务类型，同样适用6小时限制
> 本项目不使用前台服务，**不受影响**

#### 🔴 `BOOT_COMPLETED` 广播接收器限制
- `BOOT_COMPLETED` 中不允许启动以下类型前台服务：
  `dataSync`、`camera`、`mediaPlayback`、`phoneCall`、`mediaProjection`、`microphone`

#### 🔴 `SYSTEM_ALERT_WINDOW` 权限豁免收紧
- 持有 `SYSTEM_ALERT_WINDOW` 权限的应用在后台启动前台服务的豁免范围缩小

#### 🔴 新增前台服务类型 `mediaProcessing`
- 用于媒体转码等操作，同样适用6小时时间限制

### 行为变更（影响所有应用）

#### 🔴 包停止状态（Package Stopped State）变化
- 系统现在更严格地管理应用的停止状态
- 应用被停止后，小部件将被禁用
- 新 API：`PackageManager.setUninstallBlockedForUserByInstaller()`

#### 🟡 16 KB Page Size 支持（AOSP 层面）
- Android 15 开始 AOSP 支持 16 KB 内存页
- 纯 Java/Kotlin 应用不受影响，native 代码需重新编译

#### 🟡 PNG Emoji 字体移除
- 仅保留矢量 emoji 文件
- 如果应用内置了 PNG emoji 字体，需切换到矢量版本

#### 🟡 安全增强
- `targetSdkVersion < 24` 的应用**无法安装**
- OTP 诈骗防护增强
- 后台网络访问限制

#### 🟡 隐私空间（Private Space）
- 启动器应用需适配显示私有空间中的应用

---

## Android 14 (API 34)

### 行为变更（仅影响 target 34 的应用）

#### 🔴 前台服务类型必填
- **每个前台服务**必须在 Manifest 中声明 `android:foregroundServiceType`
- 新增类型：`health`、`remoteMessaging`、`shortService`、`specialUse`
- 不符合任何类型的场景迁移到 WorkManager

#### 🔴 Context-Registered Broadcast 必须指定 Export 行为
- 必须传 `RECEIVER_EXPORTED` 或 `RECEIVER_NOT_EXPORTED` 标志
- 例外：系统专用广播（如 `BATTERY_LOW`）不需要
> ✅ **本项目已兼容：** `ReceiverRegistrar` 中所有注册均传了 `RECEIVER_EXPORTED` 标志

#### 🔴 `BLUETOOTH_CONNECT` 权限强制执行
- `BluetoothAdapter.getProfileConnectionState()` 现在严格检查 `BLUETOOTH_CONNECT` 权限
> ✅ **已声明**：`AndroidManifest.xml` 中已有 `<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />`

#### 🔴 隐式 Intent 仅发送给 exported 组件
- 如果组件 `exported="false"`，必须使用显式 Intent（指定包名）

#### 🔴 Mutable PendingIntent 必须指定组件
- 不指定组件或包名的 Mutable PendingIntent **抛出异常**

#### 🔴 动态代码加载（DCL）安全增强
- 所有动态加载的文件（DEX、JAR、APK）必须**标记为只读**

#### 🟡 Zip 路径遍历防护
- `ZipFile(String)` 和 `ZipInputStream.getNextEntry()` 在条目名包含 `..` 或 `/` 开头时抛出 `ZipException`

#### 🟡 JobScheduler ANR + 网络权限
- `onStartJob` / `onStopJob` 超时导致 **ANR**
- 使用 `setRequiredNetworkType()` 需要 `ACCESS_NETWORK_STATE` 权限

### 行为变更（影响所有应用）

#### 🔴 精确闹钟（`SCHEDULE_EXACT_ALARM`）默认拒绝
- 新安装的应用默认**不授予** `SCHEDULE_EXACT_ALARM` 权限
> ⚠️ **本项目使用 AlarmManager** — 需要确保 `AndroidManifest.xml` 中声明了 `SCHEDULE_EXACT_ALARM` 权限
> 当前使用 `setInexactRepeating()`，属于非精确闹钟，不受此限制影响。
> 但如需精确触发，需声明权限并处理用户拒绝的情况。

#### 🟡 `killBackgroundProcesses()` 仅杀死自己的进程
- 传入其他应用的包名**无效**

#### 🟡 上下文注册的广播在缓存状态下排队
- 应用进入缓存状态后，广播被排队，回到前台时才投递

#### 🟡 非可关闭的前台通知现可关闭
- `FLAG_ONGOING_EVENT` 通知现在用户可关闭

#### 🟡 字体缩放支持到 200%（非线性）
- 测试 UI 在最大字号下是否正常显示

#### 🟡 MediaProjection 每次会话需要用户同意
- 不能缓存/复用 `MediaProjection` 实例

---

## Android 13 (API 33)

### 行为变更（仅影响 target 33 的应用）

#### 🔴 运行时通知权限
- **必须运行时请求** `POST_NOTIFICATIONS` 权限
- 用户拒绝后前台服务通知不显示在通知栏
> ✅ **本项目已处理：** `MainActivity` 中动态请求 `POST_NOTIFICATIONS`

#### 🔴 细粒度媒体权限
- `READ_EXTERNAL_STORAGE` 弃用
- 拆分为 `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO`
> 本项目不使用外部存储，**不受影响**

#### 🔴 附近 Wi-Fi 设备权限
- 新增 `NEARBY_WIFI_DEVICES` 权限，替代 `ACCESS_FINE_LOCATION` 的 Wi-Fi 用例
> ✅ **已声明：** `AndroidManifest.xml` 中已有 `NEARBY_WIFI_DEVICES`

#### 🔴 广告 ID 权限
- 使用 Google Play 广告 ID 需声明 `com.google.android.gms.permission.AD_ID`
> 本项目不使用广告 ID，**不受影响**

#### 🟡 `BODY_SENSORS_BACKGROUND` 权限
- 后台访问身体传感器需要该权限（硬限制）

#### 🟡 WebView `setForceDark()` 弃用
- 使用 `setAlgorithmicDarkeningAllowed()` 替代

#### 🟡 `BluetoothAdapter.enable()/disable()` 弃用
- 始终返回 `false`（系统/设备管理员除外）

### 行为变更（影响所有应用）

#### 🔴 任务管理器可以停止前台服务
- 用户可以直接从通知栏停止应用的前台服务
- 应用需要优雅处理被停止的情况

#### 🟡 电池资源利用限制
- 应用被放入"受限"待机桶后：无法启动前台服务、闹钟不触发、Job 不执行

#### 🟡 高优先级 FCM 配额
- 如果高优先级 FCM 不产生通知，将被降级为普通优先级

#### 🟡 Clipboard 敏感内容标记
- 复制密码/信用卡等敏感信息时可设置 `ClipDescription.EXTRA_IS_SENSITIVE`

#### 🟡 `sharedUserId` 弃用
- 设置 `android:sharedUserMaxSdkVersion="32"` 表明应用不再使用

---

## Android 12 (API 31~32)

### 行为变更（仅影响 target 31+ 的应用）

#### 🔴 `android:exported` 必填
- **每个**有 Intent Filter 的 Activity/Service/Receiver **必须**声明 `android:exported`
- 不声明则**无法安装**
> ✅ **本项目：** `MainActivity` 已设 `exported="true"`，广播接收器已设

#### 🔴 `PendingIntent` 可变性必填
- 每个 `PendingIntent` 必须指定 `FLAG_IMMUTABLE` 或 `FLAG_MUTABLE`
> ✅ **本项目：** `AlarmReceiver` 和 `HealthChecker` 中已使用 `FLAG_IMMUTABLE`

#### 🔴 后台启动前台服务限制
- 后台不能启动前台服务（精确闹钟触发等例外）
> 本项目不启动前台服务，**不受影响**

#### 🔴 通知跳板（Notification Trampoline）禁止
- 从 Service 或 BroadcastReceiver 启动 Activity 响应通知点击是**禁止的**
> ✅ 本项目未使用此模式

#### 🔴 自定义通知新标准模板
- 完全自定义通知不再使用完整区域
- 折叠高度从 106dp → 48dp

#### 🔴 精确闹钟权限
- 需要声明 `SCHEDULE_EXACT_ALARM` 权限，用户必须在设置中手动授予
> ⚠️ 需确认是否已声明

#### 🔴 蓝牙权限拆分
- `BLUETOOTH` → `BLUETOOTH_SCAN` + `BLUETOOTH_ADVERTISE` + `BLUETOOTH_CONNECT`
> ✅ **已声明：** `BLUETOOTH_CONNECT` 已在 Manifest 中

#### 🔴 Motion 传感器限速
- 运动/位置传感器刷新率受限

#### 🟡 粗略位置
- 用户可以授予粗略位置而非精确位置

#### 🟡 `adb backup` 默认排除应用数据
- 针对 target 12+ 的应用

### 行为变更（影响所有应用）

#### 🔴 App Splash Screen（启动画面）
- 系统**始终**显示启动画面（冷/热启动）
- 如有自定义启动画面，需迁移到 `SplashScreen` API

#### 🔴 Web Intent 解析变化
- 通用 Web Intent 仅在域名已验证时才解析到应用

#### 🔴 剪贴板访问通知
- 读取来自其他应用的剪贴板数据时弹出 Toast

#### 🔴 麦克风/摄像头开关与指示器
- 用户可全局禁用麦克风/摄像头
- 状态栏图标提示

#### 🟡 前台服务通知 10 秒延迟
- 短运行前台服务的通知可能延迟最多 10 秒显示

#### 🟡 BouncyCastle 加密库移除
- 迁移到 Conscrypt
- 512 位密钥不支持
- GCM 初始化向量必须是 12 字节

#### 🟡 `Display.getRealSize()` / `getRealMetrics()` 弃用
- 使用 `WindowManager.getCurrentWindowMetrics()` 替代

#### 🟡 多窗口在大屏幕上是标准
- ≥600dp 屏幕：即使 `resizeableActivity="false"` 也运行在多窗口模式

#### 🟡 Root Launcher Activity Back 行为变化
- 按返回键将任务移到后台而非结束 Activity

---

## Android 11 (API 30)

### 行为变更（仅影响 target 30 的应用）

#### 🔴 包可见性（Package Visibility）
- `queryIntentActivities()` 返回过滤列表
- 需要在 `AndroidManifest.xml` 中声明 `<queries>` 元素
> ⚠️ 本项目使用 `queryIntentActivities` 检查应用信息时需注意

#### 🔴 Scoped Storage 强制执行
- 只能访问应用专属目录和自有媒体
- 使用 `MediaStore` / `SAF` / `FileProvider` 访问共享存储
> 本项目不使用外部存储，**不受影响**

#### 🔴 后台位置权限变化
- 用户必须去系统设置授予后台位置权限
- 应用内运行时弹框不再提供"始终允许"选项

#### 🔴 权限自动重置
- 用户数月未与应用交互后，系统自动撤销所有敏感权限

### 行为变更（影响所有应用）

#### 🔴 一次性权限
- 用户可以授予"仅本次"的位置/相机/麦克风权限

#### 🟡 自定义后台 Toast 禁止
- 后台自定义 View Toast 被静默丢弃

#### 🟡 SIM 标识符
- `getIccId()` 返回空字符串，使用 `getSubscriptionId()` 替代

#### 🟡 APK 签名 Scheme v2 要求
- 仅 v1 签名的 APK 无法安装

#### 🟡 Firebase JobDispatcher / GCMNetworkManager 禁用
- 迁移到 WorkManager

---

## Android 10 (API 29)

### 行为变更（仅影响 target 29 的应用）

#### 🔴 Scoped Storage
- `WRITE_EXTERNAL_STORAGE` 不再授予共享存储访问权限
- 使用 `MediaStore` 和 `SAF` 替代

#### 🔴 后台位置权限
- 新增 `ACCESS_BACKGROUND_LOCATION` 权限，需单独申请

#### 🔴 全屏 Intent 权限
- 通知中的全屏 Intent 必须声明 `USE_FULL_SCREEN_INTENT` 权限

#### 🔴 多 Resume Activity
- 多个顶层 Activity 可同时处于 resumed 状态
- `onResume()` / `onPause()` 行为变化
- 使用 `onTopResumedActivityChanged()` 处理独占资源

### 行为变更（影响所有应用）

#### 🔴 深色主题（Dark Theme）
- 系统支持深色主题，应用可通过 `isLightTheme` 适配

#### 🔴 手势导航
- 内容需延伸到边缘全屏
- 处理边缘滑动避免与系统返回手势冲突

#### 🔴 TLS 1.3 默认启用
- TLS 1.3 密码套件不可自定义
- SHA-1 证书不受信任

#### 🟡 MAC 地址随机化
- 无法通过标准 API 获取设备 MAC 地址（返回 `02:00:00:00:00:00`）

#### 🟡 Wi-Fi 配置变化
- 不能直接启用/禁用/配置 Wi-Fi
- 使用 `WifiNetworkSpecifier` / `WifiNetworkSuggestion` 替代

#### 🟡 Android Beam 弃用
- NFC Beam 和相关 API 弃用

#### 🟡 `android.preference` 库弃用
- 迁移到 AndroidX Preference 库

---

## 本项目合规检查清单

### AndroidManifest.xml

| 配置项 | 状态 | 说明 |
|--------|------|------|
| `android:exported` 所有组件 | ✅ | 已正确设置 |
| `android:pageSizeCompat="enabled"` | ✅ | 16 KB 页面兼容 |
| `usesCleartextTraffic="true"` | ✅ | HTTP 明文连接 |
| `SCHEDULE_EXACT_ALARM` 权限 | ⚠️ | 当前使用非精确闹钟，暂不需要 |
| `<queries>` 包可见性声明 | ⚠️ | 如使用 `queryIntentActivities` 需检查 |
| `POST_NOTIFICATIONS` 权限声明 | ✅ | 已在 Manifest 和代码中处理 |

### 广播接收器

| 项目 | 状态 | 说明 |
|------|------|------|
| Context-registered 传 Export 标志 | ✅ | API 33+ 已用 `RECEIVER_EXPORTED` |
| 跨进程 Broadcast 优先级 | ✅ | 同进程注册，不受限 |

### 前台服务

本项目不使用前台服务。

### PendingIntent

| 项目 | 状态 | 说明 |
|------|------|------|
| 指定 `FLAG_IMMUTABLE`/`FLAG_MUTABLE` | ✅ | 已使用 `FLAG_IMMUTABLE` |

### 通知

| 项目 | 状态 | 说明 |
|------|------|------|
| 运行时请求通知权限 | ✅ | API 33+ 动态请求 |

### 隐私与权限

| 项目 | 状态 | 说明 |
|------|------|------|
| `NEARBY_WIFI_DEVICES` | ✅ | 已声明 |
| `BLUETOOTH_CONNECT` | ✅ | 已声明 |
| 媒体权限（READ_MEDIA_*） | ✅ | 不使用外部存储 |

---

## 参考链接

- [Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android 15 行为变更](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Android 14 行为变更](https://developer.android.com/about/versions/14/behavior-changes-14)
- [Android 13 行为变更](https://developer.android.com/about/versions/13/behavior-changes-13)
- [Android 12 行为变更](https://developer.android.com/about/versions/12/behavior-changes-12)
- [Android 11 行为变更](https://developer.android.com/about/versions/11/behavior-changes-11)
- [Android 10 行为变更](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Android 版 Kotlin 兼容性](https://developer.android.com/kotlin)
- [Compose BOM 版本](https://developer.android.com/jetpack/compose/bom)
