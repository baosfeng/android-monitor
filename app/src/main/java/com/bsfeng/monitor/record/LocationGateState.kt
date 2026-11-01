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
    private var listener: ((LocationSample) -> Unit)? = null

    /** 由前台服务写入；服务销毁时写回 null，心跳恢复原行为。 */
    fun update(state: MotionGate.State?) {
        this.state = state
    }

    /**
     * 注册/注销点位 listener（服务 onCreate 注册、onDestroy 传 null）。
     *
     * 只保留一个消费者：门禁证据必须汇到一个 MotionGate 实例上，多个消费者会各算各的锚点。
     */
    fun registerLocationListener(listener: ((LocationSample) -> Unit)?) {
        this.listener = listener
    }

    /**
     * 任意链路拿到的点都从这里进入门禁证据链。
     *
     * 未注册（服务没跑）时**不能崩、不能抛**：心跳路径也会调用它，定位失败不能影响上报与保活。
     */
    fun offer(sample: LocationSample) {
        listener?.invoke(sample)
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
     * 心跳是否允许采点：只有明确 [MotionGate.State.SLEEPING] 才跳过。
     *
     * 纯函数、无副作用，边界在单测里钉死（SLEEPING 跳过 / PROBING 放行 / MOVING 放行 / null 放行）。
     * PROBING 放行是因为心跳的单点探测正好可以充当门禁的位移证据；MOVING 本就在订阅，心跳是补采。
     */
    fun allowsHeartbeatSample(): Boolean = state != MotionGate.State.SLEEPING
}
