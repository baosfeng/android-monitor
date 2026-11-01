package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集器编排层的三个修复：A1 预算（日志口径）、A2 INITIAL 退避、A3 并发让路，
 * 以及 A4 的「探测点只喂模式、不落库」。
 *
 * 全部纯 JVM：Env 与 LocationSubscriptionSource 都可注入。
 */
class LocationCollectorFixTest {

    private val now = 1_700_000_000_000L

    private fun sample(
        provider: String = "gps",
        accuracy: Float = 8f,
        ageMs: Long = 0L,
        timeMs: Long = now,
        meters: Double = 0.0,
    ) = LocationSample(provider, 31.2304 + meters / 111_000.0, 121.4737, accuracy, timeMs - ageMs)

    private class FakeSource : LocationSubscriptionSource {
        var providers: List<String> = emptyList()
        var starts = 0
        var stops = 0
        var intervalMs = 0L
        var lastConsumer: ((LocationSample) -> Unit)? = null
        private var active = false

        override fun start(
            providers: List<String>,
            intervalMs: Long,
            minDistanceMeters: Float,
            onSample: (LocationSample) -> Unit,
        ): Boolean {
            starts++
            this.providers = providers
            this.intervalMs = intervalMs
            lastConsumer = onSample
            active = true
            return true
        }

        override fun stop() {
            stops++
            active = false
            lastConsumer = null
        }

        override fun isActive(): Boolean = active
    }

