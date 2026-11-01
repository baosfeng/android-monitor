package com.bsfeng.monitor.record

// 定位采集入口：订阅优先 → 缓存兜底（订阅静默）→ 一次主动定位兜底 + 落库节流 + 状态上报。
// 规则在 LocationRules / LocationThrottle / LocationQuality / LocationGapHealing / LocationSubscription；
// 订阅通道编排在 LocationSubscriptionChannel；主动定位的发令与分派在 LocationActiveFixGate / LocationActiveFixRunner；
// Android 实现在 AndroidLocationEnv / AndroidLocationSubscriber。

import android.content.Context

/**
 * 定位采集器。
 *
 * 三级取点，按可靠性排序：
 * 1. **持续订阅**（[startSubscription]）：服务存活期间按模式间隔 requestLocationUpdates，
 *    MOVING 约 30 秒、STILL 约 30 秒一个**探测点**；探测点先喂模式状态机，
 *    够到落库窗口（MOVING 30 秒 / STILL 180 秒）才落库 —— 探测间隔与落库间隔是两个量，
 *    见 [LocationPace.STILL_PROBE_INTERVAL_MS]；
 * 2. **缓存兜底**：订阅静默超过阈值（[LocationSubscriptionPlan.silentTimeoutMsOf]，Doze 常见）
 *    时读 `getLastKnownLocation`；
 * 3. **主动定位兜底**：缓存不新鲜 / 无可用点时发起一次（network 5 秒 → gps 15 秒，
 *    预算按 provider 独立计，见 [LocationPolicy.activeFixTimeoutMs]；编排见 [LocationActiveFixRunner]）。
 *
 * 断档自愈（[LocationGapHealing]）：距上次成功采样超过 1.5 倍间隔就强制补一次主动定位
 * （受发令闸最小间隔约束，静止 180 秒 / 移动 30 秒），超过 30 分钟还要重建订阅并清空节流与模式基线
 * （重建订阅要过门禁闸，见 [LocationGateState.allowsSubscription]）。
 * 订阅「活着但零投递」另有兜底：静默重建 + 首点主动定位（见 [LocationSubscriptionChannel]）。
 *
 * 驱动方两条：[com.bsfeng.monitor.service.LocationForegroundService] 的前台服务定时器（MOVING 时补采）+ [com.bsfeng.monitor.service.AlarmReceiver]
 * 1 分钟心跳（兜底；服务被杀后订阅不存在，走缓存 → 主动定位）。
 *
 * 异常契约：未授权 / 无 LocationManager / provider 不可用 / 系统抛 SecurityException
 * 一律回调 null，不向调用方抛异常（心跳与轮询链路不能被定位拖垮）。
 */
