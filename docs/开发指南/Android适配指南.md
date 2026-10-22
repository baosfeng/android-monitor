# Android 多版本适配指南（API 29~36）

> 覆盖 Android 10 (API 29) ~ Android 16 (API 36)。**实际配置：`compileSdk = 37`、`targetSdk = 37`、`minSdk = 29`**（以 `app/build.gradle.kts` 为准）。
> 只列对本项目有影响或已处理的变更；其余变更查官方 behavior-changes 页面。

## Android 16（API 36）

| 变更 | 对本项目 | 处理 |
|---|---|---|
| 强制全面屏（Edge-to-Edge），`windowOptOutEdgeToEdgeEnforcement` 弃用 | Material3 + `Scaffold` + `paddingValues` 天然支持 | 无需处理 |
| 预测性返回默认启用：`onBackPressed()` 不再调用、`KEYCODE_BACK` 不再分发 | Navigation Compose 已内置 | 无需处理 |
| Ordered Broadcast 的 `priority` 仅同进程生效 | 本项目全部同进程注册 | 不受影响 |
| 16 KB Page Size | 兼容需声明 | ✅ `android:pageSizeCompat="enabled"` |
| Intent 重定向安全保护（`removeLaunchSecurityProtection()`） | 只用标准系统 Intent | 无需处理 |
| JobScheduler 配额、无障碍公告 API（`announceForAccessibility`/`TYPE_ANNOUNCEMENT`）弃用、ART 内部变更 | 用 AlarmManager、未使用公告 API | 不受影响（依赖 ART 内部结构的库需测试） |
| 自动主题化图标 | 需 `monochrome` 图层 | ✅ 已添加 |

## Android 15（API 35）

- `dataSync` / `mediaProcessing` 前台服务 24 小时内最多 6 小时，超时回调 `onTimeout()` 需数秒内 `stopSelf()`；`BOOT_COMPLETED` 中不允许启动 dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone 类型前台服务；`SYSTEM_ALERT_WINDOW` 后台启动 FGS 豁免收紧。**本项目不使用前台服务，不受影响。**
- 包停止状态更严格：应用被停止后小部件禁用。
- 16 KB 内存页：纯 Java/Kotlin 不受影响，native 需重编。
- `targetSdkVersion < 24` 的应用无法安装。其余（PNG emoji 移除、隐私空间、OTP 防护、后台网络限制）与本项目无关。

## Android 14（API 34）

