package com.bsfeng.monitor.record

/**
 * 门禁态的进程内快照：给「拿不到 Service 实例」的心跳链路读。
 *
 * 为什么需要：1 分钟心跳（[com.bsfeng.monitor.service.AlarmReceiver]）走
 * sampleLocationOnce → [LocationCollector.collectThrottledAsync]，原先**完全不看门禁**。
 * 门禁把前台服务的订阅关掉之后，心跳仍会每分钟发起一次高精度单点请求
 * （模拟器实测 network/gps provider +registration，约 5 秒后 -registration），
 * 于是「静止 8 小时零定位」实际变成「每分钟一次单点」—— 和用户抱怨的
 * 「拿起手机定位一直开着」是同一类耗电，门禁等于有一条后门。
 *
 * 为什么是薄状态而不是回调/单例服务：心跳跑在广播接收器里，不能依赖 Service 是否还活着；
 * 一个 @Volatile 枚举快照足够，读不到（null）时按「门禁未知」处理。
 *
 * 写入方只有 [com.bsfeng.monitor.service.LocationForegroundService.applyGateState]：
 * 服务启动、状态迁移、服务销毁（写回 null）三处。
 *
 * 点位出口（[offer]）：除了「门禁态」，还要把**所有链路拿到的点**交给门禁做位移证据。
 * 模拟器实测的坐车场景里，PROBING 态的 180s 探测被采集节流挡掉、心跳拿到的点又没人转发，
 * 门禁 5 分钟拿不到证据就回 SLEEPING —— 证据链断在这里。所有采点路径（主动定位、订阅、
 * 心跳、断档自愈）统一 offer，服务注册的 listener 负责转给 MotionGate。
 * 注意线程：offer 在采集回调线程上执行，listener 里必须自己 post 到主线程再动门禁。
 */
object LocationGateState {

    /**
     * 最近一次应用的门禁态；null = 前台服务没跑（或还没应用过门禁）。
     *
     * null 必须是「放行」：服务没跑时心跳本来就是唯一的采点路径，
     * 若因为读不到状态而跳过，就会制造新的漏采。
     */
    @Volatile
    var state: MotionGate.State? = null
        private set

    /** 点位的消费者（前台服务注册）；null = 没有服务在听，offer 静默丢弃。 */
    @Volatile
    private var listener: ((LocationSample, Boolean) -> Unit)? = null

    /**
     * 最近一次门禁 ticker 拍的时刻；0 = 从没拍过。
     *
     * 为什么心跳要读它：Doze 下 Handler 的定时回调会被挂起（真机实测凌晨 04:11 之后 ticker
     * 停了 32 分钟才等到下一拍），服务自己的兜底因此完全停摆 —— 而 RTC_WAKEUP 心跳在 doze
     * 里仍会到点（最长 9 分钟一次）。没有这个戳，心跳就分不清「服务在跑且健康」与
     * 「服务在跑但已经喊不动了」。
     */
    @Volatile
    var lastTickAtMs: Long = 0L
        private set

    /** 最近一次 SLEEPING 兜底的发起时刻；心跳与 ticker 共用这一份，不各记一份。 */
    @Volatile
    var lastFallbackAtMs: Long = 0L
        private set

    /** 上一次兜底是否没拿到点：失败就把窗口收紧到 [LocationTickerPolicy.SLEEPING_FALLBACK_RETRY_MS]。 */
    @Volatile
    var lastFallbackFailed: Boolean = false
        private set

    /**
     * 服务本轮算出的兜底窗口（30 / 60 分钟，失败时 5 分钟）；0 = 还没算过。
     *
     * 心跳必须读这一份：网络指纹那条静止证据只有服务里有（[com.bsfeng.monitor.record.NetworkStillnessTracker]），
     * 心跳自己按 30 分钟算就会在「服务本来按 60 分钟待机」时提前补一枪，两边口径一分裂，
     * 省电账就再也对不上。
     */
    @Volatile
    var fallbackWindowMs: Long = 0L
        private set

