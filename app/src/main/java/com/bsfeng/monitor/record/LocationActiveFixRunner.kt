package com.bsfeng.monitor.record

// 从 LocationCollector 拆出：一次主动定位的发起与结果分派（发令 → 请求 → 成功/失败）。
// 纯逻辑（请求方与日志都是注入的），所以「恰好回调一次」「异常也要回调」能在纯 JVM 单测里钉死。

/**
 * 一次主动定位的编排：发令闸（并发互斥 + 首次成功前退避）→ 请求 → 结果分派。
 *
 * 三条出口（成功 / 空 / 精度不足）与一条异常路径都**恰好回调一次**：调用方拿到的要么是
 * 过了质量闸的点（走 [onDeliver]），要么是 null（走 [onSample]）。异常也必须回调 ——
 * 否则调用方的 PendingResult 永远不 finish（广播超时、心跳停摆）。
 */
internal class LocationActiveFixRunner(
    /** 实际发起请求（生产是 env.requestActiveFix；单测注入假实现）。第二参是预算档位。 */
    private val request: (Long, FixBudget, (LocationSample?) -> Unit) -> Unit,
    private val gate: LocationActiveFixGate = LocationActiveFixGate(),
    private val noteFailure: (LocationFailureReason, String) -> Unit = { _, _ -> },
    private val log: (String, Boolean) -> Unit = { _, _ -> },
) {

    /** 是否有请求在飞（采集入口让路与订阅重建都读它）。 */
    val isInFlight: Boolean get() = gate.isInFlight

    /** 清空退避基线（深断档后调用：断档就是「现在没点」，不该再等一个退避窗口）。 */
    fun reset() = gate.reset()

    /**
     * 发起一次主动定位。
     *
     * @param previous 质量闸的对照点（上一次被接受的点）
     * @param budget 主动定位预算档位（门禁探测传 [FixBudget.PROBE]，其余满预算）
     * @param onDeliver 拿到可用点后的落库路径（更新模式 / 占节流窗口由调用方负责）
     * @param onSample 最终回调，**恰好一次**
     */
    fun run(
        nowMs: Long,
        plan: LocationGapHealing.Plan,
        modeAtStart: SampleMode,
        budget: FixBudget = FixBudget.FULL,
        previous: LocationSample?,
        onDeliver: (LocationSample) -> Unit,
        onSample: (LocationSample?) -> Unit,
    ) {
        // 首次成功前走固定 30 秒退避：INITIAL 会跳过模式节流，不闸一道就是每 tick 一次的死循环
        // （每次还跑满两段超时，正是「掉线 + 耗电」的第二个根因）。退避固定不增长，防首次出点饿死。
        val isInitial = plan.level == LocationGapHealing.Level.INITIAL
        if (!gate.tryBegin(nowMs, isInitial)) {
            noteFailure(LocationFailureReason.THROTTLED, "首次成功前退避中")
            val since = gate.lastAttemptAtMs()?.let { (nowMs - it) / 1000 } ?: -1L
            log(
                "定位主动定位跳过：退避 " + (LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS / 1000) +
                    "s 未到（上次发起 " + since + "s 前）",
                false,
            )
            onSample(null)
            return
        }
        log(
            "定位发起单次主动定位（" + LocationPolicy.budgetSummaryOf(budget) + "，预算档=" + budget +
                "，模式=" + modeAtStart + "，断档补采=" + plan.forceActiveFix +
                "，首次成功前=" + isInitial + "）",
            false,
        )
        try {
            request(nowMs, budget) { sample ->
                gate.finish()
                dispatch(sample, nowMs, previous, onDeliver, onSample)
            }
        } catch (e: Exception) {
            // 兜底：底层抛异常也必须把「失败」交出去，否则调用方的 PendingResult 永远不 finish
            gate.finish()
            noteFailure(LocationFailureReason.EXCEPTION, "采集异常：" + e.javaClass.simpleName)
            log("定位采集异常：" + e.javaClass.simpleName, true)
            onSample(null)
        }
    }

    /** 三种结果的分派：空 / 精度不足 / 可用。 */
    private fun dispatch(
        sample: LocationSample?,
        nowMs: Long,
        previous: LocationSample?,
        onDeliver: (LocationSample) -> Unit,
        onSample: (LocationSample?) -> Unit,
    ) {
        when {
            sample == null -> {
                noteFailure(LocationFailureReason.ACTIVE_FIX_TIMEOUT, "主动定位无可用点")
                onSample(null)
            }
            !LocationQuality.shouldAccept(sample, previous, nowMs) -> {
                noteFailure(LocationFailureReason.LOW_CONFIDENCE, "主动定位点位精度不足")
                onSample(null)
            }
            else -> onDeliver(sample)
        }
    }
}
