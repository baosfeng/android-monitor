package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测间隔自适应退避：连续拿不到位移证据就把探测间隔从 180 秒拉到 300 / 600 秒。
 *
 * 注意门禁层的现实：PROBING 靠**真实证据**（步态 / 步数 / 位移）维持，而这类证据都会把阶梯清零；
 * 显著运动与抖动不再是证据（拿起手机就会触发，见 [MotionGate] 的 KDoc）。因此「连续无位移探测」
 * 最多走两轮就会被「5 分钟无证据降级」接管 —— 300 / 600 档只在纯函数层面成立。
 */
class MotionGateProbeBackoffTest {

    private val start = 1_700_000_000_000L

    private fun sample(atMs: Long, meters: Double, accuracy: Float = 10f) =
        LocationSample("gps", 31.2304 + meters / 111_000.0, 121.4737, accuracy, atMs)

    /** 进 PROBING 的合法入口：步数增量（显著运动不再唤醒门禁）。返回最后一条证据的时刻。 */
    private fun enterProbing(gate: MotionGate): Long {
        gate.onStepCounter(1_000L, start)
        gate.onStepCounter(1_010L, start + 1_000L)
        assertEquals(MotionGate.State.PROBING, gate.state)
        return start + 1_000L
    }

    /** 阶梯本身（纯函数边界）。 */
    @Test
    fun `阶梯边界 0到2次180秒 3到5次300秒 6次以上600秒`() {
        assertEquals(180_000L, LocationPace.probeIntervalMsFor(0))
        assertEquals(180_000L, LocationPace.probeIntervalMsFor(2))
        assertEquals(300_000L, LocationPace.probeIntervalMsFor(3))
        assertEquals(300_000L, LocationPace.probeIntervalMsFor(5))
        assertEquals(600_000L, LocationPace.probeIntervalMsFor(6))
        assertEquals(600_000L, LocationPace.probeIntervalMsFor(60))
    }

    @Test
    fun `默认探测间隔仍是 180 秒 与既有口径一致`() {
        val gate = MotionGate()
        assertEquals(0, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)
    }

    @Test
    fun `无位移探测累计一次 再来一轮就按 5 分钟无证据降级`() {
        val gate = MotionGate()
        val t0 = enterProbing(gate)
        assertEquals("步数增量不是位移，阶梯从 0 起算", 0, gate.quietProbeCount)

        gate.onProbeSample(sample(t0 + 180_000L, 0.0))
        assertEquals("原地没挪：算一次无位移证据的探测", 1, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)

        // 再一轮就到 5 分钟无证据：如实降级（阶梯清零，SLEEPING 由 30 分钟兜底接管）
        gate.onProbeSample(sample(t0 + 360_000L, 0.0))
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertEquals(0, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)
    }

    @Test
    fun `步数增量立刻把探测间隔打回 180 秒`() {
        val gate = MotionGate()
        val t0 = enterProbing(gate)
        gate.onProbeSample(sample(t0 + 180_000L, 0.0))
        assertEquals(1, gate.quietProbeCount)

        gate.onStepCounter(1_020L, t0 + 200_000L)
        assertEquals(0, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)
        assertEquals("走了 10 步还不够远行", MotionGate.State.PROBING, gate.state)
    }

    @Test
    fun `步态证据立刻把探测间隔打回 180 秒`() {
        val gate = MotionGate()
        val t0 = enterProbing(gate)
        gate.onProbeSample(sample(t0 + 180_000L, 0.0))
        assertEquals(1, gate.quietProbeCount)

        gate.onMotionState(MotionDetector.State.GAIT, t0 + 200_000L)
        assertEquals(0, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)
    }

    @Test
    fun `显著运动与抖动不是证据 不动阶梯也不刷活动时间`() {
        // A 根因的回归：拿起手机（显著运动）与手里晃动都不能当成「有人在动」的证据
        val gate = MotionGate()
        val t0 = enterProbing(gate)
        gate.onProbeSample(sample(t0 + 180_000L, 0.0))
        assertEquals(1, gate.quietProbeCount)

        repeat(10) { gate.onSignificantMotion(t0 + 190_000L + it * 1_000L) }
        repeat(10) { gate.onShake(1.5, t0 + 190_000L + it * 1_000L, t0 + 190_000L + it * 1_000L) }

        assertEquals("显著运动 / 抖动不许清阶梯", 1, gate.quietProbeCount)
        assertEquals("活动时间必须停在最后一次真实证据", t0, gate.lastActivityAtMs)
        assertEquals(MotionGate.State.PROBING, gate.state)
    }

    @Test
    fun `位移在噪声地板内继续累计 过地板才清零`() {
        val gate = MotionGate()
        gate.onProbeSample(sample(start, 0.0)) // 锚点，算一次无位移证据的探测
        // 20m < 噪声地板（max(30, 10+10)）：原地抖动，不算位移证据
        gate.onProbeSample(sample(start + 60_000L, 20.0))
        assertEquals(2, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)

        // 100m ≥ 噪声地板：真挪了地方，阶梯清零
        gate.onProbeSample(sample(start + 120_000L, 100.0))
        assertEquals(0, gate.quietProbeCount)
        assertEquals(MotionGate.State.PROBING, gate.state)
    }

    @Test
    fun `reset 清空阶梯`() {
        val gate = MotionGate()
        val t0 = enterProbing(gate)
        gate.onProbeSample(sample(t0 + 180_000L, 0.0))
        assertTrue(gate.quietProbeCount > 0)

        gate.reset()
        assertEquals(0, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)
        assertFalse(gate.wantsSubscription)
    }

    @Test
    fun `退避不改变三态语义 位移 500 米仍升级 MOVING`() {
        val gate = MotionGate()
        val t0 = enterProbing(gate)
        gate.onProbeSample(sample(t0 + 180_000L, 0.0))
        assertEquals(1, gate.quietProbeCount)

        // 坐车走远：探测位移 500m 照样升级，退避不影响升级判据
        gate.onProbeSample(sample(t0 + 240_000L, 500.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue(gate.wantsSubscription)
        assertEquals(0, gate.quietProbeCount)
    }
}