    /** 由前台服务写入；服务销毁时写回 null，心跳恢复原行为。 */
    fun update(state: MotionGate.State?) {
        this.state = state
    }

    /** 服务每拍调用（[com.bsfeng.monitor.service.LocationForegroundService] 的 ticker）。 */
    fun onTickerTick(nowMs: Long) {
        lastTickAtMs = nowMs
    }

    /** 兜底发起时刻的唯一写入口：服务与心跳都走它。 */
    fun onFallbackAttempt(nowMs: Long) {
        lastFallbackAtMs = nowMs
    }

    /** 兜底结果：失败时把窗口收紧成重试窗口，别让一次失败白等一整个兜底周期。 */
    fun onFallbackResult(success: Boolean) {
        lastFallbackFailed = !success
    }

    /** 服务每拍同步本轮生效的兜底窗口（心跳读它，见 [fallbackWindowMs]）。 */
    fun onFallbackWindow(intervalMs: Long) {
        fallbackWindowMs = intervalMs
    }

    /**
     * 注册/注销点位 listener（服务 onCreate 注册、onDestroy 传 null）。
     *
     * 只保留一个消费者：门禁证据必须汇到一个 MotionGate 实例上，多个消费者会各算各的锚点。
     */
    fun registerLocationListener(listener: ((LocationSample, Boolean) -> Unit)?) {
        this.listener = listener
    }

    /**
     * 任意链路拿到的点都从这里进入门禁证据链。
     *
     * 未注册（服务没跑）时**不能崩、不能抛**：心跳路径也会调用它，定位失败不能影响上报与保活。
     *
     * @param fromProbe true = 我们自己发起的探测 / 补采；false = 搭别的 App 的便车（passive 点）。
     *   只影响探测退避阶梯的计数（见 [MotionGate.onProbeSample]），位移证据一视同仁 ——
     *   被动点在坐车时正是最有价值的证据（别人帮我们测速）。
     */
    fun offer(sample: LocationSample, fromProbe: Boolean = true) {
        listener?.invoke(sample, fromProbe)
    }

    /**
     * 是否允许**重建位置订阅**：只有 [MotionGate.State.MOVING]（或门禁未知 null）放行。
     *
     * 为什么需要：DEEP 断档（≥30 分钟）会在 SLEEPING 下重建订阅（见 [LocationGapHealing]），
     * 而 SLEEPING 不会再有状态迁移去关它 —— 断档自愈自己制造了「定位一直开着」。
     * null 放行与服务没跑时的口径一致（那时心跳是唯一采点路径，跳过只会制造漏采）。
     */
    fun allowsSubscription(): Boolean = allowsSubscription(state)

    /** 纯判定（单测直接钉边界，不依赖全局快照）。 */
    fun allowsSubscription(state: MotionGate.State?): Boolean =
        state == null || state == MotionGate.State.MOVING

    /**
     * 心跳是否允许采点：**只有前台服务没跑（门禁未知）才放行**。
     *
     * 旧口径是「只有 SLEEPING 跳过」，PROBING / MOVING 都放行。真机实测暴露了两个问题：
     * 1. 服务在跑时心跳成了第二条独立驱动链，绕过服务的探测阶梯 —— 22:17:05 服务刚判
     *    「探测跳过（被动通道在投点）」，心跳 1 秒后因「断档 SHORT」强制发起一次主动定位；
     * 2. 心跳看到的「距上次成功 476s」按静止间隔（180s×1.5=270s）判成断档，而 SLEEPING 的
     *    兜底本来就是 30/60 分钟 —— 于是每隔几分钟就「自愈」一次，一天几十次白跑。
     *
     * 服务在跑时它自己的 ticker 与探测阶梯是唯一驱动源；心跳只在服务死了以后兜底。
     */
    fun allowsHeartbeatSample(): Boolean = state == null

