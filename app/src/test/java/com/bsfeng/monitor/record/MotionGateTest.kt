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
 *
 * 真机实测（23127PN0CC，10:12–11:47）：显著运动在「拿起手机」就触发（20 分钟 6 次），
 * 旧实现把它当活动证据 → MOVING 的 30 秒 GPS 订阅连续挂了 33 分钟、耗电 46mAh。
 * 所以「显著运动 / 抖动不是活动证据」这几条是本次修复的主回归。
 */
class MotionGateTest {

    private val start = 1_700_000_000_000L

    /** 造一个距出发点 [meters] 米（正北方向）的定位点。 */
    private fun sample(atMs: Long, meters: Double, accuracy: Float = 10f) =
        LocationSample("gps", 31.2304 + meters / 111_000.0, 121.4737, accuracy, atMs)

    /** 走到 500m 以上（硬件步数计路径），返回最后一步的时刻。 */
    private fun walkToMoving(gate: MotionGate): Long {
        gate.onStepCounter(1_000L, start)
        var total = 1_000L
        var ts = start
        while (total < 1_750L) {
            total += 1L
            ts += 400L
            gate.onStepCounter(total, ts)
        }
        assertEquals(MotionGate.State.MOVING, gate.state)
        return ts
    }

    @Test
    fun `没有运动证据时保持 SLEEPING 且不建订阅`() {
        val gate = MotionGate()
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        gate.onAccelerometer(0f, 0f, 9.81f, start)
        gate.onTick(start + 5 * 60_000L)
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertFalse("SLEEPING 绝不能建订阅", gate.wantsSubscription)
    }

    // ===== A 根因：显著运动 / 抖动不是活动证据 =====

    @Test
    fun `显著运动不把 SLEEPING 变 PROBING 只留诊断与观察窗提示`() {
        val gate = MotionGate()
        gate.onSignificantMotion(start)
        gate.onSignificantMotion(start + 60_000L)
        assertEquals("拿起手机不是运动证据", MotionGate.State.SLEEPING, gate.state)
        assertFalse("未确认远行不能建订阅", gate.wantsSubscription)
        assertEquals("诊断要能看出它什么时候被触发", start + 60_000L, gate.lastSignificantMotionAtMs)
        assertEquals("服务靠这个提示开 16Hz 观察窗", start + 60_000L, gate.observationHintAtMs)
        assertEquals("显著运动不许打点活动时间", 0L, gate.lastActivityAtMs)
        assertEquals("迁移原因不该被它改写", "尚无运动证据", gate.reason)
    }

    @Test
    fun `显著运动不影响 PROBING 的 5 分钟空闲降级`() {
        val gate = MotionGate()
        gate.onProbeSample(sample(start, 0.0))
        gate.onProbeSample(sample(start + 30_000L, 100.0))
        assertEquals(MotionGate.State.PROBING, gate.state)

        // 站着不动但反复拿起手机（显著运动 + 手持抖动），没有任何步数与位移
        var ts = start + 30_000L
        val quiet = ts
        while (ts <= quiet + 6 * 60_000L) {
            gate.onSignificantMotion(ts)
            gate.onMotionState(MotionDetector.State.HANDHELD, ts)
            if ((ts - quiet) % 30_000L == 0L) gate.onTick(ts)
            ts += 200L
        }
        assertEquals("拿起手机不能把降级计时永远拖住", MotionGate.State.SLEEPING, gate.state)
        assertTrue("降级原因应说明无步数无位移：" + gate.reason, gate.reason.contains("无步数"))
    }

    @Test
    fun `MOVING 期间反复拿起手机 仍按 5 分钟无真实活动降级`() {
        val gate = MotionGate()
        val stop = walkToMoving(gate)
        assertTrue(gate.wantsSubscription)

        // 停下玩手机：显著运动每 30 秒触发一次，加速度计只是随机抖动（无稳定步频）
        val random = Random(11)
        var ts = stop
        while (ts <= stop + 6 * 60_000L) {
            gate.onAccelerometer(0f, 0f, 9.81f + random.nextFloat() * 0.8f - 0.4f, ts)
            gate.onMotionState(MotionDetector.State.HANDHELD, ts)
            if ((ts - stop) % 30_000L == 0L) {
                gate.onSignificantMotion(ts)
                gate.onTick(ts)
            }
            ts += 200L
        }
        assertEquals("拿起手机不是活动证据：5 分钟必须降级", MotionGate.State.SLEEPING, gate.state)
        assertFalse("降级后必须释放订阅", gate.wantsSubscription)
    }

