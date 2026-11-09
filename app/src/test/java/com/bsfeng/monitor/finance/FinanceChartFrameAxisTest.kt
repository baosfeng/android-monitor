package com.bsfeng.monitor.finance

// 本轮测试写区固定在 finance/，而 ChartFrame 的刻度计算是记账五档图表的消费点，
// 因此把它的纯计算断言放在这里（无 Android 依赖，纯 JVM 即可跑）。

import com.bsfeng.monitor.ui.components.CHART_X_TICKS_MAX
import com.bsfeng.monitor.ui.components.chartXLabelsOf
import com.bsfeng.monitor.ui.components.chartYAxisMax
import com.bsfeng.monitor.ui.components.chartYTickLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [com.bsfeng.monitor.ui.components.ChartFrame] 的坐标轴刻度口径（Y 上限取整 / X 抽稀）。 */
class FinanceChartFrameAxisTest {

    @Test
    fun `Y 轴上限抬到 1 2 5 的整齐档`() {
        assertEquals(1f, chartYAxisMax(0f), 0.001f)
        assertEquals(1f, chartYAxisMax(0.4f), 0.001f)
        assertEquals(2f, chartYAxisMax(1.2f), 0.001f)
        assertEquals(5f, chartYAxisMax(4.6f), 0.001f)
        assertEquals(10f, chartYAxisMax(9.9f), 0.001f)
        assertEquals(500f, chartYAxisMax(468f), 0.001f)
    }

    @Test
    fun `Y 轴刻度含 0 与上限且共 5 条`() {
        val labels = chartYTickLabels(100f, 4)
        assertEquals(5, labels.size)
        assertEquals("0", labels.last())
        assertEquals("100", labels.first())
        assertTrue("中间刻度按等分给值", labels.contains("50"))
    }

    @Test
    fun `X 轴桶数不超过上限时全标`() {
        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        val picks = chartXLabelsOf(labels)
        assertEquals(7, picks.size)
        assertEquals(labels, picks.map { it.second })
    }

    @Test
    fun `X 轴 24 小时桶抽稀到上限并保留末位`() {
        val labels = (0 until 24).map { it.toString() }
        val picks = chartXLabelsOf(labels)
        assertEquals(CHART_X_TICKS_MAX, picks.size)
        assertEquals("首刻度落在 0 号桶", 0, picks.first().first)
        assertEquals("末刻度落在 23 号桶", 23, picks.last().first)
        assertTrue("桶序号单调递增", picks.zipWithNext().all { (a, b) -> a.first < b.first })
    }

    @Test
    fun `空序列不产生刻度`() {
        assertTrue(chartXLabelsOf(emptyList()).isEmpty())
        assertEquals("全 0 时上限兜底为 1，避免除零", 1f, chartYAxisMax(0f), 0.001f)
    }
}
