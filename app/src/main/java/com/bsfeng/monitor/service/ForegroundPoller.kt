package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：前台 App 每秒轮询的取点逻辑。
// 它只决定「这一秒前台是谁、要不要记账/记页面」，轮询链的启停仍由 Service 管
// （见 MonitorAccessibilityService 的 foregroundPoller）。

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.bsfeng.monitor.skip.SnapshotExporter
import com.bsfeng.monitor.skip.SnapshotRequest
import com.bsfeng.monitor.util.AppUtils

/**
 * 前台轮询的屏幕门禁（纯逻辑，单测钉住「熄屏不取窗」）。
 *
 * 轮询链每秒取一次 rootInActiveWindow（跨进程 Binder），息屏后必然拿到 null，
 * 8 小时约 2.9 万次空取。熄屏期间只保留「结算当前 session」这一件事，并把节拍拉长到
 * 30 秒：亮屏后最多 30 秒恢复逐秒采样，代价可忽略。
 */
internal object ForegroundPollPolicy {
    const val INTERACTIVE_INTERVAL_MS = 1_000L
    const val SCREEN_OFF_INTERVAL_MS = 30_000L

    /** 是否值得取根窗口：熄屏时窗口不可交互，取到的只会是 null 或陈旧节点。 */
    fun shouldQueryWindow(interactive: Boolean): Boolean = interactive

    fun nextDelayMs(interactive: Boolean): Long =
        if (interactive) INTERACTIVE_INTERVAL_MS else SCREEN_OFF_INTERVAL_MS
}

/** 屏幕是否处于交互态；取不到 PowerManager 时按亮屏处理（宁可多采，不漏采）。 */
internal fun Context.isScreenInteractive(): Boolean =
    runCatching { (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true }
        .getOrDefault(true)

internal fun MonitorAccessibilityService.pollForegroundApp() {
val now = System.currentTimeMillis()
// 熄屏不取根窗口（跨进程 Binder 空取），但要把当前 session 结算掉，
// 否则亮屏后第一跳会被 UsageTracker 判成断层之外的整段使用时长。
if (!ForegroundPollPolicy.shouldQueryWindow(isScreenInteractive())) {
    recordCollector.onTick(null, null, now)
    return
}
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
