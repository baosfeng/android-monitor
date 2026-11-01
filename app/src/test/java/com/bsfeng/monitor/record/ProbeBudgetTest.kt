package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测预算分级：门禁探测 / SLEEPING 兜底走覆盖级（network 4s + gps 8s），
 * MOVING 补采 / 断档自愈 / 首点兜底 / 门禁未知时的心跳保持满预算（5s + 15s）。
 *
 * 为什么要分档：PROBING 每轮探测都可能两个 provider 双双超时（室内常态），
 * 满预算等于每轮白烧 20 秒射频；而 MOVING 那几条路径砍预算就是重演「GPS 必然 timeout」的掉线。
 */
class ProbeBudgetTest {

    private val now = 1_700_000_000_000L

    private class RecordingSource(private val answers: Map<String, LocationSample?> = emptyMap()) : SingleFixSource {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit) {
            calls += provider to timeoutMs
            onResult(answers[provider])
        }
    }

    @Test
    fun `满预算不变 network 5 秒 gps 15 秒`() {
        assertEquals(5_000L, LocationPolicy.activeFixTimeoutMs("network", FixBudget.FULL))
        assertEquals(15_000L, LocationPolicy.activeFixTimeoutMs("gps", FixBudget.FULL))
        assertEquals(LocationPolicy.ACTIVE_FIX_BUDGET_MS, LocationPolicy.activeFixTimeoutMs("network"))
        assertEquals(LocationPolicy.GPS_FIRST_FIX_BUDGET_MS, LocationPolicy.activeFixTimeoutMs("gps"))
    }

    @Test
    fun `探测预算 network 4 秒 gps 8 秒`() {
        assertEquals(4_000L, LocationPolicy.activeFixTimeoutMs("network", FixBudget.PROBE))
        assertEquals(8_000L, LocationPolicy.activeFixTimeoutMs("gps", FixBudget.PROBE))
        assertEquals(LocationPolicy.PROBE_FIX_BUDGET_MS, LocationPolicy.activeFixTimeoutMs("passive", FixBudget.PROBE))
    }

    @Test
    fun `单次探测射频上限从 20 秒降到 12 秒`() {
        val full = LocationPolicy.ACTIVE_PROVIDERS.sumOf { LocationPolicy.activeFixTimeoutMs(it, FixBudget.FULL) }
        val probe = LocationPolicy.ACTIVE_PROVIDERS.sumOf { LocationPolicy.activeFixTimeoutMs(it, FixBudget.PROBE) }
        assertEquals("满预算 5+15", 20_000L, full)
        assertEquals("探测预算 4+8", 12_000L, probe)
        assertTrue("探测预算必须严格小于满预算", probe < full)
    }

    @Test
    fun `探测缓存窗口是 90 秒`() {
        assertEquals(90_000L, LocationPolicy.PROBE_CACHE_MAX_AGE_MS)
        assertTrue("探测窗口必须宽于移动模式的 30 秒新鲜线", LocationPolicy.PROBE_CACHE_MAX_AGE_MS > LocationPace.CACHE_FRESH_MS)
    }

    @Test
    fun `resolver 按档位取每 provider 的超时`() {
        val source = RecordingSource()
        val resolver = ActiveFixResolver(source)
        resolver.resolve(now, { true }, FixBudget.PROBE) { }
        assertEquals(listOf("network", "gps"), source.calls.map { it.first })
        assertEquals(listOf(4_000L, 8_000L), source.calls.map { it.second })

        val fullSource = RecordingSource()
        ActiveFixResolver(fullSource).resolve(now, { true }) { }
        assertEquals(listOf(5_000L, 15_000L), fullSource.calls.map { it.second })
    }

    @Test
    fun `探测档位仍按 provider 逐个降级到下一个`() {
        val gpsFix = LocationSample("gps", 39.9042, 116.4074, 5f, now)
        val source = RecordingSource(mapOf("gps" to gpsFix))
        var result: LocationSample? = null
        ActiveFixResolver(source).resolve(now, { true }, FixBudget.PROBE) { result = it }
        assertEquals(gpsFix, result)
        assertEquals(2, source.calls.size)
    }

    @Test
    fun `没有可用 provider 时探测档位也不发请求`() {
        val source = RecordingSource()
        var result: LocationSample? = null
        ActiveFixResolver(source).resolve(now, { false }, FixBudget.PROBE) { result = it }
        assertTrue(source.calls.isEmpty())
        assertNull(result)
    }

    @Test
    fun `预算摘要按档位给出 用于日志对账`() {
        assertEquals("network 5000ms / gps 15000ms", LocationPolicy.budgetSummaryOf(FixBudget.FULL))
        assertEquals("network 4000ms / gps 8000ms", LocationPolicy.budgetSummaryOf(FixBudget.PROBE))
        assertEquals(LocationPolicy.ACTIVE_FIX_BUDGET_SUMMARY, LocationPolicy.budgetSummaryOf(FixBudget.FULL))
        val resolver = ActiveFixResolver(RecordingSource())
        assertEquals("network 4000ms / gps 8000ms", resolver.budgetSummaryOf(FixBudget.PROBE))
        assertEquals("network 5000ms / gps 15000ms", resolver.budgetSummary)
    }

    @Test
    fun `心跳预算跟着门禁走 PROBING 覆盖级 其余满预算`() {
        assertEquals(FixBudget.PROBE, LocationProbePolicy.heartbeatBudget(MotionGate.State.PROBING))
        assertEquals(FixBudget.FULL, LocationProbePolicy.heartbeatBudget(MotionGate.State.MOVING))
        assertEquals("服务没跑（门禁未知）时心跳是唯一采点路径，必须满预算", FixBudget.FULL, LocationProbePolicy.heartbeatBudget(null))
        assertEquals("SLEEPING 心跳本就被门禁挡住，档位不参与决策", FixBudget.PROBE, LocationProbePolicy.heartbeatBudget(MotionGate.State.SLEEPING))
    }
}
