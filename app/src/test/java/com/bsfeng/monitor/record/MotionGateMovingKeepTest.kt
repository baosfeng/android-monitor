package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 省电第二轮的三条回归（真机 11-12 数据驱动）：
 *
 * 1. MOVING 只按「5 分钟无活动」降级 → 办公室里每 5 分钟走几步就永不降级，
 *    30 秒订阅一挂一整天（实测 8 小时订阅 / network 1095 次 / gps 645 次）；
 * 2. 探测点被质量闸拒绝后不喂门禁 → 既不升级也不进退避阶梯，门禁永远 180 秒探测；
 * 3. 质量闸拒绝「与上一点重合的低置信点」→ 人整天待在原地一个点不落，行程判成「采集中断」。
 */
class MotionGateMovingKeepTest {

    private val start = 1_700_000_000_000L

    private fun sample(atMs: Long, meters: Double, provider: String = "gps", accuracy: Float = 10f) =
        LocationSample(provider, 31.2304 + meters / 111_000.0, 121.4737, accuracy, atMs)

    /** 步数走到 500m 以上，进入 MOVING。返回升级那一刻。 */
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
    fun `MOVING 三十分钟没有三百米位移就退回 PROBING`() {
        val gate = MotionGate()
        val enteredAt = walkToMoving(gate)
        // 办公室来回走动：每 3 分钟 5 步。有「活动」，但没有远行实质。
        var total = 1_750L
        var ts = enteredAt
        repeat(10) {
            ts += 180_000L
            total += 5L
            gate.onStepCounter(total, ts)
        }
        assertEquals(
            "有活动不等于在远行：退回 PROBING 收回 30 秒订阅",
            MotionGate.State.PROBING,
            gate.state,
        )
        assertFalse("退回 PROBING 必须释放订阅", gate.wantsSubscription)
        assertTrue("降级原因要能看出是位移规模不足：${gate.reason}", gate.reason.contains("300m"))
    }

    @Test
    fun `MOVING 期间持续有三百米以上位移就保持 MOVING`() {
        val gate = MotionGate()
        val enteredAt = walkToMoving(gate)
        var ts = enteredAt
        // 坐车：每 5 分钟相对锚点 600m 以上
        var meters = 0.0
        repeat(6) {
            ts += 5 * 60_000L
            meters += 600.0
            gate.onProbeSample(sample(ts, meters))
        }
        assertEquals("车上就该继续 30 秒订阅", MotionGate.State.MOVING, gate.state)
        assertTrue(gate.wantsSubscription)
    }

    @Test
    fun `退回 PROBING 会清空锚点 不会用旧锚点立刻顶回 MOVING`() {
        val gate = MotionGate()
        val enteredAt = walkToMoving(gate)
        var total = 1_750L
        var ts = enteredAt
        repeat(10) {
            ts += 180_000L
            total += 5L
            gate.onStepCounter(total, ts)
        }
        assertEquals(MotionGate.State.PROBING, gate.state)
        // 清锚点后重新从当前位置评估：没有 500m 位移就不许再升
        gate.onProbeSample(sample(ts + 30_000L, 0.0))
        gate.onProbeSample(sample(ts + 60_000L, 100.0))
        assertEquals(MotionGate.State.PROBING, gate.state)
    }
}

/**
 * 硬件运动 / 静止检测（API 29 一次性触发传感器）作为门禁事件源。
 *
 * 这是「定时轮询 → 事件驱动」的落点：人没动就不该烧射频（真机 8 小时 1095 次 network 请求）。
 */
class MotionGateHardwareDetectTest {

    private val start = 1_700_000_000_000L

    @Test
    fun `硬件运动检测把 SLEEPING 拉到 PROBING`() {
        val gate = MotionGate()
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertEquals(MotionGate.State.PROBING, gate.onMotionDetect(start))
        assertEquals("硬件判定的运动是证据：阶梯归零", 0, gate.quietProbeCount)
        assertEquals("活动时间要被刷新", start, gate.lastActivityAtMs)
    }

    @Test
    fun `硬件静止检测把 PROBING 拉回 SLEEPING`() {
        val gate = MotionGate()
        gate.onMotionDetect(start)
        assertEquals(MotionGate.State.SLEEPING, gate.onStationaryDetect(start + 60_000L))
        assertFalse(gate.wantsSubscription)
    }

    @Test
    fun `硬件静止检测把 MOVING 也拉回 SLEEPING 并清锚点`() {
        val gate = MotionGate()
        gate.onStepCounter(1_000L, start)
        var total = 1_000L
        var ts = start
        while (total < 1_750L) {
            total += 1L
            ts += 400L
            gate.onStepCounter(total, ts)
        }
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertEquals(MotionGate.State.SLEEPING, gate.onStationaryDetect(ts + 60_000L))
        assertFalse("静止检测后必须释放订阅", gate.wantsSubscription)
        assertEquals("锚点要清：否则旧锚点会把 PROBING 一路顶回 MOVING", 0.0, gate.probeDistanceM, 0.0)
    }
}

/**
 * 主动定位结果分派：被拒的点也要喂门禁；原地确认点要落库但不覆盖质量闸对照点。
 */
class LocationActiveFixRejectTest {

    private val now = 1_700_000_000_000L

    private val plan = LocationGapHealing.plan(now - 1_000L, now, SampleMode.STILL)

    /** 更优的对照点（GPS 10m）。 */
    private fun previous() = LocationSample("gps", 31.2304, 121.4737, 10f, now - 60_000L)

    /** 会被质量闸拒的候选点：network 200m 且与对照点重合。 */
    private fun weakCandidate() = LocationSample("network", 31.2304, 121.4737, 200f, now)

    @Test
    fun `质量闸拒绝的点仍然交给门禁当位移证据`() {
        val rejected = mutableListOf<LocationSample>()
        var delivered = false
        val runner = LocationActiveFixRunner(
            request = { _, _, onResult -> onResult(weakCandidate()) },
            onRejected = { rejected += it },
        )
        runner.run(
            nowMs = now,
            plan = plan,
            modeAtStart = SampleMode.STILL,
            previous = previous(),
            onDeliver = { _, _ -> delivered = true },
            onSample = { },
        )
        assertEquals("被拒点必须喂门禁，否则探测永不退避", 1, rejected.size)
        assertFalse("被拒点不落库", delivered)
    }

    @Test
    fun `超出确认窗口时原地确认点落库并标记原地`() {
        var deliverInPlace: Boolean? = null
        val runner = LocationActiveFixRunner(
            request = { _, _, onResult -> onResult(weakCandidate()) },
            allowInPlaceConfirm = { true },
        )
        runner.run(
            nowMs = now,
            plan = plan,
            modeAtStart = SampleMode.STILL,
            previous = previous(),
            onDeliver = { _, inPlace -> deliverInPlace = inPlace },
            onSample = { },
        )
        assertEquals("原地确认点要落库", true, deliverInPlace)
    }
}
