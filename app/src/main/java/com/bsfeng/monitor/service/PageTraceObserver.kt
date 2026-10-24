package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：页面轨迹（指纹档）的观测入口。
// 只做「喂一次观测 + 把结果交给 RecordCollector」，指纹状态机本身在 record/PageTracker。

import android.util.Log
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.record.PageEvent

internal fun MonitorAccessibilityService.pageTracingEnabled(): Boolean = FeatureFlags.get().isEnabled(Feature.PAGE_TRACE)

/**
 * 喂一次页面观测；任何异常都不外抛（页面轨迹异常不能影响监控与跳广告主链路）。
 *
 * 关闭「页面轨迹」时只做一次结算：已开始的页面先落一条带时长的记录，之后不再采集。
 */
internal fun MonitorAccessibilityService.observePage(
    packageName: String?,
    activityClass: String?,
    visibleTexts: List<String>,
    appName: String?,
    timestamp: Long,
    source: String,
) {
    try {
        if (!pageTracingEnabled()) {
            emitPageEvents(pageTracker.flush(timestamp))
            return
        }
        emitPageEvents(
            pageTracker.observe(packageName, activityClass, visibleTexts, appName, source, timestamp)
        )
    } catch (e: Exception) {
        Log.w("MonitorService", "页面轨迹采集异常", e)
    }
}

internal fun MonitorAccessibilityService.emitPageEvents(events: List<PageEvent>) {
    for (event in events) recordCollector.onPageChanged(event)
}
