package com.bsfeng.monitor.record

// 从 LocationCollector 拆出：主动定位的发令闸（并发互斥 + 首次成功前的固定退避）。
// 纯逻辑、无 Android 依赖，因此「并发叠加」与「INITIAL 死循环」这两条都能在纯 JVM 单测里钉死。

/**
 * 主动定位的发令闸。
 *
 * 管两件事，两件都是实测出来的事故：
 *
 * 1. **并发互斥**（[isInFlight]）：旧实现只有一个布尔标志，且只在「重建订阅」路径上读过 ——
 *    两次心跳 / tick 叠在一起时仍会同时发起两套主动定位，两个 provider 互相抢，
 *    日志里能看到两条请求相差 2 秒同时进行。现在发起前必须 [tryBegin] 成功；
 * 2. **首次成功前的固定退避**（[LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS]）：首次成功前
 *    `lastSuccessAtMs` 还是 null，GapHealing 每轮都判 INITIAL → forceActiveFix → 跳过节流，
 *    于是每 10 秒发起一次、每次都跑满两段超时，是「耗电 + 永远出不来点」的死循环。
 *
 * 退避**固定 30 秒、不指数增长**：指数退避会让冷启动首次出点越推越远，
 * 而 GPS 冷启动前几次超时是常态，固定间隔保证首次成功最多晚 30 秒回来（防饥饿）。
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
     * @param isInitial 当前处于「首次成功前」阶段（GapHealing 的 INITIAL）：只有这个阶段走退避，
     *   之后的重试由模式节流与断档自愈管，不再叠一层。
     * @return true 表示拿到了发令资格，调用方**必须**在结束（成功 / 失败 / 超时 / 异常）时调 [finish]
     */
    fun tryBegin(nowMs: Long, isInitial: Boolean): Boolean = synchronized(lock) {
        if (inFlight) return false
        if (isInitial) {
            val last = lastAttemptAtMs
            if (last != null) {
                // 时钟回拨：按「基线作废」处理（与 GapHealing 的 CLOCK_BACKWARD 同口径），
                // 否则时间倒退一次就会把退避锁住到时钟追平为止。
                if (nowMs < last) {
                    lastAttemptAtMs = nowMs
                } else if (nowMs - last < LocationPace.INITIAL_ACTIVE_FIX_RETRY_MS) {
                    return false
                }
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

    /** 清空全部状态（深断档 / 一次成功之后调用）。 */
    fun reset() {
        synchronized(lock) {
            inFlight = false
            lastAttemptAtMs = null
        }
    }
}
