package com.bsfeng.monitor.domain.totp

/**
 * 换码倒计时。
 *
 * UI 每秒调用一次并直接渲染返回值，所以这里只做纯算术：不读时钟（时间由调用方传入），
 * 不订阅协程，测试可以任意快进时间。
 */
object TotpCountdown {

    /**
     * 计算 epochMillis 时刻的倒计时。
     *
     * 口径：
     * - 剩余秒数向上取整并夹在 1..periodSec——换码瞬间显示满格而不是 0；
     * - progress 为剩余比例，恰好落在整窗口时 = 1.0；
     * - 非法 periodSec 按 30 秒、负时间按 0 处理（与 TotpAlgorithm.totp 的降级口径一致，
     *   否则倒计时和验证码会按不同周期各走各的）。
     */
    fun of(epochMillis: Long, periodSec: Int): Countdown {
        val period = if (isSupportedPeriod(periodSec)) periodSec else TotpParams.DEFAULT_PERIOD_SEC
        val safeMillis = if (epochMillis < 0L) 0L else epochMillis
        val periodMs = period * 1000L
        val remainingMs = periodMs - (safeMillis % periodMs)
        val remainingSec = ((remainingMs + 999L) / 1000L).toInt().coerceIn(1, period)
        val progress = (remainingMs.toFloat() / periodMs).coerceIn(0f, 1f)
        return Countdown(remainingSec, progress)
    }
}
