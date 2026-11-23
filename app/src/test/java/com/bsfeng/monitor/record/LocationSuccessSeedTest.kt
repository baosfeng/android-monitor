package com.bsfeng.monitor.record

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程重启后回填「上次定位成功时刻」的复现与边界。
 *
 * 缺陷形态（真机观测时踩到）：`lastSuccessAgoSec` 一直是 -1，因为 lastSuccessAtMs 只在拿到点时写、
 * 是纯进程内状态，装包重启后归零 —— 自检面板显示「还没有成功定位」，与「真的从没采到」无法区分。
 */
class LocationSuccessSeedTest {

    @After
    fun resetSnapshot() {
        LocationGateState.resetForTest()
    }

    private fun healthState() = LocationHealthState()

    @Test
    fun `进程重启后从库里最近一条 LOCATION 回填`() {
        val state = healthState()
        val lastAt = 1_700_000_000_000L
        // 重启后进程内是空的
        assertNull(state.lastSuccessAtMs)
        state.seedLastSuccessAtMs(lastAt)
        assertEquals("回填后要能算出「上次成功多久」", lastAt, state.lastSuccessAtMs)
        val now = lastAt + 90_000L
        assertEquals(90_000L, state.snapshot(now, SampleMode.STILL, 180_000L, null).msSinceLastSuccess)
    }

    @Test
    fun `库里没有记录时保持 null 而不是塞 0`() {
        val state = healthState()
        state.seedLastSuccessAtMs(null)
        assertNull("null 是「从没成功过」的既有语义，0 会被读成 1970 年成功过", state.lastSuccessAtMs)
        state.seedLastSuccessAtMs(0L)
        assertNull(state.lastSuccessAtMs)
        state.seedLastSuccessAtMs(-1L)
        assertNull(state.lastSuccessAtMs)
    }

    @Test
    fun `已有本进程的成功时刻时不被旧库值覆盖`() {
        val state = healthState()
        val fresh = 1_700_000_500_000L
        state.onAccepted(LocationSample("gps", 31.2, 121.4, 8f, fresh), fresh)
        state.seedLastSuccessAtMs(1_700_000_000_000L)
        assertEquals("本进程已经采到点，旧库值不许把时间推回过去", fresh, state.lastSuccessAtMs)
    }

    @Test
    fun `回填进程只跑一次 失败也算跑过`() {
        var loads = 0
        var applied: Long? = null
        val seed = LocationSuccessSeed(
            loadLastSuccessAtMs = { loads += 1; 1_700_000_000_000L },
            applySeed = { applied = it },
        )
        assertTrue(seed.seed())
        assertFalse("第二次必须直接返回，不能每拍查库", seed.seed())
        assertEquals("查库只能发生一次", 1, loads)
        assertEquals(1_700_000_000_000L, applied)
    }

    @Test
    fun `查库抛异常时不上抛 且保持 null 语义`() {
        var applied: Long? = 123L
        val seed = LocationSuccessSeed(
            loadLastSuccessAtMs = { throw IllegalStateException("db closed") },
            applySeed = { applied = it },
        )
        assertTrue(seed.seed())
        assertNull("查询失败要按「不知道」处理，不能塞上次的残留值", applied)
        assertTrue("失败也算跑过，避免每次心跳都重试查库", seed.seeded)
    }

    @Test
    fun `回填只影响成功时刻 不伪造质量闸参考点`() {
        val state = healthState()
        state.seedLastSuccessAtMs(1_700_000_000_000L)
        val snapshot = state.snapshot(1_700_000_060_000L, SampleMode.STILL, 180_000L, null)
        assertNull("库里的点没有精度上下文，不能当对照点", snapshot.lastAccuracyMeters)
        assertNull(snapshot.lastProvider)
    }
}
