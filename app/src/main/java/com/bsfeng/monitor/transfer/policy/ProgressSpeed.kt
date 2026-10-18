package com.bsfeng.monitor.transfer.policy

/**
 * 传输速度估算（纯逻辑，无 Android 依赖，可纯 JVM 单测）。
 *
 * 与 [ProgressThrottle] 配套：进度发射被节流到 150ms 一次后，这里仍然用
 * 「距上次**发射**的真实字节差 / 真实时间差」算瞬时速度再做指数平滑，
 * 所以节流不会让速度变成 0 或乱跳（慢速文件也不会因为窗口内没跑满一块而归零）。
 *
 * 平滑口径：新值 = (上次 × 3 + 本次瞬时) / 4，即约 4 个采样窗口收敛。
 */
object ProgressSpeed {

    /**
     * @param previousReceived 上次发射时的已传字节。
     * @param previousBps 上次发射时的速度（0 = 首次采样）。
     * @param previousAtMs 上次发射时刻。
     * @param received 当前已传字节。
     * @param nowMs 当前时刻。
     */
    fun bytesPerSecond(
        previousReceived: Long,
        previousBps: Long,
        previousAtMs: Long,
        received: Long,
        nowMs: Long,
    ): Long {
        val deltaTime = nowMs - previousAtMs
        // 时钟回拨 / 同毫秒内采样：保持上次值，不制造无穷大
        if (deltaTime <= 0L) return previousBps
        val deltaBytes = received - previousReceived
        // 没有新增字节（如续传补齐）：保持上次值，不把速度打成 0
        if (deltaBytes <= 0L) return previousBps
        val instant = deltaBytes * 1_000L / deltaTime
        return if (previousBps <= 0L) instant else (previousBps * 3 + instant) / 4
    }
}
