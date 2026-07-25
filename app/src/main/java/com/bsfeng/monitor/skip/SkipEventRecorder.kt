package com.bsfeng.monitor.skip

import android.util.Log
import com.bsfeng.monitor.domain.model.EventType
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.pipeline.EventSource
import com.bsfeng.monitor.pipeline.RawEventData

/**
 * 跳过事件记录器。
 *
 * 每次成功跳过广告时，将事件记录到事件管道（与 VIEW_CLICKED 共用事件类型，
 * 但 rawExtra 中标记 skip_ad: true）。
 */
object SkipEventRecorder {

    private const val TAG = "SkipRecorder"
    private const val TL = "MonitorTimeline"

    /**
     * 记录一次成功的跳过操作。
     *
     * @param packageName 包名
     * @param appName 应用名称
     * @param selectorExpr 匹配的选择器表达式
     * @param className 当前 Activity 类名
     */
    fun recordSkip(
        packageName: String,
        appName: String,
        selectorExpr: String,
        className: String?
    ) {
        Log.d(TAG, "记录跳过事件: $appName ($packageName)")

        val summary = "⏭️ 自动跳过 $appName 的开屏广告"

        Log.d(TL, "⏭️ 跳过广告: $appName <- $selectorExpr")

        MonitorApp.instance.container.collector.emit(object : EventSource {
            override fun collectRaw() = RawEventData(
                eventType = EventType.VIEW_CLICKED,
                packageName = packageName,
                className = className,
                summary = summary,
                rawExtra = mapOf(
                    "app_name" to appName,
                    "package_name" to packageName,
                    "skip_ad" to "true",
                    "skip_selector" to selectorExpr,
                    "skip_action" to "click"
                )
            )
        })
    }
}
