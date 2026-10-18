package com.bsfeng.monitor.transfer.policy

/**
 * 进度发射节流（纯逻辑，无 Android 依赖，可纯 JVM 单测）。
 *
 * 真机 Bug：100MB 文件按 64KiB 一块上传 = 1600 次 `onProgress`，27.8s 内平均 57 次/秒；
 * 每次回调都全量复制任务列表并发射一次 `StateFlow`，Compose 每秒重组几十次，
 * 主线程被打满 → 传输中整个界面白屏，传完立刻恢复。
 *
 * 口径（与 UI「进行中」卡片约定一致）：
 * - 只节流**数值进度**；`OFFERED → TRANSFERRING`、`TRANSFERRING → COMPLETED/FAILED/CANCELLED`
 *   这些状态跃迁由调用方直接落库 / 发射，**不经过本类型**，不会被窗口吞掉；
 * - 按**时间窗口**合并（默认 150ms ≈ 每秒最多 6~7 次），不做百分比量化，
 *   低速文件也能持续有反馈；
 * - 速度仍按「距上次发射的真实字节差 / 真实时间差」计算，节流后口径一致（不会变 0 或跳变）。
 */
object ProgressThrottle {

    /** 默认窗口：150ms（UI 侧每秒最多感知约 6~7 次进度）。 */
    const val DEFAULT_WINDOW_MS: Long = 150L

    /** 「从未发射过」的哨兵值（首块进度立即发射）。 */
    const val NO_EMIT: Long = Long.MIN_VALUE

    /**
     * 是否该发射一次进度。
     *
     * @param lastEmitMs 上次发射时刻；[NO_EMIT] 表示还没发射过。
     * @param nowMs 当前时刻。
     * @param windowMs 节流窗口（毫秒）。
     */
    fun shouldEmit(
        lastEmitMs: Long,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Boolean {
        if (lastEmitMs == NO_EMIT) return true
        return nowMs - lastEmitMs >= windowMs
    }

    /**
     * 推进节流状态：该发射时返回 [nowMs]，否则原样返回 [lastEmitMs]。
     *
     * 调用方拿到返回值直接覆盖自己的 `lastEmitMs` 即可：
     * `lastEmitMs = ProgressThrottle.nextEmitMs(lastEmitMs, now)`.
     */
    fun nextEmitMs(
        lastEmitMs: Long,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Long = if (shouldEmit(lastEmitMs, nowMs, windowMs)) nowMs else lastEmitMs
}