class LocationCollector internal constructor(
    private val env: Env,
    private val throttle: LocationThrottle = LocationThrottle(),
    private val modeTracker: LocationModeTracker = LocationModeTracker(),
    /** 时钟：订阅回调没有调用方的 `nowMs`，且单测要能控制时间（否则造的点一律判 stale）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * 订阅重建的闸：只有门禁 MOVING（或门禁未知）才允许重建订阅。
     *
     * 默认读 [LocationGateState] 的进程内快照（心跳链路拿不到 Service 实例）；
     * 单测注入常量，避免依赖那个全局快照。
     */
    private val subscriptionAllowed: () -> Boolean = { LocationGateState.allowsSubscription() },
) {

    /**
     * 采集依赖：可替换以便单测（生产实现见 [AndroidEnv]）。
     *
     * 订阅相关两个方法给了默认实现（返回 null / 空），旧单测里的假 Env 不必全量实现。
     */
    internal interface Env {
        /** 是否拿到「精确位置」（ACCESS_FINE_LOCATION）。只给大致位置时为 false。 */
        fun isFineGranted(): Boolean

        /**
         * 是否至少拿到「大致位置」（ACCESS_COARSE_LOCATION）。
         *
         * 默认委托 [isFineGranted]：精确位置必然包含大致位置，而且既有单测里的假 Env
         * 只实现了 isFineGranted —— 给默认值就不必逐个补实现，语义也不会变。
         */
        fun isCoarseGranted(): Boolean = isFineGranted()

        fun isBackgroundGranted(): Boolean

        /** 读系统缓存位置（三个 provider 各一条，可能为空）。 */
        fun readCached(): List<LocationSample>

        /** 发起一次主动定位（满预算）；无 LocationManager 时回调 null。 */
        fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit)

        /**
         * 带预算档位的主动定位（见 [FixBudget]）。
         *
         * 默认委托上一版两参方法：既有单测的假 Env 只实现两参版本，不必逐个补实现，
         * 也不会因为「假 Env 忽略预算」而让断言失真。生产实现 [AndroidEnv] 覆盖它。
         */
        fun requestActiveFix(nowMs: Long, budget: FixBudget, onResult: (LocationSample?) -> Unit) {
            requestActiveFix(nowMs, onResult)
        }

        /** 诊断日志出口（纯 JVM 单测里 android.util.Log 会抛 not mocked，必须可注入）。 */
        fun log(message: String, warn: Boolean) {}

        /** 持续订阅通道；无 LocationManager 时返回 null（订阅退化为缓存 + 主动定位）。 */
        fun subscriptionSource(): LocationSubscriptionSource? = null

        /** 当前可用的 provider（fused / gps / network）；无权限或无 LocationManager 时为空。 */
        fun availableProviders(): List<String> = emptyList()

        /**
         * 系统是否处于省电模式：静止模式的探测间隔据此从 30s 回退到 180s（见
         * [LocationSubscriptionPlan.probeIntervalMsOf]）。默认 false（非省电），
         * 读不到 BatteryManager 的机型就走默认值，不影响任何控制流。
         */
        fun isPowerSaveMode(): Boolean = false
    }

    constructor(context: Context) : this(AndroidEnv(context.applicationContext))

    /** 订阅通道：只在 [startSubscription] 时建立，[stopSubscription] 时释放。 */
    private val channel = LocationSubscriptionChannel(
        supervisor = env.subscriptionSource()?.let { LocationSubscriptionSupervisor(it, env::log) },
        log = env::log,
        // 订阅只需「有任一位置权限」：带 COARSE 时系统仍会派 network 点（虽然后面会被精度闸丢）
        fineGranted = { env.isFineGranted() || env.isCoarseGranted() },
        availableProviders = env::availableProviders,
        powerSave = env::isPowerSaveMode,
        probeIntervalOverride = { probeIntervalOverrideMs },
        modeOf = { mode },
        onFailure = ::noteFailure,
        inFlight = { activeFixGate.isInFlight },
        processor = { sample, target -> onSubscriptionSample(sample, target) },
    )

    private val lock = Any()

    /** 采集状态（成功时间 / 失败原因 / 质量闸对照点），见 [LocationHealthState]。 */
    private val healthState = LocationHealthState()

    /** 发令闸：并发互斥（同时在飞最多一个）+ 首次成功前的固定退避。 */
    private val activeFixGate = LocationActiveFixGate()

    /**
     * 主动定位编排：发令、请求、结果分派（见 [LocationActiveFixRunner]）。
     *
     * 取代原来的单个 in-flight 布尔标志 —— 那个只在重建订阅时被读过，
     * 两条驱动链叠在一起时照样会同时发起两套主动定位（实测日志相差 2 秒的两条请求）。
     */
    private val activeFixRunner = LocationActiveFixRunner(
        request = { nowMs, budget, onResult -> env.requestActiveFix(nowMs, budget, onResult) },
        gate = activeFixGate,
        noteFailure = ::noteFailure,
        log = env::log,
    )

    /** 当前采样模式（进程内状态；重启回静止）。 */
    val mode: SampleMode get() = modeTracker.mode

    /**
     * 传感器给的派点间隔覆盖（毫秒）；null = 用默认。
     *
     * 由 [com.bsfeng.monitor.service.LocationForegroundService] 按运动状态设置：
     * 手持 180s（玩手机不该耗电）、静止 30s、确认移动后忽略覆盖改用 30s。
     * 放进程内是因为它只影响下一次调速，重启后由传感器重新判定，不需要持久化。
     */
    @Volatile
    private var probeIntervalOverrideMs: Long? = null

    /** 设置传感器派点覆盖；传 null 恢复默认。 */
    fun setProbeIntervalOverride(ms: Long?) {
        probeIntervalOverrideMs = ms
    }

    /**
     * 落库窗口覆盖（毫秒）；null = 按模式取 [LocationPace.intervalOf]。
     *
     * 为什么需要：室内 passive 点精度 30m 时，走路 30 秒只走 40m，过不了「位移 > 两端精度之和」
     * 的噪声地板，模式判定停在 STILL —— 于是落库被 180s 节流卡住，传感器明明确认在走路却
     * 三分钟才一个点。传感器的稳定步频是可信信号，不该再等位移确认。
     */
    @Volatile
    private var captureIntervalOverrideMs: Long? = null

    /** 设置落库窗口覆盖；传 null 恢复按模式取。 */
    fun setCaptureIntervalOverride(ms: Long?) {
        captureIntervalOverrideMs = ms
    }

    /** 实际落库窗口：传感器覆盖优先，否则按当前模式。 */
    private fun captureIntervalMs(): Long = captureIntervalOverrideMs ?: LocationPace.intervalOf(mode)

    /** 当前模式对应的采样间隔（毫秒），供定时器与日志使用。 */
    fun samplingIntervalMs(): Long = captureIntervalMs()

    /** 「现在该不该采」的纯判断：只读节流与模式状态，**不读定位、不发起请求**。 */
    fun shouldSampleNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        throttle.canAcquire(nowMs, samplingIntervalMs())

    /**
     * 是否至少有定位权限（精确或大致）可以发起采集。
     *
     * 注意与 [Env.isFineGranted] 的区别：那个是「有没有精确位置」，只给大致位置时为 false；
     * 这里是「能不能发起采集」。只给大致位置时仍然要采 —— 点是基站级的、会被 [LocationQuality]
     * 丢弃，但把链路闸死只会让失败原因误报成「未授权」，引导用户去开「始终允许」（错的下一步）。
     */
    private fun hasLocationPermission(): Boolean = env.isFineGranted() || env.isCoarseGranted()

    /** 兼容旧调用点：只读缓存的同步采集（无节流副作用以外的主动定位）。 */
    fun collect(nowMs: Long = System.currentTimeMillis()): LocationSample? {
        if (!hasLocationPermission()) return null
        return LocationPolicy.pick(env.readCached(), nowMs)
    }

    /** 只读状态快照：最后成功时间 / 失败原因 / 订阅是否活跃 / 模式 / 间隔。 */
    fun health(nowMs: Long = System.currentTimeMillis()): LocationStatus =
        healthState.snapshot(nowMs, mode, samplingIntervalMs(), channel.snapshot(nowMs))

    // ===== 持续订阅通道 =====

    /**
     * 建立（或按当前模式调速）持续订阅。幂等：重复调用只在配置变化时重建。
     *
     * 服务 [com.bsfeng.monitor.service.MonitorAccessibilityService.onServiceConnected] 调它，
     * [onSample] 是订阅回调的出口（落库）。失败（未授权 / 无 provider）返回 false，
     * 此时调用方与心跳仍走缓存 / 主动定位兜底。
     */
    fun startSubscription(
        nowMs: Long = System.currentTimeMillis(),
        onSample: (LocationSample) -> Unit,
    ): Boolean {
        val ok = channel.start(nowMs, onSample)
        env.log("位置订阅状态：" + health(nowMs).summary(), false)
        return ok
    }

    /** 释放订阅。幂等：未建立时是空操作；服务 [onDestroy] 必须调用（否则 listener 跟着进程泄漏）。 */
    fun stopSubscription() = channel.stop()

    /** 订阅是否活跃（设置页自检 / 日志用）。 */
    fun isSubscriptionActive(): Boolean = channel.isActive()

    /**
     * 订阅回调入口：质量闸 → 节流 → 交付。
     *
     * 订阅的间隔本身已经控频，这里再过一道节流是为了「订阅 + 心跳 + 定时器」三条链路共用
     * 同一个落库窗口，避免同一时刻重复落点。
     */
    private fun onSubscriptionSample(sample: LocationSample, consumer: (LocationSample) -> Unit) {
        val nowMs = clock()
        if (!LocationPolicy.isFresh(sample.timeMs, nowMs)) {
            noteFailure(LocationFailureReason.ACTIVE_FIX_TIMEOUT, "订阅点位 stale provider=" + sample.provider)
            return
        }
        if (!LocationQuality.shouldAccept(sample, healthState.lastAccepted, nowMs)) {
            noteFailure(LocationFailureReason.LOW_CONFIDENCE, "订阅点位精度不足 provider=" + sample.provider)
            return
        }
        // 门禁位移证据：先 offer 再判落库窗口 / 50m 抖动 —— 那些过滤只影响落库，
        // 不能让门禁看不到点（PROBING 的坐车升级就靠订阅点与探测点的位移）。
        LocationGateState.offer(sample)
        // 探测点（静止 30s 一个）先喂模式状态机，再判落库窗口：模式判定要的是密信号，
        // 落库仍按 180s 省电。只判落库窗口的话，用户走起来要等最多 3 分钟才切到移动。
        observeForMode(sample)
        if (!throttle.canAcquire(nowMs, captureIntervalMs())) {
            env.log("定位探测点仅更新模式（未到落库窗口 provider=" + sample.provider + "）", false)
            return
        }
        // 静止时 50 米内的位移忽略：点抖动不该产生新记录（用户口径）
        if (shouldIgnoreStillJitter(sample)) {
            env.log("静止点位位移 < " + LocationQuality.STILL_IGNORE_RADIUS_METERS + "m，忽略", false)
            return
        }
        accept(sample, nowMs)
        consumer(sample)
    }

    // ===== 定时/心跳驱动的按需采集 =====

    /**
     * 按模式间隔采集（异步）：重订阅 → 缓存 → 主动定位。
     *
     * @param force 门禁探测专用：跳过落库节流，**允许用 ≤[LocationPolicy.PROBE_CACHE_MAX_AGE_MS] 的系统缓存点**
     *   顶替主动定位（超过 90 秒才主动定位，见 [LocationProbePolicy.canUseCached]）。
     *   PROBING 的探测如果被采集节流挡住（模拟器实测「定位节流中：STILL 间隔 180s 内已采过」），
     *   门禁就永远拿不到位移证据，5 分钟无证据直接回 SLEEPING —— 坐车位移 675m 也升级不了。
     * @param budget 主动定位预算档位：门禁探测 / SLEEPING 兜底传 [FixBudget.PROBE]（覆盖级 4s+8s），
     *   MOVING 静默补采 / 断档自愈 / 首点兜底保持默认 [FixBudget.FULL]（满预算 5s+15s）。
     * @param onResult **恰好回调一次**（缓存命中 / 被节流 / 未授权时同步，需要主动定位时异步）
     */
    fun collectThrottledAsync(
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false,
        budget: FixBudget = FixBudget.FULL,
        onResult: (LocationSample?) -> Unit,
    ) {
        val modeAtStart = mode
        val plan = LocationGapHealing.plan(healthState.lastSuccessAtMs, nowMs, modeAtStart)
        if (plan.resetBaseline) resetBaseline()
        logGap(plan, nowMs)
        // A3：已有主动定位在飞就让路。两次定位叠在一起会互抢 provider（实测日志相差 2 秒的
        // 两条请求），既耗电又把双方都拖到超时；本次不占节流窗口，下一个心跳/ tick 再来。
        if (activeFixGate.isInFlight) {
            noteFailure(LocationFailureReason.THROTTLED, "已有主动定位在飞，本次让路")
            env.log("定位采集让路：已有主动定位在飞", false)
            onResult(null)
            return
        }
        val interval = captureIntervalMs()
        // 首点兜底：订阅建立了但**一个点都没投递**超过静默阈值 —— 这时候节流窗口是没有意义的
        // 概念（从来没成功过），必须绕过它发起一次主动定位，否则「订阅建立 → 有第一个点」
        // 会无限等下去。这正是用户说的「过一会就掉线」的正面修复。
        val initialFix = channel.needsInitialFix(nowMs)
        if (initialFix) {
            env.log("位置订阅零投递，发起首点兜底主动定位（" + modeAtStart + "）", true)
        }
        // 断档自愈用满预算，但 SLEEPING 的兜底例外：断档自愈是「现在真的拿不到点」，
        // 砍预算只会把断档拉得更长；而 SLEEPING 的 30 分钟兜底**本身就是设计好的静默**，
        // 距上次成功必然 ≥30 分钟 —— 对它升级满预算等于把「常态低价值探测」全部变成
        // 每 30 分钟一次 network 5s + gps 15s（室内两个 provider 都超时就是一天白烧 16 分钟射频）。
        val effectiveBudget = LocationProbePolicy.effectiveBudget(budget, plan.isGap, LocationGateState.state)
        if (force) {
            env.log(
                "门禁探测：跳过采集节流，预算档=" + effectiveBudget +
                    "（" + LocationPolicy.budgetSummaryOf(effectiveBudget) + "，" + modeAtStart + "）",
                false,
            )
        }
        // 断档时跳过节流：断档本身就是「窗口早过了却没拿到点」，再等一个窗口只会更晚恢复。
        // force（门禁探测）永远放行：拿不到「现在在哪」，门禁的位移证据链就断了。
        val throttleAllows = throttle.canAcquire(nowMs, interval)
        if (!LocationProbePolicy.shouldRequestActiveFix(force, plan.forceActiveFix, initialFix, throttleAllows)) {
            noteFailure(LocationFailureReason.THROTTLED, "节流中 " + (interval / 1000) + "s")
            env.log("定位节流中：" + modeAtStart.name + " 间隔 " + (interval / 1000) + "s 内已采过", false)
            onResult(null)
            return
        }
        if (!hasLocationPermission()) {
            noteFailure(LocationFailureReason.PERMISSION, "未授权")
            env.log("定位跳过：未授权", true)
            onResult(null)
            return
        }
        // 订阅重建必须过门禁闸：SLEEPING / PROBING 都不该有订阅。DEEP 断档（≥30 分钟）原来会在
        // SLEEPING 下重建订阅，而 SLEEPING 不再有状态迁移去关它 —— 定位芯片就此常亮。
        // 门禁未知（null，服务没跑）放行：那时心跳是唯一采点路径。
        if (!subscriptionAllowed()) {
            env.log(
                "位置订阅不重建：门禁 " + LocationGateState.state + "（只有 MOVING 允许订阅）",
                false,
            )
        } else {
            if (plan.restartSubscription) {
                // 深断档重建订阅。这里**不再**清发令闸：DEEP 期间心跳每分钟都判断档，
                // 一重置就变成每分钟一次满预算主动定位（实测约 40 次/小时白跑）；
                // 最小间隔由闸按模式兜住（静止 180s / 移动 30s）。
                channel.restart(nowMs)
            }
            // 订阅「活着但零投递」也要重建：fused 在部分设备/模拟器上从不派点给订阅者，
            // 状态一直显示「活跃」，只有投递计数能拆穿。旧实现根本没调过 needsRestart。
            channel.restartIfSilent(nowMs)
        }
        val cached = LocationPolicy.pick(env.readCached(), nowMs)
        val cachedFresh = cached != null && nowMs - cached.timeMs <= LocationPace.CACHE_FRESH_MS
        // 门禁探测的缓存窗口（90 秒）比移动模式的 30 秒宽：探测周期本身是 180 秒起，
        // 拿一个 89 秒前别人定位到的点，比再烧 12 秒射频去测「现在在哪」更划算（见基线文档）。
        val cachedAgeMs = cached?.let { nowMs - it.timeMs }
        // 静止：缓存只要没过期就用；移动：必须够新鲜，否则主动定位一次（30 秒节奏不能被旧点糊弄）。
        // 断档时**跳过缓存**：断档就是「缓存里那些点已经不能代表现在」，拿旧缓存顶上只会把
        // 掉线的表象维持下去（用户看到的最后一条记录仍停在断档之前）。
        // 首点兜底：优先主动定位（订阅零投递的实测形态正是「系统缓存有 passive 点能 pick 到，
        // 但订阅一个点都不派」——只用缓存会让状态看起来正常却不解决订阅不工作）。
        // 主动定位也拿不到点时仍回落缓存：能用的点不要因为兜底路径而丢掉。
        if (initialFix) {
            requestInitialFix(nowMs, modeAtStart, plan, cached, onResult)
            return
        }
        if (LocationProbePolicy.canUseCached(
                force = force,
                hasCached = cached != null,
                isGap = plan.isGap,
                modeAtStart = modeAtStart,
                cachedFresh = cachedFresh,
                cachedAgeMs = cachedAgeMs,
            )
        ) {
            val usable = cached!!
            if (LocationQuality.shouldAccept(usable, healthState.lastAccepted, nowMs)) {
                env.log(
                    "定位命中缓存 provider=" + usable.provider + " accuracy=" + usable.accuracyMeters +
                        " age=" + (nowMs - usable.timeMs) + "ms 探测=" + force,
                    false,
                )
                // 探测用新鲜缓存顶替主动定位时，这个点**就是**本轮探测结果：同样要进门禁证据链，
                // 否则「缓存优先」等于把探测作废（门禁拿不到位移证据，误降级回 SLEEPING）。
                // 非探测路径仍不喂：静止兜底的缓存点最老 30 分钟，拿来算位移是错的方向。
                if (force) LocationGateState.offer(usable)
                deliver(usable, nowMs, modeAtStart, onResult)
                return
            }
            env.log("定位缓存点被质量闸拒绝 provider=" + usable.provider + " accuracy=" + usable.accuracyMeters, false)
        }
        requestActiveFixOnce(nowMs, modeAtStart, plan, effectiveBudget, onResult)
    }

    /** 断档自愈的日志出口（抽出来只为把 collectThrottledAsync 压进 70 行）。 */
    private fun logGap(plan: LocationGapHealing.Plan, nowMs: Long) {
        if (!plan.isGap) return
        val sinceMs = (nowMs - (healthState.lastSuccessAtMs ?: nowMs)) / 1000
        env.log(
            "定位断档自愈：level=" + plan.level + " 距上次成功=" + sinceMs +
                "s 强制主动定位=" + plan.forceActiveFix + " 重建订阅=" + plan.restartSubscription,
            true,
        )
    }

    /** 发起一次主动定位（缓存不可用时的最后一级兜底）；编排与结果分派见 [LocationActiveFixRunner]。 */
    private fun requestActiveFixOnce(
        nowMs: Long,
        modeAtStart: SampleMode,
        plan: LocationGapHealing.Plan,
        budget: FixBudget,
        onResult: (LocationSample?) -> Unit,
    ) = activeFixRunner.run(
        nowMs = nowMs,
        plan = plan,
        modeAtStart = modeAtStart,
        budget = budget,
        previous = healthState.lastAccepted,
        onDeliver = { sample ->
            // 主动定位点无条件作为门禁位移证据：50 米抖动过滤 / 落库节流只影响落库，不能影响证据。
            // 缓存点不在这里喂（旧点不是「现在在哪」）；订阅路径的点在 onSubscriptionSample 里 offer。
            LocationGateState.offer(sample)
            deliver(sample, nowMs, modeAtStart, onResult, forceEmit = plan.forceActiveFix)
        },
        onSample = onResult,
    )

    /**
     * 交付一个点：更新模式（状态机）→ 占节流窗口 → 回调。
     *
     * 顺序有意义：模式要在下一次「该不该采」判断前更新，否则移动的第一个点之后还会按静止间隔等。
     */
    private fun deliver(
        sample: LocationSample,
        nowMs: Long,
        modeAtStart: SampleMode,
        onResult: (LocationSample?) -> Unit,
        /**
         * 是否跳过去抖强制落库：断档补采 / 首点兜底的语义是「证明链路活着」，
         * 人没动也必须留下一个点，否则状态看起来还是掉线的。
         */
        forceEmit: Boolean = false,
    ) {
        // 静止时 50 米内的位移忽略：心跳 / 缓存路径同样适用
        if (!forceEmit && modeAtStart == SampleMode.STILL && shouldIgnoreStillJitter(sample)) {
            env.log("静止点位位移 < " + LocationQuality.STILL_IGNORE_RADIUS_METERS + "m，忽略", false)
            onResult(null)
            return
        }
        val updated = accept(sample, nowMs)
        if (updated != modeAtStart) {
            env.log(
                "定位采样模式切换：" + modeAtStart.name + " → " + updated.name +
                    "（间隔 " + (LocationPace.intervalOf(updated) / 1000) + "s）",
                false,
            )
        }
        env.log(
            "定位采样成功 provider=" + sample.provider + " accuracy=" + sample.accuracyMeters +
                " 模式=" + updated + " 间隔=" + (LocationPace.intervalOf(updated) / 1000) + "s",
            false,
        )
        onResult(sample)
    }

    /**
     * 只喂模式状态机、不落库、不占落库窗口（订阅探测点专用）。
     *
     * 注意**不**更新 [LocationHealthState.lastAccepted]：那个点是质量闸的对照基准，
     * 被探测点顶掉会让「低置信点不许覆盖更优位置」失效。
     */
    private fun observeForMode(sample: LocationSample) {
        val before = mode
        val after = synchronized(lock) { modeTracker.onSample(sample) }
        if (after != before) {
            env.log("定位采样模式切换：" + before.name + " → " + after.name + "（探测点触发，间隔 " + (LocationPace.intervalOf(after) / 1000) + "s）", false)
        }
    }

    /**
     * 静止模式下这个点是否只是抖动（与上一次接受点相距 < 50 米）。
     *
     * 静止时定位点有几十米误差，人没动也会漂；不忽略的话轨迹会被这些点填满，
     * 覆盖条与「停留」判定都会被噪声支配。
     */
    private fun shouldIgnoreStillJitter(sample: LocationSample): Boolean {
        if (mode != SampleMode.STILL) return false
        val previous = healthState.lastAccepted ?: return false
        return LocationQuality.withinStillIgnoreRadius(previous, sample)
    }

    /** 记录一次被接受的成功采样：模式状态机、节流窗口、成功时间三处同步。 */
    private fun accept(sample: LocationSample, nowMs: Long): SampleMode {
        healthState.onAccepted(sample, nowMs)
        return synchronized(lock) {
            throttle.markAcquired(nowMs)
            modeTracker.onSample(sample)
        }
    }

    /** 记录一次失败（只为可观测，不影响任何控制流）。 */
    private fun noteFailure(reason: LocationFailureReason, detail: String) =
        healthState.onFailure(reason, detail)

    /** 断档后清空基线：节流窗口、模式状态机、对照点都没意义了（旧点 30 分钟后本来就 stale）。 */
    private fun resetBaseline() = synchronized(lock) {
        healthState.resetAccepted()
        throttle.reset()
        modeTracker.reset()
    }

    /**
     * 首点兜底：主动定位一次，失败再回落缓存。
     *
     * 退避复用发令闸的「首次成功前」口径（固定 30s）：零投递会持续成立（deliveryCount 恒为 0），
     * 没这道限制每个 tick 都会发起一次。语义也一致 —— 都是「还没拿到有效点」。
     */
    private fun requestInitialFix(
        nowMs: Long,
        modeAtStart: SampleMode,
        plan: LocationGapHealing.Plan,
        cached: LocationSample?,
        onResult: (LocationSample?) -> Unit,
    ) {
        // 首点兜底是「订阅零投递也必须拿到第一个点」的路径，预算一律满档（砍预算会重演掉线）
        requestActiveFixOnce(
            nowMs = nowMs,
            modeAtStart = modeAtStart,
            plan = plan.copy(level = LocationGapHealing.Level.INITIAL),
            budget = FixBudget.FULL,
        ) { sample ->
            when {
                sample != null -> onResult(sample)
                fallbackToCache(cached, nowMs) -> {
                    env.log("首点兜底主动定位失败，回落缓存（订阅仍零投递）", true)
                    deliver(cached!!, nowMs, modeAtStart, onResult, forceEmit = true)
                }
                else -> onResult(null)
            }
        }
    }

    /** 首点兜底的缓存回落：主动定位也失败时能用的点不要丢（仍须过质量闸）。 */
    private fun fallbackToCache(cached: LocationSample?, nowMs: Long): Boolean =
        cached != null && LocationQuality.shouldAccept(cached, healthState.lastAccepted, nowMs)

    /** 当前定位权限四态，供设置页自检。 */
    fun status(): LocationPermissionStatus = LocationPermissionRules.resolveStatus(
        fineGranted = env.isFineGranted(),
        coarseGranted = env.isCoarseGranted(),
        backgroundGranted = env.isBackgroundGranted(),
    )

    companion object {
        private const val TAG = "LocationCollector"

        @Volatile
        private var instance: LocationCollector? = null

        /**
         * 进程内单例。
         *
         * 节流状态（上次落库时间）、采样模式与订阅必须跨心跳 / 定时器 / 服务重建保留：
         * 每次 new 一个采集器会让节流失效（每次调用都落一条坐标），模式也会一直停在静止，
         * 订阅还会重复注册。懒加载 + 双重校验，与仓库其它单例一致。
         */
        fun get(context: Context): LocationCollector = instance ?: synchronized(this) {
            instance ?: LocationCollector(context.applicationContext).also { instance = it }
        }

        /** 权限四态判定（委托到 [LocationPermissionRules]，纯函数，设置页与单测共用）。 */
        fun resolveStatus(
            fineGranted: Boolean,
            coarseGranted: Boolean,
            backgroundGranted: Boolean,
        ): LocationPermissionStatus =
            LocationPermissionRules.resolveStatus(fineGranted, coarseGranted, backgroundGranted)
    }
}

