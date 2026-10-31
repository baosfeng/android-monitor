package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 定位功耗门禁：只有「确认真的人移动了很远」才允许建立位置订阅。
 *
 * 用户原话：拿起手机，定位就一直开着。所以核心回归是「玩手机不升级」「没证据不建订阅」，
 * 以及「步行 / 坐车真的走远才升级」「停下 5 分钟必须复位」。
 */
class MotionGateTest {

    private val start = 1_700_000_000_000L

    /** 造一个距出发点 [meters] 米（正北方向）的定位点。 */
    private fun sample(atMs: Long, meters: Double, accuracy: Float = 10f) =
        LocationSample("gps", 31.2304 + meters / 111_000.0, 121.4737, accuracy, atMs)

    @Test
    fun `没有运动证据时保持 SLEEPING 且不建订阅`() {
        val gate = MotionGate()
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        gate.onAccelerometer(0f, 0f, 9.81f, start)
        gate.onTick(start + 5 * 60_000L)
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertFalse("SLEEPING 绝不能建订阅", gate.wantsSubscription)
    }

    @Test
    fun `显著运动触发只进 PROBING 不建订阅`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        assertEquals(MotionGate.State.PROBING, gate.state)
        assertFalse("未确认远行不能建订阅", gate.wantsSubscription)
        assertTrue("原因应说明是显著运动触发：" + gate.reason, gate.reason.contains("显著运动"))
    }

    @Test
    fun `玩手机 10 分钟有抖动无步数 不升级也不建订阅`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        assertEquals(MotionGate.State.PROBING, gate.state)

        val random = Random(7)
        var ts = start
        while (ts <= start + 10 * 60_000L) {
            // 玩手机：随机抖动、无稳定步频（与 MotionDetector 真机标定一致）
            gate.onAccelerometer(0f, 0f, 9.81f + random.nextFloat() * 0.8f - 0.4f, ts)
            gate.onMotionState(MotionDetector.State.HANDHELD, ts)
            if ((ts - start) % 30_000L == 0L) gate.onTick(ts)
            ts += 200L
        }
        assertNotEquals("玩手机不能被当成远行", MotionGate.State.MOVING, gate.state)
        assertFalse("未确认远行绝不能建订阅", gate.wantsSubscription)
        assertTrue(
            "软件步数不能把玩手机凑成 500m，实际 " + gate.stepDistanceM + "m",
            gate.stepDistanceM < 100.0,
        )
    }

    @Test
    fun `步行 750 步走完 500m 升级 MOVING`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onStepCounter(1_000L, start)

        var total = 1_000L
        var ts = start
        while (total < 1_750L) {
            total += 1L
            ts += 400L
            gate.onStepCounter(total, ts)
        }
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue("确认远行后必须建订阅", gate.wantsSubscription)
        assertTrue("累计步数距离应过 500m，实际 " + gate.stepDistanceM + "m", gate.stepDistanceM >= 500.0)
        assertTrue("原因应说明步数：" + gate.reason, gate.reason.contains("步数"))
    }

    @Test
    fun `坐车 无步数 探测位移 1km 升级 MOVING`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onProbeSample(sample(start, 0.0))
        gate.onProbeSample(sample(start + 60_000L, 1_000.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue(gate.wantsSubscription)
        assertTrue("原因应说明探测位移：" + gate.reason, gate.reason.contains("探测位移"))
    }

    @Test
    fun `无步数传感器时 软件步数可以累计到 500m`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)

        // 模拟器常见路径：没有 TYPE_STEP_COUNTER，只能靠加速度峰值推步数。
        // 2Hz 正弦是走路模长的理想化形态（1 个周期 1 步）。
        var ts = start
        val end = start + 400_000L
        while (ts <= end) {
            val elapsedSec = (ts - start) / 1000.0
            val magnitude = (9.81 + sin(2.0 * PI * 2.0 * elapsedSec)).toFloat()
            gate.onAccelerometer(0f, 0f, magnitude, ts)
            ts += 62L
        }
        assertTrue("软件步数应累计 700 步以上，实际 " + gate.stepsSinceAnchor, gate.stepsSinceAnchor >= 700L)
        assertTrue("软件步数距离应过 500m，实际 " + gate.stepDistanceM + "m", gate.stepDistanceM >= 500.0)
        assertEquals(MotionGate.State.MOVING, gate.state)
    }

    @Test
    fun `BATCHED 模式下持续明显抖动会唤醒 PROBING`() {
        // 没有显著运动传感器、加速度计只能 5Hz batching 的设备：MotionDetector 判不出步态，
        // 只能靠「标准差持续偏高」把门禁从 SLEEPING 拉起来，否则永远不升级。
        val gate = MotionGate()
        assertEquals(MotionGate.State.SLEEPING, gate.state)

        var ts = start
        repeat(60) { // 12 秒，200ms 一个样本
            gate.onShake(1.2, ts, ts)
            ts += 200L
        }
        assertEquals(MotionGate.State.PROBING, gate.state)
        assertFalse("抖动唤醒不建订阅", gate.wantsSubscription)
        assertTrue("唤醒原因应说明抖动：" + gate.reason, gate.reason.contains("抖动"))
    }

    @Test
    fun `抖动低于阈值不会唤醒`() {
        val gate = MotionGate()
        var ts = start
        repeat(100) { // 20 秒
            gate.onShake(0.5, ts, ts)
            ts += 200L
        }
        assertEquals("0.5 低于 0.8 阈值，不能唤醒", MotionGate.State.SLEEPING, gate.state)
    }

    @Test
    fun `已在 PROBING 时抖动不重复迁移`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        val reasonBefore = gate.reason

        var ts = start
        repeat(60) {
            gate.onShake(1.5, ts, ts)
            ts += 200L
        }
        assertEquals(MotionGate.State.PROBING, gate.state)
        assertEquals("抖动唤醒不能覆盖已有的迁移原因", reasonBefore, gate.reason)
    }

    @Test
    fun `SLEEPING 下软件步数也能唤醒门禁并升级`() {
        // 无显著运动传感器的退化模式：加速度计常开，走路必须自己把门禁从 SLEEPING 拉起来
        val gate = MotionGate()
        var ts = start
        val end = start + 400_000L
        while (ts <= end) {
            val elapsedSec = (ts - start) / 1000.0
            val magnitude = (9.81 + sin(2.0 * PI * 2.0 * elapsedSec)).toFloat()
            gate.onAccelerometer(0f, 0f, magnitude, ts)
            ts += 62L
        }
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue(gate.wantsSubscription)
    }

    @Test
    fun `静止 5 分钟回 SLEEPING 并清空行程锚点`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onStepCounter(1_000L, start)
        gate.onStepCounter(1_750L, start + 60_000L)
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue(gate.stepsSinceAnchor > 0L)

        var ts = start + 60_000L
        val quietStart = ts
        while (ts <= quietStart + 6 * 60_000L) {
            gate.onAccelerometer(0f, 0f, 9.81f, ts)
            gate.onMotionState(MotionDetector.State.STILL, ts)
            if ((ts - quietStart) % 30_000L == 0L) gate.onTick(ts)
            ts += 200L
        }
        assertEquals("静止 5 分钟必须回 SLEEPING", MotionGate.State.SLEEPING, gate.state)
        assertFalse(gate.wantsSubscription)
        assertEquals("降级必须清空步数锚点", 0L, gate.stepsSinceAnchor)
        assertEquals("降级必须清空探测锚点", 0.0, gate.probeDistanceM, 0.001)
        assertTrue("降级原因应说明静止：" + gate.reason, gate.reason.contains("静止"))
    }

    @Test
    fun `探测 300m 但不满 10 分钟不升级 满 10 分钟才升级`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onProbeSample(sample(start, 0.0))
        gate.onProbeSample(sample(start + 3 * 60_000L, 100.0))
        gate.onProbeSample(sample(start + 6 * 60_000L, 200.0))
        gate.onProbeSample(sample(start + 9 * 60_000L, 300.0))
        assertEquals("9 分钟 300m 还不够「持续移动 10 分钟」", MotionGate.State.PROBING, gate.state)

        gate.onProbeSample(sample(start + 10 * 60_000L + 1_000L, 300.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue("原因应说明持续时间：" + gate.reason, gate.reason.contains("持续移动"))
    }

    @Test
    fun `探测 400m 不升级 500m 才升级`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onProbeSample(sample(start, 0.0))
        gate.onProbeSample(sample(start + 60_000L, 400.0))
        assertEquals(MotionGate.State.PROBING, gate.state)
        gate.onProbeSample(sample(start + 120_000L, 500.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
    }

    @Test
    fun `硬件步数首次读取只建基线 不算成位移`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onStepCounter(5_000L, start + 1_000L)
        assertEquals(0L, gate.stepsSinceAnchor)
        assertEquals(0.0, gate.stepDistanceM, 0.001)
        assertEquals(MotionGate.State.PROBING, gate.state)
    }

    @Test
    fun `MOVING 单向保持 证据回退不满 5 分钟不降级`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onStepCounter(0L, start)
        gate.onStepCounter(800L, start + 60_000L)
        assertEquals(MotionGate.State.MOVING, gate.state)

        // 步数停了、探测点回到出发点附近（10m 在噪声地板内），但还没满 5 分钟
        gate.onProbeSample(sample(start + 90_000L, 10.0))
        gate.onTick(start + 120_000L)
        assertEquals("未满 5 分钟不能把已确认的行程关掉", MotionGate.State.MOVING, gate.state)
    }

    @Test
    fun `reset 清空状态与证据`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onStepCounter(0L, start)
        gate.onStepCounter(800L, start + 60_000L)
        assertEquals(MotionGate.State.MOVING, gate.state)

        gate.reset()
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertFalse(gate.wantsSubscription)
        assertEquals(0L, gate.stepsSinceAnchor)
        assertEquals(0.0, gate.probeDistanceM, 0.001)
        assertEquals(0L, gate.lastActivityAtMs)
    }

    @Test
    fun `静态降级 无步数且无位移 5 分钟 也回 SLEEPING`() {
        // 不喂 STILL 证据（例如显著运动传感器触发了但加速度计没开），
        // 只要 5 分钟没有步数与位移，同样必须降级，否则 PROBING 会永远挂着。
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onTick(start + 4 * 60_000L)
        assertEquals(MotionGate.State.PROBING, gate.state)
        gate.onTick(start + 5 * 60_000L + 1_000L)
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertTrue("降级原因应说明无步数无位移：" + gate.reason, gate.reason.contains("无步数"))
    }
}
