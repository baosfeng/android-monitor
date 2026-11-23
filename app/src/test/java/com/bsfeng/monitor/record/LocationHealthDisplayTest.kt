package com.bsfeng.monitor.record

import com.bsfeng.monitor.ui.LocationHealthLevel
import com.bsfeng.monitor.ui.LocationHealthText
import com.bsfeng.monitor.ui.components.BadgeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示口径的最高优先级：**断档 > 服务异常 > 省电待机 > 正常**。
 *
 * 为什么这条用例放在 record 包的测试目录而不是 ui 的：本轮改动的写范围只到 record 包，
 * 而它钉的正是「门禁快照 + 服务健康」的合成口径 —— 输入全部来自 record 层的
 * [LocationStatus] / [ServiceSignals] / [ServiceHealth]，换个包放不会改变它的语义。
 *
 * 规格来源（leader 裁决）：用户关心的是「有没有采到点」，所以主标题给后果（已断档 N 小时），
 * 服务降级作为原因进副标题，信息一条不丢。
 */
class LocationHealthDisplayTest {

    private fun status(
        serviceHealth: ServiceHealth,
        sinceLastSuccessMs: Long?,
        tickerIdleMs: Long = 0L,
    ) = LocationStatus(
        lastSuccessAtMs = sinceLastSuccessMs?.let { 1_000_000L - it },
        msSinceLastSuccess = sinceLastSuccessMs,
        subscriptionActive = false,
        service = ServiceSignals(
            health = serviceHealth,
            tickerIdleMs = tickerIdleMs,
            startForegroundCount = 1,
            foregroundAlive = serviceHealth != ServiceHealth.SHELLED,
        ),
    )

    @Test
    fun `壳化且已断档时主标题必须是断档而不是服务异常`() {
        // 真机壳化现场：门禁快照写着 SLEEPING、最后一点在 5.4 小时前
        val s = status(ServiceHealth.SHELLED, 5 * 3600_000L + 27 * 60_000L, tickerIdleMs = 19_628_000L)
        val level = LocationHealthText.levelOf(s, MotionGate.State.SLEEPING)
        assertEquals("断档优先级高于服务异常", LocationHealthLevel.STALE, level)
        assertEquals("已断档", LocationHealthText.badge(s, MotionGate.State.SLEEPING))
        assertEquals(BadgeTone.Danger, LocationHealthText.toneOf(s, MotionGate.State.SLEEPING))
        val text = LocationHealthText.describe(s, MotionGate.State.SLEEPING)
        assertTrue("主标题之外的副标题要给出原因：" + text, text.contains("原因：服务被系统降级"))
        assertTrue("原因里要带定时器停了多久：" + text, text.contains("定时器已停 5 小时"))
        assertTrue("断档时仍要能看出最后一点多久以前：" + text, text.contains("5 小时前"))
    }

    @Test
    fun `壳化但数据仍新鲜时服务异常才提为主标题`() {
        // 刚拿到过点、服务刚被降级：这时主标题讲原因，因为没有可感知的断档
        val s = status(ServiceHealth.SHELLED, 2 * 60_000L, tickerIdleMs = 11 * 60_000L)
        assertEquals(LocationHealthLevel.SERVICE_PENDING, LocationHealthText.levelOf(s, MotionGate.State.SLEEPING))
        assertEquals("服务异常", LocationHealthText.badge(s, MotionGate.State.SLEEPING))
        val text = LocationHealthText.describe(s, MotionGate.State.SLEEPING)
        assertTrue("要说明已请求重新拉起：" + text, text.contains("已请求重新拉起"))
        assertTrue("原因里要带定时器停了多久：" + text, text.contains("定时器已停 11 分钟"))
    }

    @Test
    fun `doze 停摆但数据新鲜时报已自愈而不是断档`() {
        val s = status(ServiceHealth.TICKER_STALLED, 20 * 60_000L, tickerIdleMs = 12 * 60_000L)
        assertEquals(LocationHealthLevel.SERVICE_HEALED, LocationHealthText.levelOf(s, MotionGate.State.SLEEPING))
        assertEquals("已自愈", LocationHealthText.badge(s, MotionGate.State.SLEEPING))
        assertTrue(
            LocationHealthText.describe(s, MotionGate.State.SLEEPING).contains("兜底已由心跳接管"),
        )
    }

    @Test
    fun `doze 停摆且已断档时仍以断档为主标题`() {
        val s = status(ServiceHealth.TICKER_STALLED, 3 * 3600_000L, tickerIdleMs = 90 * 60_000L)
        assertEquals(LocationHealthLevel.STALE, LocationHealthText.levelOf(s, MotionGate.State.SLEEPING))
        val text = LocationHealthText.describe(s, MotionGate.State.SLEEPING)
        assertTrue("断档副标题要带上停摆原因：" + text, text.contains("定时器被系统挂起"))
    }

    @Test
    fun `服务健康时原有待机与断档口径一字不变`() {
        val dormant = status(ServiceHealth.HEALTHY, 20 * 60_000L)
        assertEquals(LocationHealthLevel.DORMANT, LocationHealthText.levelOf(dormant, MotionGate.State.SLEEPING))
        assertEquals("省电待机", LocationHealthText.badge(dormant, MotionGate.State.SLEEPING))
        assertEquals(
            "省电待机 · 静止时不采点，最长 60 分钟兜底",
            LocationHealthText.describe(dormant, MotionGate.State.SLEEPING),
        )
        val stale = status(ServiceHealth.HEALTHY, 3 * 3600_000L)
        assertEquals(LocationHealthLevel.STALE, LocationHealthText.levelOf(stale, MotionGate.State.SLEEPING))
        assertTrue(
            "服务健康时不该出现服务相关措辞",
            !LocationHealthText.describe(stale, MotionGate.State.SLEEPING).contains("服务被系统降级"),
        )
    }
}
