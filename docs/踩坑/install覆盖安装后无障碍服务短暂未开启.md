# 踩坑：`adb install -r` 覆盖安装后无障碍服务短暂显示「未开启」

## 问题描述

在小米 14（HyperOS）上执行 `adb install -r app-debug.apk` 覆盖安装后，App 设置页「无障碍服务」
显示 **❌ 未开启**，但此时：

- `adb shell dumpsys accessibility` 里服务**仍在已启用列表**；
- 无障碍引擎日志正常（`MonitorDebug: SkipEngine: ...` 持续输出）。

几秒后（或重进 App）状态自行恢复为「✅ 已开启」。

## 影响范围

- **设备：** 小米 14 / HyperOS（其他 ROM 可能只表现为 `AccessibilityManager` 列表滞后）
- **界面：** 设置页「无障碍服务」状态文案 → `app/src/main/java/com/bsfeng/monitor/ui/SettingsScreen.kt`
- **判定逻辑：** `app/src/main/java/com/bsfeng/monitor/util/AccessibilityStatus.kt`

## 根本原因

覆盖安装会重启进程，系统重新绑定无障碍服务有延迟，两个数据源在此期间会不一致：

| 数据源 | 覆盖安装后瞬间的表现 |
|--------|---------------------|
| `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_GENERIC)` | 列表可能为空（旧实现只信它 → 误报「未开启」） |
| `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | 已包含本服务（用户勾选状态，权威） |
| 服务实例 `MonitorAccessibilityService.isRunning` | 尚未 `onServiceConnected` → false |

## 解决方案

**三重判定，任一命中即视为已开启**（`AccessibilityStatus.isEnabled`）：

```kotlin
fun isEnabled(context: Context, serviceRunning: Boolean): Boolean =
    serviceRunning || isEnabledInSettings(context) || isEnabledInManager(context)
```

- 系统设置项为权威来源，且解析时兼容类名的相对写法（`.service.MonitorAccessibilityService`）；
- 服务列表改用 `AccessibilityServiceInfo.FEEDBACK_ALL_MASK`（`FEEDBACK_GENERIC` 会被反馈类型过滤掉）；
- 服务实例标志由 `MonitorAccessibilityService.onServiceConnected/onDestroy` 维护；
- 设置页状态分三档：`✅ 已开启` / `✅ 已开启（服务未连接，稍候或重进 App 生效）` / `❌ 未开启`，
  并在 `LifecycleResumeEffect` 时立即刷新（不必等 3 秒轮询）。

## 验证方法

```bash
A=~/android-platform-tools/adb
$A shell settings get secure enabled_accessibility_services   # 应包含 com.bsfeng.monitor/...MonitorAccessibilityService
$A shell dumpsys accessibility | grep -i bsfeng
# 设置页文案：用 uiautomator 读，比截图更可靠
$A shell uiautomator dump /sdcard/ui.xml && $A shell cat /sdcard/ui.xml | grep -o '已开启'
```

单元测试：`app/src/test/java/com/bsfeng/monitor/util/AccessibilityStatusTest.kt`（8 例，覆盖两种来源、相对类名、
多服务冒号分隔、其它应用同名类、空值与乱码）。

## 相关文件

- 判定逻辑：`app/src/main/java/com/bsfeng/monitor/util/AccessibilityStatus.kt`
- 服务实例标志：`app/src/main/java/com/bsfeng/monitor/service/MonitorAccessibilityService.kt`（`companion object.isRunning`）
- 设置页展示：`app/src/main/java/com/bsfeng/monitor/ui/SettingsScreen.kt`
- 状态刷新：`app/src/main/java/com/bsfeng/monitor/ui/MainViewModel.kt`（`refreshAccessibilityStatus`）
- 相邻踩坑：[MIUI force-stop 导致无障碍服务被自动移除](MIUI-force-stop导致无障碍服务被自动移除.md)
