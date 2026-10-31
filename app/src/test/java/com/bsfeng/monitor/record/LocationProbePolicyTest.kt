package com.bsfeng.monitor.record

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁探测的纯判定：force 路径必须绕过采集节流与缓存。
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
    fun `force 探测跳过缓存 直取主动定位`() {
        // 缓存可用、静止模式、非断档 —— 非 force 会直接用缓存；force 必须不用
        assertTrue(LocationProbePolicy.canUseCached(false, true, false, SampleMode.STILL, true))
        assertFalse(LocationProbePolicy.canUseCached(true, true, false, SampleMode.STILL, true))
    }

    @Test
    fun `断档或没有缓存时不用缓存`() {
        assertFalse(LocationProbePolicy.canUseCached(false, true, true, SampleMode.STILL, true))
        assertFalse(LocationProbePolicy.canUseCached(false, false, false, SampleMode.STILL, true))
    }
}
