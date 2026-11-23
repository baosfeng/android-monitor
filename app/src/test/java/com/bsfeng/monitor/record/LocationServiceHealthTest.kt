package com.bsfeng.monitor.record

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现「服务壳化但自检显示正常」这条已确认 bug（真机 lastSuccessAgoSec 17719 → 19628，停了 5.4 小时）。
 *
 * bug 形态：LocationGateState.state 非 null 被当成「服务健康」的唯一证据，但它只代表
 * onDestroy 没跑过；服务被系统变成壳子后（isForeground=false、types=0x0、startForegroundCount
 * 不再增长），state 仍写着 SLEEPING，心跳据此让路，采点完全停摆。
 *
 * 这里的每一条用例都对应旧实现的**错误结论**：旧实现只看 state 是否非 null。
 */
class LocationServiceHealthTest {

    private val now = 1_700_000_000_000L
    private val tickerStallMs = LocationGateState.TICKER_STALL_MS
    private val successStaleMs = LocationGateState.SUCCESS_STALE_MS

    @After
    fun resetSnapshot() {
        LocationGateState.resetForTest()
    }

    private fun observe(
        state: MotionGate.State? = MotionGate.State.SLEEPING,
        lastTickAtMs: Long = now - 60_000L,
        startForegroundCount: Int = 1,
        foregroundAlive: Boolean? = true,
        lastSuccessAtMs: Long? = now - 60_000L,
    ) = ServiceObservation(state, lastTickAtMs, startForegroundCount, foregroundAlive, lastSuccessAtMs)

    private fun judge(observation: ServiceObservation, at: Long = now): ServiceHealth =
        ServiceHealthJudge.judge(observation, at, tickerStallMs, successStaleMs)

    // ===== 旧实现判错的现场（回归的主体）=====

    @Test
    fun `state 非 null 但 ticker 停摆超过阈值时不再当健康`() {
        // 真机壳化现场：门禁快照还在（SLEEPING）、ticker 已经 5.4 小时没拍、前台身份丢了
        val health = judge(
            observe(
                state = MotionGate.State.SLEEPING,
                lastTickAtMs = now - 19_628_000L,
                startForegroundCount = 1,
                foregroundAlive = false,
                lastSuccessAtMs = now - 19_628_000L,
            ),
        )
        assertEquals("state 非 null 不等于健康：壳化必须判成 SHELLED", ServiceHealth.SHELLED, health)
    }

    @Test
    fun `壳化时心跳必须自愈接管而不是继续让路`() {
        LocationGateState.update(MotionGate.State.SLEEPING)
        LocationGateState.onTickerTick(now - 19_628_000L)
        LocationGateState.onStartForeground(1)
        LocationGateState.onForegroundObserved(false)
        val stale: Long? = now - 19_628_000L
        assertEquals(
            "门禁快照非 null 但前台身份丢了：必须判成壳化",
            ServiceHealth.SHELLED,
            LocationGateState.serviceHealth(now, stale),
        )
        assertTrue("壳化必须请求重启前台服务", LocationGateState.shouldRestartService(now, stale))
        LocationGateState.onFallbackWindow(60 * 60_000L)
        LocationGateState.onFallbackAttempt(now - 61 * 60_000L)
        assertTrue(
            "壳化后兜底到期要由心跳补上（旧口径只看 state 非 null 就永远不动手）",
            LocationGateState.heartbeatShouldRecoverShell(now, stale),
        )
    }

    @Test
    fun `壳化但没有到兜底窗口时不发起定位`() {
        // 省电口径不倒退：自愈接管的是「该不该管」，真的采点仍要等到期
        LocationGateState.update(MotionGate.State.SLEEPING)
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onStartForeground(1)
        LocationGateState.onForegroundObserved(false)
        LocationGateState.onFallbackWindow(60 * 60_000L)
        LocationGateState.onFallbackAttempt(now - 10 * 60_000L)
        val fresh: Long? = now - 10 * 60_000L
        assertFalse(
            "刚兜底过 10 分钟：壳化也不许每分钟发一次定位",
            LocationGateState.heartbeatShouldRecoverShell(now, fresh),
        )
    }

    // ===== 各档边界 =====

    @Test
    fun `服务没跑时是 NOT_RUNNING 不是健康`() {
        assertEquals(ServiceHealth.NOT_RUNNING, judge(observe(state = null)))
    }

    @Test
    fun `ticker 在拍时健康`() {
        assertEquals(ServiceHealth.HEALTHY, judge(observe(lastTickAtMs = now - 60_000L)))
    }

    @Test
    fun `从未拍过不算停摆 服务刚起也算健康`() {
        assertEquals(ServiceHealth.HEALTHY, judge(observe(lastTickAtMs = 0L)))
    }

