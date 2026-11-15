package com.bsfeng.monitor.record

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 心跳门禁判定：SLEEPING 时 1 分钟心跳必须跳过定位采样。
 *
 * 背景（模拟器实测）：AlarmReceiver → sampleLocationOnce → collectThrottledAsync 原先完全不看门禁，
 * 门禁把订阅关掉后，静止 8 小时仍会变成每分钟一次高精度单点（network/gps +registration），
 * 和用户抱怨的「拿起手机定位一直开着」是同一类耗电 —— 门禁不能留这条后门。
 */
class LocationGateStateTest {

    /** 快照是进程内全局状态：每个用例后复位，避免污染同一 JVM 里的其它用例。 */
    @After
    fun resetSnapshot() {
        LocationGateState.resetForTest()
    }

    @Test
    fun `SLEEPING 时心跳跳过定位采样`() {
        LocationGateState.update(MotionGate.State.SLEEPING)
        assertFalse("前台服务在跑（SLEEPING）时心跳不碰定位", LocationGateState.allowsHeartbeatSample())
    }

    @Test
    fun `PROBING 时心跳让路`() {
        // 服务在跑时它自己的探测阶梯是唯一驱动源：心跳再采就是绕过阶梯的第二条链
        // （真机 22:17:05：服务刚跳过探测，心跳 1 秒后强制探测一次）
        LocationGateState.update(MotionGate.State.PROBING)
        assertFalse(LocationGateState.allowsHeartbeatSample())
    }

    @Test
    fun `MOVING 时心跳让路`() {
        // MOVING 本来就有 30 秒订阅；心跳再采只会与订阅抢 provider
        LocationGateState.update(MotionGate.State.MOVING)
        assertFalse(LocationGateState.allowsHeartbeatSample())
    }

    @Test
    fun `门禁未知时保持原行为放行`() {
        // 服务没跑（null）时心跳是唯一采点路径：读不到状态就跳过会制造新的漏采
        LocationGateState.update(null)
        assertTrue(LocationGateState.allowsHeartbeatSample())
    }

    // ===== 订阅重建闸（C 根因）=====

    @Test
    fun `只有 MOVING 或门禁未知才允许重建订阅`() {
        assertFalse("SLEEPING 下 DEEP 断档不许把订阅重建起来", LocationGateState.allowsSubscription(MotionGate.State.SLEEPING))
        assertFalse("PROBING 也不建订阅（它只做低频探测）", LocationGateState.allowsSubscription(MotionGate.State.PROBING))
        assertTrue(LocationGateState.allowsSubscription(MotionGate.State.MOVING))
        assertTrue("服务没跑时心跳是唯一采点路径", LocationGateState.allowsSubscription(null))
    }

    @Test
    fun `订阅重建闸读的是当前快照`() {
        LocationGateState.update(MotionGate.State.SLEEPING)
        assertFalse(LocationGateState.allowsSubscription())
        LocationGateState.update(MotionGate.State.MOVING)
        assertTrue(LocationGateState.allowsSubscription())
        LocationGateState.update(null)
        assertTrue(LocationGateState.allowsSubscription())
    }

    @Test
    fun `offer 把点位转发给注册的 listener`() {
        val received = mutableListOf<LocationSample>()
        LocationGateState.registerLocationListener { sample, _ -> received += sample }
        try {
            val sample = LocationSample("gps", 31.2304, 121.4737, 8f, 1_700_000_000_000L)
            LocationGateState.offer(sample)
            assertEquals("点位必须原样转发给门禁证据出口", 1, received.size)
            assertSame(sample, received.first())
        } finally {
            LocationGateState.registerLocationListener(null)
        }
    }

    // ===== ticker 停摆时心跳接手 SLEEPING 兜底（真机 04:11→06:55 空 163 分钟）=====

    @Test
    fun `ticker 健康时心跳绝不接手兜底`() {
        // 服务在跑、拍子正常：心跳补位会绕过服务的探测阶梯（真机 22:17:05 那个问题的回归）
        LocationGateState.update(MotionGate.State.SLEEPING)
        val now = 1_700_000_000_000L
        LocationGateState.onTickerTick(now - 60_000L)
        LocationGateState.onFallbackAttempt(now - 90 * 60_000L)
        assertFalse(LocationGateState.heartbeatShouldRunSleepingFallback(now))
    }

