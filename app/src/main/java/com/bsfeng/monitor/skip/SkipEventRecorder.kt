package com.bsfeng.monitor.skip

import android.util.Log
import com.bsfeng.monitor.MonitorApp

/**
 * 跳过事件记录器。
 *
 * 每次成功跳过广告时，通过 RecordCollector 记录跳过事件。
 */
object SkipEventRecorder {

    private const val TAG = "SkipRecorder"
    private const val TL = "MonitorTimeline"

    /**
     * 记录一次成功的跳过操作。
     */
    fun recordSkip(
        packageName: String,
        appName: String,
        selectorExpr: String,
        className: String?
    ) {
        Log.d(TAG, "记录跳过事件: $appName ($packageName)")
        Log.d(TL, "⏭️ 跳过广告: $appName <- $selectorExpr")

        MonitorApp.instance.container.recordCollector.onSkipAd(
            appName = appName,
            packageName = packageName,
            selectorExpr = selectorExpr
        )
    }
}
