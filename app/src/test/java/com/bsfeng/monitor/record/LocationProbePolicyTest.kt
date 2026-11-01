package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁探测的纯判定：force 路径必须绕过采集节流；缓存只在 90 秒内可以顶替主动定位。
 *
 * 背景（模拟器实测）：PROBING 的 180 秒探测被「STILL 间隔 180s 内已采过」挡掉，
 * 门禁拿不到位移证据，5 分钟无证据直接回 SLEEPING —— 坐车位移 675m 也升级不了。
 */
class LocationProbePolicyTest {

    @Test
    fun `force 探测不被采集节流挡住`() {
        assertTrue(
            LocationProbePolicy.shouldRequestActiveFix(
                force = true,
                planForceActiveFix = false,
                initialFix = false,
                throttleAllows = false,
            ),
        )
    }

    @Test
    fun `非 force 且节流窗口内不发起请求`() {
        assertFalse(
            LocationProbePolicy.shouldRequestActiveFix(
                force = false,
                planForceActiveFix = false,
                initialFix = false,
                throttleAllows = false,
            ),
        )
    }

    @Test
    fun `断档与首点兜底仍然放行`() {
        assertTrue(LocationProbePolicy.shouldRequestActiveFix(false, true, false, false))
        assertTrue(LocationProbePolicy.shouldRequestActiveFix(false, false, true, false))
    }

    @Test
    fun `非 force 照旧用缓存 force 只认 90 秒内的缓存`() {
        // 缓存可用、静止模式、非断档：非 force 直接用缓存
        assertTrue(LocationProbePolicy.canUseCached(false, true, false, SampleMode.STILL, true))
        // force 探测：89 秒命中（顶替一次射频打开），91 秒必须主动定位
        assertTrue(LocationProbePolicy.canUseCached(true, true, false, SampleMode.STILL, true, 89_000L))
        assertFalse(LocationProbePolicy.canUseCached(true, true, false, SampleMode.STILL, true, 91_000L))
    }

    @Test
    fun `force 探测没有缓存或断档时仍主动定位`() {
        assertFalse(LocationProbePolicy.canUseCached(true, false, false, SampleMode.STILL, true, 0L))
        assertFalse("断档时缓存不能代表现在", LocationProbePolicy.canUseCached(true, true, true, SampleMode.STILL, true, 10_000L))
    }

    @Test
    fun `断档时升级满预算但门禁 SLEEPING 的兜底不升级`() {
        // 真机对照：SLEEPING 的 30 分钟兜底距上次成功必然 ≥30 分钟，每次都会被判成断档。
        // 不拦的话它每次都跑 network 5s + gps 15s（室内双超时 = 一天白烧 16 分钟射频）。
        assertEquals(
            "SLEEPING 兜底保持覆盖级预算",
            FixBudget.PROBE,
            LocationProbePolicy.effectiveBudget(FixBudget.PROBE, true, MotionGate.State.SLEEPING),
        )
        assertEquals(
            "PROBING 的断档要救，升级满预算",
            FixBudget.FULL,
            LocationProbePolicy.effectiveBudget(FixBudget.PROBE, true, MotionGate.State.PROBING),
        )
        assertEquals(
            "MOVING 的断档同样升级",
            FixBudget.FULL,
            LocationProbePolicy.effectiveBudget(FixBudget.PROBE, true, MotionGate.State.MOVING),
        )
        assertEquals(
            "门禁未知（服务没跑）时心跳是唯一路径，不能砍预算",
            FixBudget.FULL,
            LocationProbePolicy.effectiveBudget(FixBudget.PROBE, true, null),
        )
        assertEquals(
            "非断档一律按调用方给的档位",
            FixBudget.PROBE,
            LocationProbePolicy.effectiveBudget(FixBudget.PROBE, false, MotionGate.State.MOVING),
        )
    }

    @Test
    fun `断档或没有缓存时不用缓存`() {
        assertFalse(LocationProbePolicy.canUseCached(false, true, true, SampleMode.STILL, true))
        assertFalse(LocationProbePolicy.canUseCached(false, false, false, SampleMode.STILL, true))
    }
}
