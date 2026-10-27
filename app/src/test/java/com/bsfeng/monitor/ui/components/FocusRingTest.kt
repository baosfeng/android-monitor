package com.bsfeng.monitor.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘焦点环线宽回归（契约 s01：2dp；颜色对比 ≥3:1 由 t.accent 保证，触屏不画环由 InputMode 判定）。 */
class FocusRingTest {

    @Test
    fun `焦点环线宽为 2dp`() {
        assertEquals(2f, FOCUS_RING_WIDTH.value, 0f)
        assertEquals(2.dp, FOCUS_RING_WIDTH)
    }

    @Test
    fun `线宽不低于 1dp 且不吞掉整块点击区`() {
        // 低于 1dp 在高密度屏上几乎看不见；超过点击区高度则环本身盖住内容
        assertTrue(FOCUS_RING_WIDTH >= 1.dp)
        assertTrue(FOCUS_RING_WIDTH < 44.dp)
    }
}
