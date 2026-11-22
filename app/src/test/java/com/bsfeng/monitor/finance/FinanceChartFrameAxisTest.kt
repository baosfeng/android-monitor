package com.bsfeng.monitor.finance

// 本轮测试写区固定在 finance/，而 ChartFrame 的刻度计算是记账五档图表的消费点，
// 因此把它的纯计算断言放在这里（无 Android 依赖，纯 JVM 即可跑）。

import com.bsfeng.monitor.finance.model.PeriodBucket
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.finance.ui.YEAR_TREND_X_TICKS
import com.bsfeng.monitor.ui.components.CHART_X_TICKS_MAX
import com.bsfeng.monitor.ui.components.ChartFrameKind
import com.bsfeng.monitor.ui.components.chartLabelRowIndex
import com.bsfeng.monitor.ui.components.chartRowFraction
import com.bsfeng.monitor.ui.components.chartXTicksByStep
import com.bsfeng.monitor.ui.components.chartXTicksFor
import com.bsfeng.monitor.ui.components.dualLineXTicks
import com.bsfeng.monitor.ui.components.heatXTicks
import com.bsfeng.monitor.ui.components.shouldLabelBarValue
import com.bsfeng.monitor.ui.components.chartXLabelsOf
import com.bsfeng.monitor.ui.components.chartYAxisMax
import com.bsfeng.monitor.ui.components.chartYTickLabels
import com.bsfeng.monitor.ui.components.chartValueLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `双线图可用原型固定刻度 1 3 5 7 9 12`() {
        val labels = (1..12).map { it.toString() }
        val picks = dualLineXTicks(labels, YEAR_TREND_X_TICKS)
        assertEquals(listOf("1", "3", "5", "7", "9", "12"), picks.map { it.second })
        assertEquals(listOf(0, 2, 4, 6, 8, 11), picks.map { it.first })
    }

    @Test
    fun `双线图自定义刻度无效时回退通用抽稀`() {
        val labels = (1..12).map { it.toString() }
        assertEquals(chartXLabelsOf(labels), dualLineXTicks(labels, listOf(99, 100)))
        assertEquals(chartXLabelsOf(labels), dualLineXTicks(labels, null))
    }

    @Test
    fun `Y 轴刻度一律取整`() {
        // 原型 99-app.js:657 Math.round(t * mx)：面积图 max=293 的 ½ 刻度写 147，不是 146.5
        assertEquals(listOf("293", "220", "147", "73", "0"), chartYTickLabels(293f, 4))
        // 轴取整不影响数值直标：峰值 / 柱顶仍是真实金额
        assertEquals("146.5", chartValueLabel(146.5f))
        // rows = 0 的退化调用给单条上限文案，不产出 NaN
        assertEquals(listOf("293"), chartYTickLabels(293f, 0))
    }

    @Test
    fun `柱顶 0 值不直标`() {
        assertFalse(shouldLabelBarValue(0f))
        assertTrue(shouldLabelBarValue(0.01f))
        assertTrue(shouldLabelBarValue(25f))
    }

    @Test
    fun `面积图五条网格只标三个刻度`() {
        // 原型面积图网格 [0,.25,.5,.75,1] 全画、文本只给 t=0/.5/1（99-app.js:653-659）
        assertEquals(listOf(0, 2, 4), (0..2).map { chartLabelRowIndex(it, 4, 2) })
        // 柱图 2 条网格 2 个刻度（99-app.js:753-757）：两条标签直接压 0 与 1 号网格
        assertEquals(listOf(0, 1), (0..1).map { chartLabelRowIndex(it, 1, 1) })
        // 概览卡的矮图（yTicks=2）标签仍落在三条网格线上
        assertEquals(listOf(0, 1, 2), (0..2).map { chartLabelRowIndex(it, 2, 2) })
        // 退化输入不炸
        assertEquals(0, chartLabelRowIndex(2, 0, 0))
    }

    @Test
    fun `面积图十二桶跳奇数索引`() {
        // 原型 99-app.js:661-670：labels.length > 7 时 i % 2 !== 0 跳过 → 1/3/5/7/9/11
        val labels = (1..12).map { it.toString() }
        val picks = chartXTicksByStep(labels, step = 2, keepLast = false)
        assertEquals(listOf(0, 2, 4, 6, 8, 10), picks.map { it.first })
        assertEquals(listOf("1", "3", "5", "7", "9", "11"), picks.map { it.second })
    }

    @Test
    fun `月档三十根柱首根加每五根加末根`() {
        // 原型 99-app.js:750-751 注释「首根 + 每 5 根 + 末根」；同文件 769 行实现从末位倒推，
        // 会得 1/5/10/…，这里按注释口径（任务验收 1/6/11/16/21/26/30）
        val labels = (1..30).map { it.toString() }
        val picks = chartXTicksByStep(labels, step = 5, keepLast = true)
        assertEquals(listOf("1", "6", "11", "16", "21", "26", "30"), picks.map { it.second })
        assertEquals(0, picks.first().first)
        assertEquals(29, picks.last().first)
    }

    @Test
    fun `热力刻度落在格边界而不是格中心`() {
        // 原型 99-app.js:822-833：24 格 → 边界 0/6/12/18/24，位置 i2·(cw+gap)
        val picks = heatXTicks(listOf("0", "6", "12", "18", "24"), count = 24)
        assertEquals(listOf(0, 6, 12, 18, 24), picks.map { it.first })
        assertEquals(listOf("0", "6", "12", "18", "24"), picks.map { it.second })
        // 12 格 → 0/3/6/9/12（原型注释里给的同一组例子）
        assertEquals(
            listOf(0, 3, 6, 9, 12),
            heatXTicks(listOf("0", "3", "6", "9", "12"), count = 12).map { it.first },
        )
    }

    @Test
    fun `X 轴刻度分派热力走边界柱走步长面积走等距`() {
        val heat = chartXTicksFor(ChartFrameKind.Heat, listOf("0", "6", "12", "18", "24"), 24, step = 5, maxTicks = 7)
        assertEquals("热力忽略步长、只认格边界", listOf(0, 6, 12, 18, 24), heat.map { it.first })
        val bars = chartXTicksFor(ChartFrameKind.Bars, (1..30).map { it.toString() }, 30, step = 5, maxTicks = 7)
        assertEquals("柱走步长 + 末根", 7, bars.size)
        val years = chartXTicksFor(ChartFrameKind.Area, (2018..2026).map { it.toString() }, 9, step = null, maxTicks = 9)
        assertEquals("全部档放宽到 9 个年份全标", 9, years.size)
    }
}
