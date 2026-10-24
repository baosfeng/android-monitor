package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：设置页的「自动记账调试入口」。
// 不经过识别链路，直接弹出确认浮窗（模拟器没有微信/支付宝，靠它验证浮窗渲染）。

import android.util.Log

/**
 * 调试入口：不经过识别链路，直接弹出自动记账确认浮窗。
 *
 * @return false = 服务未连接（浮窗挂不上），调用方据此提示用户
 */
internal fun MonitorAccessibilityService.debugShowAutoBookSheet(): Boolean {
    if (!MonitorAccessibilityService.isRunning) return false
    runCatching { autoBookEngine.showDebugSheet() }
        .onFailure { Log.w("MonitorService", "调试浮窗启动失败: ${it.message}") }
    return true
}
