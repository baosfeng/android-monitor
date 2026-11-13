package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒门槛：**睡着时只有「连续走出一段距离」才开始跟行程**。
 *
 * 用户原话：「我有的时候都没移动，只是拿手机出来玩一玩，然后定位就开始了 ——
 * 是不是得配合传感器检测到我真的移动、或者算到我真的移动了一段距离之后才开始监听？」
 *
 * 三类「有活动但没出门」的输入都必须停在 SLEEPING：
 * - 零步数（拿手机、擦桌子、放口袋里晃）—— 本来就不产生步数增量；
 * - 零散步数（工位起身、走去倒水、上厕所）—— 十到几十步，不够门槛；
 * - 断续累计步数（坐一天时时起身）—— 单次都过不了门槛，加起来也不能过（窗口断档就清零）。
 *
 * 望取 40m（约 57 步、30 秒步行），窗口 2 分钟：容得下路口等一次红灯，
 * 又挡得住「坐一天偶尔起身」。真机 23127PN0CC 上的对照是 motion_detect 在 30 分钟内
 * 触发 9 次（同期用户没离开座位）。
 */
class MotionGateWakeThresholdTest {

    private val start = 1_700_000_000_000L

    /** 步行步长约 0.7m：40m 门槛 ≈ 58 步。 */
    private val wakeSteps = 58L

    private fun gate() = MotionGate().apply { onStepCounter(10_000L, start) }

    @Test
    fun `拿手机不产生步数 门禁停在 SLEEPING`() {
        val gate = gate()
        gate.onSignificantMotion(start + 1_000L)
        gate.onMotionDetect(start + 2_000L)
        gate.onMotionState(MotionDetector.State.HANDHELD, start + 3_000L)
        assertEquals("拿起手机不是远行：一个探测都不该发", MotionGate.State.SLEEPING, gate.state)
        assertFalse(gate.wantsSubscription)
        assertEquals(0L, gate.lastActivityAtMs)
        assertEquals("提示仍然要给：服务靠它开 16Hz 观察窗", start + 2_000L, gate.observationHintAtMs)
    }

    @Test
    fun `起身体走十几步不唤醒`() {
        val gate = gate()
        // 走去倒水：20 步约 14m，不够 40m
        gate.onStepCounter(10_020L, start + 20_000L)
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        assertEquals("零散步数不进任何行程证据", 0L, gate.stepsSinceAnchor)
        assertEquals(0L, gate.lastActivityAtMs)
    }

    @Test
    fun `连续走出门槛才进 PROBING 且只带这一段步数`() {
        val gate = gate()
        gate.onStepCounter(10_030L, start + 10_000L) // 先走 30 步（睡着）
        assertEquals(MotionGate.State.SLEEPING, gate.state)
        // 25 秒后又走 30 步：窗口内累计 60 步 ≈ 42m，过门槛
        gate.onStepCounter(10_060L, start + 35_000L)
        assertEquals(MotionGate.State.PROBING, gate.state)
        assertEquals("应把窗口内这 60 步带进新行程", 60L, gate.stepsSinceAnchor)
        assertEquals(start + 35_000L, gate.lastActivityAtMs)
        assertFalse("40m 还远不够远行", gate.wantsSubscription)
        assertTrue("原因要能看出是连续走路：" + gate.reason, gate.reason.contains("连续走了"))
    }

    @Test
    fun `断续累计步数不能凑出唤醒`() {
        val gate = gate()
        // 坐一天、每隔 5 分钟起身走 40 步（不到 58 步），累计 240 步
        var total = 10_000L
        repeat(6) { i ->
            total += 40L
            gate.onStepCounter(total, start + (i + 1) * 5 * 60_000L)
            assertEquals("单次 40 步 < 门槛，第 $i 次不许唤醒", MotionGate.State.SLEEPING, gate.state)
        }
        assertEquals("窗口断档必须清零，否则一天攒的步数会顶成远行", 0L, gate.stepsSinceAnchor)
    }

    @Test
    fun `唤醒后探测锚点从当前位置重新起算`() {
        val gate = gate()
        // 睡着时的被动点（锚点 A）。别的 App 定位时顺手投给我们，不能当成「我们测到的新位置」
        gate.onProbeSample(sample(start + 1_000L, 0.0), fromProbe = false)
        assertEquals(0.0, gate.probeDistanceM, 0.001)

        gate.onStepCounter(10_060L, start + 40_000L)
        assertEquals(MotionGate.State.PROBING, gate.state)

        // 唤醒后的第一个探测点了在 400m 外：若锚点没清，这里会立刻算出 400m 位移
        // （再过一次就凑成坐车判据，人还在原地就被判成远行）
        gate.onProbeSample(sample(start + 220_000L, 400.0))
        assertEquals("唤醒必须作废睡着期间的锚点", 0.0, gate.probeDistanceM, 0.001)
        // 既有口径：首个点只建锚点，拿不出位移，同样算一次「无位移证据的探测」
        assertEquals("第一个点是新锚点，拿不出位移", 1, gate.quietProbeCount)
        assertEquals(
            "一个新锚点不能升级远行：" + gate.reason,
            MotionGate.State.PROBING,
            gate.state,
        )
        assertFalse(gate.wantsSubscription)
    }

    @Test
    fun `连续走够 500 米照样升级 MOVING`() {
        val gate = gate()
        var total = 10_000L
        var ts = start
        while (total < 10_000 + wakeSteps + 714) {
            total += 1L
            ts += 400L
            gate.onStepCounter(total, ts)
        }
        assertEquals(MotionGate.State.MOVING, gate.state)
        assertTrue(gate.wantsSubscription)
    }

    private fun sample(atMs: Long, meters: Double, accuracy: Float = 10f) =
        LocationSample("network", 31.2304 + meters / 111_000.0, 121.4737, accuracy, atMs)
}
