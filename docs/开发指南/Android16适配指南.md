# Android 16（API 36）适配指南

> 版本号以 [构建与测试](构建与测试.md) 为唯一事实源，本文只记行为变更的**处置结论**。

## 一、已落地（不用再改）

| 变更 | 处置 | 证据 |
|---|---|---|
| 16 KB Page Size | 声明兼容模式，不改对齐 | `AndroidManifest.xml` 的 `android:pageSizeCompat="enabled"` |
| Edge-to-Edge 强制全面屏 | `Scaffold` + `padding(paddingValues)` 已用满屏 | `Theme.Monitor` 继承 `android:Theme.Material.Light.NoActionBar`，无窗口装饰 |
| 预测性返回默认启用 | 显式打开回调体系 | `android:enableOnBackInvokedCallback="true"`；Compose `BackHandler`（35 处）在该体系下自动经 `OnBackInvokedDispatcher` 分发，后注册者优先，各屏触发条件与顺序不变 |
| Intent 重定向保护 | 只用 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 这类标准 Intent | 不存在未受信 sub-intent 传递 |
| Ordered Broadcast 优先级不跨进程 | 所有 receiver 都在主进程 `registerReceiver` | 不依赖 `android:priority` |

## 二、需要留意的两条

**JobScheduler 配额收紧**：本项目用 AlarmManager（`setInexactRepeating`，1 分钟 heartbeat），不受影响。**一旦改成 WorkManager / JobScheduler**，配额按待机模式与可见性计算，保活心跳会被推迟到分钟级——不要为「省电」做这个替换。

**无障碍公告 API 弃用**：`announceForAccessibility()` / `TYPE_ANNOUNCEMENT` 已弃用，改用 `Modifier.semantics { paneTitle }`（窗口变化）、`LiveRegionMode`（UI 更新）、`CONTENT_CHANGE_TYPE_ERROR`（错误提示）。项目当前未使用弃用 API。

## 三、与本项目无关的新特性

`BODY_SENSORS` 权限细分（不用传感器）、`JobInfo#setImportantWhileForeground` 移除（不用 JobScheduler）、`elegantTextHeight` 弃用（Compose 不涉及）、Virtual Device Owner。

## 四、Kotlin / Compose 口径

- `object` 单例对系统无状态工具是允许的：`HealthChecker`、`AppUtils`、`ApiService`；需要可测试的状态机放 `domain`（纯 JVM）。
- `StateFlow` + `collectAsState()`、`viewModelScope.launch`、`LaunchedEffect`、`LazyColumn` + `items` 均为项目标准写法。
- `Result<T>` 作为 `EventReporter` 等对外返回值，不抛异常。

## 五、已知待改（改到相关代码时顺手处理）

1. **`AlarmReceiver` 的 `CoroutineScope(Dispatchers.IO + SupervisorJob())` 不取消**：广播接收器返回后协程仍可能跑（定位采样最长 5 秒）。它是独立 `PendingResult`，靠 `finishOnce` 守卫不让系统报超时；换成可取消作用域时要同时保证五条路径都 `finish()`。
2. **`MainViewModel` 用 `MonitorApp.instance` 取 Context**：项目是手动 DI，不走 `AndroidViewModel`；新增 ViewModel 优先经 `ServiceContainer` 构造器注入。

## 六、Manifest 检查清单

- [x] `android:usesCleartextTraffic="true"`（互传 HTTP 服务端）
- [x] `android:pageSizeCompat="enabled"`
- [x] `android:enableOnBackInvokedCallback="true"`
- [x] `POST_NOTIFICATIONS` 运行时请求（API 33+；29–32 无此权限，直接申请会被拒）
- [x] 无障碍服务声明 + `accessibility_service_config.xml`

## 七、参考

[行为变更（所有应用）](https://developer.android.com/about/versions/16/behavior-changes-all) · [仅 target 16 的变更](https://developer.android.com/about/versions/16/behavior-changes-16) · [16 KB Page Size](https://developer.android.com/guide/practices/page-size)
