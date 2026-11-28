package com.bsfeng.monitor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** 横条组的条宽比例：总数为 0 时不能除零，值溢出时夹到 0..1。 */
class BarRowsTest {

    @Test
    fun `按总数取比例`() {
        assertEquals(0.5f, barFraction(52, 104), 1e-6f)
        assertEquals(1f, barFraction(8, 8), 1e-6f)
    }

    @Test
    fun `总数为零与负值时落零`() {
        assertEquals(0f, barFraction(8, 0), 1e-6f)
        assertEquals(0f, barFraction(8, -3), 1e-6f)
    }

    @Test
    fun `负数与超出总数的值被夹到 0 到 1`() {
        assertEquals(0f, barFraction(-2, 10), 1e-6f)
        assertEquals(1f, barFraction(20, 10), 1e-6f)
    }
}
