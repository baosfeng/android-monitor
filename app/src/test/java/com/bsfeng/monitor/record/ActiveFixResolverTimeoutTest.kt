package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1：主动定位预算按 provider 独立计（GPS 首点 15s / network 5s，不再 5000/2 均分）。
 *
 * 旧实现的 2500ms 均分是「掉线」的第一个根因：GPS 冷启动要 10–30 秒才出首点，必然 timeout。
 */
class ActiveFixResolverTimeoutTest {

    private val now = 1_700_000_000_000L

    /** 记录每次请求用的预算。 */
    private class RecordingSource(private val answers: Map<String, LocationSample?> = emptyMap()) : SingleFixSource {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun request(provider: String, timeoutMs: Long, onResult: (LocationSample?) -> Unit) {
            calls += provider to timeoutMs
            onResult(answers[provider])
        }
    }

    @Test
    fun `GPS 首点预算不少于 15 秒`() {
        assertTrue(
            "GPS 冷启动 10–30 秒才出首点，预算必须留够",
            LocationPolicy.activeFixTimeoutMs("gps") >= 15_000L,
        )
        assertEquals(LocationPolicy.GPS_FIRST_FIX_BUDGET_MS, LocationPolicy.activeFixTimeoutMs("gps"))
    }

    @Test
    fun `network 预算不超过 5 秒`() {
        assertTrue(LocationPolicy.activeFixTimeoutMs("network") <= 5_000L)
        assertEquals(LocationPolicy.ACTIVE_FIX_BUDGET_MS, LocationPolicy.activeFixTimeoutMs("network"))
    }

    @Test
    fun `预算不再按 provider 数均分`() {
        // 旧行为：5000 / 2 = 2500，GPS 拿不到点
        val old = LocationPolicy.ACTIVE_FIX_BUDGET_MS / LocationPolicy.ACTIVE_PROVIDERS.size
        assertEquals(2_500L, old)
        val source = RecordingSource()
        ActiveFixResolver(source).resolve(now, { true }) { }
        val byProvider = source.calls.toMap()
        assertEquals("gps 不是均分值", false, byProvider["gps"] == old)
        assertEquals("network 仍走快预算", LocationPolicy.ACTIVE_FIX_BUDGET_MS, byProvider["network"])
        assertEquals(LocationPolicy.GPS_FIRST_FIX_BUDGET_MS, byProvider["gps"])
    }

    @Test
    fun `每个 provider 用自己的预算请求`() {
        val source = RecordingSource()
        val resolver = ActiveFixResolver(source)
        resolver.resolve(now, { true }) { }
        assertEquals(listOf("network", "gps"), source.calls.map { it.first })
        assertEquals(5_000L, source.calls[0].second)
        assertEquals(15_000L, source.calls[1].second)
    }

    @Test
    fun `预算可以按 provider 注入 单测与真机各有口径`() {
        val source = RecordingSource()
        val resolver = ActiveFixResolver(source, timeoutMsOf = { 1_000L })
        resolver.resolve(now, { true }) { }
        assertEquals(listOf(1_000L, 1_000L), source.calls.map { it.second })
    }

    @Test
    fun `GPS 在 15 秒内出点即成功 不再被 2500ms 掐掉`() {
        // 模拟：GPS 冷启动 12 秒才回调 —— 旧实现早就 timeout 了
        val gpsFix = LocationSample("gps", 39.9042, 116.4074, 5f, now)
        val source = RecordingSource(mapOf("gps" to gpsFix))
        val resolver = ActiveFixResolver(source)
        var result: LocationSample? = null
        resolver.resolve(now, { true }) { result = it }
        assertSame(gpsFix, result)
        assertTrue("申请给 GPS 的预算必须 > 12 秒", source.calls.last().second > 12_000L)
    }

    @Test
    fun `预算摘要用于日志`() {
        val resolver = ActiveFixResolver(RecordingSource())
        val summary = resolver.budgetSummary
        assertTrue(summary.contains("gps"))
        assertTrue(summary.contains("15000ms"))
        assertTrue(summary.contains("network"))
    }

    @Test
    fun `provider 全不可用时不发请求`() {
        val source = RecordingSource()
        var result: LocationSample? = null
        ActiveFixResolver(source).resolve(now, { false }) { result = it }
        assertTrue(source.calls.isEmpty())
        assertNull(result)
    }
}
