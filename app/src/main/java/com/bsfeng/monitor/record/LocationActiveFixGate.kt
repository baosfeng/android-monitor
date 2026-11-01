package com.bsfeng.monitor.record

// 从 LocationCollector 拆出：主动定位的发令闸（并发互斥 + 按模式的最小间隔）。
// 纯逻辑、无 Android 依赖，因此「并发叠加」「INITIAL 死循环」「断档自愈每分钟重试」都能在纯 JVM 单测里钉死。

/**
 * 主动定位的发令闸。
 *
 * 管三件事，三件都是实测出来的事故：
 *
 * 1. **并发互斥**（[isInFlight]）：旧实现只有一个布尔标志，且只在「重建订阅」路径上读过 ——
 *    两次心跳 / tick 叠在一起时仍会同时发起两套主动定位，两个 provider 互相抢，
 *    日志里能看到两条请求相差 2 秒同时进行。现在发起前必须 [tryBegin] 成功；
 * 2. **首次成功前的固定退避**（[LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS]）：首次成功前
 *    `lastSuccessAtMs` 还是 null，GapHealing 每轮都判 INITIAL → forceActiveFix → 跳过节流，
 *    于是每 10 秒发起一次、每次都跑满两段超时，是「耗电 + 永远出不来点」的死循环。
 *    退避**固定不指数增长**：指数退避会让冷启动首次出点越推越远；
 * 3. **全路径最小间隔**（[minIntervalMs]）：一次主动定位就是 network 5s + gps 15s 的射频，
 *    而断档自愈的 forceActiveFix 会绕过节流。23127PN0CC 实测：上次成功在很久以前时
 *    （SLEEPING 只靠 30 分钟兜底），1 分钟心跳每次都判断档 → 每小时约 40 次满预算主动定位，
 *    11 小时只落库 8 个点 —— 绝大多数探测是白跑的。所以**所有**发起（含 force / 断档自愈 /
 *    首点兜底）都受最小间隔约束：静止 180 秒、移动 30 秒；失败同样占位，避免下一拍重试。
 */
class LocationActiveFixGate {

    private val lock = Any()

    private var inFlight: Boolean = false
    private var lastAttemptAtMs: Long? = null

    /** 是否有主动定位在飞（入口让路与订阅重建都读它）。 */
    val isInFlight: Boolean get() = synchronized(lock) { inFlight }

    /** 上次发起时刻（诊断 / 单测用）。 */
    fun lastAttemptAtMs(): Long? = synchronized(lock) { lastAttemptAtMs }

    /**
     * 申请发起一次主动定位。
     *
     * @param mode 当前采样模式：最小间隔按它取（移动 30s / 静止 180s）
     * @param isInitial 当前处于「首次成功前」阶段（GapHealing 的 INITIAL）：它的 30 秒固定退避
     *   只是**下限**，静止模式仍按 180 秒走（冷启动首点最多晚 3 分钟，换来不再每分钟白烧射频）
     * @return true 表示拿到了发令资格，调用方**必须**在结束（成功 / 失败 / 超时 / 异常）时调 [finish]
     */
    fun tryBegin(nowMs: Long, mode: SampleMode, isInitial: Boolean): Boolean = synchronized(lock) {
        if (inFlight) return false
        val last = lastAttemptAtMs
        if (last != null) {
            // 时钟回拨：按「基线作废」处理（与 GapHealing 的 CLOCK_BACKWARD 同口径），
            // 否则时间倒退一次就会把最小间隔锁住到时钟追平为止。
            if (nowMs < last) {
                lastAttemptAtMs = nowMs
            } else if (nowMs - last < minIntervalMs(mode, isInitial)) {
                return false
            }
        }
        inFlight = true
        lastAttemptAtMs = nowMs
        return true
    }

    /** 一次主动定位结束（任何出口都要调，否则闸会一直关着）。 */
    fun finish() {
        synchronized(lock) { inFlight = false }
    }

    companion object {
        /**
         * 两次发起之间的最小间隔（纯函数）：静止 180 秒、移动 30 秒。
         *
         * 首次成功前再叠一层 [LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS] 作下限（取较大值）：
         * 移动模式两者都是 30 秒，静止模式由 180 秒主导 —— 这正是要压掉的「每分钟白跑」。
         */
        fun minIntervalMs(mode: SampleMode, isInitial: Boolean): Long {
            val modeInterval = LocationPace.intervalOf(mode)
            return if (isInitial) {
                maxOf(modeInterval, LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS)
            } else {
                modeInterval
            }
        }
    }
}
