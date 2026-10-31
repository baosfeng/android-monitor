package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅通道编排：零投递 → 静默重建 → 首点判定。
 *
 * 这组用例的存在理由本身就是一个教训：静默重建整套逻辑写好了、单测也有，
 * 但生产代码里 **一次都没调用** needsRestart —— 实测订阅全程零投递、状态却一直显示「活跃」。
 * 所以这里直接从 channel 入口验证「会不会真的重建 / 真的补首点」，而不是只测纯判定函数。
 */
class LocationSubscriptionChannelTest {

    private val now = 1_700_000_000_000L

    private class FakeSource : LocationSubscriptionSource {
        var starts = 0
        var stops = 0
        var providers: List<String> = emptyList()
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
        }

        override fun isActive(): Boolean = active
    }

    private fun channel(
        source: FakeSource?,
        providers: List<String> = listOf("fused", "gps", "network"),
        mode: SampleMode = SampleMode.STILL,
        logs: MutableList<String> = mutableListOf(),
        fine: Boolean = true,
        inFlight: Boolean = false,
        processed: MutableList<LocationSample> = mutableListOf(),
    ) = LocationSubscriptionChannel(
        supervisor = source?.let { LocationSubscriptionSupervisor(it) { m, _ -> logs += m } },
        log = { m, _ -> logs += m },
        fineGranted = { fine },
        availableProviders = { providers },
        powerSave = { false },
        modeOf = { mode },
        onFailure = { _, _ -> },
        inFlight = { inFlight },
        processor = { sample, _ -> processed += sample },
    )

    // ===== 建立：provider 并集 =====

    @Test
    fun `建立时同时注册 fused gps network`() {
        val source = FakeSource()
        val port = channel(source)
        assertTrue(port.start(now) { })
        assertEquals(listOf("fused", "network", "gps"), source.providers)
    }

    @Test
    fun `没有可用 provider 时不建立`() {
        val source = FakeSource()
        val port = channel(source, providers = emptyList())
        assertFalse(port.start(now) { })
        assertEquals(0, source.starts)
    }

    @Test
    fun `未授权时不建立`() {
        val source = FakeSource()
        val port = channel(source, fine = false)
        assertFalse(port.start(now) { })
        assertEquals(0, source.starts)
    }

    // ===== 首点判定 =====

    @Test
    fun `刚建立时不算零投递超时`() {
        val source = FakeSource()
        val port = channel(source)
        port.start(now) { }
        assertFalse("刚建立不该补首点", port.needsInitialFix(now + 1_000L))
    }

    @Test
    fun `零投递超过静默阈值要补首点`() {
        val source = FakeSource()
        val port = channel(source)
        port.start(now) { }
        assertTrue(port.needsInitialFix(now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL)))
    }

    @Test
    fun `投递过就不再算零投递`() {
        val source = FakeSource()
        val port = channel(source)
        port.start(now) { }
        source.lastConsumer?.invoke(LocationSample("gps", 31.23, 121.47, 8f, now))
        assertFalse(port.needsInitialFix(now + 10 * 60_000L))
    }

    // ===== 静默重建 =====

    @Test
    fun `静默超阈值会真的重建并打日志`() {
        val source = FakeSource()
        val logs = mutableListOf<String>()
        val port = channel(source, logs = logs)
        port.start(now) { }
        val before = source.starts
        assertTrue(port.restartIfSilent(now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL) + 1_000L))
        assertTrue("必须真的重启底层订阅", source.starts > before)
        assertTrue("日志要能看出是订阅静默重建", logs.any { it.contains("订阅静默重建") })
    }

    @Test
    fun `未静默时不重建`() {
        val source = FakeSource()
        val port = channel(source)
        port.start(now) { }
        val before = source.starts
        assertFalse(port.restartIfSilent(now + 1_000L))
        assertEquals(before, source.starts)
    }

    @Test
    fun `重建后新回调仍走处理链`() {
        // 回归：重构时曾出现「重建后 consumer 自我递归」的写法，这条钉死重建后投递仍然通
        val source = FakeSource()
        val processed = mutableListOf<LocationSample>()
        val port = channel(source, processed = processed)
        port.start(now) { }
        port.restartIfSilent(now + LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL) + 1_000L)
        source.lastConsumer?.invoke(LocationSample("gps", 31.23, 121.47, 8f, now))
        assertEquals("重建后的点必须进处理链", 1, processed.size)
    }

    @Test
    fun `主动定位在飞时推迟静默重建`() {
        val source = FakeSource()
        val logs = mutableListOf<String>()
        val port = channel(source, logs = logs, inFlight = true)
        port.start(now) { }
        val before = source.starts
        assertFalse(port.restartIfSilent(now + 10 * 60_000L))
        assertEquals("两套定位不许同时跑", before, source.starts)
        assertTrue(logs.any { it.contains("推迟") })
    }

    @Test
    fun `无 supervisor 时所有入口都安全`() {
        val port = channel(source = null)
        assertFalse(port.start(now) { })
        assertFalse(port.restart(now))
        assertFalse(port.restartIfSilent(now))
        assertFalse(port.needsInitialFix(now))
        assertFalse(port.isActive())
        port.stop()
    }
}
