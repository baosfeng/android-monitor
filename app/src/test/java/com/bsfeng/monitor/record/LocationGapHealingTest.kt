package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断档自愈判定（纯逻辑）：正常节奏 / 5 分钟断档 / 30 分钟以上断档 / 时钟回拨。
 *
 * 反向验证（见 .open-design/impl-notes/location-fix.md）：把 [LocationGapHealing.plan] 改成
 * 旧的「永远 NONE」，[fiveMinuteGapForcesActiveFix] 与 [deepGapRestartsSubscription] 会变红。
 */
class LocationGapHealingTest {

    private val now = 1_700_000_000_000L

    // ===== 正常路径 =====

    @Test
    fun `刚采过不触发自愈`() {
        val plan = LocationGapHealing.plan(now - 5_000L, now, SampleMode.MOVING)
        assertEquals(LocationGapHealing.Level.NONE, plan.level)
        assertFalse(plan.forceActiveFix)
        assertFalse(plan.restartSubscription)
        assertFalse(plan.isGap)
    }

    @Test
    fun `移动模式按45秒阈值判断 静止模式按270秒`() {
        assertEquals(45_000L, LocationGapHealing.shortGapThresholdMs(SampleMode.MOVING))
        assertEquals(270_000L, LocationGapHealing.shortGapThresholdMs(SampleMode.STILL))
        // 移动：50 秒前采过就算断档；静止：同样 50 秒完全正常
        assertTrue(LocationGapHealing.plan(now - 50_000L, now, SampleMode.MOVING).isGap)
        assertFalse(LocationGapHealing.plan(now - 50_000L, now, SampleMode.STILL).isGap)
    }

    // ===== 边界 =====

    @Test
    fun `正好卡在45秒阈值上不算断档`() {
        val plan = LocationGapHealing.plan(now - 45_000L, now, SampleMode.MOVING)
        assertEquals(LocationGapHealing.Level.NONE, plan.level)
        assertTrue(LocationGapHealing.plan(now - 45_001L, now, SampleMode.MOVING).isGap)
    }

    @Test
    fun `正好卡在30分钟按深断档处理`() {
        val plan = LocationGapHealing.plan(now - LocationGapHealing.DEEP_GAP_MS, now, SampleMode.STILL)
        assertEquals(LocationGapHealing.Level.DEEP, plan.level)
        // 差 1ms 只是浅断档
        assertEquals(
            LocationGapHealing.Level.SHORT,
            LocationGapHealing.plan(now - LocationGapHealing.DEEP_GAP_MS + 1, now, SampleMode.STILL).level,
        )
    }

    // ===== 断档场景（反向验证锚点） =====

    @Test
    fun `fiveMinuteGapForcesActiveFix`() {
        // 原 bug：静止模式断了 5 分钟，没有任何补偿，要等下一个 3 分钟周期才可能恢复
        val plan = LocationGapHealing.plan(now - 5 * 60_000L, now, SampleMode.STILL)
        assertEquals(LocationGapHealing.Level.SHORT, plan.level)
        assertTrue("断档必须强制补一次主动定位", plan.forceActiveFix)
        assertFalse("5 分钟还不需要重建订阅", plan.restartSubscription)
        assertTrue(plan.isGap)
    }

    @Test
    fun `deepGapRestartsSubscription`() {
        // 原 bug：Doze 30 分钟后系统可能已摘掉监听，旧实现既不重建也不清基线
        val plan = LocationGapHealing.plan(now - 45 * 60_000L, now, SampleMode.STILL)
        assertEquals(LocationGapHealing.Level.DEEP, plan.level)
        assertTrue(plan.forceActiveFix)
        assertTrue("深断档必须重建订阅", plan.restartSubscription)
        assertTrue("深断档要清掉节流与模式基线", plan.resetBaseline)
    }

    @Test
    fun `浅断档不清基线`() {
        val plan = LocationGapHealing.plan(now - 5 * 60_000L, now, SampleMode.STILL)
        assertFalse("浅断档只补一次，不该丢掉 3 次静止计数的进展", plan.resetBaseline)
    }

    // ===== 异常路径 =====

    @Test
    fun `时钟回拨不补采也不误判断档`() {
        val plan = LocationGapHealing.plan(now + 10 * 60_000L, now, SampleMode.STILL)
        assertEquals(LocationGapHealing.Level.CLOCK_BACKWARD, plan.level)
        assertFalse(plan.forceActiveFix)
        assertFalse(plan.restartSubscription)
        assertTrue("基线要重置，否则时间倒退后永远不采样", plan.resetBaseline)
        assertFalse(plan.isGap)
    }

    @Test
    fun `从未成功采样时立即补一次`() {
        val plan = LocationGapHealing.plan(null, now, SampleMode.STILL)
        assertEquals(LocationGapHealing.Level.INITIAL, plan.level)
        assertTrue(plan.forceActiveFix)
        assertFalse("首次建立订阅由服务负责，不在这里重建", plan.restartSubscription)
        assertFalse("新进程没有基线可清", plan.resetBaseline)
    }
}
