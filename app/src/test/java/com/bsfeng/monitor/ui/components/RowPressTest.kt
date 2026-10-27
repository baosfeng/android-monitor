package com.bsfeng.monitor.ui.components

import com.bsfeng.monitor.ui.foundation.PRESS_SCALE_TARGET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表行按压反馈常量回归（原型 s03 `.tl-item:active` → scale(.99) / opacity(.9)）。 */
class RowPressTest {

    @Test
    fun `列表行按压缩到百分之九十九`() {
        assertEquals(0.99f, ROW_PRESS_SCALE, 0f)
    }

    @Test
    fun `列表行按压透明度降到零点九`() {
        assertEquals(0.9f, ROW_PRESS_ALPHA, 0f)
    }

    @Test
    fun `比按钮的 0_96 更轻微：大面积行缩太多会晃眼`() {
        // 0.99 > 0.96 —— 行的收缩量必须小于按钮，两者共用一套实现就会串味
        assertTrue("行 $ROW_PRESS_SCALE 应比按钮 $PRESS_SCALE_TARGET 缩得少", ROW_PRESS_SCALE > PRESS_SCALE_TARGET)
        assertTrue("行的缩放变化不得超过 2%", 1f - ROW_PRESS_SCALE < 0.02f)
    }

    @Test
    fun `两个按压目标都是可见变化且不会让行消失`() {
        assertTrue(ROW_PRESS_SCALE in 0f..1f && ROW_PRESS_SCALE > 0.9f)
        assertTrue(ROW_PRESS_ALPHA in 0f..1f && ROW_PRESS_ALPHA >= 0.9f)
    }
}
