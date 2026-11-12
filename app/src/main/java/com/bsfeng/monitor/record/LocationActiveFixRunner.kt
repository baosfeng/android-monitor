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
    /**
     * 被质量闸拒绝的点仍交给门禁当**位移证据**（不落库、不占节流窗口）。
     *
     * 不喂的后果是实测到的一对死结：探测点精度不够被拒 → 门禁既拿不到位移证据、也不进
     * 退避阶梯（[com.bsfeng.monitor.record.MotionGate.quietProbeCount] 只在 has-sample 时增加）
     * → 门禁永远停在 180 秒探测，一天几百次白跑射频，而行程里一条记录都没有。
     */
    private val onRejected: (LocationSample) -> Unit = {},
    /**
     * 是否允许「原地确认点」落库：距上次成功采样已超过窗口（30 分钟）。
     *
     * 静止时质量闸会拒掉「位移 < 150m 的低置信点」—— 那条规则本意是防漂移，副作用是
     * 人一整天待在原地就**一个点都不落**，行程把「没动」判成「采集中断」。
     */
    private val allowInPlaceConfirm: (Long) -> Boolean = { false },
) {

    /** 是否有请求在飞（采集入口让路与订阅重建都读它）。 */
    val isInFlight: Boolean get() = gate.isInFlight

    /**
     * 发起一次主动定位。
     *
     * @param previous 质量闸的对照点（上一次被接受的点）
     * @param budget 主动定位预算档位（门禁探测传 [FixBudget.PROBE]，其余满预算）
     * @param onDeliver 拿到可用点后的落库路径（更新模式 / 占节流窗口由调用方负责）；
     *   第二参 true 表示这是「原地确认点」，调用方要跳过去抖过滤（它本来就与上一点重合）
     * @param onSample 最终回调，**恰好一次**
     */
    fun run(
        nowMs: Long,
        plan: LocationGapHealing.Plan,
        modeAtStart: SampleMode,
        budget: FixBudget = FixBudget.FULL,
        previous: LocationSample?,
        onDeliver: (LocationSample, Boolean) -> Unit,
        onSample: (LocationSample?) -> Unit,
    ) {
        // 全路径最小间隔（静止 180s / 移动 30s，首次成功前不短于 30s）：INITIAL 与断档自愈都会
        // 跳过采集节流，不闸一道就是每 tick 一次的死循环（每次还跑满两段超时）——
        // 实测每小时约 40 次满预算主动定位、11 小时只落 8 个点，绝大多数探测是白跑的。
        val isInitial = plan.level == LocationGapHealing.Level.INITIAL
        if (!gate.tryBegin(nowMs, modeAtStart, isInitial)) {
            noteFailure(LocationFailureReason.THROTTLED, "最小间隔未到")
            val since = gate.lastAttemptAtMs()?.let { (nowMs - it) / 1000 } ?: -1L
            log(
                "定位主动定位跳过：最小间隔 " +
                    (LocationActiveFixGate.minIntervalMs(modeAtStart, isInitial) / 1000) +
                    "s 未到（" + modeAtStart + "，上次发起 " + since + "s 前）",
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
        onDeliver: (LocationSample, Boolean) -> Unit,
        onSample: (LocationSample?) -> Unit,
    ) {
        when {
            sample == null -> {
                noteFailure(LocationFailureReason.ACTIVE_FIX_TIMEOUT, "主动定位无可用点")
                onSample(null)
            }
            // 原地确认：质量闸本会拒（与上一点重合 / 低置信不许覆盖），但距上次成功已过窗口，
            // 这条点证明「人还在原地」，落一条才能让行程判成停留而不是采集中断。
            !LocationQuality.shouldAccept(sample, previous, nowMs) && allowInPlaceConfirm(nowMs) -> {
                log("定位原地确认落库（质量闸本会拒绝，距上次成功已超窗口）", false)
                onDeliver(sample, true)
            }
            !LocationQuality.shouldAccept(sample, previous, nowMs) -> {
                noteFailure(LocationFailureReason.LOW_CONFIDENCE, "主动定位点位精度不足")
                // 证据照喂：门禁靠它判断有没有位移、要不要进退避阶梯；被拒的点不落库。
                onRejected(sample)
                onSample(null)
            }
            else -> onDeliver(sample, false)
        }
    }
}
