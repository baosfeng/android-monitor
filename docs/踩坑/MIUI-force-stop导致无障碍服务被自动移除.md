# 踩坑：MIUI/HyperOS 上 force-stop 导致无障碍服务被自动移除

## 问题描述

在使用 `adb shell am force-stop` 强制停止应用进程后，MIUI/HyperOS 的安全机制会**自动将该应用的无障碍服务从已启用列表中移除**。重新启动应用后，无障碍服务不再工作，必须用户在设置中手动重新开启。

## 影响范围

- **设备：** 小米 14 (HyperOS) 及其他 MIUI/HyperOS 设备
- **应用：** `com.bsfeng.monitor`
- **服务：** `MonitorAccessibilityService`（`app/src/main/java/com/bsfeng/monitor/service/MonitorAccessibilityService.kt`）
- **Android 版本：** MIUI/HyperOS 全版本（基于 Android 的定制系统）

## 触发条件

以下任一操作都可能触发：

| 操作 | 命令 | 风险 |
|------|------|------|
| 强制停止应用 | `adb shell am force-stop com.bsfeng.monitor` | ⚠️ 高 — 必定触发 |
| 直接修改 settings 数据库 | `adb shell settings put secure enabled_accessibility_services ...` | ⚠️ 中 — 可能被 VirusScanJobService 回滚 |
| 从开发者选项中强制停止 | 设置 → 应用管理 → 强制停止 | ⚠️ 高 — 必定触发 |
| 卸载应用 | `adb uninstall` | ⚠️ 正常 — 卸载后服务当然会消失 |

## 根本原因

MIUI/HyperOS 在系统层增加了额外的安全监控（`VirusScanJobService`）和 servicetracker 服务，当检测到：

1. **无障碍服务进程异常终止**（非正常 `unbind`）：系统认为可能是恶意行为，自动从 `enabled_accessibility_services` 中移除该条目
2. **应用被强制杀死**：安全机制判断该应用不值得信任，回收其无障碍权限

这是 MIUI 的安全设计，并非 Android 原生行为。标准 Android 上 `force-stop` 不会自动移除无障碍服务。

## 日志特征

```log
# 服务被销毁时
vendor.qti.hardware.servicetrackeraidl-service: destroyService is called for service : 
  com.bsfeng.monitor/.service.MonitorAccessibilityService

# MIUI 安全扫描检测到变更
VirusScanJobService: onChanged: [...com.bsfeng.monitor/.service.MonitorAccessibilityService]

# 再次查询已启用列表 — 条目已消失
settings get secure enabled_accessibility_services
# 输出中不再包含 com.bsfeng.monitor/.service.MonitorAccessibilityService
```

## 解决方案

### 方案一：避免 force-stop（推荐）

**永远不要使用 `adb shell am force-stop` 来重启应用。** 改用以下替代方案：

| 场景 | 正确做法 |
|------|---------|
| 安装新版 APK | `adb install -r app-debug.apk`（覆盖安装会自动处理） |
| 仅重启应用 | `adb shell am start -n com.bsfeng.monitor/.MainActivity`（唤醒前台即可） |
| 更新后重置状态 | 覆盖安装后自然重启即可，不要手动 force-stop |

### 方案二：如果服务已被移除

1. 打开手机 **设置 → 辅助功能 → 已安装的服务**
2. 找到 **行为记录（Monitor）**
3. 手动开启无障碍服务开关
4. 在弹出的确认对话框中点击 **确定**

### 方案三：完整全流程（无 force-stop）

```bash
# 正确的开发流程：一行完成测试、编译、安装
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
./gradlew test && ./gradlew assembleDebug \
  && ~/Downloads/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
# ↑ 不需要 force-stop！adb install -r 会自动处理进程切换
```

## 验证服务是否正常运行

```bash
# 方法一：检查已启用服务列表
~/Downloads/platform-tools/adb shell settings get secure enabled_accessibility_services

# 方法二：检查系统无障碍服务列表
~/Downloads/platform-tools/adb shell dumpsys accessibility \
  | grep -A 5 "com.bsfeng.monitor/.service.MonitorAccessibilityService"

# 方法三：查看 MonitorService 日志（无障碍事件处理中）
~/Downloads/platform-tools/adb logcat -v time | grep "MonitorService"
# 正常输出示例：
# MonitorService: onAccessibilityEvent: type=32, pkg=com.android.settings
# MonitorService:   -> APP_FOREGROUND for com.xxx
```

## 相关文件

- 无障碍服务实现：`app/src/main/java/com/bsfeng/monitor/service/MonitorAccessibilityService.kt`
- ADB 调试指南：`docs/开发指南/ADB调试指南.md`
- 后台服务概述：`docs/后台服务/概述.md`
