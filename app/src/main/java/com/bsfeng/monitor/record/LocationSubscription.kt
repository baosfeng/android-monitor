package com.bsfeng.monitor.record

// 持续订阅通道：按模式调速的 requestLocationUpdates 封装 + 生命周期状态机（纯逻辑可单测）。
// 生产实现见 AndroidLocationSubscriber；这里只定义口径、provider 计划与幂等主管。

/**
 * 持续位置订阅的抽象。
 *
 * 抽成接口是为了让「订阅生命周期（幂等建立 / 幂等释放 / 静默重建 / 模式变速）」
 * 能在纯 JVM 单测里跑，生产实现是 AndroidLocationSubscriber。
 *
 * 口径：
 * - [start] 成功返回 true 并开始持续回调 [onSample]；失败（无 provider / 未授权）返回 false 且不抛异常；
 * - 已在订阅时再调 [start] 由调用方（LocationSubscriptionSupervisor）保证只在新配置下才触发；
 * - [stop] 幂等：未订阅时调用是空操作；
 * - 任何回调都不允许把异常抛回系统（LocationListener 在主线程）。
 */
interface LocationSubscriptionSource {
    fun start(
        providers: List<String>,
        intervalMs: Long,
        minDistanceMeters: Float,
        onSample: (LocationSample) -> Unit,
    ): Boolean

    fun stop()

    fun isActive(): Boolean
}

/**
 * 订阅的参数计划（纯函数）。
 *
 * - provider 优先 fused（系统融合定位，省电且不会两个 provider 各吐一条）；
 * - fused 不可用时用 gps + network 组合；两者都不可用返回空列表（调用方据此判失败）；
 * - 间隔直接复用 [LocationPace.intervalOf]（移动 30s / 静止 180s），保证订阅与节流同一口径。
 */
object LocationSubscriptionPlan {

    /** 系统融合 provider 名（Android 12+ 的 LocationManager.FUSED_PROVIDER 取值）。 */
    const val FUSED_PROVIDER = "fused"

    /** 订阅的最小距离阈值：0 表示不按位移过滤（间隔已经控频，位移过滤会吞掉慢速移动的轨迹点）。 */
    const val MIN_DISTANCE_METERS = 0f

    /**
     * 按可用 provider 选出订阅要用的 provider 列表。
     *
     * 口径（与旧实现不同，别改回去）：**有 fused 时只挂 fused + network，不挂裸 gps**。
     * 旧实现把 fused + gps + network 无条件全部注册，起因是 emulator-5554 上 fused 被列为可用、
     * 但 gms FLP 从不给订阅者派点（订阅零投递却显示活跃）。代价是裸 gps 那一路以
     * HIGH_ACCURACY + 30 秒常驻，GNSS 长期热着空转 —— 而真机 8 天 2176 个点里 gps 只出 4 个。
     *
     * 现在：fused 可用 → fused + network；network 也未启用 → fused + gps（否则把唯一能出点的
     * 那路丢掉）；fused 不可用 → gps + network。任一来源出点即可，由 [LocationQuality.shouldAccept]
     * 与落库节流去重。
     */
    fun providersFor(available: Collection<String>): List<String> {
        // 有 fused 时不注册裸 gps：真机 8 天 2176 个点里 gps 只出 4 个，而裸 gps 订阅是
        // HIGH_ACCURACY + 30 秒一次，等于让 GNSS 长期热着空转。fused 需要卫星时自己会调 GPS，
        // 不需要我们再挂一条并行订阅（两路各吐一条点，还得靠质量闸去重）。
        // 没有 fused 的设备（少数 ROM）才退回 gps + network，否则精度掉到基站级。
        val names = if (FUSED_PROVIDER in available) {
            // 有 fused 时不挂裸 gps（它会以 HIGH_ACCURACY + 30 秒常驻，GNSS 长期热着空转）。
            // 但 network 也拿不到时保留 gps：否则 available=[fused, gps] 的机型会把唯一能出点的那路丢掉。
            if (NETWORK_PROVIDER in available) {
                listOf(FUSED_PROVIDER, NETWORK_PROVIDER)
            } else {
                listOf(FUSED_PROVIDER, GPS_PROVIDER)
            }
        } else {
            listOf(GPS_PROVIDER, NETWORK_PROVIDER)
        }
        return names.filter { it in available }
    }

    private const val GPS_PROVIDER = "gps"
    private const val NETWORK_PROVIDER = "network"

    /** 落库节流间隔：移动 30s / 静止 180s（与 [LocationPace.intervalOf] 同源）。 */
    fun intervalMsOf(mode: SampleMode): Long = LocationPace.intervalOf(mode)

