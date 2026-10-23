package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位过滤/节流规则 + 「缓存空 → 单次主动定位」编排的用例。
 *
 * 全部纯逻辑：不依赖真实 LocationManager（`LocationCollector.Env` / `SingleFixSource` 都是可注入的）。
 */
class LocationCollectorTest {

    private val now = 1_700_000_000_000L

    private fun sample(
        provider: String = "network",
        accuracy: Float = 30f,
        ageMs: Long = 60_000L,
        lat: Double = 31.2304,
        lng: Double = 121.4737,
    ) = LocationSample(provider, lat, lng, accuracy, now - ageMs)

    // ===== 时间新鲜度 =====

    @Test
    fun `刚拿到的点算新鲜`() {
        assertTrue(LocationPolicy.isFresh(now, now))
        assertTrue(LocationPolicy.isFresh(now - LocationPolicy.MAX_AGE_MS, now))
    }

    @Test
    fun `超过30分钟视为 stale`() {
        assertFalse(LocationPolicy.isFresh(now - LocationPolicy.MAX_AGE_MS - 1, now))
        assertFalse(LocationPolicy.isFresh(now - 31 * 60 * 1000L, now))
    }

    @Test
    fun `时钟回拨不算 stale`() {
        assertTrue(LocationPolicy.isFresh(now + 5 * 60 * 1000L, now))
    }

    // ===== 精度 =====

    @Test
    fun `精度门槛是100米`() {
        assertTrue(LocationPolicy.isAccurate(100f))
        assertTrue(LocationPolicy.isAccurate(5f))
        assertFalse(LocationPolicy.isAccurate(100.1f))
        assertFalse(LocationPolicy.isAccurate(2000f))
    }

    @Test
    fun `精度缺失按不可用处理`() {
        assertFalse(LocationPolicy.isAccurate(0f))
        assertFalse(LocationPolicy.isAccurate(-1f))
    }

    // ===== 挑选 =====

    @Test
    fun `passive 优先 其次 network 最后 gps`() {
        val candidates = listOf(
            sample(provider = "gps", accuracy = 5f),
            sample(provider = "network", accuracy = 50f),
            sample(provider = "passive", accuracy = 80f),
        )
        assertEquals("passive", LocationPolicy.pick(candidates, now)?.provider)
        assertEquals("network", LocationPolicy.pick(candidates.dropLast(1), now)?.provider)
        assertEquals("gps", LocationPolicy.pick(listOf(candidates[0]), now)?.provider)
    }

    @Test
    fun `高优先级不可用时回落到低优先级`() {
        val stalePassive = sample(provider = "passive", ageMs = 31 * 60 * 1000L)
        val coarseNetwork = sample(provider = "network", accuracy = 500f)
        val goodGps = sample(provider = "gps", accuracy = 8f)
        assertEquals("gps", LocationPolicy.pick(listOf(stalePassive, coarseNetwork, goodGps), now)?.provider)
    }

    @Test
    fun `全部不可用时返回 null 不猜位置`() {
        val candidates = listOf(
            sample(provider = "passive", ageMs = 60 * 60 * 1000L),
            sample(provider = "network", accuracy = 800f),
        )
        assertNull(LocationPolicy.pick(candidates, now))
        assertNull(LocationPolicy.pick(emptyList(), now))
    }

    // ===== 坐标量化 =====

    @Test
    fun `坐标保留三位小数`() {
        assertEquals("31.230", LocationPolicy.quantize(31.2304))
        assertEquals("121.474", LocationPolicy.quantize(121.4737))
        assertEquals("-33.869", LocationPolicy.quantize(-33.8688))
    }

    @Test
    fun `极小负坐标归一成零避免同一个点两种写法`() {
        assertEquals("0.000", LocationPolicy.quantize(-0.0001))
        assertEquals("0.000", LocationPolicy.quantize(0.0))
    }

    // ===== 节流 =====

    @Test
    fun `COORDINATE 十分钟内最多一条`() {
        val throttle = LocationThrottle()
        assertTrue(throttle.canAcquire(now))
        throttle.markAcquired(now)
        assertFalse(throttle.canAcquire(now + 9 * 60 * 1000L))
        assertTrue(throttle.canAcquire(now + LocationPolicy.MIN_INTERVAL_MS))
    }

    @Test
    fun `采集失败不占用节流窗口`() {
        val throttle = LocationThrottle()
        // 只看时间窗、不 mark：下一次立刻还能采
        assertTrue(throttle.canAcquire(now))
        assertTrue(throttle.canAcquire(now + 1_000L))
        throttle.markAcquired(now)
        throttle.reset()
        assertTrue(throttle.canAcquire(now + 1_000L))
    }

    // ===== 权限三态 =====

    @Test
    fun `定位权限三态`() {
        assertEquals(
            LocationPermissionStatus.NOT_GRANTED,
            LocationCollector.resolveStatus(fineGranted = false, backgroundGranted = false),
        )
        assertEquals(
            LocationPermissionStatus.MISSING_BACKGROUND,
            LocationCollector.resolveStatus(fineGranted = true, backgroundGranted = false),
        )
        assertEquals(
            LocationPermissionStatus.GRANTED,
            LocationCollector.resolveStatus(fineGranted = true, backgroundGranted = true),
        )
    }

    // ===== 缓存空 → 单次主动定位（task-9 的核心回路） =====

