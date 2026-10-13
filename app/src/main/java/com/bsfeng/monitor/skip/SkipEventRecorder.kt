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
     * 记录一次跳过操作。
     *
     * @param confirmed 点击后确认结果：true 已确认（广告消失）/ false 未确认（广告仍在）/
     *   null 未做复核
     */
    fun recordSkip(
        packageName: String,
        appName: String,
        selectorExpr: String,
        className: String?,
        confirmed: Boolean? = null
    ) {
        val suffix = when (confirmed) {
            true -> "（已确认）"
            false -> "（未确认）"
            null -> ""
        }
        Log.d(TAG, "记录跳过事件: $appName ($packageName)$suffix")
        Log.d(TL, "⏭️ 跳过广告: $appName <- $selectorExpr$suffix")

        MonitorApp.instance.container.recordCollector.onSkipAd(
            appName = appName,
            packageName = packageName,
            selectorExpr = selectorExpr,
            confirmed = confirmed
        )
    }
}
