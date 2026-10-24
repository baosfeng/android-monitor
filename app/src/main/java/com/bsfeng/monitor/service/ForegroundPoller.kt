package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：前台 App 每秒轮询的取点逻辑。
// 它只决定「这一秒前台是谁、要不要记账/记页面」，轮询链的启停仍由 Service 管
// （见 MonitorAccessibilityService 的 foregroundPoller）。

import android.util.Log
import com.bsfeng.monitor.record.PageEvent
import com.bsfeng.monitor.skip.SnapshotExporter
import com.bsfeng.monitor.skip.SnapshotRequest
import com.bsfeng.monitor.util.AppUtils

internal fun MonitorAccessibilityService.pollForegroundApp() {
val now = System.currentTimeMillis()
val root = rootInActiveWindow ?: run {
    observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
    return
}
val pkg = root.packageName?.toString()
// 页面文本必须在 recycle 之前取；桌面/系统 UI/本 App 不参与页面轨迹，跳过遍历（省掉每秒一次全树扫描）
val pageTexts = if (pkg != null && pageTracingEnabled() &&
    !isHomeApp(pkg) && !isSystemUi(pkg) && pkg != packageName
) {
    collectVisibleTexts(root)
} else {
    emptyList()
}
root.recycle()
if (pkg == null) {
    observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
    return
}

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
    observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
    lastPackageName = pkg
    return
}
isOnHomeScreen = false

// 系统 UI → flush 当前 session（不计时）
if (isSystemUi(pkg)) {
    recordCollector.onTick(null, null, now)
    observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_POLL)
    return
}

val appName = AppUtils.getAppName(pkg)

// 日志（仅 App 切换时）
if (pkg != lastPackageName) {
    Log.d("MonitorService", "pollForegroundApp: $appName ($pkg)")
    timeline("📱", appName)
    lastPackageName = pkg
}

// 通知 UsageTracker 累加时长（状态驱动，每秒调用一次）
recordCollector.onTick(pkg, appName, now)
// 页面轨迹：轮询路径用「Activity（窗口事件给的）+ 可见文本」算指纹
observePage(pkg, lastActivityClass, pageTexts, appName, now, PageEvent.SOURCE_POLL)
}