    @Test
    fun `ticker 停摆但前台身份还在且仍在拿点时只判 doze 停摆`() {
        val health = judge(
            observe(
                lastTickAtMs = now - 30 * 60_000L,
                foregroundAlive = true,
                lastSuccessAtMs = now - 5 * 60_000L,
            ),
        )
        assertEquals("doze 挂起回调不等于壳化", ServiceHealth.TICKER_STALLED, health)
    }

    @Test
    fun `ticker 停摆且连点都拿不到时按壳化处理`() {
        // 前台身份读得到但链路最后一段也断了：宁可多重启一次前台服务
        val health = judge(
            observe(
                lastTickAtMs = now - 30 * 60_000L,
                foregroundAlive = true,
                lastSuccessAtMs = now - 80 * 60_000L,
            ),
        )
        assertEquals(ServiceHealth.SHELLED, health)
    }

    @Test
    fun `前台身份读不到时不把读不到当壳化证据`() {
        val health = judge(
            observe(
                lastTickAtMs = now - 30 * 60_000L,
                foregroundAlive = null,
                lastSuccessAtMs = now - 5 * 60_000L,
            ),
        )
        assertEquals("读不到前台身份只能退回 doze 口径", ServiceHealth.TICKER_STALLED, health)
    }

    @Test
    fun `读不到前台身份且从未进过前台时按壳化处理`() {
        // startForeground 一次都没成功过：拿不到前台优先级，ticker 迟早被掐
        val health = judge(
            observe(
                lastTickAtMs = now - 30 * 60_000L,
                startForegroundCount = 0,
                foregroundAlive = null,
                lastSuccessAtMs = now - 5 * 60_000L,
            ),
        )
        assertEquals(ServiceHealth.SHELLED, health)
    }

    @Test
    fun `壳化时不看陈旧门禁态 也不在未到期时采点`() {
        // 壳化时残留门禁态可能是 MOVING：拿它当闸会让采点一直空着（就是这轮修的 bug）；
        // 但采点节奏仍受兜底窗口约束
        LocationGateState.update(MotionGate.State.MOVING)
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onStartForeground(1)
        LocationGateState.onForegroundObserved(false)
        LocationGateState.onFallbackWindow(30 * 60_000L)
        LocationGateState.onFallbackAttempt(now - 40 * 60_000L)
        val stale: Long? = now - 40 * 60_000L
        assertEquals(
            ServiceHealth.SHELLED,
            LocationGateState.serviceHealth(now, stale),
        )
        assertTrue(
            "壳化 + 兜底到期：陈旧 MOVING 不能拦住心跳接管",
            LocationGateState.heartbeatShouldRecoverShell(now, stale),
        )
        LocationGateState.onFallbackAttempt(now - 60_000L)
        assertFalse(
            "到过就不许再来一次",
            LocationGateState.heartbeatShouldRecoverShell(now, stale),
        )
    }

    @Test
    fun `壳化且兜底起点从未初始化时按现在到期处理`() {
        // 服务在跑到「记起点」那一拍之前就被壳化：壳子永远不会初始化锚点，
        // 若仍按「未到期」判，这条自愈路在真机上永远不触发
        LocationGateState.update(MotionGate.State.SLEEPING)
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onStartForeground(1)
        LocationGateState.onForegroundObserved(false)
        assertTrue(
            LocationGateState.heartbeatShouldRecoverShell(now, now - 30 * 60_000L),
        )
        assertFalse(
            "健康服务不受这条影响",
            run {
                LocationGateState.onForegroundObserved(true)
                LocationGateState.onTickerTick(now - 60_000L)
                LocationGateState.heartbeatShouldRecoverShell(now, now - 60_000L)
            },
        )
    }

    @Test
    fun `doze 停摆时心跳只在 SLEEPING 补兜底`() {
        LocationGateState.update(MotionGate.State.MOVING)
        LocationGateState.onTickerTick(now - 30 * 60_000L)
        LocationGateState.onStartForeground(1)
        LocationGateState.onForegroundObserved(true)
        assertFalse(
            "MOVING 有 30 秒订阅，心跳补位只会抢 provider",
            LocationGateState.heartbeatShouldCoverSleepingFallback(now),
        )
    }

    @Test
    fun `服务销毁写回 null 时复位前台信号`() {
        LocationGateState.update(MotionGate.State.SLEEPING)
        LocationGateState.onStartForeground(3)
        LocationGateState.onForegroundObserved(false)
        LocationGateState.update(null)
        assertEquals(0, LocationGateState.startForegroundCount)
        assertEquals(null, LocationGateState.foregroundAlive)
        assertEquals(ServiceHealth.NOT_RUNNING, LocationGateState.serviceHealth(now, null))
        assertFalse("服务没跑时不该走壳化自愈那条路", LocationGateState.shouldRestartService(now, null))
    }
}
