package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s13-head 退出后焦点回设置页「外观与主题」行的跨页信号（第 3 批走查 P1-6）。
 *
 * 外观屏由宿主导航以覆盖层打开，设置页收不到关闭回调，故用代次信号衔接；这里锁住三点：
 * 退出必 +1、代次没变不抢焦点、进页面前的旧代次不抢焦点。
 */
class AppearanceFocusReturnTest {

    @Test
    fun `外观退出一次代次前进一位`() {
        val before = AppearanceFocusReturn.generation
        AppearanceFocusReturn.request()
        assertEquals(before + 1, AppearanceFocusReturn.generation)
    }

    @Test
    fun `连续退出按次累加`() {
        val before = AppearanceFocusReturn.generation
        AppearanceFocusReturn.request()
        AppearanceFocusReturn.request()
        assertEquals(before + 2, AppearanceFocusReturn.generation)
    }

    @Test
    fun `代次未变化时不回焦`() {
        assertFalse(appearanceReturnFocusPending(currentGeneration = 7, seenGeneration = 7))
    }

    @Test
    fun `代次前进时回焦`() {
        assertTrue(appearanceReturnFocusPending(currentGeneration = 8, seenGeneration = 7))
    }

    @Test
    fun `进入设置页时把当前代次当基线不抢焦点`() {
        val baseline = AppearanceFocusReturn.generation
        assertFalse(appearanceReturnFocusPending(baseline, seenGeneration = baseline))
    }
}