/**
 * 探测请求的纯判定（抽出来只为可单测）。
 *
 * [shouldRequestActiveFix] 是 collectThrottledAsync 的节流闸：force 路径永远放行 ——
 * 门禁探测必须真的拿到「现在在哪」，否则 PROBING 的位移证据链会断（模拟器实测坐车不升级）。
 *
 * [canUseCached] 决定是否直接用系统缓存顶替主动定位：force（门禁探测）只认
 * ≤[LocationPolicy.PROBE_CACHE_MAX_AGE_MS] 的系统缓存点 —— 90 秒内的点与刚探测出来的点
 * 在位移判定上等价，拿它顶替一次满射频探测是纯省电；更旧的点算不出「现在在哪」。
 */
internal object LocationProbePolicy {

    /**
     * 本次主动定位真正使用的预算档位。
     *
     * 断档（[isGap]）默认升级到 [FixBudget.FULL]：断档就是「现在真的拿不到点」，这时候砍预算
     * 只会把断档拉得更长（与首点兜底同理）。**唯一例外是门禁 SLEEPING**：它的 30 分钟兜底
     * 距上次成功必然 ≥30 分钟，每次都会被判成断档 —— 那不是掉线，是省电设计本来的静默，
     * 升级满预算只会让「什么都没发生」的半小时也烧 20 秒射频（真机实测：改前每 75~94 秒一次
     * 满预算定位，11 小时只落 8 个点）。
     *
     * [gateState] 为 null（前台服务没跑、门禁未知）时仍升级：那时心跳是唯一采点路径，砍预算
     * 等于制造漏采。
     */
    fun effectiveBudget(budget: FixBudget, isGap: Boolean, gateState: MotionGate.State?): FixBudget =
        if (isGap && gateState != MotionGate.State.SLEEPING) FixBudget.FULL else budget

