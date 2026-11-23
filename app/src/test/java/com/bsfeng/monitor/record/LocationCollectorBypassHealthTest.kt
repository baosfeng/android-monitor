package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * debug 的 bypass 采点必须进进程单例的健康统计（真机观测时踩到的分叉）。
 *
 * 缺陷形态：`sample_location --bypass_throttle` 新建一个 LocationCollector 实例（节流状态挂在实例上），
 * 它采到的点落了库，但 `lastSuccessAtMs` 只写在自己那份 healthState 里 —— 进程单例仍显示
 * 「上次成功 1250s 前」，而库里最近一条只有 15 分钟（真机 23:25 那条时间轴的两列矛盾就是它）。
 */
class LocationCollectorBypassHealthTest {

    private class FakeEnv : LocationCollector.Env {
        override fun isFineGranted(): Boolean = true
        override fun isBackgroundGranted(): Boolean = true
        override fun readCached(): List<LocationSample> = emptyList()
        override fun requestActiveFix(nowMs: Long, onResult: (LocationSample?) -> Unit) = onResult(null)
    }

    private val sample = LocationSample("passive", 31.2175, 121.2578, 15f, 1_700_000_000_000L)

    @Test
    fun `bypass 采点回写给单例后 单例健康统计才反映这次成功`() {
        val singleton = LocationCollector(FakeEnv())
        assertNull("旁路实例采点前，单例没有任何成功记录", singleton.health(1_700_000_060_000L).lastSuccessAtMs)

        val bypass = LocationCollector(FakeEnv())
        val now = 1_700_000_060_000L
        singleton.adoptSample(sample, now)

        val health = singleton.health(now)
        assertEquals("回写后单例必须看到这次成功", now, health.lastSuccessAtMs)
        assertEquals(0L, health.msSinceLastSuccess)
    }

    @Test
    fun `回写不替换质量闸的对照点`() {
        val singleton = LocationCollector(FakeEnv())
        singleton.adoptSample(sample, 1_700_000_060_000L)
        val health = singleton.health(1_700_000_060_000L)
        assertNull("调试点多半是 mock / 粗点，不能当位移判定的对照点", health.lastProvider)
        assertNull(health.lastAccuracyMeters)
    }
}
