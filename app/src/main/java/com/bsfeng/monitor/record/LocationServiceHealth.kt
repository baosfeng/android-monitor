package com.bsfeng.monitor.record

/**
 * 前台服务健康度的可观测信号 + 判定（纯逻辑，可在 JVM 单测里钉死）。
 *
 * 为什么需要它：旧口径把 [LocationGateState.state] != null 当成「服务健康」的唯一证据，
 * 但那个字段只代表 onDestroy 没跑过 —— 服务被系统变成壳子时（真机实测：isForeground=false、
 * types=0x0、startForegroundCount 不再增加），state 仍然写着 SLEEPING，于是心跳据此
 * 「服务在跑、心跳别碰定位」，而服务自己的 ticker 早已停摆：采点完全停了 5.4 小时
 * （lastSuccessAgoSec 17719 → 19628），自检面板却一直显示正常。
 *
 * 所以健康必须由**心跳能观测到的活证据**判定：ticker 近期拍过、前台身份还在、或者最近
 * 确实拿到过点。三者都不成立时，服务就是壳子 —— 心跳必须自愈接管，而不是继续让路。
 *
 * 三个信号各有边界：
 * - ticker：doze 会挂起 Handler 定时回调（真机 04:11→06:55 空 163 分钟），停摆不等于壳化，
 *   但停摆超过 [LocationGateState.TICKER_STALL_MS] 时心跳至少要接手兜底；
 * - foreground：壳化的直接证据（被系统摘掉前台身份），但它要 API 24+ 才读得到，
 *   读不到时按「未知」处理，不参与否决；
 * - 采点价龄：sensor hub 仍在给证据时服务可能很健康却没落点，所以它只在 ticker 也停摆时才作数。
 */
enum class ServiceHealth {
    /** 服务没跑（门禁未知 / onDestroy 跑过）：心跳是唯一采点路径，照旧兜底采点。 */
    NOT_RUNNING,

    /** 服务在跑且健康：心跳完全不碰定位（服务自己的 ticker 与探测阶梯是唯一驱动源）。 */
    HEALTHY,

    /** ticker 停摆且前台身份丢了/从没进过前台：服务已壳化，心跳要自愈拉起并接手。 */
    SHELLED,

    /** 服务在跑、但 ticker 停摆（多半是 doze 挂起回调）：心跳只接手到期的 SLEEPING 兜底。 */
    TICKER_STALLED,
}

/**
 * 心跳侧观测到的服务信号快照。
 *
 * 全部字段都由服务写入 [LocationGateState]，心跳只读：心跳跑在广播接收器里，拿不到 Service 实例。
 *
 * @param state 门禁态；null = 服务没跑
 * @param lastTickAtMs ticker 最近一拍的时刻（0 = 从没拍过）
 * @param startForegroundCount 服务成功进前台的累计次数（壳化后不再增长）；0 = 从没进过前台
 * @param foregroundAlive 最近一次观测到的前台身份；null = 当前系统版本读不到
 * @param lastSuccessAtMs 最近一次拿到点的时刻
 */
data class ServiceObservation(
    val state: MotionGate.State?,
    val lastTickAtMs: Long,
    val startForegroundCount: Int,
    val foregroundAlive: Boolean?,
    val lastSuccessAtMs: Long?,
)

object ServiceHealthJudge {

    /**
     * 把观测翻成健康档。
     *
     * @param tickerStallMs ticker 停摆判定窗口（生产值 [LocationGateState.TICKER_STALL_MS]）；
     *   单测直接传窗口，不复制一份常量
     * @param successStaleMs 采点「一直拿不到」的判据：超过它就认为链路的最后一段也断了；
     *   判定只在 ticker 停摆且前台身份丢失时用到，所以取一个粗粒度窗口就够
     */
    fun judge(
        observation: ServiceObservation,
        nowMs: Long,
        tickerStallMs: Long,
        successStaleMs: Long,
    ): ServiceHealth {
        if (observation.state == null) return ServiceHealth.NOT_RUNNING
        // 拍子还在 = 活证据成立。从未拍过同样算活：服务刚起，第一拍还排在队列里。
        val tickerStalled = observation.lastTickAtMs > 0L &&
            nowMs - observation.lastTickAtMs >= tickerStallMs
        if (!tickerStalled) return ServiceHealth.HEALTHY
        val successFresh = observation.lastSuccessAtMs?.let { nowMs - it < successStaleMs } ?: false
        return when (observation.foregroundAlive) {
            // 前台身份明确丢失：壳化的直接证据，不再看别的信号
            false -> ServiceHealth.SHELLED
            // 前台身份还在：拍子停了多半是 doze 挂起了 Handler 回调；但连点都拿不到了，
            // 说明链路最后一段也断了，仍按壳化处理（宁可多重启一次前台服务）
            true -> if (successFresh) ServiceHealth.TICKER_STALLED else ServiceHealth.SHELLED
            // 读不到前台身份（系统不给服务列表）：只能看剩下的两条证据。
            // counter 为 0 = startForeground 一次都没成功过（进前台在 startTicker 之前，
            // 正常服务不可能出现这个组合），拿不到前台优先级 —— 按壳化处理；
            // 否则「最近仍在拿点」只判 doze 停摆，连点都没了就按壳化。
            null -> when {
                observation.startForegroundCount <= 0 -> ServiceHealth.SHELLED
                successFresh -> ServiceHealth.TICKER_STALLED
                else -> ServiceHealth.SHELLED
            }
        }
    }
}