    fun shouldRequestActiveFix(
        force: Boolean,
        planForceActiveFix: Boolean,
        initialFix: Boolean,
        throttleAllows: Boolean,
    ): Boolean = force || planForceActiveFix || initialFix || throttleAllows

    /**
     * 是否用系统缓存顶替主动定位。
     *
     * - 无缓存 / 断档 → 不用（断档语义就是「缓存里的点不能代表现在」）；
     * - force（门禁探测）→ 只认 [LocationPolicy.PROBE_CACHE_MAX_AGE_MS] 以内的点，更旧就主动定位；
     * - 其余 → 静止模式一律可用，移动模式要求 [LocationPace.CACHE_FRESH_MS] 以内的新鲜点。
     *
     * 时钟回拨（age 为负）按「刚采到」处理，与 [LocationPolicy.isFresh] 同口径。
     */
    fun canUseCached(
        force: Boolean,
        hasCached: Boolean,
        isGap: Boolean,
        modeAtStart: SampleMode,
        cachedFresh: Boolean,
        cachedAgeMs: Long? = null,
        forceCacheMaxAgeMs: Long = LocationPolicy.PROBE_CACHE_MAX_AGE_MS,
    ): Boolean {
        if (!hasCached || isGap) return false
        if (force) return cachedAgeMs != null && cachedAgeMs <= forceCacheMaxAgeMs
        return modeAtStart == SampleMode.STILL || cachedFresh
    }

    /**
     * 心跳链路的预算档位：门禁 PROBING 用覆盖级，MOVING 与「门禁未知」保持满预算。
     *
     * 为什么心跳也要分档：心跳与前台服务的探测共用同一批 provider，谁先抢到发令闸谁发起。
     * 只在服务里改档的话，落在 PROBING 的心跳仍会发起一轮 5s+15s 的满预算请求，
     * 「单次探测射频上限 20s→12s」就会被这条后门打破。
     * 门禁未知（null，服务没跑）必须满预算：那时心跳是唯一采点路径，砍预算等于制造掉线。
     */
    fun heartbeatBudget(state: MotionGate.State?): FixBudget =
        if (state == null || state == MotionGate.State.MOVING) FixBudget.FULL else FixBudget.PROBE
}
