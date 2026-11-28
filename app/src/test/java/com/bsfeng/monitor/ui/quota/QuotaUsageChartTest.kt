package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 柱图几何（原型 s18 JS：Math.max(4, round(v/max*100))、峰值 fg-2、最多 30 根、Y 轴三档）。 */
class QuotaUsageChartTest {

    @Test
    fun `柱高按峰值归一且最小 4%`() {
        val bars = quotaChartBars(listOf(0f, 50f, 100f))
        assertEquals(3, bars.size)
        assertEquals(0.04f, bars[0].fraction, 0.0001f)
        assertEquals(0.5f, bars[1].fraction, 0.0001f)
        assertEquals(1f, bars[2].fraction, 0.0001f)
    }

    @Test
    fun `只有第一个最大值标峰值`() {
        val bars = quotaChartBars(listOf(10f, 80f, 80f))
        assertEquals(listOf(false, true, false), bars.map { it.peak })
    }

    @Test
    fun `超过三十根取最近三十根`() {
        val values = (1..40).map { it.toFloat() }
        val bars = quotaChartBars(values)
        assertEquals(QUOTA_CHART_MAX_BARS, bars.size)
        // 取最近 30 根 = 11..40；峰值 40 在最后一根，首根 = 11 / 40
        assertEquals("首根是第 11 个值", 11f / 40f, bars.first().fraction, 0.0001f)
        assertEquals("最后一根是峰值", 1f, bars.last().fraction, 0.0001f)
        assertTrue(bars.last().peak)
    }

    @Test
    fun `全零数据不标峰值且每根留 4% 高度`() {
        val bars = quotaChartBars(listOf(0f, 0f))
        assertFalse(bars.any { it.peak })
        assertTrue(bars.all { it.fraction == 0.04f })
    }

    @Test
    fun `空数据没有柱且轴是三个零`() {
        assertTrue(quotaChartBars(emptyList()).isEmpty())
        assertEquals(listOf("0", "0", "0"), quotaChartAxis(emptyList()))
    }

    @Test
    fun `Y 轴三档是峰值一半与零`() {
        assertEquals(listOf("84", "42", "0"), quotaChartAxis(listOf(12f, 84f, 3f)))
        assertEquals(listOf("2k", "1k", "0"), quotaChartAxis(listOf(1500f, 2000f)))
    }
}
