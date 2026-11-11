package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 被动定位通道（passive）：零射频拿点、高频回调下限、新鲜度与授权闸。
 *
 * passive 是"别的 App 点亮芯片后的副产品"，处理器必须假设回调频率不可控（导航 1 秒一次），
 * 所以这里把「多久处理一个」「过期点要不要」「没权限怎么办」逐条钉死。
 */
class PassiveLocationTapTest {

    private var clockNow = 1_700_000_000_000L

    private class FakeSource(var succeed: Boolean = true) : LocationSubscriptionSource {
        var providers: List<String> = emptyList()
        var consumer: ((LocationSample) -> Unit)? = null
        var stops = 0

        override fun start(
            providers: List<String>,
            intervalMs: Long,
            minDistanceMeters: Float,
            onSample: (LocationSample) -> Unit,
        ): Boolean {
            this.providers = providers
            consumer = onSample
            return succeed
        }

        override fun stop() {
            stops++
            consumer = null
        }

        override fun isActive(): Boolean = consumer != null
    }

    private class FakeEnv(var fine: Boolean = true, val passive: FakeSource = FakeSource()) :
        LocationCollector.Env {
        val logs = mutableListOf<String>()

        override fun isFineGranted(): Boolean = fine
        override fun isBackgroundGranted(): Boolean = fine
        override fun readCached(): List<LocationSample> = emptyList()
        override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) = onResult(null)
        override fun log(message: String, warn: Boolean) {
            logs += message
        }

        override fun passiveSource(): LocationSubscriptionSource? = passive
    }

    private fun sample(ageMs: Long = 0L, lat: Double = 31.2304, accuracy: Float = 20f) =
        LocationSample("passive", lat, 121.4737, accuracy, clockNow - ageMs)

    private fun collector(env: FakeEnv) = LocationCollector(env, clock = { clockNow })

    @Test
    fun `被动通道只注册 passive provider`() {
        val source = FakeSource()
        val env = FakeEnv(passive = source)
        val received = mutableListOf<LocationSample>()

        assertTrue(collector(env).startPassiveTap { received += it })
        assertEquals(listOf(LocationPolicy.PASSIVE_PROVIDER), source.providers)
    }

    @Test
    fun `被动点过了质量闸才落库`() {
        val source = FakeSource()
        val collector = collector(FakeEnv(passive = source))
        val received = mutableListOf<LocationSample>()

        collector.startPassiveTap { received += it }
        source.consumer?.invoke(sample())
        assertEquals(1, received.size)
    }

    @Test
    fun `15 秒内的被动点只处理第一个`() {
        val source = FakeSource()
        val collector = collector(FakeEnv(passive = source))
        val received = mutableListOf<LocationSample>()

        collector.startPassiveTap { received += it }
        source.consumer?.invoke(sample())
        clockNow += 5_000L
        source.consumer?.invoke(sample())
        assertEquals("高频回调被 15 秒下限丢弃", 1, received.size)
    }

    @Test
    fun `过期被动点不落库也不记失败`() {
        val source = FakeSource()
        val collector = collector(FakeEnv(passive = source))
        val received = mutableListOf<LocationSample>()

        collector.startPassiveTap { received += it }
        // MAX_AGE_MS = 30 分钟：40 分钟前的点算过期
        source.consumer?.invoke(sample(ageMs = 40 * 60_000L))
        assertEquals(0, received.size)
        // 过期点不算「我们的链路失败」：被动点是等别人定位，没点是正常状态
        // 不占 15 秒处理窗口，随后的新鲜点照常处理
        clockNow += 20_000L
        source.consumer?.invoke(sample())
        assertEquals(1, received.size)
    }

    @Test
    fun `未授权时不注册被动通道`() {
        val source = FakeSource()
        val env = FakeEnv(fine = false, passive = source)

        assertFalse(collector(env).startPassiveTap { })
        assertEquals(emptyList<String>(), source.providers)
    }

    @Test
    fun `stop 后回调不再落库`() {
        val source = FakeSource()
        val collector = collector(FakeEnv(passive = source))
        val received = mutableListOf<LocationSample>()

        collector.startPassiveTap { received += it }
        source.consumer?.invoke(sample())
        collector.stopPassiveTap()
        assertEquals(1, source.stops)
        assertFalse(collector.isPassiveTapActive())
    }
}
