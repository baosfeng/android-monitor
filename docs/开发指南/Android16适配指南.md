# Android 16 (API 36) 适配指南

> 最后更新：2026-10-23

---

## 一、项目当前版本栈

版本清单以 [构建与测试](构建与测试.md) 为唯一事实源，本文只记 Android 16 行为变更。

---

## 二、Android 16 行为变更 — 对本项目的影响

### 🔴 需要处理

#### 1. 16 KB Page Size 兼容模式

Android 16 默认对 4 KB 对齐的应用弹出提示对话框。需要在 `AndroidManifest.xml` 中声明：

```xml
<application
    android:pageSizeCompat="true"
    ...>
```

这样系统会自动启用兼容模式，不会弹出对话框。长期方案是将 APK 对齐到 16 KB（需 Android Studio 插件支持）。

**同时影响：** 如果后续编译使用 16 KB 对齐，所有 native 库也需要重新编译对齐。

#### 2. 强制全面屏 (Edge-to-Edge)

Android 16 禁用了 `windowOptOutEdgeToEdgeEnforcement`。当前项目使用 Material3 + `Scaffold` + `padding(paddingValues)`，**已经支持全面屏**。

<!-- 待确认：MainActivity 的 theme 是否有相关设置 -->
项目使用的 theme：`android:Theme.Material.Light.NoActionBar` — 没有设置窗口装饰，不影响。

✅ 当前代码兼容，无需修改。

#### 3. Ordered Broadcast 优先级不再跨进程

Android 16 限制 `android:priority` 和 `IntentFilter.setPriority()` 仅在同一进程内生效。本项目所有 BroadcastReceiver 均通过 `context.registerReceiver()` 在主进程注册，**不受影响**。

✅ 当前代码兼容，无需修改。

#### 4. 预测性返回 (Predictive Back) 默认启用

Android 16 默认启用预测性返回动画，`onBackPressed()` 不再调用，`KEYCODE_BACK` 不再分发。

本项目使用 **Navigation Compose**（`2.10.2`），已经内置支持预测性返回。无需额外处理。

✅ 当前代码兼容，无需修改。

#### 5. Intent 重定向安全保护

Android 16 默认对 Intent 重定向攻击提供保护。本项目在 `MainActivity` 中使用 `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)` 等标准 Intent 打开系统设置，**不存在未受信的 sub-intent 传递**。

✅ 当前代码兼容，无需修改。

---

### 🟡 值得关注的变更

#### 6. JobScheduler 配额优化

Android 16 调整了 JobScheduler 的运行配额，根据应用待机模式和应用可见性来限制任务执行时间。

本项目使用 **AlarmManager**（`setInexactRepeating`）而非 JobScheduler，所以**不受影响**。但需要注意：
- `HealthChecker` 每 30 分钟通过 AlarmManager 触发一次
- 如果未来改用 WorkManager 或 JobScheduler，需要了解配额机制

✅ 当前代码兼容，无需修改。

#### 7. 无障碍公告 API 弃用

`announceForAccessibility()` 和 `TYPE_ANNOUNCEMENT` 被弃用。建议使用：
- `setAccessibilityPaneTitle()` / `Modifier.semantics { paneTitle }` — 窗口变化
- `setAccessibilityLiveRegion()` / `LiveRegionMode` — UI 更新
- `AccessibilityEvent.CONTENT_CHANGE_TYPE_ERROR` — 错误提示

本项目未直接使用上述弃用 API。

✅ 当前代码兼容，无需修改。

#### 8. 自动主题化应用图标

Android 16 自动给应用图标应用主题色。本项目已有 adaptive icon（`ic_launcher_foreground.xml` 白色眼睛 + 深蓝背景）。

为了更好的主题化效果，应该**添加 monochrome 图层**（单色图标），这样系统在应用主题色时不会把有色图标变成灰蒙蒙的效果。

> **后续优化项** — 参见过时的「17. 添加 Monochrome 图标图层」

---

### 🟢 不相关的新特性

