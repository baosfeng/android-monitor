package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳门禁判定：SLEEPING 时 1 分钟心跳必须跳过定位采样。
 *
 * 背景（模拟器实测）：AlarmReceiver → sampleLocationOnce → collectThrottledAsync 原先完全不看门禁，
 * 门禁把订阅关掉后，静止 8 小时仍会变成每分钟一次高精度单点（network/gps +registration），
 * 和用户抱怨的「拿起手机定位一直开着」是同一类耗电 —— 门禁不能留这条后门。
 */
class LocationGateStateTest {

    @Test
    fun `SLEEPING 时心跳跳过定位采样`() {
        LocationGateState.update(MotionGate.State.SLEEPING)
        assertFalse("静止期心跳必须跳过定位", LocationGateState.allowsHeartbeatSample())
    }

    @Test
    fun `PROBING 时心跳放行`() {
        // PROBING 本来就不建订阅；这条单点探测正好可以当门禁的位移证据
        LocationGateState.update(MotionGate.State.PROBING)
        assertTrue(LocationGateState.allowsHeartbeatSample())
    }

    @Test
    fun `MOVING 时心跳放行`() {
        LocationGateState.update(MotionGate.State.MOVING)
        assertTrue(LocationGateState.allowsHeartbeatSample())
    }

    @Test
    fun `门禁未知时保持原行为放行`() {
        // 服务没跑（null）时心跳是唯一采点路径：读不到状态就跳过会制造新的漏采
        LocationGateState.update(null)
        assertTrue(LocationGateState.allowsHeartbeatSample())
    }

    @Test
    fun `offer 把点位转发给注册的 listener`() {
        val received = mutableListOf<LocationSample>()
        LocationGateState.registerLocationListener { received += it }
        try {
            val sample = LocationSample("gps", 31.2304, 121.4737, 8f, 1_700_000_000_000L)
            LocationGateState.offer(sample)
            assertEquals("点位必须原样转发给门禁证据出口", 1, received.size)
            assertSame(sample, received.first())
        } finally {
            LocationGateState.registerLocationListener(null)
        }
    }

    @Test
    fun `未注册 listener 时 offer 不崩`() {
        // 心跳在服务没跑时也会 offer（采集器内部统一出口）：没有消费者必须静默丢弃
        LocationGateState.registerLocationListener(null)
        LocationGateState.offer(LocationSample("gps", 31.2304, 121.4737, 8f, 1_700_000_000_000L))
    }
}