    /**
     * 心跳是否该接手 SLEEPING 兜底：**服务在跑、但 ticker 已经停摆**才放行。
     *
     * 为什么需要这第二条放行口径：Android 的 doze 会挂起 Handler 的定时回调，而 RTC_WAKEUP
     * 心跳仍按时到点（[com.bsfeng.monitor.service.HeartbeatAlarmSchedule]）。真机实测
     * 11-15 凌晨 04:11 到 06:55 中间 163 分钟没有任何定位请求 —— ticker 那几拍被挂起，
     * 兜底从来没到期，服务自己无解。心跳是 doze 里唯一还活着的唤醒源。
     *
     * 三条边界：
     * - 门禁未知（服务没跑）：走 [allowsHeartbeatSample]，不走这条；
     * - 只有 SLEEPING 交给心跳：PROBING / MOVING 有各自的 30 秒拍子，心跳补位只会与探测抢 provider；
     * - ticker 还算活着（[TICKER_STALL_MS] 内拍过）就绝不补：否则又变成「心跳绕过服务阶梯」，
     *   真机 22:17:05 那个「服务刚跳过、心跳 1 秒后强制探测」的问题就是这么回来的。
     */
    fun heartbeatShouldCoverSleepingFallback(nowMs: Long): Boolean =
        state == MotionGate.State.SLEEPING && tickerStalled(nowMs)

    /** ticker 是否已停摆（超过 [TICKER_STALL_MS] 没拍过）。从未拍过不算停摆：服务刚起，拍子在排队。 */
    fun tickerStalled(nowMs: Long): Boolean =
        lastTickAtMs > 0L && nowMs - lastTickAtMs >= TICKER_STALL_MS

    /**
     * 心跳这一拍是否该**真的发起**一次 SLEEPING 兜底：ticker 停摆 **且** 兜底已到期。
     *
     * 只用 [heartbeatShouldCoverSleepingFallback] 判「该不该管」不够：心跳是 1 分钟一次，
     * 不看到期就会在 doze 里每分钟发一次定位请求 —— 那正是这轮省电改造要消灭的行为。
     * 兜底到期判定与窗口口径全部复用服务那一份（[LocationTickerPolicy.sleepingFallbackDue]）。
     */
    fun heartbeatShouldRunSleepingFallback(nowMs: Long): Boolean =
        heartbeatShouldCoverSleepingFallback(nowMs) &&
            LocationTickerPolicy.sleepingFallbackDue(
                nowMs,
                lastFallbackAtMs,
                lastFallbackFailed,
                heartbeatFallbackWindowMs(),
            )

    /** 进程内全局快照复位（仅供单测：几个 @Volatile 字段是进程级状态，用例之间会互相污染）。 */
    internal fun resetForTest() {
        state = null
        listener = null
        lastTickAtMs = 0L
        lastFallbackAtMs = 0L
        lastFallbackFailed = false
        fallbackWindowMs = 0L
    }

    /**
     * 心跳用的兜底窗口：服务同步过来的 [fallbackWindowMs]；服务还没同步过就按 30 分钟保守算。
     *
     * 保守取 30 而不是 60：同步缺失意味着「不知道网络环境静止证据」，此时多采一次的代价
     * 远小于把待机窗口猜成 60 分钟、在真断档时白等一小时。
     */
    fun heartbeatFallbackWindowMs(): Long =
        fallbackWindowMs.takeIf { it > 0L } ?: LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS

    /**
     * ticker 停摆判定窗口：10 分钟。
     *
     * SLEEPING 的拍子上界是 5 分钟（[LocationTickerPolicy.SLEEPING_MAX_TICK_MS]），取它的两倍
     * 才不会把「拍子刚好排到 5 分钟后 + 一两分钟调度漂移」误判成停摆。
     */
    const val TICKER_STALL_MS = 10 * 60_000L
}