    /**
     * 订阅的**派点**间隔。
     *
     * - 已确认移动（[SampleMode.MOVING]）：30 秒，一律加密 —— 位移判定比传感器可靠，
     *   坐车（手机平稳但位移大）正是靠这条升上来的；
     * - 传感器手持 / 静止：[override] 优先（手持 180s、静止 30s），否则退回静止探测间隔。
     *
     * 与 [intervalMsOf] 分开是「模式判定精度」与「落库省电」的拆解：订阅点先喂模式状态机，
     * 只有够到落库窗口的才落库（见 [LocationCollector.onSubscriptionSample]）。
     */
    fun probeIntervalMsOf(mode: SampleMode, override: Long? = null): Long {
        if (mode == SampleMode.MOVING) return LocationPace.MOVING_INTERVAL_MS
        return override ?: LocationPace.STILL_PROBE_INTERVAL_MS
    }

    /** 静止省电降级：系统开了省电模式就退回 180s 派点（模式判定变钝，换续航）。 */
    fun probeIntervalMsOf(mode: SampleMode, powerSave: Boolean, override: Long? = null): Long =
        if (mode == SampleMode.STILL && powerSave) LocationPace.STILL_INTERVAL_MS
        else probeIntervalMsOf(mode, override)

    /**
     * 静默超时：多久没收到回调就判定订阅失效并重建。
     *
     * 按**派点**间隔算（静止 30s -> 105s），不是落库间隔：订阅已经按 30 秒派点，
     * 用 180 秒算出来的 555 秒阈值会让「派点已停但落库窗口还没到」的空窗拖到 9 分钟。
     * 取 3 倍 + 15 秒余量：Doze 里系统本就会延迟派发，阈值太紧会来回重建（反而更费电）。
     */
    fun silentTimeoutMsOf(mode: SampleMode): Long = probeIntervalMsOf(mode) * 3 + 15_000L

    /**
     * 首点兜底：订阅已建立、超过 [silentTimeoutMsOf] 仍**一个点都没投递**。
     *
     * 与「静默重建」的区别：重建是订阅已经工作过又停了（deliveryCount > 0 后静默）；
     * 这里是**从建立起就没出过点**，重建同一批 provider 也不会好转（fused 的发病形态）。
     * 触发后由调用方绕过模式节流主动定位一次，保证「订阅建立 → 有第一个点」不会无限等。
     */
    fun needsInitialFix(snapshot: ActiveSnapshot, mode: SampleMode): Boolean =
        snapshot.active && snapshot.deliveryCount == 0 &&
            (snapshot.silentMs ?: 0L) >= silentTimeoutMsOf(mode)

    /** 判据用的最小状态（避免把整个 [LocationSubscriptionSupervisor.Snapshot] 传进来）。 */
    interface ActiveSnapshot {
        val active: Boolean
        val silentMs: Long?
        val deliveryCount: Int
    }
}

/**
 * 订阅主管：幂等建立 / 幂等释放 / 变速重建 / 静默重建。
 *
 * 全部状态读写都在 [lock] 内完成，回调（[onSample]）在锁外执行 —— 回调里会落库、
 * 甚至可能反过来调 [ensure]，持锁回调迟早死锁。
 */