target 34 相关：
- 前台服务必须在 Manifest 声明 `foregroundServiceType`（本项目无前台服务）。
- **Context 注册广播必须传 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`**（系统专用广播如 `BATTERY_LOW` 例外）。✅ `ReceiverRegistrar` / `MonitorApp` 均已传 `RECEIVER_EXPORTED`。
- `BLUETOOTH_CONNECT` 强制执行（`getProfileConnectionState()` 会严格检查）。✅ 已声明。
- 隐式 Intent 只发给 exported 组件；Mutable `PendingIntent` 必须指定组件（否则抛异常）；动态代码加载的文件必须标记只读；Zip 条目名含 `..` 或 `/` 开头抛 `ZipException`。
- JobScheduler `onStartJob`/`onStopJob` 超时导致 ANR；`setRequiredNetworkType()` 需 `ACCESS_NETWORK_STATE`。

影响所有应用：
- **`SCHEDULE_EXACT_ALARM` 默认拒绝**：新安装应用不授予。本项目用 `setInexactRepeating()`（非精确闹钟）不受限；如需精确触发，必须声明权限并处理用户拒绝。
- `killBackgroundProcesses()` 只能杀自己；缓存状态下上下文广播排队到前台投递；`FLAG_ONGOING_EVENT` 通知用户可关闭；字体缩放支持到 200%（非线性，需测最大字号）；MediaProjection 每次会话需用户同意。

## Android 13（API 33）

- **运行时通知权限 `POST_NOTIFICATIONS`**：拒绝后前台服务通知不显示。✅ `MainActivity` 动态请求。
- 细粒度媒体权限（`READ_MEDIA_*`）：本项目不用外部存储。
- `NEARBY_WIFI_DEVICES` 取代 `ACCESS_FINE_LOCATION` 的 Wi-Fi 用例。✅ 已声明。
- 广告 ID 权限：不使用。
- 其它：任务管理器可停止前台服务；受限待机桶下无法启 FGS、闹钟不触发、Job 不执行；高优先级 FCM 不出通知会被降级；剪贴板敏感标记；`sharedUserId` 弃用；WebView `setForceDark` 弃用；`BluetoothAdapter.enable/disable` 弃用（恒返回 false）。

## Android 12（API 31~32）

- **`android:exported` 必填**：有 Intent Filter 的 Activity/Service/Receiver 不声明则**无法安装**。✅ `MainActivity` 已 `exported="true"`，接收器已设。
- **`PendingIntent` 必须指定 `FLAG_IMMUTABLE` / `FLAG_MUTABLE`**。✅ `AlarmReceiver`、`HealthChecker` 用 `FLAG_IMMUTABLE`。
- 后台不能启动前台服务；通知跳板（Service/Receiver 直接起 Activity）禁止 —— 本项目均不涉及。
- 精确闹钟需 `SCHEDULE_EXACT_ALARM` 且用户手动授予（当前用非精确闹钟，不需要）。
- 蓝牙权限拆分（`BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT`）。✅ 已声明 `BLUETOOTH_CONNECT`。
- 自定义通知折叠高度 106dp → 48dp；Motion 传感器限速；粗略位置可选。
- 影响所有应用：系统始终显示 Splash（自定义需迁 `SplashScreen` API）；Web Intent 仅域名已验证时解析；读剪贴板弹 Toast；麦克风/摄像头全局开关；BouncyCastle 移除（迁 Conscrypt，512 位密钥不支持，GCM IV 必须 12 字节）；`Display.getRealSize()` 弃用（改 `WindowManager.getCurrentWindowMetrics()`）；≥600dp 屏即使 `resizeableActivity="false"` 也在多窗口；根 Launcher Activity 返回键把任务移后台。

## Android 11（API 30）

- **包可见性**：`queryIntentActivities()` 返回过滤列表，需在 Manifest 声明 `<queries>`。⚠️ 用 `queryIntentActivities` 查应用信息时检查此项。
- Scoped Storage 强制（本项目不用外部存储）；后台位置需去系统设置授予；权限自动重置（数月未交互撤销敏感权限）。
- 影响所有应用：一次性权限；后台自定义 View Toast 被静默丢弃；`getIccId()` 返回空（改 `getSubscriptionId()`）；仅 v1 签名的 APK 无法安装；Firebase JobDispatcher / GCMNetworkManager 禁用（迁 WorkManager）。

## Android 10（API 29）

- Scoped Storage（`WRITE_EXTERNAL_STORAGE` 不再给共享存储）；新增 `ACCESS_BACKGROUND_LOCATION`；通知全屏 Intent 需 `USE_FULL_SCREEN_INTENT`；多 Resume Activity（`onTopResumedActivityChanged()` 处理独占资源）。
- 影响所有应用：深色主题（`isLightTheme`）；手势导航（内容延伸到边缘、避免与返回手势冲突）；TLS 1.3 默认启用（SHA-1 证书不受信）；MAC 地址随机化（返回 `02:00:00:00:00:00`）；不能直接开关/配置 Wi-Fi（用 `WifiNetworkSpecifier`/`WifiNetworkSuggestion`）；`android.preference` 弃用（迁 AndroidX）。

## 本项目合规清单

| 项 | 状态 | 说明 |
|---|---|---|
| `android:exported` 全组件 | ✅ | 已设置 |
| `android:pageSizeCompat="enabled"` | ✅ | 16 KB 兼容 |
| `usesCleartextTraffic="true"` | ✅ | 局域网 HTTP 明文 |
| `SCHEDULE_EXACT_ALARM` | ✅ | 用非精确闹钟，暂不需要 |
| `<queries>` 包可见性 | ⚠️ | 用 `queryIntentActivities` 前需确认 |
| `POST_NOTIFICATIONS` | ✅ | Manifest + 运行时请求 |
| Context 广播 export 标志 | ✅ | 统一 `RECEIVER_EXPORTED` |
| 跨进程广播优先级 | ✅ | 同进程注册 |
| 前台服务 | ✅ | 不使用 |
| `PendingIntent` | ✅ | 用 `FLAG_IMMUTABLE` |
| `NEARBY_WIFI_DEVICES` / `BLUETOOTH_CONNECT` | ✅ | 已声明 |
| 媒体权限 | ✅ | 不用外部存储 |

> ⚠️ **minSdk=29 仍需要 `Build.VERSION.SDK_INT` 守卫**（现存 8 处）：minSdk 29 意味着 Android 10~15 设备仍会安装，API 30+ 能力（如无障碍截图）必须做版本判断。

## 参考

[16](https://developer.android.com/about/versions/16/behavior-changes-16) · [15](https://developer.android.com/about/versions/15/behavior-changes-15) · [14](https://developer.android.com/about/versions/14/behavior-changes-14) · [13](https://developer.android.com/about/versions/13/behavior-changes-13) · [12](https://developer.android.com/about/versions/12/behavior-changes-12) · [11](https://developer.android.com/about/versions/11/behavior-changes-11) · [10](https://developer.android.com/about/versions/10/behavior-changes-10)
