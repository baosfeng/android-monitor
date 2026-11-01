package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测缓存优先：门禁探测（PROBING 低频探测 / SLEEPING 30 分钟兜底）允许用 ≤90 秒的系统缓存点
 * 顶替主动定位，超过 90 秒才真的开射频。
 *
 * 依据（真机 8 天基线）：2176 个点里 passive 2134 / network 38 / gps 4 —— 系统缓存几乎总是新鲜的，
 * 而旧的 force 路径从不看缓存，每轮都白烧一次 12 秒探测。
 */
class LocationProbeCacheTest {

    private val now = 1_700_000_000_000L

    private fun sample(
        provider: String = "gps",
        accuracy: Float = 8f,
        ageMs: Long = 0L,
        meters: Double = 0.0,
    ) = LocationSample(provider, 31.2304 + meters / 111_000.0, 121.4737, accuracy, now - ageMs)

    private class FakeEnv(
        var cached: List<LocationSample> = emptyList(),
        var onActive: (Long, FixBudget, (LocationSample?) -> Unit) -> Unit = { _, _, cb -> cb(null) },
    ) : LocationCollector.Env {
        var activeRequests = 0
        val budgets = mutableListOf<FixBudget>()
        val logs = mutableListOf<String>()

        override fun isFineGranted(): Boolean = true
        override fun isBackgroundGranted(): Boolean = true
        override fun readCached(): List<LocationSample> = cached
        override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) {
            activeRequests++
            budgets += FixBudget.FULL
            onActive(nowMs, FixBudget.FULL, onResult)
        }
        override fun requestActiveFix(nowMs: Long, budget: FixBudget, onResult: (LocationSample?) -> Unit) {
            activeRequests++
            budgets += budget
            onActive(nowMs, budget, onResult)
        }
        override fun log(message: String, warn: Boolean) { logs += message }
    }

    @Test
    fun `探测命中 89 秒缓存 不发起主动定位且点位进门禁证据链`() {
        val cached = sample(provider = "passive", accuracy = 20f, ageMs = 89_000L)
        val env = FakeEnv(cached = listOf(cached))
        val collector = LocationCollector(env, clock = { now })

        val offered = mutableListOf<LocationSample>()
        LocationGateState.registerLocationListener { offered += it }
        var result: LocationSample? = null
        try {
            collector.collectThrottledAsync(now, force = true, budget = FixBudget.PROBE) { result = it }
        } finally {
            LocationGateState.registerLocationListener(null)
        }

        assertEquals("89 秒缓存必须顶替主动定位", 0, env.activeRequests)
        assertTrue(env.budgets.isEmpty())
        assertEquals("探测结果就是这个缓存点", cached, result)
        assertTrue("探测缓存点同样是本轮证据，必须进门禁证据链", offered.any { it === cached })
    }

    @Test
    fun `探测遇到 91 秒缓存 必须主动定位且用覆盖级预算`() {
        val cached = sample(provider = "passive", accuracy = 20f, ageMs = 91_000L)
        val fixed = sample(provider = "gps", accuracy = 5f)
        val env = FakeEnv(cached = listOf(cached), onActive = { _, _, cb -> cb(fixed) })
        val collector = LocationCollector(env, clock = { now })

        var result: LocationSample? = null
        collector.collectThrottledAsync(now, force = true, budget = FixBudget.PROBE) { result = it }

        assertEquals("超过 90 秒的缓存不能代表「现在在哪」，必须主动定位", 1, env.activeRequests)
        assertEquals(listOf(FixBudget.PROBE), env.budgets)
        assertEquals(fixed, result)
    }

    @Test
    fun `探测缓存点也要过质量闸 低置信点不许覆盖更优位置`() {
        val high = sample(provider = "gps", accuracy = 5f, ageMs = 0L)
        val low = sample(provider = "network", accuracy = 300f, ageMs = 10_000L, meters = 10.0)
        val env = FakeEnv(cached = listOf(high))
        val collector = LocationCollector(env, clock = { now })
        // 先接住一个高置信点，作为质量闸的对照基准
        collector.collectThrottledAsync(now) { }
        assertEquals(0, env.activeRequests)

        env.cached = listOf(low)
        collector.collectThrottledAsync(now + 2_000L, force = true, budget = FixBudget.PROBE) { }
        assertEquals("10 米外的低置信点过不了质量闸，必须主动定位", 1, env.activeRequests)
        assertEquals(listOf(FixBudget.PROBE), env.budgets)
    }

    @Test
    fun `非探测路径命中缓存仍然不喂门禁证据`() {
        val cached = sample(provider = "gps", accuracy = 8f, ageMs = 89_000L)
        val env = FakeEnv(cached = listOf(cached))
        val collector = LocationCollector(env, clock = { now })

        val offered = mutableListOf<LocationSample>()
        LocationGateState.registerLocationListener { offered += it }
        var result: LocationSample? = null
        try {
            collector.collectThrottledAsync(now) { result = it }
        } finally {
            LocationGateState.registerLocationListener(null)
        }

        assertEquals(cached, result)
        assertTrue("非探测路径的缓存点（最老 30 分钟）不是「现在在哪」，不能当门禁证据", offered.isEmpty())
    }

    @Test
    fun `探测预算档位透传到 Env 默认仍是满预算`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, _, cb -> cb(null) })
        val collector = LocationCollector(env, clock = { now })

        collector.collectThrottledAsync(now, budget = FixBudget.PROBE) { }
        // 第二次必须等满发令闸的最小间隔（静止 180 秒），否则会被闸挡掉、看不到第二档预算
        collector.collectThrottledAsync(now + LocationPace.STILL_INTERVAL_MS) { }
        assertEquals(listOf(FixBudget.PROBE, FixBudget.FULL), env.budgets)
    }

    @Test
    fun `断档自愈不吃探测预算 一律升到满预算`() {
        val fixed = sample(provider = "gps", accuracy = 5f)
        var calls = 0
        val env = FakeEnv(cached = emptyList(), onActive = { _, _, cb ->
            calls++
            cb(fixed)
        })
        val collector = LocationCollector(env, clock = { now })
        collector.collectThrottledAsync(now) { } // 先成功一次，建立「上次成功」基线
        assertEquals(1, calls)

        // 45 分钟后：深断档（>30 分钟）。此时即使调用方给的是覆盖级预算，也必须用满预算
        collector.collectThrottledAsync(now + 45 * 60_000L, budget = FixBudget.PROBE) { }
        assertEquals(listOf(FixBudget.FULL, FixBudget.FULL), env.budgets)
    }

    @Test
    fun `没有缓存时探测直接主动定位`() {
        val env = FakeEnv(cached = emptyList(), onActive = { _, _, cb -> cb(null) })
        val collector = LocationCollector(env, clock = { now })
        var result: LocationSample? = null
        collector.collectThrottledAsync(now, force = true, budget = FixBudget.PROBE) { result = it }
        assertEquals(1, env.activeRequests)
        assertNull(result)
    }
}