class LocationSubscriptionSupervisor(
    private val source: LocationSubscriptionSource,
    private val log: (String, Boolean) -> Unit = { _, _ -> },
) {

    private val lock = Any()

    private var active: Boolean = false
    private var providers: List<String> = emptyList()
    private var intervalMs: Long = 0L
    private var startedAtMs: Long = 0L
    private var lastDeliveryMs: Long? = null
    private var deliveryCount: Int = 0

    /** 一次订阅的只读快照（日志与 [LocationStatus] 用）。 */
    data class Snapshot(
        override val active: Boolean,
        val providers: List<String>,
        val intervalMs: Long,
        override val silentMs: Long?,
        override val deliveryCount: Int,
    ) : LocationSubscriptionPlan.ActiveSnapshot

    /**
     * 幂等建立订阅。
     *
     * - 未激活：按 [providers] / [intervalMs] 建立；
     * - 已激活且配置相同：空操作，返回 true；
     * - 已激活但间隔变了（模式切换）：先 stop 再 start，保证系统按新间隔派发。
     *
     * @return 订阅最终是否处于活跃状态（provider 为空或底层失败时 false）
     */
    fun ensure(nowMs: Long, providers: List<String>, intervalMs: Long, onSample: (LocationSample) -> Unit): Boolean {
        if (providers.isEmpty()) {
            log("位置订阅跳过：没有可用的 provider", true)
            stop()
            return false
        }
        val needRestart = synchronized(lock) {
            !active || this.providers != providers || this.intervalMs != intervalMs
        }
        if (!needRestart) return true
        return restart(nowMs, providers, intervalMs, onSample)
    }

    /** 强制重建（深断档 / 判定静默失效时用）；未激活时退化为首次建立。 */
    fun restart(
        nowMs: Long,
        providers: List<String>,
        intervalMs: Long,
        onSample: (LocationSample) -> Unit,
    ): Boolean {
        stop()
        if (providers.isEmpty()) {
            log("位置订阅跳过：没有可用的 provider", true)
            return false
        }
        val wrapped: (LocationSample) -> Unit = { sample ->
            onDelivery(System.currentTimeMillis())
            onSample(sample)
        }
        val started = runCatching {
            source.start(providers, intervalMs, LocationSubscriptionPlan.MIN_DISTANCE_METERS, wrapped)
        }.getOrElse { error ->
            log("位置订阅异常：" + error.javaClass.simpleName, true)
            false
        }
        if (!started) {
            log("位置订阅建立失败 providers=" + providers.joinToString() + " 间隔=" + (intervalMs / 1000) + "s", true)
            return false
        }
        synchronized(lock) {
            active = true
            this.providers = providers
            this.intervalMs = intervalMs
            startedAtMs = nowMs
            lastDeliveryMs = null
            deliveryCount = 0
        }
        log("位置订阅已建立 providers=" + providers.joinToString() + " 间隔=" + (intervalMs / 1000) + "s", false)
        return true
    }

    /** 幂等释放：未激活时是空操作，重复调用不重复打日志。 */
    fun stop() {
        val wasActive = synchronized(lock) { stopLocked() }
        if (!wasActive) return
        runCatching { source.stop() }.onFailure { log("位置订阅释放异常：" + it.javaClass.simpleName, true) }
        log("位置订阅已释放", false)
    }

    private fun stopLocked(): Boolean {
        if (!active) return false
        active = false
        providers = emptyList()
        intervalMs = 0L
        lastDeliveryMs = null
        deliveryCount = 0
        return true
    }

    /** 记录一次投递（供静默判定与状态快照使用）。 */
    fun onDelivery(nowMs: Long) {
        synchronized(lock) {
            lastDeliveryMs = nowMs
            deliveryCount++
        }
    }

    fun isActive(): Boolean = synchronized(lock) { active }

    /**
     * 订阅是否已经静默到需要重建。
     *
     * 未激活返回 false（那是「该建立」，不是「该重建」）。判据从最后一次投递算起，
     * 没有投递过则从建立时刻算起。
     */
    fun needsRestart(nowMs: Long, mode: SampleMode): Boolean = synchronized(lock) {
        if (!active) return false
        val since = lastDeliveryMs ?: startedAtMs
        nowMs - since > LocationSubscriptionPlan.silentTimeoutMsOf(mode)
    }

    /**
     * 静默重建（生产路径唯一入口）：判静默 + 重启 + 打日志，返回是否重建了。
     *
     * 分开叫它不是重复：[needsRestart] 是纯判定，调用方还需要「判定 → 重建 → 可观测」这一整条，
     * 三段如果散在调用点，很容易漏掉其中一段（旧实现就漏到**一次都没被调用**）。
     */
    fun restartIfSilent(
        nowMs: Long,
        mode: SampleMode,
        providers: List<String>,
        intervalMs: Long,
        onSample: (LocationSample) -> Unit,
    ): Boolean {
        val snapshot = snapshot(nowMs)
        if (!needsRestart(nowMs, mode)) return false
        val silentSeconds = (snapshot.silentMs ?: 0L) / 1000
        log(
            "订阅静默重建：已 " + silentSeconds + "s 无投递（阈值 " +
                (LocationSubscriptionPlan.silentTimeoutMsOf(mode) / 1000) + "s，已投递 " +
                snapshot.deliveryCount + " 次，providers=" + snapshot.providers.joinToString() + "）",
            true,
        )
        return restart(nowMs, providers, intervalMs, onSample)
    }

    fun snapshot(nowMs: Long): Snapshot = synchronized(lock) {
        val since = if (active) (lastDeliveryMs ?: startedAtMs) else null
        Snapshot(
            active = active,
            providers = providers,
            intervalMs = intervalMs,
            silentMs = since?.let { nowMs - it },
            deliveryCount = deliveryCount,
        )
    }

}
