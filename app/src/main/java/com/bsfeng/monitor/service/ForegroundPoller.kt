package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：前台 App 每秒轮询的取点逻辑。
// 它只决定「这一秒前台是谁、要不要记账/记页面」，轮询链的启停仍由 Service 管
// （见 MonitorAccessibilityService 的 foregroundPoller）。

import android.util.Log
import com.bsfeng.monitor.skip.SnapshotExporter
import com.bsfeng.monitor.skip.SnapshotRequest
import com.bsfeng.monitor.util.AppUtils

internal fun MonitorAccessibilityService.pollForegroundApp() {
val now = System.currentTimeMillis()
val root = rootInActiveWindow ?: return
val pkg = root.packageName?.toString()
root.recycle()
if (pkg == null) return

// ===== 页面快照导出（UI 请求 → 切到目标 App 后延迟导出"当时的前台页面"）=====
SnapshotRequest.onForegroundPackage(pkg, now)
if (SnapshotRequest.consumeIfDue(now)) {
    val snapshotRoot = rootInActiveWindow
    val path = SnapshotExporter.exportCurrentPage(this, snapshotRoot, pkg, lastActivityClass ?: "")
    snapshotRoot?.recycle()
    SnapshotRequest.record(path)
    Log.i("MonitorService", "页面快照导出: " + path)
    timeline("📸", if (path == null) "快照导出失败" else "已导出页面快照")
}

// 桌面
if (isHomeApp(pkg)) {
    if (!isOnHomeScreen) {
        isOnHomeScreen = true
        timeline("🏠", "回到桌面")
    }
    // flush 当前 session（桌面不计时）
    recordCollector.onTick(null, null, now)
    lastPackageName = pkg
    return
}
isOnHomeScreen = false

// 系统 UI → flush 当前 session（不计时）
if (isSystemUi(pkg)) {
    recordCollector.onTick(null, null, now)
    return
}

// 显示名只在 App 变化时取（AppUtils 有 LRU，但取不到仍要跨进程两次）：
// 主线程每秒一次的固定开销会挤占事件回调与开屏跳过的节点遍历。
if (pkg != lastPackageName) {
    lastAppName = AppUtils.getAppName(pkg)
    Log.d("MonitorService", "pollForegroundApp: $lastAppName ($pkg)")
    timeline("📱", lastAppName.orEmpty())
    lastPackageName = pkg
}
val appName = lastAppName ?: AppUtils.getAppName(pkg)

// 通知 UsageTracker 累加时长（状态驱动，每秒调用一次）
recordCollector.onTick(pkg, appName, now)
}
