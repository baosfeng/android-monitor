package com.bsfeng.monitor.record

// 从 LocationCollector 拆出：断档自愈判定。
// 纯逻辑：只吃「上次成功采样时间」与「当前模式」，因此 5 分钟 / 30 分钟 / 时钟回拨都能在纯 JVM 单测里钉死。

/**
 * 断档自愈（纯函数）。
 *
 * 背景：主动定位只有 5 秒预算、缓存点 30 分钟就 stale，Doze / 应用待机时会出现长断档；
 * 旧实现没有任何时间维度的补偿，恢复全靠下一个心跳，用户感知为「掉线」。
 *
 * 判定口径：
 * - [Level.NONE]：间隔在「1.5 倍当前模式间隔」以内 —— 正常节奏；
 * - [Level.SHORT]：超过 1.5 倍但不到 30 分钟 —— 强制提升一次采样优先级（静止模式也立即补一次）；
 * - [Level.DEEP]：>= 30 分钟 —— 除强制补采外还要重建订阅并清空节流/模式基线；
 * - [Level.INITIAL]：进程刚起、还没有成功采样 —— 直接补一次；
 * - [Level.CLOCK_BACKWARD]：now < lastSuccess（时钟回拨 / 时区改变）—— 不补采也不误判断档，
 *   只重置基线，避免「时间倒退导致永远不采样」。
 */
object LocationGapHealing {

    /** 深断档阈值：超过它就重建订阅（Doze 里系统可能已经悄悄摘掉了监听）。 */
    const val DEEP_GAP_MS = 30 * 60_000L

    enum class Level { INITIAL, NONE, SHORT, DEEP, CLOCK_BACKWARD }

    /**
     * 自愈计划。
     *
     * [resetBaseline] 为 true 时调用方要清空节流窗口、模式状态机与「上一次最优点位」。
     */
    data class Plan(
        val level: Level,
        val forceActiveFix: Boolean,
        val restartSubscription: Boolean,
        val resetBaseline: Boolean,
    ) {
        /** 是否真的发生断档（INITIAL 与 CLOCK_BACKWARD 不算断档，只是要重置基线）。 */
        val isGap: Boolean get() = level == Level.SHORT || level == Level.DEEP
    }

    /** 计算自愈计划；[lastSuccessMs] 为 null 表示从未成功采样过。 */
    fun plan(lastSuccessMs: Long?, nowMs: Long, mode: SampleMode): Plan {
        if (lastSuccessMs == null) {
            return Plan(Level.INITIAL, forceActiveFix = true, restartSubscription = false, resetBaseline = false)
        }
        val gapMs = nowMs - lastSuccessMs
        if (gapMs < 0) {
            return Plan(Level.CLOCK_BACKWARD, forceActiveFix = false, restartSubscription = false, resetBaseline = true)
        }
        if (gapMs >= DEEP_GAP_MS) {
            return Plan(Level.DEEP, forceActiveFix = true, restartSubscription = true, resetBaseline = true)
        }
        if (gapMs > shortGapThresholdMs(mode)) {
            return Plan(Level.SHORT, forceActiveFix = true, restartSubscription = false, resetBaseline = false)
        }
        return Plan(Level.NONE, forceActiveFix = false, restartSubscription = false, resetBaseline = false)
    }

    /** 浅断档阈值（毫秒）：1.5 x 模式间隔（静止 180s -> 270s，移动 30s -> 45s）。 */
    fun shortGapThresholdMs(mode: SampleMode): Long =
        LocationPace.intervalOf(mode) * 3 / 2
}
