package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：窗口列表变化（WINDOWS_CHANGED）的处理。
// 第三方悬浮窗出现/消失时根窗口会短暂指向悬浮窗，这里重读一次根窗口再决定是否结算页面。

import com.bsfeng.monitor.record.PageEvent
import com.bsfeng.monitor.util.AppUtils

/** WINDOWS_CHANGED 的合并窗口：窗口列表变化可能一秒多次，1 秒内只重读一次根窗口。 */
private const val WINDOWS_CHANGED_THROTTLE_MS = 1_000L

/**
 * 处理窗口列表变化事件。
 *
 * 第三方悬浮窗（来电、录屏、歌词）出现/消失时，`rootInActiveWindow` 会短暂指向悬浮窗自身的
 * 窗口，只看 WINDOW_STATE_CHANGED 会把那一瞬间当成一次页面切换。这里在窗口列表变化时
 * **重新读一次根窗口的包名**，只有真的换了包名/Activity 才结算页面。
 *
 * 只参与页面轨迹（不写文本、不扫描跳过规则），关闭页面轨迹时直接返回。
 */
internal fun MonitorAccessibilityService.handleWindowsChanged() {
    if (!pageTracingEnabled()) return
    val now = System.currentTimeMillis()
    // 窗口列表变化可能一秒多次（输入法、悬浮窗动画）：1 秒内只重读一次根窗口
    if (now - windowsChangedAtMs < WINDOWS_CHANGED_THROTTLE_MS) return
    val root = rootInActiveWindow ?: return
    windowsChangedAtMs = now
    val pkg = root.packageName?.toString()
    // 本 App（设置/实时页切 Tab）与桌面/系统 UI 一样不参与页面轨迹，也不做文本遍历
    val trackable = pkg != null && !isHomeApp(pkg) && !isSystemUi(pkg) && pkg != packageName
    val texts = if (trackable) collectVisibleTexts(root) else emptyList()
    root.recycle()
    if (pkg == null || !trackable) {
        observePage(null, null, emptyList(), null, now, PageEvent.SOURCE_WINDOW_EVENT)
        return
    }
    observePage(pkg, lastActivityClass, texts, AppUtils.getAppName(pkg), now, PageEvent.SOURCE_WINDOW_EVENT)
}
