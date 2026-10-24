package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：无障碍事件 → 开屏跳过引擎的转发层。
// 只做「什么事件、什么条件下交给 SkipRuleEngine」，不含任何跳过判定逻辑本身。

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.skip.SkipRuleEngine

/**
 * 开屏跳过的事件转发（window state / content changed 两条入口）。
 *
 * 引擎以「App 切换时刻」为锚点开窗匹配：窗口内由 CONTENT_CHANGED 事件驱动扫描 +
 * 兜底轮询，根节点未就绪时自行重试。广告跳过关闭时不打开匹配窗口
 * （不扫描、不轮询、不执行点击规则），但前台 App 追踪、自动记账、页面快照等
 * 其余链路照常运行 —— 因此关闭判断只包住本类这一段。
 */
internal object AdSkipEventBridge {

    private const val TAG = "MonitorService"

    /** 窗口状态变化：按「进入本 App / 切到别的 App」分派给引擎。 */
    fun onWindowStateChanged(
        service: MonitorAccessibilityService,
        packageName: String,
        className: String,
        appSwitchedFrom: String?,
        skipEngine: SkipRuleEngine,
    ) {
        val skipEnabled = FeatureFlags.get().isEnabled(Feature.AD_SKIP)
        try {
            if (packageName == service.packageName) {
                // 进入本 App：停止开屏跳过监控并重置状态（页面轨迹已下线，这里只需管开屏跳过）。
                if (skipEnabled) skipEngine.resetAppState(packageName)
            } else {
                if (packageName != appSwitchedFrom) service.removeCurrentDanmaku()
                if (skipEnabled) skipEngine.onWindowChanged(packageName, className)
            }
        } catch (e: Exception) {
            Log.w(TAG, "skipEngine.onWindowChanged 异常", e)
        }
    }

    /**
     * 窗口内容变化 → 引擎立即扫描。
     *
     * 开屏广告的跳过按钮通常在广告加载完成后才出现（倒计时结束/广告渲染），
     * 此时节点树必然发生变化产生 CONTENT_CHANGED 事件；转发给引擎即可在监控窗口内
     * 立即扫描而非等待轮询。系统 UI / 桌面事件直接丢弃（监控目标仅限应用窗口）。
     */
    fun onContentChanged(
        event: android.view.accessibility.AccessibilityEvent,
        isSystemUi: (String) -> Boolean,
        isHomeApp: (String) -> Boolean,
        skipEngine: SkipRuleEngine,
    ) {
        // 广告跳过关闭：内容变化事件不进规则引擎（不扫描节点、不评估规则）
        if (!FeatureFlags.get().isEnabled(Feature.AD_SKIP)) return
        val pkg = event.packageName?.toString() ?: return
        if (isSystemUi(pkg) || isHomeApp(pkg)) return
        skipEngine.onContentChanged(pkg)
    }
}
