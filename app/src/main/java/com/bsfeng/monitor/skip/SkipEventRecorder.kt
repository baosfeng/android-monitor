package com.bsfeng.monitor.skip

import android.util.Log
import com.bsfeng.monitor.MonitorApp

/**
 * 跳过事件记录器。
 *
 * 两种结局分开落库（A 项口径）：
 * - [recordSkip] → `AD_SKIPPED`：跳过成功（`confirmed` 为 true 或 null）
 * - [recordUnconfirmed] → `AD_SKIP_UNCONFIRMED`：点击了但复核时按钮仍在，**不算成功**
 *
 * 两者都写判定证据（`matched_text` / `verdict_reason`）：误判排查全靠它 —— 只有 selector
 * 的话，事后无法回答"点的是什么字、被哪条判据放行"。
 */
object SkipEventRecorder {

    private const val TAG = "SkipRecorder"
    private const val TL = "MonitorTimeline"

    /**
     * 记录一次跳过操作。
     *
     * @param confirmed 点击后确认结果：true 已确认（广告消失）/ false 未确认（广告仍在）/
     *   null 未做复核
     * @param matchedText 命中节点文案（判据证据，可空）
     * @param verdictReason 点击前判据结论（判据证据，可空）
     */
    fun recordSkip(
        packageName: String,
        appName: String,
        selectorExpr: String,
        className: String?,
        confirmed: Boolean? = null,
        matchedText: String? = null,
        verdictReason: String? = null
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
            confirmed = confirmed,
            matchedText = matchedText,
            verdictReason = verdictReason
        )
    }

    /**
     * 记录一次「已点击但未确认」：`AD_SKIP_UNCONFIRMED`，**不计入任何跳过成功口径**。
     *
     * 误判排查靠这批数据（含命中文案与判据结论），所以必须落库、必须能在实时事件流里看到，
     * 只是不能算进「跳过成功」。见 [SkipRuleEngine.finishSkip] 的分流。
     */
    fun recordUnconfirmed(
        packageName: String,
        appName: String,
        selectorExpr: String,
        matchedText: String? = null,
        verdictReason: String? = null
    ) {
        Log.w(TAG, "记录未确认跳过: $appName ($packageName) ← $selectorExpr")
        Log.d(TL, "⏭️ 点击跳过但未确认: $appName <- $selectorExpr")

        MonitorApp.instance.container.recordCollector.onSkipAdUnconfirmed(
            appName = appName,
            packageName = packageName,
            selectorExpr = selectorExpr,
            matchedText = matchedText,
            verdictReason = verdictReason
        )
    }
}