| 特性 | 说明 | 与本项目关系 |
|------|------|-------------|
| Health & Fitness 权限细化 | `BODY_SENSORS` → `android.permissions.health` | 不使用传感器 |
| 16 KB Page Size | 兼容模式已处理 | 见上方 🔴 |
| `JobInfo#setImportantWhileForeground` 完全移除 | | 不使用 JobScheduler |
| `elegantTextHeight` 弃用 | 字体属性 | Compose 不涉及 |
| Virtual Device Owner | 虚拟设备投影 | 不相关 |

---

## 三、Kotlin / Compose 语法注意事项

### 3.1 Kotlin 2.4.21 特性与兼容性

当前使用 Kotlin 2.4.21，支持以下现代语法（已在项目中使用的标记 ✅）：

| 特性 | 状态 | 说明 |
|------|------|------|
| `data class` ✅ | 已大量使用 | 事件模型、DTO 等 |
| `object` 单例 ✅ | `EventCollector`, `HealthChecker`, `AppUtils` 等 | 推荐使用 |
| `sealed class` / `sealed interface` ✅ | `Screen` 密封类 | 状态管理等场景使用 |
| `companion object` ✅ | `DatabaseHelper`, `EventType`, `MonitorApp` | 推荐 |
| `StateFlow` + `collectAsState()` ✅ | `MainViewModel` | Compose 标准模式 |
| `viewModelScope.launch` ✅ | `loadData()`, `uploadNow()` | 协程标准用法 |
| `CoroutineScope` 手动管理 ✅ | `AlarmReceiver` 中使用 | 注意及时取消 |
| 扩展函数 | 可使用 | 工具类场景 |
| `Result<T>` ✅ | `EventReporter` 返回值 | Kotlin 标准结果类型 |

### 3.2 Compose 最新推荐写法

| 模式 | 本项目现状 | 建议 |
|------|-----------|------|
| `Scaffold` + `paddingValues` | ✅ 已使用 | 标准全面屏适配 |
| `NavigationBar` (Material3) | ✅ 已使用 | 底部导航栏 |
| `LazyColumn` + `items` | ✅ 已使用 | 事件列表 |
| `Card` / `MaterialTheme` | ✅ 已使用 | Material3 |
| `collectAsState()` with `by` | ✅ 已使用 | `val state by viewModel.state.collectAsState()` |
| `LaunchedEffect` | ✅ 已使用 | 页面加载时刷新数据 |

### 3.3 已发现的可优化写法

1. **`AlarmReceiver` 中的 `CoroutineScope` 未取消** — `scope.launch` 创建的协程如果广播接收器执行完毕而协程还在运行，可能导致内存泄漏。建议使用 `GlobalScope` 或 `application` 作用域。

2. **`MainViewModel` 使用 `MonitorApp.instance` 获取 Application 实例** — 这是 Kotlin + Compose 中 ViewModel 获取 Context 的常见方式。如果未来使用 Hilt 或 manual DI 可以改进。

---

## 四、AndroidManifest.xml 检查清单

- [x] `android:usesCleartextTraffic="true"` — 已添加（HTTP 服务器连接）
- [x] `android:pageSizeCompat="true"` — **待添加**（16 KB 页面兼容）
- [x] `android:enableOnBackInvokedCallback` — 未设置，Navigation Compose 自动处理
- [x] 通知权限 `POST_NOTIFICATIONS` — 已在代码中动态请求
- [x] 无障碍服务声明 — 已配置 `accessibility_service_config.xml`

---

## 五、参考链接

- [Android 16 行为变更（面向所有应用）](https://developer.android.com/about/versions/16/behavior-changes-all)
- [Android 16 行为变更（仅面向 target 16 的应用）](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android 16 开发者概览](https://developer.android.com/about/versions/16/overview)
- [16 KB Page Size 支持](https://developer.android.com/guide/practices/page-size)
- [Edge-to-Edge 适配指南](https://developer.android.com/develop/ui/views/layout/edge-to-edge-edge)
- [Predictive Back 迁移](https://developer.android.com/guide/navigation/predictive-back)
