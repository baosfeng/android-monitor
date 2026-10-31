package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅主管（幂等建立 / 幂等释放 / 变速重建 / 静默重建）与订阅计划（provider 选择）的用例。
 *
 * 用假 [LocationSubscriptionSource] 记录 start/stop 次数，因此能在纯 JVM 里把「恰好一次、不泄漏」钉死。
 */
class LocationSubscriptionTest {

    private val now = 1_700_000_000_000L

    private fun sample(provider: String = "gps", timeMs: Long = now) =
        LocationSample(provider, 31.2304, 121.4737, 8f, timeMs)

    /** 假订阅源：记录调用次数与最近一次参数。 */
    private class FakeSource(var starts: Int = 0, var stops: Int = 0, var succeed: Boolean = true) : LocationSubscriptionSource {
        var providers: List<String> = emptyList()
        var intervalMs: Long = 0L
        var minDistance: Float = -1f
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
            this.minDistance = minDistanceMeters
            this.lastConsumer = onSample
            active = succeed
            return succeed
        }

        override fun stop() {
            stops++
            active = false
        }

        override fun isActive(): Boolean = active
    }

    // ===== provider 计划 =====

    @Test
    fun `fused 可用时也同时注册 gps 和 network`() {
        // 原缺陷：fused 可用就只用 fused。实测（emulator-5554）fused 被列为可用，
        // 但 gms FLP 从不给订阅者派点 —— 订阅全程零投递、状态却显示「活跃」，
        // 重建多少次都选回同一个 fused。所以必须并集注册，任一来源出点即可。
        assertEquals(
            listOf("fused", "network", "gps"),
            LocationSubscriptionPlan.providersFor(listOf("fused", "gps", "network")),
        )
    }

    @Test
    fun `fused 在并集里仍排第一`() {
        // 顺序有意义：fused 最省电，先注册它；gps/network 是它不派点时的活路
        val providers = LocationSubscriptionPlan.providersFor(listOf("gps", "network", "fused"))
        assertEquals("fused", providers.first())
        assertTrue("必须真的带上 gps", providers.contains("gps"))
        assertTrue("必须真的带上 network", providers.contains("network"))
    }

    @Test
    fun `fused 不可用时退 gps 加 network`() {
        assertEquals(
            listOf("network", "gps"),
            LocationSubscriptionPlan.providersFor(listOf("gps", "network")),
        )
        assertEquals(listOf("gps"), LocationSubscriptionPlan.providersFor(listOf("gps")))
        assertEquals(emptyList<String>(), LocationSubscriptionPlan.providersFor(emptyList()))
    }

    @Test
    fun `不在可用列表里的 provider 不会被注册`() {
        // 只注册系统确认启用的：注册一个 disabled 的 provider 只会让 start 里多个失败分支
        assertEquals(listOf("fused", "gps"), LocationSubscriptionPlan.providersFor(listOf("fused", "gps")))
    }

    // ===== 首点兜底判定 =====

    @Test
    fun `订阅零投递超过静默阈值才触发首点兜底`() {
        val notYet = snapshot(active = true, silentMs = 104_999L, deliveryCount = 0)
        assertFalse("阈值内还不该补", LocationSubscriptionPlan.needsInitialFix(notYet, SampleMode.STILL))
        val due = snapshot(active = true, silentMs = 105_000L, deliveryCount = 0)
        assertTrue("满阈值必须补", LocationSubscriptionPlan.needsInitialFix(due, SampleMode.STILL))
    }

    @Test
    fun `已经投递过就不再算首点兜底`() {
        // 投递过但静默，属于「静默重建」的范畴，不是「首点」问题
        val delivered = snapshot(active = true, silentMs = 10 * 60_000L, deliveryCount = 1)
        assertFalse(LocationSubscriptionPlan.needsInitialFix(delivered, SampleMode.STILL))
    }

    @Test
    fun `订阅未建立时不算首点兜底`() {
        val inactive = snapshot(active = false, silentMs = 10 * 60_000L, deliveryCount = 0)
        assertFalse(LocationSubscriptionPlan.needsInitialFix(inactive, SampleMode.STILL))
    }

    /** 判据输入的最小假实现：只填 needsInitialFix 会读的三个字段。 */
    private fun snapshot(active: Boolean, silentMs: Long?, deliveryCount: Int) =
        object : LocationSubscriptionPlan.ActiveSnapshot {
            override val active = active
            override val silentMs = silentMs
            override val deliveryCount = deliveryCount
        }

    @Test
    fun `订阅间隔与采样节奏同源`() {
        assertEquals(30_000L, LocationSubscriptionPlan.intervalMsOf(SampleMode.MOVING))
        assertEquals(180_000L, LocationSubscriptionPlan.intervalMsOf(SampleMode.STILL))
        assertEquals(LocationPace.intervalOf(SampleMode.MOVING), LocationSubscriptionPlan.intervalMsOf(SampleMode.MOVING))
    }

    @Test
    fun `静默超时按派点间隔算是3倍加15秒余量`() {
        assertEquals(105_000L, LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.MOVING))
        // 静止派点是 30s（探测间隔），不是落库的 180s：按 180s 算会得到 555s，
        // 那会把「派点已停但落库窗口还没到」的空窗拖到 9 分钟，用户看到的就是掉线。
        assertEquals(105_000L, LocationSubscriptionPlan.silentTimeoutMsOf(SampleMode.STILL))
    }

    // ===== 幂等建立 / 释放 =====

    @Test
    fun `重复 ensure 同一配置只建立一次`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        val consumer: (LocationSample) -> Unit = { }
        assertTrue(supervisor.ensure(now, listOf("gps"), 10_000L, consumer))
        assertTrue(supervisor.ensure(now + 1_000L, listOf("gps"), 10_000L, consumer))
        assertTrue(supervisor.ensure(now + 2_000L, listOf("gps"), 10_000L, consumer))
        assertEquals("幂等：只能 start 一次", 1, source.starts)
        assertEquals(0, source.stops)
        assertTrue(supervisor.isActive())
    }

    @Test
    fun `模式变化按新间隔重建`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        supervisor.ensure(now, listOf("gps"), 10_000L) { }
        supervisor.ensure(now + 1_000L, listOf("gps"), 180_000L) { }
        assertEquals(2, source.starts)
        assertEquals("重建必须先释放旧监听", 1, source.stops)
        assertEquals(180_000L, source.intervalMs)
    }

    @Test
    fun `重复 stop 只释放一次`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        supervisor.ensure(now, listOf("gps"), 10_000L) { }
        supervisor.stop()
        supervisor.stop()
        supervisor.stop()
        assertEquals("幂等：不重复 removeUpdates", 1, source.stops)
        assertFalse(supervisor.isActive())
    }

    @Test
    fun `未建立时 stop 是空操作`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        supervisor.stop()
        assertEquals(0, source.stops)
        assertFalse(supervisor.isActive())
    }

    // ===== 边界 / 异常 =====

    @Test
    fun `没有可用 provider 时不建立`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        assertFalse(supervisor.ensure(now, emptyList(), 10_000L) { })
        assertEquals(0, source.starts)
        assertFalse(supervisor.isActive())
    }

    @Test
    fun `底层建立失败时不标记活跃`() {
        val source = FakeSource(succeed = false)
        val supervisor = LocationSubscriptionSupervisor(source)
        assertFalse(supervisor.ensure(now, listOf("gps"), 10_000L) { })
        assertFalse(supervisor.isActive())
        // 失败后还能再试（不留下「已建立」的假状态）
        source.succeed = true
        assertTrue(supervisor.ensure(now + 1_000L, listOf("gps"), 10_000L) { })
    }

    @Test
    fun `底层抛异常时不外抛`() {
        val source = object : LocationSubscriptionSource {
            override fun start(providers: List<String>, intervalMs: Long, minDistanceMeters: Float, onSample: (LocationSample) -> Unit): Boolean =
                throw IllegalStateException("boom")
            override fun stop() = Unit
            override fun isActive(): Boolean = false
        }
        val supervisor = LocationSubscriptionSupervisor(source)
        assertFalse(supervisor.ensure(now, listOf("gps"), 10_000L) { })
        assertFalse(supervisor.isActive())
    }

    // ===== 静默重建 =====

    @Test
    fun `订阅静默超过阈值判定需要重建`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        supervisor.ensure(now, listOf("gps"), 10_000L) { }
        assertFalse("刚开始不算静默", supervisor.needsRestart(now + 1_000L, SampleMode.MOVING))
        // 移动派点 30s -> 静默阈值 3×30s + 15s = 105s
        assertTrue(supervisor.needsRestart(now + 106_000L, SampleMode.MOVING))
    }

    @Test
    fun `收到点后静默计时从投递时刻重算`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        supervisor.ensure(now, listOf("gps"), 10_000L) { }
        supervisor.onDelivery(now + 40_000L)
        assertFalse("刚收到点，不该重建", supervisor.needsRestart(now + 60_000L, SampleMode.MOVING))
        assertTrue(supervisor.needsRestart(now + 40_000L + 106_000L, SampleMode.MOVING))
    }

    @Test
    fun `未激活时不要求重建 由 ensure 负责建立`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        assertFalse(supervisor.needsRestart(now + 10 * 60_000L, SampleMode.STILL))
    }

    @Test
    fun `快照反映订阅状态与静默时长`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        assertNull(supervisor.snapshot(now).silentMs)
        assertFalse(supervisor.snapshot(now).active)
        supervisor.ensure(now, listOf("gps"), 10_000L) { }
        val snapshot = supervisor.snapshot(now + 5_000L)
        assertTrue(snapshot.active)
        assertEquals(10_000L, snapshot.intervalMs)
        assertEquals(listOf("gps"), snapshot.providers)
        assertEquals(5_000L, snapshot.silentMs)
    }

    @Test
    fun `投递经过主管转发给调用方`() {
        val source = FakeSource()
        val supervisor = LocationSubscriptionSupervisor(source)
        var received: LocationSample? = null
        supervisor.ensure(now, listOf("gps"), 10_000L) { received = it }
        source.lastConsumer?.invoke(sample())
        assertEquals("gps", received?.provider)
        assertEquals(1, supervisor.snapshot(now).deliveryCount)
    }
}