    /** 注入式的假环境：不碰 LocationManager。 */
    private class FakeEnv(
        var fine: Boolean = true,
        var background: Boolean = true,
        var cached: List<LocationSample> = emptyList(),
        var onActive: (Long, (LocationSample?) -> Unit) -> Unit = { _, callback -> callback(null) },
    ) : LocationCollector.Env {
        var activeRequests = 0
        var lastRequestedAt = 0L

        override fun isFineGranted(): Boolean = fine
        override fun isBackgroundGranted(): Boolean = background
        override fun readCached(): List<LocationSample> = cached
        override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) {
            activeRequests++
            lastRequestedAt = nowMs
            onActive(nowMs, onResult)
        }
    }

    @Test
    fun `缓存为空时进入单次主动定位且成功即占节流窗口`() {
        val fresh = LocationSample("network", 39.9042, 116.4074, 30f, now)
        val env = FakeEnv(cached = emptyList(), onActive = { _, callback -> callback(fresh) })
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        var callbacks = 0
        collector.collectThrottledAsync(now) {
            result = it
            callbacks++
        }
        assertEquals("缓存空必须发起一次主动定位", 1, env.activeRequests)
        assertEquals("回调必须恰好一次", 1, callbacks)
        assertSame(fresh, result)

        // 成功后 10 分钟内不再采（节流被占用）
        var second: LocationSample? = null
        collector.collectThrottledAsync(now + 1_000L) { second = it }
        assertEquals(1, env.activeRequests)
        assertNull(second)
    }

    @Test
    fun `主动定位超时返回 null 且不占节流窗口`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, callback -> callback(null) })
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertNull(result)
        assertEquals(1, env.activeRequests)

        // 失败（超时/过滤空）不占用窗口：下一个心跳会重新发起
        collector.collectThrottledAsync(now + 60_000L) { result = it }
        assertNull(result)
        assertEquals("超时后下一次心跳仍应重新请求", 2, env.activeRequests)
    }

    @Test
    fun `缓存命中的可用点不发主动请求`() {
        val cached = LocationSample("passive", 31.23, 121.47, 25f, now)
        val env = FakeEnv(cached = listOf(cached))
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(0, env.activeRequests)
        assertSame(cached, result)
    }

    @Test
    fun `缓存只有 stale 或超精度点时才发起主动定位`() {
        val stale = LocationSample("passive", 31.23, 121.47, 20f, now - LocationPolicy.MAX_AGE_MS - 1)
        val coarse = LocationSample("network", 31.23, 121.47, LocationPolicy.MAX_ACCURACY_METERS + 100f, now)
        val fresh = LocationSample("network", 39.9042, 116.4074, 18f, now)
        val env = FakeEnv(cached = listOf(stale, coarse), onActive = { _, callback -> callback(fresh) })
        val collector = LocationCollector(env)

        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(1, env.activeRequests)
        assertSame(fresh, result)
    }

    @Test
    fun `未授权时不发起主动定位`() {
        val env = FakeEnv(fine = false, onActive = { _, callback -> callback(LocationSample("network", 0.0, 0.0, 5f, now)) })
        val collector = LocationCollector(env)
        var result: LocationSample? = null
        collector.collectThrottledAsync(now) { result = it }
        assertEquals(0, env.activeRequests)
        assertNull(result)
    }

    // ===== 主动定位的 provider 编排 =====

    private fun recordingSource(answers: Map<String, LocationSample?>): Pair<SingleFixSource, MutableList<String>> {
        val calls = mutableListOf<String>()
        val source = object : SingleFixSource {
            override fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit) {
                calls += provider
                onResult(answers[provider])
            }
        }
        return source to calls
    }

    @Test
    fun `主动定位按 network 然后 gps 顺序请求`() {
        val (source, calls) = recordingSource(emptyMap())
        val resolver = ActiveFixResolver(source)
        var result: LocationSample? = null
        resolver.resolve(now, { true }) { result = it }
        assertEquals(listOf("network", "gps"), calls)
        assertNull(result)
    }

    @Test
    fun `network 拿不到点时回落到 gps`() {
        val gpsFix = LocationSample("gps", 39.9042, 116.4074, 8f, now)
        val (source, calls) = recordingSource(mapOf("gps" to gpsFix))
        val resolver = ActiveFixResolver(source)
        var result: LocationSample? = null
        resolver.resolve(now, { true }) { result = it }
        assertEquals(listOf("network", "gps"), calls)
        assertSame(gpsFix, result)
    }

    @Test
    fun `不可用的 provider 直接跳过`() {
        val (source, calls) = recordingSource(emptyMap())
        val resolver = ActiveFixResolver(source)
        resolver.resolve(now, { it != "network" }) { }
        assertEquals(listOf("gps"), calls)
    }

    @Test
    fun `总预算按 provider 数均分到 5 秒`() {
        val resolver = ActiveFixResolver(object : SingleFixSource {
            override fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit) = Unit
        })
        assertEquals(LocationPolicy.ACTIVE_FIX_BUDGET_MS, resolver.timeoutPerProviderMs * resolver.providers.size)
    }

    @Test
    fun `主动定位拿到的点仍要过 stale 与精度过滤`() {
        val staleFix = LocationSample("network", 39.9042, 116.4074, 10f, now - LocationPolicy.MAX_AGE_MS - 1)
        val (source, calls) = recordingSource(mapOf("network" to staleFix))
        val resolver = ActiveFixResolver(source)
        var result: LocationSample? = null
        resolver.resolve(now, { true }) { result = it }
        // network 的点 stale → 继续问 gps；gps 无点 → 放弃
        assertEquals(listOf("network", "gps"), calls)
        assertNull(result)
    }
}