    @Test
    fun `ticker 停摆且兜底到期时心跳接手`() {
        LocationGateState.update(MotionGate.State.SLEEPING)
        val now = 1_700_000_000_000L
        // doze 把 ticker 挂起：最后一次拍子在 30 分钟前
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        // 兜底起点在 61 分钟前（基础窗口 60 分钟）→ 已到期
        LocationGateState.onFallbackAttempt(now - 61 * 60_000L)
        LocationGateState.onFallbackWindow(60 * 60_000L)
        assertTrue(LocationGateState.heartbeatShouldRunSleepingFallback(now))
    }

    @Test
    fun `ticker 停摆但兜底没到期 心跳只管看不动手`() {
        // 心跳是 1 分钟一次：不看到期就补位等于在 doze 里每分钟发一次定位请求
        LocationGateState.update(MotionGate.State.SLEEPING)
        val now = 1_700_000_000_000L
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onFallbackAttempt(now - 10 * 60_000L)
        LocationGateState.onFallbackWindow(60 * 60_000L)
        assertFalse(LocationGateState.heartbeatShouldRunSleepingFallback(now))
    }

    @Test
    fun `只有 SLEEPING 交给心跳补位`() {
        val now = 1_700_000_000_000L
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onFallbackAttempt(now - 61 * 60_000L)
        LocationGateState.onFallbackWindow(60 * 60_000L)
        LocationGateState.update(MotionGate.State.PROBING)
        assertFalse("PROBING 有自己的探测阶梯，心跳补位只会抢 provider", LocationGateState.heartbeatShouldRunSleepingFallback(now))
        LocationGateState.update(MotionGate.State.MOVING)
        assertFalse(LocationGateState.heartbeatShouldRunSleepingFallback(now))
    }

    @Test
    fun `服务没跑时不算 ticker 停摆`() {
        // 门禁未知由 allowsHeartbeatSample 那条口径负责，不要两条口径同时放行
        val now = 1_700_000_000_000L
        LocationGateState.update(null)
        LocationGateState.onFallbackAttempt(now - 61 * 60_000L)
        assertFalse(LocationGateState.heartbeatShouldRunSleepingFallback(now))
    }

    @Test
    fun `从未拍过不算停摆`() {
        // 服务刚起：拍子还在排队，此时让心跳抢跑会与服务首拍重复采点
        LocationGateState.update(MotionGate.State.SLEEPING)
        val now = 1_700_000_000_000L
        LocationGateState.onFallbackAttempt(now - 61 * 60_000L)
        assertFalse(LocationGateState.tickerStalled(now))
    }

    @Test
    fun `兜底失败把窗口收紧到 5 分钟`() {
        val now = 1_700_000_000_000L
        LocationGateState.update(MotionGate.State.SLEEPING)
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onFallbackWindow(60 * 60_000L)
        LocationGateState.onFallbackAttempt(now - 6 * 60_000L)
        LocationGateState.onFallbackResult(success = false)
        assertTrue(
            "一次失败不该白等一整个 60 分钟窗口（真机 05:40 那次失败空了 163 分钟）",
            LocationGateState.heartbeatShouldRunSleepingFallback(now),
        )
        LocationGateState.onFallbackResult(success = true)
        assertFalse("成功后恢复 60 分钟窗口", LocationGateState.heartbeatShouldRunSleepingFallback(now))
    }

    @Test
    fun `服务没同步窗口时按 30 分钟保守算`() {
        LocationGateState.update(MotionGate.State.SLEEPING)
        val now = 1_700_000_000_000L
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onFallbackAttempt(now - 31 * 60_000L)
        assertEquals(
            "同步缺失时不能猜 60 分钟",
            LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS,
            LocationGateState.heartbeatFallbackWindowMs(),
        )
        assertTrue(LocationGateState.heartbeatShouldRunSleepingFallback(now))
    }

    @Test
    fun `未注册 listener 时 offer 不崩`() {
        // 心跳在服务没跑时也会 offer（采集器内部统一出口）：没有消费者必须静默丢弃。
        // 断言「不抛异常」必须显式写成失败，否则函数体被删掉用例依然绿（基建审计 I13）。
        LocationGateState.registerLocationListener(null)
        try {
            LocationGateState.offer(LocationSample("gps", 31.2304, 121.4737, 8f, 1_700_000_000_000L))
        } catch (t: Throwable) {
            fail("未注册 listener 时 offer 不得抛：$t")
        }
    }
}
