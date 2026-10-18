package com.bsfeng.monitor.domain.transfer

/**
 * 免确认策略（纯 Kotlin，可纯 JVM 单测）。
 *
 * 两条**或**关系：
 * 1. **免配对窗口**：用户手动开启一段限时（5 / 10 / 30 分钟），窗口内未配对设备的 offer 直接 approved；
 * 2. **体积阈值**：本次 offer 的文件总大小 ≤ 阈值（MB）时直接 approved；`0` = 关闭（一律需要 PIN）。
 *
 * 任一满足即免确认；到期 / 阈值未命中时回到 PIN 流程（协议 §5）。
 */
object PairingPolicy {

    /** 阈值关闭值（MB）。 */
    const val THRESHOLD_OFF_MB: Int = 0

    /** 可选窗口时长（分钟），与设置页 chip 一致。 */
    val WINDOW_MINUTES: List<Int> = listOf(5, 10, 30)

    /** 可选体积阈值（MB），0 = 关闭。 */
    val THRESHOLD_OPTIONS_MB: List<Int> = listOf(0, 10, 50, 100)

    /** 阈值 MB → 字节。 */
    fun thresholdBytes(mb: Int): Long = mb.coerceAtLeast(0).toLong() * 1024L * 1024L

    /** 窗口是否生效（到期时刻严格大于当前时刻）。 */
    fun isWindowActive(expiresAtMs: Long, nowMs: Long): Boolean = expiresAtMs > nowMs

    /** 窗口剩余毫秒（未开启 / 已过期返回 0）。 */
    fun remainingMs(expiresAtMs: Long, nowMs: Long): Long = (expiresAtMs - nowMs).coerceAtLeast(0L)

    /**
     * 是否免确认。
     *
     * @param totalBytes 本次 offer 的文件总大小；负数视为非法输入，只按窗口判定。
     * @return 窗口生效，或阈值开启且 `totalBytes <= 阈值`。
     */
    fun isAutoApproved(
        windowExpiresAtMs: Long,
        thresholdMb: Int,
        totalBytes: Long,
        nowMs: Long,
    ): Boolean {
        if (isWindowActive(windowExpiresAtMs, nowMs)) return true
        if (thresholdMb <= THRESHOLD_OFF_MB || totalBytes < 0L) return false
        return totalBytes <= thresholdBytes(thresholdMb)
    }
}