    @Test
    fun `持续抖动不唤醒 只给观察窗提示`() {
        // 没有显著运动传感器的设备：抖动只是「可能有人在动」的提示，服务据此开 16Hz 观察窗
        val gate = MotionGate()
        var ts = start
        repeat(60) { // 12 秒，200ms 一个样本
            gate.onShake(1.2, ts, ts)
            ts += 200L
        }
        assertEquals("抖动不是运动证据", MotionGate.State.SLEEPING, gate.state)
        assertFalse("抖动唤醒不建订阅", gate.wantsSubscription)
        assertEquals("抖动不许打点活动时间", 0L, gate.lastActivityAtMs)
        assertTrue("确认抖动后要给观察窗提示", gate.observationHintAtMs >= start + 5_000L)
        assertEquals(gate.observationHintAtMs, gate.lastShakeAtMs)
    }

    @Test
    fun `抖动低于阈值不给提示`() {
        val gate = MotionGate()
        var ts = start
        repeat(100) { // 20 秒
            gate.onShake(0.5, ts, ts)
            ts += 200L
        }
        assertEquals("0.5 低于 0.8 阈值", MotionGate.State.SLEEPING, gate.state)
        assertEquals("没确认抖动就不该开观察窗", 0L, gate.observationHintAtMs)
    }

    @Test
    fun `玩手机 10 分钟有抖动无步数 不升级也不建订阅`() {
        val gate = MotionGate()
        val random = Random(7)
        var ts = start
        while (ts <= start + 10 * 60_000L) {
            // 玩手机：随机抖动、无稳定步频（与 MotionDetector 真机标定一致）
            gate.onAccelerometer(0f, 0f, 9.81f + random.nextFloat() * 0.8f - 0.4f, ts)
            gate.onMotionState(MotionDetector.State.HANDHELD, ts)
            gate.onShake(random.nextDouble(), ts, ts)
            if ((ts - start) % 30_000L == 0L) gate.onSignificantMotion(ts)
            ts += 200L
        }
        assertNotEquals("玩手机不能被当成远行", MotionGate.State.MOVING, gate.state)
        assertFalse("未确认远行绝不能建订阅", gate.wantsSubscription)
        assertTrue(
            "软件步数不能把玩手机凑成 500m，实际 " + gate.stepDistanceM + "m",
            gate.stepDistanceM < 100.0,
        )
    }

    // ===== 升级路径（只认步态 / 步数 / 位移）=====

    @Test
    fun `步行 750 步走完 500m 升级 MOVING`() {
        val gate = MotionGate()
        walkToMoving(gate)
        assertTrue("确认远行后必须建订阅", gate.wantsSubscription)
        assertTrue("累计步数距离应过 500m，实际 " + gate.stepDistanceM + "m", gate.stepDistanceM >= 500.0)
        assertTrue("原因应说明步数：" + gate.reason, gate.reason.contains("步数"))
    }

    @Test
    fun `步态证据把门禁从 SLEEPING 拉到 PROBING`() {
        // 显著运动不再唤醒门禁，GAIT 是「真的在走」的第一手证据
        val gate = MotionGate()
        gate.onMotionState(MotionDetector.State.GAIT, start)
        assertEquals(MotionGate.State.PROBING, gate.state)
        assertFalse("步态还不足以建订阅", gate.wantsSubscription)
        assertTrue("原因应说明步频：" + gate.reason, gate.reason.contains("步频"))
        assertEquals(start, gate.lastActivityAtMs)
    }

