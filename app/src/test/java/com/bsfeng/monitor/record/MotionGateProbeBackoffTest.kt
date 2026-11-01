package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测间隔自适应退避：连续拿不到位移证据就把探测间隔从 180 秒拉到 300 / 600 秒，任何运动证据立刻回 180 秒。
 *
 * 为什么需要：PROBING（玩手机 / 室内走动）只要有弱活动证据就能一直停着，按 180 秒一轮探测，
 * 一小时 20 次、每次最多 20 秒射频 —— 而这一小时往往什么都没发生。
 *
 * 注意「保持 PROBING 不降级」需要活动证据，而唯一不影响退避阶梯的活动证据是显著运动：
 * 步数 / 步态 / 抖动 / 位移全都属于「运动证据」，会立刻把阶梯清零（这是设计，不是漏洞）。
 */
class MotionGateProbeBackoffTest {

    private val start = 1_700_000_000_000L

    private fun sample(atMs: Long, meters: Double, accuracy: Float = 10f) =
        LocationSample("gps", 31.2304 + meters / 111_000.0, 121.4737, accuracy, atMs)

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
    fun `默认探测间隔仍是 180 秒 与既有契约一致`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        assertEquals(0, gate.quietProbeCount)
        assertEquals(LocationPace.HANDHELD_PROBE_INTERVAL_MS, gate.probeIntervalMs)
    }

    @Test
    fun `连续无位移证据的探测按 180 到 300 到 600 阶梯退避`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        val ladder = mutableListOf<Long>()
        repeat(8) {
            gate.onProbeSample(sample(ts, 0.0))
            ladder += gate.probeIntervalMs
            ts += 60_000L
            // 反复拿起手机：有活动（不降级），但不是位移证据（不重置阶梯）
            gate.onSignificantMotion(ts)
        }
        assertEquals(
            listOf(180_000L, 180_000L, 300_000L, 300_000L, 300_000L, 600_000L, 600_000L, 600_000L),
            ladder,
        )
        assertEquals(8, gate.quietProbeCount)
        assertEquals("退避期间仍是 PROBING，不建订阅", false, gate.wantsSubscription)
    }

    @Test
    fun `步数增量立刻把探测间隔打回 180 秒`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        gate.onStepCounter(1_000L, ts)
        repeat(6) {
            gate.onProbeSample(sample(ts, 0.0))
            ts += 30_000L
            gate.onSignificantMotion(ts)
        }
        assertEquals(600_000L, gate.probeIntervalMs)

        gate.onStepCounter(1_010L, ts)
        assertEquals(0, gate.quietProbeCount)
        assertEquals(180_000L, gate.probeIntervalMs)
        assertEquals("走了 10 步还不够远行", MotionGate.State.PROBING, gate.state)
    }

    @Test
    fun `步态证据立刻把探测间隔打回 180 秒`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        repeat(6) {
            gate.onProbeSample(sample(ts, 0.0))
            ts += 30_000L
            gate.onSignificantMotion(ts)
        }
        assertEquals(600_000L, gate.probeIntervalMs)

        gate.onMotionState(MotionDetector.State.GAIT, ts)
        assertEquals(180_000L, gate.probeIntervalMs)
    }

    @Test
    fun `抖动证据立刻把探测间隔打回 180 秒`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        repeat(6) {
            gate.onProbeSample(sample(ts, 0.0))
            ts += 30_000L
            gate.onSignificantMotion(ts)
        }
        assertEquals(600_000L, gate.probeIntervalMs)

        gate.onShake(1.5, ts, ts)
        assertEquals(180_000L, gate.probeIntervalMs)
    }

    @Test
    fun `位移过噪声地板才是证据 噪声内的位移继续退避`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        gate.onProbeSample(sample(ts, 0.0)) // 锚点
        // 20m < 噪声地板（max(30, 10+10)）：原地抖动，不算位移证据
        ts += 180_000L
        gate.onSignificantMotion(ts)
        gate.onProbeSample(sample(ts, 20.0))
        assertEquals(2, gate.quietProbeCount)

        // 100m ≥ 噪声地板：真挪了地方，阶梯清零
        ts += 180_000L
        gate.onSignificantMotion(ts)
        gate.onProbeSample(sample(ts, 100.0))
        assertEquals(0, gate.quietProbeCount)
        assertEquals(180_000L, gate.probeIntervalMs)
    }

    @Test
    fun `降级回 SLEEPING 时阶梯清零`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        repeat(5) {
            gate.onProbeSample(sample(ts, 0.0))
            ts += 30_000L
        }
        assertEquals(300_000L, gate.probeIntervalMs)

        // 5 分钟没有任何活动证据 → 降级（SLEEPING 由 30 分钟兜底接管，阶梯重新起算）
        gate.onTick(ts + LocationPace.STILL_INTERVAL_MS)
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertEquals(0, gate.quietProbeCount)
        assertEquals(180_000L, gate.probeIntervalMs)
    }

    @Test
    fun `reset 清空阶梯`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        repeat(6) {
            gate.onProbeSample(sample(ts, 0.0))
            ts += 30_000L
            gate.onSignificantMotion(ts)
        }
        assertTrue(gate.quietProbeCount > 0)

        gate.reset()
        assertEquals(0, gate.quietProbeCount)
        assertEquals(180_000L, gate.probeIntervalMs)
        assertFalse(gate.wantsSubscription)
    }

    @Test
    fun `退避不改变三态语义 位移 500 米仍升级 MOVING`() {
        val gate = MotionGate()
        var ts = start
        gate.onSignificantMotion(ts)
        repeat(6) {
            gate.onProbeSample(sample(ts, 0.0))
            ts += 30_000L
            gate.onSignificantMotion(ts)
        }
        assertEquals(600_000L, gate.probeIntervalMs)
        assertEquals(MotionGate.State.PROBING, gate.state)

        // 坐车走远：探测位移 500m 照样升级，退避不影响升级判据
        gate.onProbeSample(sample(ts + 60_000L, 500.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue(gate.wantsSubscription)
        assertEquals(0, gate.quietProbeCount)
    }
}
