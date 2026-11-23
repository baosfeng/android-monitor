package com.bsfeng.monitor.record

/**
 * 进程启动时把「上次定位成功时刻」从库里回填给采集器（一次性）。
 *
 * 为什么需要：`LocationHealthState.lastSuccessAtMs` 只在拿到点的时候写，是**纯进程内状态** ——
 * 进程重启（装包 / 系统回收 / 用户杀进程）后归零，于是自检面板显示「还没有成功定位」，
 * 哪怕 5 分钟前刚采到点；诊断上「重启后的空值」与「真的从没采到」也分不开（真机观测时踩到）。
 *
 * 三条纪律：
 * - **进程内只回填一次**：查库是 I/O，不能每拍采样都跑；[seed] 用 [seeded] 做一次性闸，
 *   并发调用的后来者直接被丢弃（它们本该读同一份值）；
 * - **在 IO 线程执行**：调用方负责调度（服务的 ioWork / 心跳的协程），本类不碰线程；
 * - **失败与无记录都保持 null**：null 是「不知道 / 从没成功过」的既有语义，塞 0 会被读成
 *   「1970 年成功过」，断档与健康判定全部失真。
 *
 * @param loadLastSuccessAtMs 查库：返回库里最近一条 LOCATION 的时刻，没有记录 / 查询失败返回 null
 * @param applySeed 把结果交给采集器（生产实现是 [LocationCollector.seedLastSuccessAtMs]）
 */
class LocationSuccessSeed(
    private val loadLastSuccessAtMs: () -> Long?,
    private val applySeed: (Long?) -> Unit,
) {

    /** 本进程是否已经跑过回填（含失败）：失败也置位，避免每次心跳都去查一次库。 */
    @Volatile
    var seeded: Boolean = false
        private set

    /**
     * 执行一次回填（幂等：返回 false 表示本进程已经跑过，本次什么都没做）。
     *
     * 异常一律吞掉：回填只是让自检更准，不能影响采集主链路。
     */
    fun seed(): Boolean {
        if (seeded) return false
        seeded = true
        val loaded = runCatching { loadLastSuccessAtMs() }.getOrNull()
        runCatching { applySeed(loaded) }
        return true
    }

    /** 仅供单测：把一次性闸复位（同一 JVM 里多个用例会互相污染）。 */
    internal fun resetForTest() {
        seeded = false
    }
}