    @Test
    fun `坐车 无步数 探测位移 1km 升级 MOVING`() {
        val gate = MotionGate()
        gate.onProbeSample(sample(start, 0.0))
        gate.onProbeSample(sample(start + 60_000L, 1_000.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue(gate.wantsSubscription)
        assertTrue("原因应说明探测位移：" + gate.reason, gate.reason.contains("探测位移"))
    }

    @Test
    fun `无步数传感器时 软件步数可以累计到 500m`() {
        val gate = MotionGate()
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

    // ===== 降级路径 =====

    @Test
    fun `静止 5 分钟回 SLEEPING 并清空行程锚点`() {
        val gate = MotionGate()
        val moving = walkToMoving(gate)
        assertTrue(gate.stepsSinceAnchor > 0L)

        var ts = moving
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
    fun `无步数且无位移 5 分钟 也回 SLEEPING`() {
        // 不喂 STILL 证据（例如观察窗关了、加速度计没数据），
        // 只要 5 分钟没有步数与位移，同样必须降级，否则 PROBING 会永远挂着。
        val gate = MotionGate()
        gate.onProbeSample(sample(start, 0.0))
        gate.onProbeSample(sample(start + 30_000L, 100.0))
        assertEquals(MotionGate.State.PROBING, gate.state)

        gate.onTick(start + 30_000L + 4 * 60_000L)
        assertEquals(MotionGate.State.PROBING, gate.state)
        gate.onTick(start + 30_000L + 5 * 60_000L + 1_000L)
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertTrue("降级原因应说明无步数无位移：" + gate.reason, gate.reason.contains("无步数"))
    }

    @Test
    fun `探测 300m 但不满 10 分钟不升级 满 10 分钟才升级`() {
        val gate = MotionGate()
        gate.onProbeSample(sample(start, 0.0))
        // 100m 过噪声地板：进 PROBING，持续移动从此刻起算
        gate.onProbeSample(sample(start + 60_000L, 100.0))
        assertEquals(MotionGate.State.PROBING, gate.state)

        gate.onProbeSample(sample(start + 5 * 60_000L, 200.0))
        gate.onProbeSample(sample(start + 9 * 60_000L, 300.0))
        assertEquals("9 分钟 300m 还不够「持续移动 10 分钟」", MotionGate.State.PROBING, gate.state)

        gate.onProbeSample(sample(start + 11 * 60_000L + 1_000L, 300.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue("原因应说明持续时间：" + gate.reason, gate.reason.contains("持续移动"))
    }

    @Test
    fun `探测 400m 不升级 500m 才升级`() {
        val gate = MotionGate()
        gate.onProbeSample(sample(start, 0.0))
        gate.onProbeSample(sample(start + 60_000L, 400.0))
        assertEquals(MotionGate.State.PROBING, gate.state)
        gate.onProbeSample(sample(start + 120_000L, 500.0))
        assertEquals(MotionGate.State.MOVING, gate.state)
    }

    @Test
    fun `硬件步数首次读取只建基线 不算成位移`() {
        val gate = MotionGate()
        gate.onStepCounter(5_000L, start)
        assertEquals("累计值不能一次性算成远行证据", 0L, gate.stepsSinceAnchor)
        assertEquals(0.0, gate.stepDistanceM, 0.001)
        assertEquals("没有证据就不该唤醒", MotionGate.State.SLEEPING, gate.state)

        gate.onStepCounter(5_010L, start + 60_000L)
        assertEquals(10L, gate.stepsSinceAnchor)
        assertEquals(MotionGate.State.PROBING, gate.state)
    }

    @Test
    fun `MOVING 单向保持 证据回退不满 5 分钟不降级`() {
        val gate = MotionGate()
        val moving = walkToMoving(gate)
        // 步数停了、探测点回到出发点附近（10m 在噪声地板内），但还没满 5 分钟
        gate.onProbeSample(sample(moving + 30_000L, 10.0))
        gate.onTick(moving + 60_000L)
        assertEquals("未满 5 分钟不能把已确认的行程关掉", MotionGate.State.MOVING, gate.state)
    }

    @Test
    fun `reset 清空状态与证据`() {
        val gate = MotionGate()
        walkToMoving(gate)
        gate.onSignificantMotion(start + 90_000L)
        gate.onShake(1.5, start + 90_000L, start + 90_000L)

        gate.reset()
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertFalse(gate.wantsSubscription)
        assertEquals(0L, gate.stepsSinceAnchor)
        assertEquals(0.0, gate.probeDistanceM, 0.001)
        assertEquals(0L, gate.lastActivityAtMs)
        assertEquals(0L, gate.lastSignificantMotionAtMs)
        assertEquals(0L, gate.lastShakeAtMs)
        assertEquals(0L, gate.observationHintAtMs)
    }
}
