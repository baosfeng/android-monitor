package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络环境静止证据（纯逻辑）：同一 WiFi + 无步数增量 → SLEEPING 兜底放宽到 2 小时；
 * 换网络 / 断网 / 有步数 → 立刻回 30 分钟；读不到 SSID 不算变化。
 *
 * 反向验证（改坏实现时应看到的红）：
 * - 把「未知 SSID」当成变化 → 没给定位权限的机器每次回调都清一次证据，2 小时永远不成立；
 * - 去掉 transport 比对 → 从 WiFi 切到移动网络（换地点最典型的一种）仍被当成原地；
 * - 去掉步数证据 → 公司 / 商场同一个 SSID 下走一整天也不恢复 30 分钟。
 */
class NetworkEnvironmentTest {

    private val base = 1_700_000_000_000L
    private val minute = 60_000L

    private fun wifi(ssid: String?) = NetworkFingerprint(NetworkTransport.WIFI, ssid)

    private fun fallbackInterval(tracker: NetworkStillnessTracker, nowMs: Long): Long =
        LocationTickerPolicy.sleepingFallbackIntervalMs(tracker.stillSinceMs, nowMs)

    // ===== 指纹比对 =====

    @Test
    fun `首次观测只建基线 不算变化`() {
        val tracker = NetworkStillnessTracker()
        assertFalse("没有历史就谈不上「变了」", tracker.observeNetwork(wifi("家"), base))
        assertEquals("第一条观测就开始攒静止证据", base, tracker.stillSinceMs)
        assertEquals(0L, tracker.lastChangedAtMs)
        assertEquals(wifi("家"), tracker.fingerprint)
    }

    @Test
    fun `同一个 WiFi 再接一次不算变化 证据不重算`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        assertFalse(tracker.observeNetwork(wifi("家"), base + 10 * minute))
        assertEquals("证据起点不能被重复观测推后", base, tracker.stillSinceMs)
    }

    @Test
    fun `换 SSID 算变化 静止证据清零`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        assertTrue(tracker.observeNetwork(wifi("公司"), base + 20 * minute))
        assertEquals("证据清零 = 兜底回 30 分钟", 0L, tracker.stillSinceMs)
        assertEquals(base + 20 * minute, tracker.lastChangedAtMs)
    }

    @Test
    fun `WiFi 切到移动网络算变化`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        assertTrue(tracker.observeNetwork(NetworkFingerprint(NetworkTransport.CELLULAR), base + minute))
        assertEquals(0L, tracker.stillSinceMs)
    }

    @Test
    fun `读不到 SSID 时按 transport 视为同环境`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        assertFalse("null 不是「换了 WiFi」的证据", tracker.observeNetwork(wifi(null), base + 10 * minute))
        assertFalse(
            "系统占位串同样按未知处理",
            tracker.observeNetwork(wifi("<unknown ssid>"), base + 11 * minute),
        )
        assertFalse("脱敏后的 0x 同样按未知处理", tracker.observeNetwork(wifi("0x"), base + 12 * minute))
        assertEquals("证据一直没被打断", base, tracker.stillSinceMs)
    }

    @Test
    fun `先读不到后来读得到不算变化`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi(null), base)
        assertFalse(
            "刚授权定位的那一刻不能凭空清一次证据",
            tracker.observeNetwork(wifi("家"), base + 5 * minute),
        )
    }

    @Test
    fun `一次读不到 SSID 不会抹掉已知的 SSID`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        tracker.observeNetwork(wifi(null), base + 10 * minute)
        assertTrue(
            "家 → 读不到 → 公司：中间那次读失败不能把换地点这件事吃掉",
            tracker.observeNetwork(wifi("公司"), base + 20 * minute),
        )
    }

    @Test
    fun `带引号的 SSID 与不带引号视为同一个`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("\"家\""), base)
        assertFalse(tracker.observeNetwork(wifi("家"), base + minute))
    }

    // ===== 兜底间隔（与 LocationTickerPolicy 联动）=====

    @Test
    fun `指纹未变满 30 分钟后兜底放宽到 60 分钟`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)

        assertEquals(
            "证据不满一轮 30 分钟兜底时先按旧节奏验",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            fallbackInterval(tracker, base + 29 * minute),
        )
        assertEquals(
            LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS,
            fallbackInterval(tracker, base + 30 * minute),
        )
        assertEquals(60 * minute, LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS)
    }

    @Test
    fun `指纹变化后兜底立刻回 30 分钟`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        assertEquals(
            LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS,
            fallbackInterval(tracker, base + 31 * minute),
        )

        assertTrue(tracker.observeNetwork(wifi("公司"), base + 31 * minute))
        assertEquals(
            "换了 WiFi 就不该再享受 60 分钟放松",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            fallbackInterval(tracker, base + 31 * minute),
        )
    }

    @Test
    fun `有步数增量时不延长`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        assertEquals(60 * minute, fallbackInterval(tracker, base + 40 * minute))

        tracker.onMotionEvidence(base + 40 * minute)
        assertEquals(base + 40 * minute, tracker.lastMotionEvidenceAtMs)
        assertEquals(
            "同一 SSID 不代表同一地点：走过路就必须回 30 分钟",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            fallbackInterval(tracker, base + 41 * minute),
        )
    }

    @Test
    fun `没有基线时兜底保持 30 分钟`() {
        val tracker = NetworkStillnessTracker()
        assertEquals(
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            fallbackInterval(tracker, base + 24 * 60 * minute),
        )
    }

    @Test
    fun `时钟倒退时不放宽`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        assertEquals(
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            fallbackInterval(tracker, base - 5 * minute),
        )
    }

    @Test
    fun `reset 清空指纹与证据`() {
        val tracker = NetworkStillnessTracker()
        tracker.observeNetwork(wifi("家"), base)
        tracker.onMotionEvidence(base + minute)

        tracker.reset()
        assertNull(tracker.fingerprint)
        assertEquals(0L, tracker.stillSinceMs)
        assertEquals(0L, tracker.lastChangedAtMs)
        assertEquals(0L, tracker.lastMotionEvidenceAtMs)
        assertFalse("重置后第一条观测重新建基线", tracker.observeNetwork(wifi("家"), base + 2 * minute))
    }

    // ===== 服务层的证据来源（步数 → MotionGate.lastActivityAtMs）=====

    @Test
    fun `步数增量会刷新门禁的活动时刻 服务据此打断静止证据`() {
        val gate = MotionGate()
        gate.onStepCounter(1_000L, base)
        assertEquals("第一次读步数计只建基线", 0L, gate.lastActivityAtMs)

        gate.onStepCounter(1_010L, base + minute)
        assertEquals(
            "服务读门禁的活动时刻喂给静止证据，2 小时才会被步数打断",
            base + minute,
            gate.lastActivityAtMs,
        )
    }

    // ===== 承载类型 =====

    @Test
    fun `承载类型映射`() {
        assertEquals(NetworkTransport.WIFI, NetworkTransport.of(wifi = true, cellular = false))
        assertEquals(NetworkTransport.CELLULAR, NetworkTransport.of(wifi = false, cellular = true))
        assertEquals(NetworkTransport.OTHER, NetworkTransport.of(wifi = false, cellular = false))
    }
}
