package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleEngine.kt 的点击后确认；职责：动作执行后用同一选择器复核广告是否消失，多轮复核与结果上报。

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.util.DebugConfig

/** 待确认的跳过动作（复核期间只做复核，不再执行任何规则，避免重复点击） */
internal class PendingVerify(
    val result: SkipRuleEngine.SkipResult,
    val selector: String,
    val fastQuery: Boolean,
    var attempt: Int,
    var dueAt: Long
)

/**
 * 点击后确认（P2-4）。
 *
 * 跳过按钮点了不等于广告真的消失（点到装饰性节点、点了没反应、广告换了容器都会误判成功），
 * 因此延迟复核：命中不到才算"已确认"。复核失败且还有次数就再等一轮 —— 广告消失通常有
 * 几十到几百毫秒的动画延迟。
 */
internal class SkipClickVerifier(
    private val rootNodeProvider: () -> AccessibilityNodeInfo?,
    private val verifyDelaysMs: () -> List<Long>,
    private val onFinished: (SkipRuleEngine.SkipResult) -> Unit,
) {

    /**
     * 动作已执行：安排第一次复核。
     *
     * @return 下次复核的等待时长（毫秒）；null 表示不做复核（已直接上报）
     */
    fun start(hit: SkipHit, now: Long): Long? {
        val delays = verifyDelaysMs()
        if (delays.isEmpty()) {
            onFinished(hit.result)
            return null
        }
        val pv = PendingVerify(hit.result, hit.result.selector, hit.matchRule.fastQuery, 0, now + delays[0])
        pending = pv
        DebugConfig.log("SkipEngine: 动作已执行，等待 " + delays[0] + "ms 确认广告是否消失 → " + hit.result.selector)
        return delays[0]
    }

    /** 当前待复核动作（null = 没有复核在途） */
    var pending: PendingVerify? = null
        private set

    /** 清空在途复核（停窗 / 重置状态时调用） */
    fun clear() {
        pending = null
    }

    /**
     * 执行一次复核。
     *
     * @return 需要再等一轮时返回下一次的等待时长（毫秒）；null 表示已结束（结果已上报）
     */
    fun verify(pv: PendingVerify, now: Long): Long? {
        val root = rootNodeProvider()
        var confirmed: Boolean? = null
        if (root != null) {
            try {
                confirmed = RuleNodeFinder.matches(pv.selector, pv.fastQuery, root)?.not()
            } finally {
                root.recycle()
            }
        }
        val delays = verifyDelaysMs()
        if (confirmed != true && pv.attempt + 1 < delays.size) {
            pv.attempt++
            pv.dueAt = now + delays[pv.attempt]
            DebugConfig.log("SkipEngine: 复核未通过（广告仍在或根节点不可用），" + delays[pv.attempt] + "ms 后再复核一次")
            return delays[pv.attempt]
        }
        onFinished(pv.result.copy(confirmed = confirmed))
        return null
    }

    /**
     * 上报跳过结果（日志分流 + 回调），由调用方负责停窗。
     *
     * **分流规则（A 项）**：只有 `confirmed != false` 才走 [onSkipSuccess]（写 `AD_SKIPPED` +
     * 弹「已跳过」）。`confirmed == false` 走 [onSkipUnconfirmed]：落一条
     * `AD_SKIP_UNCONFIRMED`、不计入任何「跳过成功」计数、弹幕如实说「未确认」。
     *
     * 注意 `confirmed == true` 也只代表「同一选择器不再匹配」，不等于「广告真的消失」——
     * 点掉导航/引导节点同样会判 true。误判防线在点击前的判据（见 [SplashCandidateVerdict]）。
     */
    fun finish(
        result: SkipRuleEngine.SkipResult,
        tag: String,
        onSkipSuccess: ((SkipRuleEngine.SkipResult) -> Unit)?,
        onSkipUnconfirmed: ((SkipRuleEngine.SkipResult) -> Unit)? = null,
    ) {
        pending = null
        when (result.confirmed) {
            true -> Log.d(tag, "✅ 跳过成功（已确认广告消失）: " + result.packageName + " ← " + result.selector)
            false -> Log.w(tag, "⚠️ 跳过未确认（点完广告仍在）: " + result.packageName + " ← " + result.selector)
            null -> Log.d(tag, "✅ 跳过成功: " + result.packageName + " ← " + result.selector)
        }
        if (result.confirmed == false) {
            onSkipUnconfirmed?.invoke(result)
        } else {
            onSkipSuccess?.invoke(result)
        }
    }
}
