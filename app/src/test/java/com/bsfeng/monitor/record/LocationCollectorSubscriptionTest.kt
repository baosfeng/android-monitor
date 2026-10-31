package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集器的三级取点（订阅 → 缓存 → 主动定位）与断档自愈的编排用例。
 *
 * 全部纯 JVM：Env 与 LocationSubscriptionSource 都可注入。
 */
class LocationCollectorSubscriptionTest {

    private val now = 1_700_000_000_000L

    private fun sample(
        provider: String = "gps",
        accuracy: Float = 8f,
        ageMs: Long = 0L,
        lat: Double = 31.2304,
    ) = LocationSample(provider, lat, 121.4737, accuracy, now - ageMs)

    private fun north(lat: Double, meters: Double) = lat + meters / 111_000.0

    private class FakeSource(var succeed: Boolean = true) : LocationSubscriptionSource {
        var starts = 0
        var stops = 0
        var providers: List<String> = emptyList()
        var intervalMs = 0L
        var consumer: ((LocationSample) -> Unit)? = null
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
            consumer = onSample
            active = succeed
            return succeed
        }

        override fun stop() {
            stops++
            active = false
            consumer = null
        }

        override fun isActive(): Boolean = active
    }

    private class FakeEnv(
        var fine: Boolean = true,
        var cached: List<LocationSample> = emptyList(),
        var providers: List<String> = listOf("gps", "network"),
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
    }

    // ===== 订阅通道 =====

    @Test
    fun `订阅按静止间隔建立 采样后直接落库`() {
        val source = FakeSource()
        val env = FakeEnv(source = source)
        val collector = LocationCollector(env, clock = { now })
        val received = mutableListOf<LocationSample>()

        assertTrue(collector.startSubscription(now) { received += it })
        assertEquals(1, source.starts)
        assertEquals(listOf("network", "gps"), source.providers)
        // 静止按 30 秒**探测**（喂模式状态机），落库仍按 180 秒窗口（见 LocationModeSignalTest）
        assertEquals("静止探测 30 秒", LocationPace.STILL_PROBE_INTERVAL_MS, source.intervalMs)

        source.consumer?.invoke(sample())
        assertEquals(1, received.size)
        assertTrue(collector.health(now).subscriptionActive)
    }

    @Test
    fun `重复调用 startSubscription 不叠加监听`() {
        val source = FakeSource()
        val collector = LocationCollector(FakeEnv(source = source))
        collector.startSubscription(now) { }
        collector.startSubscription(now + 1_000L) { }
        collector.startSubscription(now + 2_000L) { }
        assertEquals("幂等：只能建立一次", 1, source.starts)
    }

    @Test
    fun `stopSubscription 幂等 且释放后不再是活跃`() {
        val source = FakeSource()
        val collector = LocationCollector(FakeEnv(source = source))
        collector.startSubscription(now) { }
        collector.stopSubscription()
        collector.stopSubscription()
        assertEquals(1, source.stops)
        assertFalse(collector.isSubscriptionActive())
    }

    @Test
    fun `未授权时不建立订阅`() {
        val source = FakeSource()
        val env = FakeEnv(fine = false, source = source)
        val collector = LocationCollector(env)
        assertFalse(collector.startSubscription(now) { })
        assertEquals(0, source.starts)
    }

    @Test
    fun `没有可用provider时不建立订阅 但不抛异常`() {
        val source = FakeSource()
        val collector = LocationCollector(FakeEnv(source = source, providers = emptyList()))
        assertFalse(collector.startSubscription(now) { })
        assertEquals(0, source.starts)
    }

    @Test
    fun `模式切到移动后 订阅按30秒重建`() {
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = listOf(sample(ageMs = 60_000L)))
        val collector = LocationCollector(env, modeTracker = LocationModeTracker(SampleMode.MOVING))
        collector.startSubscription(now) { }
        assertEquals(30_000L, source.intervalMs)
    }

    // ===== 断档自愈 =====

    @Test
    fun `断档5分钟时静止模式也强制主动定位`() {
        val env = FakeEnv(
            cached = listOf(sample(ageMs = 5 * 60_000L)),
            onActive = { _, cb -> cb(sample()) },
        )
        val collector = LocationCollector(env)
        // 先成功一次，建立「上次成功时间」
        collector.collectThrottledAsync(now) { }
        assertEquals(0, env.activeRequests)

        var result: LocationSample? = null
        collector.collectThrottledAsync(now + 5 * 60_000L) { result = it }
        assertEquals("断档必须强制补一次主动定位", 1, env.activeRequests)
        assertNotNull(result)
    }

    @Test
    fun `正常节奏不额外发起主动定位`() {
        val env = FakeEnv(cached = listOf(sample()), onActive = { _, cb -> cb(sample()) })
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        collector.collectThrottledAsync(now + LocationPace.STILL_INTERVAL_MS) { }
        assertEquals("缓存命中就走缓存", 0, env.activeRequests)
    }

    @Test
    fun `深断档重建订阅并清掉节流基线`() {
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = listOf(sample()), onActive = { _, cb -> cb(sample()) })
        val collector = LocationCollector(env)
        collector.startSubscription(now) { }
        collector.collectThrottledAsync(now) { }
        val startsBefore = source.starts

        collector.collectThrottledAsync(now + 45 * 60_000L) { }
        assertTrue("深断档必须重建订阅", source.starts > startsBefore)
        assertEquals("重建前先释放", startsBefore, source.stops)
    }

    @Test
    fun `时钟回拨时不误报断档`() {
        val env = FakeEnv(cached = listOf(sample()), onActive = { _, cb -> cb(sample()) })
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        // now 倒退 10 分钟：不强制补采（否则每个前进一次都会打一次主动定位）
        collector.collectThrottledAsync(now - 10 * 60_000L) { }
        assertEquals(0, env.activeRequests)
    }

    // ===== 缓存兜底与质量闸 =====

    @Test
    fun `订阅静默后缓存兜底仍然可用`() {
        val source = FakeSource()
        val env = FakeEnv(source = source, cached = listOf(sample(provider = "network", accuracy = 300f)))
        val collector = LocationCollector(env)
        collector.startSubscription(now) { }
        // 订阅没派点：心跳走缓存 + 主动定位兜底
        var result: LocationSample? = null
        collector.collectThrottledAsync(now + 4 * 60_000L) { result = it }
        assertEquals("network", result?.provider)
    }

    @Test
    fun `主动定位拿到低置信点且已有更优位置时不落库`() {
        val gps = sample(provider = "gps", accuracy = 8f)
        val coarse = sample(provider = "network", accuracy = 80f)
        val env = FakeEnv(cached = listOf(gps), onActive = { _, cb -> cb(coarse) })
        val collector = LocationCollector(env, modeTracker = LocationModeTracker(SampleMode.MOVING))
        collector.collectThrottledAsync(now) { }
        // 移动模式 + 5 分钟旧缓存 -> 走主动定位，但主动定位只给粗点
        env.cached = listOf(gps.copy(timeMs = now - 5 * 60_000L))
        var result: LocationSample? = null
        collector.collectThrottledAsync(now + LocationPace.MOVING_INTERVAL_MS) { result = it }
        assertNull("低置信点不许覆盖更好的 GPS 位置", result)
    }

    // ===== 可观测 =====

    @Test
    fun `状态快照反映成功时间 模式 订阅`() {
        val source = FakeSource()
        val collector = LocationCollector(FakeEnv(source = source, cached = listOf(sample())))
        collector.startSubscription(now) { }
        collector.collectThrottledAsync(now) { }
        val status = collector.health(now + 30_000L)
        assertNotNull(status.lastSuccessAtMs)
        assertEquals(30_000L, status.msSinceLastSuccess)
        assertTrue(status.subscriptionActive)
        assertEquals(LocationFailureReason.NONE, status.lastFailure)
        assertEquals(SampleMode.STILL, status.mode)
        assertTrue(status.summary().isNotBlank())
    }

    @Test
    fun `失败原因被记录成可读枚举`() {
        val env = FakeEnv(fine = false)
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        assertEquals(LocationFailureReason.PERMISSION, collector.health(now).lastFailure)

        val env2 = FakeEnv(cached = emptyList(), onActive = { _, cb -> cb(null) })
        val collector2 = LocationCollector(env2)
        collector2.collectThrottledAsync(now) { }
        assertEquals(LocationFailureReason.ACTIVE_FIX_TIMEOUT, collector2.health(now).lastFailure)
    }

    @Test
    fun `一次成功会清掉之前的失败原因`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, cb -> cb(null) })
        val collector = LocationCollector(env)
        collector.collectThrottledAsync(now) { }
        assertEquals(LocationFailureReason.ACTIVE_FIX_TIMEOUT, collector.health(now).lastFailure)
        env.cached = listOf(sample())
        collector.collectThrottledAsync(now + 60_000L) { }
        assertEquals(LocationFailureReason.NONE, collector.health(now + 60_000L).lastFailure)
    }

    // ===== 异常路径 =====

    @Test
    fun `主动定位抛异常仍然恰好回调一次`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, _ -> throw IllegalStateException("boom") })
        val collector = LocationCollector(env)
        var callbacks = 0
        collector.collectThrottledAsync(now) { callbacks++ }
        assertEquals(1, callbacks)
        assertEquals(LocationFailureReason.EXCEPTION, collector.health(now).lastFailure)
    }

    @Test
    fun `无订阅源时退化为缓存加主动定位`() {
        val env = FakeEnv(source = null, cached = listOf(sample()), onActive = { _, cb -> cb(sample()) })
        val collector = LocationCollector(env)
        assertFalse(collector.startSubscription(now) { })
        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertNotNull("没有订阅也必须能采到点", result)
    }
}
