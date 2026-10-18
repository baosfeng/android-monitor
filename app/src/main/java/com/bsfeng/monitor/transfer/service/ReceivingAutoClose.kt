package com.bsfeng.monitor.transfer.service

/**
 * 「自动关闭接收」的倒计时状态机与文案（纯 Kotlin，可纯 JVM 单测）。
 *
 * 目的：不用时不让接收服务白耗电。用户选一段时长（5 / 10 / 30 分钟；默认关闭），
 * 服务**启动成功后**起算，到点自动停止，磁贴回 INACTIVE、常驻通知移除。
 *
 * 约定：
 * - `0` = 关闭（默认），不计时；
 * - 倒计时起点只在内存里（[AutoCloseCountdown]），**进程重启不恢复**——重启后再开接收就重新计时，
 *   避免「关掉 App 一晚，第二天打开就立刻被自动关掉」这种反直觉行为；
 * - 手动关闭服务即取消计时，重新开启则重新计时（由引擎在 `start()` / `stop()` 里收口）。
 */
object ReceivingAutoClose {

    /** 关闭（不计时）。 */
    const val OFF: Int = 0

    /** 可选时长（分钟），`0` = 关闭；与设置页 chip 一致。 */
    val MINUTES_OPTIONS: List<Int> = listOf(OFF, 5, 10, 30)

    /** 默认值：关闭。 */
    const val DEFAULT_MINUTES: Int = OFF

    /** 最后一分钟改用警示色（契约：最后 60 秒）。 */
    const val WARNING_MS: Long = 60_000L

    /** 一分钟的毫秒数。 */
    const val MINUTE_MS: Long = 60_000L

    /** 非法取值（null / 未列出的分钟数）一律回落到 [DEFAULT_MINUTES]，不让坏数据把服务一直挂着。 */
    fun normalize(minutes: Int): Int = if (minutes in MINUTES_OPTIONS) minutes else DEFAULT_MINUTES

    /** 选项文案：`0` → `关闭`，其余 `10 分钟`。 */
    fun label(minutes: Int): String =
        if (normalize(minutes) == OFF) "关闭" else normalize(minutes).toString() + " 分钟"

    /** 时长 → 到期时刻；[OFF] 返回 0（未计时）。 */
    fun deadlineMs(nowMs: Long, minutes: Int): Long {
        val normalized = normalize(minutes)
        return if (normalized == OFF) 0L else nowMs + normalized * MINUTE_MS
    }

    /** 剩余毫秒（未计时 / 已到点返回 0）。 */
    fun remainingMs(deadlineMs: Long, nowMs: Long): Long =
        if (deadlineMs <= 0L) 0L else (deadlineMs - nowMs).coerceAtLeast(0L)

    /** 是否该停止（仅在计时中且已到点）。 */
    fun shouldStop(deadlineMs: Long, nowMs: Long): Boolean = deadlineMs > 0L && nowMs >= deadlineMs

    /** 是否进入最后一分钟（含 0 秒；调用方只在计时中展示）。 */
    fun isWarning(remainingMs: Long): Boolean = remainingMs <= WARNING_MS

    /**
     * 剩余时间文案：
     * - `<= 0` → `即将自动关闭`；
     * - `< 1 分钟` → `N 秒后自动关闭`（向上取整，1..60）；
     * - `>= 1 分钟` → `N 分钟后自动关闭`（向上取整，如 4 分钟 = `4 分钟后自动关闭`）。
     */
    fun remainingText(remainingMs: Long): String {
        val safe = remainingMs.coerceAtLeast(0L)
        return when {
            safe <= 0L -> "即将自动关闭"
            safe < MINUTE_MS -> "${ceilDiv(safe, 1_000L)} 秒后自动关闭"
            else -> "${ceilDiv(safe, MINUTE_MS)} 分钟后自动关闭"
        }
    }

    private fun ceilDiv(value: Long, unit: Long): Long = (value + unit - 1L) / unit
}

/**
 * 倒计时状态机：只记一个「到期时刻」，不持久化。
 *
 * 时钟通过构造参数注入，单测直接给假时间；生产用系统墙钟（[System.currentTimeMillis]）。
 * 所有读取方法都接受 `nowMs`，因此不存在「读到一半时间跳变」的中间态。
 */
class AutoCloseCountdown(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile
    private var deadline: Long = 0L

    /** 到期时刻（epoch ms；0 = 未计时）。 */
    fun deadlineMs(): Long = deadline

    /** 是否计时中。 */
    fun isActive(): Boolean = deadline > 0L

    /** 开始 / 重新开始计时；[minutes] 为 [ReceivingAutoClose.OFF] 时等价于取消。 */
    fun start(nowMs: Long = clock(), minutes: Int = ReceivingAutoClose.DEFAULT_MINUTES) {
        deadline = ReceivingAutoClose.deadlineMs(nowMs, minutes)
    }

    /** 取消计时（手动关闭服务 / 设置改为关闭）。 */
    fun cancel() {
        deadline = 0L
    }

    /** 剩余毫秒（未计时返回 0）。 */
    fun remainingMs(nowMs: Long = clock()): Long = ReceivingAutoClose.remainingMs(deadline, nowMs)

    /** 是否到点该停（未计时恒为 false）。 */
    fun shouldStop(nowMs: Long = clock()): Boolean = ReceivingAutoClose.shouldStop(deadline, nowMs)

    /** 剩余时间文案；未计时返回 null（UI 据此不展示这一行）。 */
    fun remainingText(nowMs: Long = clock()): String? =
        if (!isActive()) null else ReceivingAutoClose.remainingText(remainingMs(nowMs))

    /** 是否进入最后一分钟（未计时返回 false）。 */
    fun isWarning(nowMs: Long = clock()): Boolean =
        isActive() && ReceivingAutoClose.isWarning(remainingMs(nowMs))
}