    private class FakeEnv(
        var fine: Boolean = true,
        var cached: List<LocationSample> = emptyList(),
        var providers: List<String> = listOf("gps", "network"),
        var powerSave: Boolean = false,
        val source: FakeSource? = FakeSource(),
        var onActive: (Long, (LocationSample?) -> Unit) -> Unit = { _, cb -> cb(null) },
    ) : LocationCollector.Env {
        var activeRequests = 0
        val logs = mutableListOf<String>()

        override fun isFineGranted(): Boolean = fine
        override fun isBackgroundGranted(): Boolean = fine
        override fun readCached(): List<LocationSample> = cached
        override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) {
            activeRequests++
            onActive(nowMs, onResult)
        }
        override fun log(message: String, warn: Boolean) { logs += message }
        override fun subscriptionSource(): LocationSubscriptionSource? = source
        override fun availableProviders(): List<String> = providers
        override fun isPowerSaveMode(): Boolean = powerSave
    }

    // ===== task-6：订阅零投递兜底 =====

    @Test
    fun `订阅建立后 providers 是并集 不只 fused`() {
        // 实测：fused 被列为可用但 FLP 从不派点。只用 fused 就是永久静默。
        val source = FakeSource()
        val env = FakeEnv(source = source, providers = listOf("fused", "gps", "network"))
        val collector = LocationCollector(env, clock = { now })
        collector.startSubscription(now) { }
        assertEquals(listOf("fused", "network", "gps"), source.providers)
    }

    @Test
    fun `订阅零投递超过静默阈值 会发起首点兜底主动定位`() {
        // 订阅建立了但一次都不回调（fused 的发病形态），必须靠主动定位把第一个点拿到
        val source = FakeSource()
        val fixed = sample(provider = "gps", accuracy = 5f)
        val env = FakeEnv(
            source = source,
            cached = emptyList(),
            onActive = { _, cb -> cb(fixed) },
        )
        val collector = LocationCollector(env, clock = { now })
        collector.startSubscription(now) { }

        var result: LocationSample? = null
        collector.collectThrottledAsync(now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL)) {
            result = it
        }
        assertEquals("零投递必须主动定位一次", 1, env.activeRequests)
        assertNotNull("首点必须拿到", result)
        assertTrue(
            "日志要能看出是首点兜底",
            env.logs.any { it.contains("零投递") || it.contains("首点兜底") },
        )
    }

    @Test
    fun `订阅正常投递时不做首点兜底`() {
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = emptyList())
        val collector = LocationCollector(env, clock = { now })
        collector.startSubscription(now) { }
        // 订阅正常派点（一次就够：deliveryCount > 0 后不再是「首点」问题）
        source.lastConsumer?.invoke(sample(timeMs = now))
        collector.collectThrottledAsync(now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL)) { }
        assertEquals("订阅有投递就不该抢着主动定位", 0, env.activeRequests)
    }

    @Test
    fun `首点兜底不占用节流窗口 但受30秒退避约束`() {
        // 零投递会持续成立（deliveryCount 恒为 0），没有退避的话每个 tick 都会发起一次
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = emptyList(), onActive = { _, cb -> cb(null) })
        val collector = LocationCollector(env, clock = { now })
        collector.startSubscription(now) { }
        val due = now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL)

        collector.collectThrottledAsync(due) { }
        collector.collectThrottledAsync(due + 10_000L) { }
        assertEquals("10 秒内第二次必须退避", 1, env.activeRequests)
        collector.collectThrottledAsync(due + 30_000L) { }
        assertEquals("满 30 秒才再发起", 2, env.activeRequests)
    }

    @Test
    fun `首点兜底主动定位失败 回落缓存`() {
        val source = FakeSource()
        val cached = sample(provider = "network", accuracy = 300f)
        val env = FakeEnv(source = source, cached = listOf(cached), onActive = { _, cb -> cb(null) })
        val collector = LocationCollector(env, clock = { now })
        collector.startSubscription(now) { }

        var result: LocationSample? = null
        collector.collectThrottledAsync(now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL)) {
            result = it
        }
        assertEquals("主动定位失败后能用的缓存点不许丢", "network", result?.provider)
    }

    @Test
    fun `订阅静默时真的触发重建并打出可观测日志`() {
        // 旧实现：needsRestart 在生产代码里一次都没被调用 —— 静默重建形同虚设
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = emptyList())
        val collector = LocationCollector(env, clock = { now })
        collector.startSubscription(now) { }
        val startsBefore = source.starts

        collector.collectThrottledAsync(now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL) + 1_000L) { }
        assertTrue("静默必须真的重建订阅", source.starts > startsBefore)
        assertTrue(
            "日志要能看出是「订阅静默重建」",
            env.logs.any { it.contains("订阅静默重建") },
        )
    }

    // ===== A3 并发让路 =====

    @Test
    fun `第一次主动定位在飞时 第二次直接让路不发起`() {
        // 主动定位永远不回调，模拟「还卡在 GPS 冷启动里」
        val env = FakeEnv(cached = emptyList(), onActive = { _, _ -> })
        val collector = LocationCollector(env)

        var first: LocationSample? = null
        var second: LocationSample? = null
        collector.collectThrottledAsync(now) { first = it }
        collector.collectThrottledAsync(now + 1_000L) { second = it }

        assertEquals("第二次必须让路，不能同时发起两套定位", 1, env.activeRequests)
        assertNull(first)
        assertNull(second)
        assertEquals(LocationFailureReason.THROTTLED, collector.health(now).lastFailure)
    }

    @Test
    fun `前一次主动定位结束后可以再次发起`() {
        var pending: ((LocationSample?) -> Unit)? = null
        val env = FakeEnv(cached = emptyList(), onActive = { _, cb -> pending = cb })
        val collector = LocationCollector(env)

        collector.collectThrottledAsync(now) { }
        pending?.invoke(null)
        // 已结束：后续 tick 仍可发起（失败不占窗口，见节流用例）
        collector.collectThrottledAsync(now + 60_000L) { }
        assertEquals(2, env.activeRequests)
    }

    @Test
    fun `主动定位在飞时 心跳仍然恰好回调一次`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, _ -> })
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        var callbacks = 0
        collector.collectThrottledAsync(now + 1_000L) { callbacks++ }
        assertEquals("让路也必须回调，否则调用方永远不 finish", 1, callbacks)
    }

    // ===== A2 首次成功前的固定退避 =====

    @Test
    fun `首次成功前每30秒才发起一次 不再每10秒死循环`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, cb -> cb(null) })
        val collector = LocationCollector(env)

        // 模拟 10 秒 tick 连打 6 次（共 50 秒）：旧实现每次都 forceActiveFix，会发起 6 次
        var tick = now
        repeat(6) {
            collector.collectThrottledAsync(tick) { }
            tick += 10_000L
        }
        assertEquals("50 秒内最多 2 次（0s 与 30s）", 2, env.activeRequests)
    }

    @Test
    fun `首次成功后不再受退避约束`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, cb -> cb(sample()) })
        // 移动模式的节流窗口现在是 30 秒（与退避同值）：这里验证「成功后不受退避约束」
        val collector = LocationCollector(env, modeTracker = LocationModeTracker(SampleMode.MOVING))
        collector.collectThrottledAsync(now) { }
        assertEquals(1, env.activeRequests)
        assertNotNull(collector.health(now).lastSuccessAtMs)
        // 已成功：满一个移动节流窗口就能再采
        collector.collectThrottledAsync(now + LocationPace.MOVING_INTERVAL_MS) { }
        assertEquals("成功后按移动窗口 30 秒再采", 2, env.activeRequests)
    }

    @Test
    fun `连续失败也不会把重试推远 固定30秒`() {
        // 防饥饿：GPS 冷启动前几次超时是常态，指数退避会让首次出点越推越远
        val env = FakeEnv(cached = emptyList(), onActive = { _, cb -> cb(null) })
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        collector.collectThrottledAsync(now + 30_000L) { }
        collector.collectThrottledAsync(now + 60_000L) { }
        assertEquals("每满 30 秒都要真的发起", 3, env.activeRequests)
        collector.collectThrottledAsync(now + 89_000L) { }
        assertEquals("29 秒还差 1 秒，必须仍在退避", 3, env.activeRequests)
    }

    // ===== A1 日志与预算口径 =====

    @Test
    fun `发起日志带上各 provider 的独立预算`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, cb -> cb(null) })
        LocationCollector(env).collectThrottledAsync(now) { }
        val line = env.logs.firstOrNull { it.contains("发起单次主动定位") }
        assertNotNull(line)
        assertTrue("GPS 预算要写进日志，便于对照 timeout 证据", line!!.contains("gps 15000ms"))
        assertTrue(line.contains("network 5000ms"))
    }

    // ===== A4 探测点只喂模式 =====

    @Test
    fun `静止订阅按30秒派点 而不是180秒`() {
        val source = FakeSource()
        val collector = LocationCollector(FakeEnv(source = source), clock = { now })
        collector.startSubscription(now) { }
        assertEquals(30_000L, source.intervalMs)
    }

    @Test
    fun `省电模式下静止订阅回退到180秒`() {
        val source = FakeSource()
        val collector = LocationCollector(FakeEnv(source = source, powerSave = true), clock = { now })
        collector.startSubscription(now) { }
        assertEquals(180_000L, source.intervalMs)
    }

    @Test
    fun `探测点能切到移动 但没到落库窗口就不落库`() {
        // 静止落库间隔 180s，探测间隔 30s：走 42 米（步行 30 秒）应立刻切 MOVING，但不落库
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = emptyList())
        val collector = LocationCollector(env, clock = { now })
        val delivered = mutableListOf<LocationSample>()
        collector.startSubscription(now) { delivered += it }

        source.lastConsumer?.invoke(sample(timeMs = now))
        assertEquals("第一个点占掉落库窗口", 1, delivered.size)

        source.lastConsumer?.invoke(sample(timeMs = now + 30_000L, meters = 42.0))
        assertEquals("30 秒后切到移动：探测信号够密", SampleMode.MOVING, collector.mode)
        assertEquals("没到落库窗口，不重复落库", 1, delivered.size)
    }

    @Test
    fun `passive 高精度点走完整链路后能切到移动`() {
        // lead 的实测复现路径：订阅几乎总是先给 passive 点（系统缓存），若 passive 判 LOW，
        // 模式就永远 STILL、落库被 150m 位移门槛卡住 —— 这正是「地图不准」。
        // 这条从订阅回调一路走到模式状态机，覆盖「精度分级 → 速度判定」整条链。
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = emptyList())
        var clockNow = now
        val collector = LocationCollector(env, clock = { clockNow })
        val delivered = mutableListOf<LocationSample>()
        collector.startSubscription(now) { delivered += it }

        source.lastConsumer?.invoke(sample(provider = "passive", accuracy = 5f, timeMs = now))
        assertEquals(SampleMode.STILL, collector.mode)

        clockNow = now + 30_000L
        // 5 秒 40m 是步行以上的速度；这里用 30 秒 40m = 1.3m/s 也过 0.45m/s 门槛
        source.lastConsumer?.invoke(sample(provider = "passive", accuracy = 5f, timeMs = clockNow, meters = 40.0))
        assertEquals("passive 高精度点必须能驱动模式切换", SampleMode.MOVING, collector.mode)
        assertEquals("切到 MOVING 后间隔变 10s", LocationPace.MOVING_INTERVAL_MS, collector.samplingIntervalMs())
    }

    @Test
    fun `探测点不更新质量闸的对照点`() {
        // 探测点若顶掉 lastAccepted，会让「低置信点不许覆盖更优位置」失效（室内粗点刷轨迹）
        val source = FakeSource()
        val env = FakeEnv(source = source)
        val collector = LocationCollector(env, clock = { now })
        val delivered = mutableListOf<LocationSample>()
        collector.startSubscription(now) { delivered += it }
        source.lastConsumer?.invoke(sample(provider = "gps", accuracy = 5f, timeMs = now))
        // 30 秒后来一个几百米的 network 探测点：只喂模式，不许顶掉 GPS 对照点
        source.lastConsumer?.invoke(sample(provider = "network", accuracy = 400f, timeMs = now + 30_000L))
        // 落库窗口到点后，低置信点仍必须被质量闸拦住
        source.lastConsumer?.invoke(sample(provider = "network", accuracy = 400f, timeMs = now + 180_000L))
        assertEquals("只落库了那一个高置信 GPS 点", 1, delivered.size)
        assertEquals("gps", delivered.first().provider)
    }

    @Test
    fun `移动模式的落库窗口仍是10秒`() {
        val source = FakeSource()
        val env = FakeEnv(source = source)
        var clockNow = now
        val collector = LocationCollector(
            env,
            clock = { clockNow },
            modeTracker = LocationModeTracker(SampleMode.MOVING),
        )
        val delivered = mutableListOf<LocationSample>()
        collector.startSubscription(now) { delivered += it }
        assertEquals(30_000L, source.intervalMs)
        source.lastConsumer?.invoke(sample(timeMs = now))
        clockNow = now + LocationPace.MOVING_INTERVAL_MS
        source.lastConsumer?.invoke(sample(timeMs = clockNow))
        assertEquals("移动模式 30 秒一个落库点", 2, delivered.size)
    }

    @Test
    fun `深断档会清掉退避基线 立刻允许补采`() {
        var attempts = 0
        val env = FakeEnv(cached = emptyList(), onActive = { _, cb ->
            attempts++
            cb(null)
        })
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        assertEquals(1, attempts)
        // 退避窗口内不让路；45 分钟后是深断档，必须清基线后立刻补一次
        collector.collectThrottledAsync(now + 45 * 60_000L) { }
        assertEquals("深断档重建订阅后要真的补一次", 2, attempts)
    }

    @Test
    fun `未授权时不占用发令闸`() {
        val env = FakeEnv(fine = false)
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        assertEquals(0, env.activeRequests)
        // 授权恢复后应能立刻发起（闸没被白白占掉）
        env.fine = true
        env.cached = emptyList()
        collector.collectThrottledAsync(now + 1_000L) { }
        assertEquals(1, env.activeRequests)
    }

    @Test
    fun `缓存命中不占用发令闸`() {
        val cached = sample(timeMs = now)
        val env = FakeEnv(cached = listOf(cached))
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        assertEquals(0, env.activeRequests)
        assertFalse(collector.health(now).subscriptionActive)
    }

    // ===== 门禁探测：force 绕过采集节流 =====

    @Test
    fun `force 探测在采集节流窗口内仍发起主动定位 且点位进入门禁证据`() {
        // 缓存是 2 分钟前的（超过 90 秒探测窗口）：force 必须自己发起主动定位
        val cached = sample(ageMs = 120_000L)
        val fixed = sample(timeMs = now + 2_000L, meters = 100.0)
        val env = FakeEnv(cached = listOf(cached), onActive = { _, cb -> cb(fixed) })
        val collector = LocationCollector(env, clock = { now })

        // 第一次：缓存命中，占掉落库节流窗口；第二次：节流窗口内且非 force → 不发起主动定位
        collector.collectThrottledAsync(now) { }
        collector.collectThrottledAsync(now + 1_000L) { }
        assertEquals("非 force 在节流窗口内不应发起主动定位", 0, env.activeRequests)

        val offered = mutableListOf<LocationSample>()
        LocationGateState.registerLocationListener { offered += it }
        try {
            collector.collectThrottledAsync(now + 2_000L, force = true) { }
        } finally {
            LocationGateState.registerLocationListener(null)
        }
        assertEquals("force 必须真的发起主动定位（不被节流挡住）", 1, env.activeRequests)
        assertTrue("force 探测点必须进入门禁证据链", offered.any { it === fixed })
    }
}
