package com.bsfeng.monitor.finance

// 本轮测试写区固定在 finance/，而 ChartFrame 的刻度计算是记账五档图表的消费点，
// 因此把它的纯计算断言放在这里（无 Android 依赖，纯 JVM 即可跑）。

import com.bsfeng.monitor.ui.components.CHART_X_TICKS_MAX
import com.bsfeng.monitor.ui.components.chartRowFraction
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
    fun `短标签放宽刻度数后九个年份全标`() {
        val years = (2018..2026).map { it.toString() }
        assertEquals("全部档逐年 9 个刻度全标", years, chartXLabelsOf(years, 9).map { it.second })
        // 默认阈值不变：其它图表仍是 7 个上限（只放宽调用方显式要求的这一档）
        assertEquals(CHART_X_TICKS_MAX, chartXLabelsOf(years).size)
    }

    @Test
    fun `网格线与 Y 轴刻度共用同一区间`() {
        // 无顶部留白（折线 / 热力外的默认）：首刻度在顶边、末刻度在底边，均分
        assertEquals(0f, chartRowFraction(0, 4, 0f), 0.0001f)
        assertEquals(0.25f, chartRowFraction(1, 4, 0f), 0.0001f)
        assertEquals(0.5f, chartRowFraction(2, 4, 0f), 0.0001f)
        assertEquals(1f, chartRowFraction(4, 4, 0f), 0.0001f)

        // 柱 / 面积：顶部留白 22dp（148dp 高的绘图区）→ 首刻度落在柱顶，末刻度仍在底边
        val ratio = 22f / 148f
        assertEquals("首刻度 = 柱顶（不是画布顶）", ratio, chartRowFraction(0, 4, ratio), 0.0001f)
        assertEquals(1f, chartRowFraction(4, 4, ratio), 0.0001f)
        assertTrue("中间刻度随留白整体下移", chartRowFraction(1, 4, ratio) > ratio)

        // 退化输入不炸：0 行、越界比例
        assertEquals(0f, chartRowFraction(0, 0, 0f), 0.0001f)
        assertEquals(1f, chartRowFraction(4, 4, 5f), 0.0001f)
    }

    @Test
    fun `空序列不产生刻度`() {
        assertTrue(chartXLabelsOf(emptyList()).isEmpty())
        assertEquals("全 0 时上限兜底为 1，避免除零", 1f, chartYAxisMax(0f), 0.001f)
    }
}
