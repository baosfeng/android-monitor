package com.bsfeng.monitor.finance

// s07「时间范围卡 / 年度趋势 / 年度 Top 分类」三块的**纯派生**断言：
// 周均口径、Top 分类排序与截断、双线共用 Y 轴上限、年度 12 桶补齐、年度区间边界。
// 纯 JVM：无 Android、无数据库；「今天」一律由用例显式传入，不读系统时间。

import com.bsfeng.monitor.finance.model.PeriodBucket
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.ui.components.dualSeriesAxisMax
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceYearStatsTest {

    @Test
    fun `Top 分类按金额降序取前五`() {
        val rows = mapOf(
            "餐饮" to 50.0,
            "购物" to 300.0,
            "交通" to 20.0,
            "娱乐" to 10.0,
            "居住" to 5.0,
            "医疗" to 1.0,
        )
        val tops = topCategoriesOf(rows)
        assertEquals("默认截断到 5 条（原型 102-114 列 5 行）", 5, tops.size)
        assertEquals(listOf("购物", "餐饮", "交通", "娱乐", "居住"), tops.map { it.name })
        assertEquals(300.0, tops.first().amount, 0.001)
    }

    @Test
    fun `Top 分类同额按名称升序且滤掉非正金额`() {
        val rows = mapOf("交通" to 10.0, "餐饮" to 10.0, "退款抵扣" to 0.0, "负数分类" to -5.0)
        val tops = topCategoriesOf(rows)
        // 同额不定序会让色点在两次刷新之间跳位；≤0 的分类不该占 Top 名额
        assertEquals(listOf("交通", "餐饮"), tops.map { it.name })
    }

    @Test
    fun `Top 分类条数上限可下调`() {
        val rows = (1..8).associate { "c" + it to it.toDouble() }
        assertEquals(3, topCategoriesOf(rows, limit = 3).size)
        assertTrue("limit 非正时返回空列表而不是整表", topCategoriesOf(rows, limit = 0).isEmpty())
    }

    @Test
    fun `已过去周数按年内第几天整周切分`() {
        // 第 1 周 = 1/1..1/7；跨年不借用 ISO 周号（ISO 第 1 周可能落在去年 12 月）
        assertEquals(1, elapsedWeeksOf(LocalDate.parse("2026-01-01")))
        assertEquals(1, elapsedWeeksOf(LocalDate.parse("2026-01-07")))
        assertEquals(2, elapsedWeeksOf(LocalDate.parse("2026-01-08")))
        assertEquals(53, elapsedWeeksOf(LocalDate.parse("2024-12-31")))
    }

    @Test
    fun `周均锚点 今年取今天 往年取年末 未来年取年初`() {
        val today = LocalDate.parse("2026-10-15")
        assertEquals(today, yearAverageAnchor(2026, today))
        assertEquals(LocalDate.parse("2025-12-31"), yearAverageAnchor(2025, today))
        assertEquals(LocalDate.parse("2027-01-01"), yearAverageAnchor(2027, today))
    }

    @Test
    fun `周均取整到元 且零支出不除爆`() {
        // 2026-10-15 是年内第 288 天 → 第 42 周（(288-1)/7+1 = 42）
        val anchor = LocalDate.parse("2026-10-15")
        assertEquals(42, elapsedWeeksOf(anchor))
        assertEquals(600.0, yearWeeklyAverage(25200.0, anchor), 0.001)
        // 100/42 = 2.38 → 取整到元 2
        assertEquals(2.0, yearWeeklyAverage(100.0, anchor), 0.001)
        assertEquals("零支出不做除法", 0.0, yearWeeklyAverage(0.0, anchor), 0.001)
        assertEquals("负支出（净退款）按 0 处理", 0.0, yearWeeklyAverage(-10.0, anchor), 0.001)
    }

    @Test
    fun `双线共用 Y 轴上限取两条线的较大者`() {
        // 支出峰值 468、收入峰值 3200 → 上限由收入决定（同一坐标系才可比大小）
        assertEquals(5000f, dualSeriesAxisMax(listOf(0f, 468f), listOf(0f, 3200f)), 0.001f)
        assertEquals("两条线都空时兜底为 1，避免除零", 1f, dualSeriesAxisMax(emptyList(), emptyList()), 0.001f)
    }

    @Test
    fun `年度 12 桶缺数据时补零桶`() {
        val filled = yearBucketsOf(null)
        assertEquals("还没取到也要 12 个桶（图表整块不消失）", 12, filled.size)
        assertEquals("1", filled.first().label)
        assertEquals("12", filled.last().label)
        assertTrue(filled.all { it.expense == 0.0 && it.income == 0.0 })

        val stats = PeriodStats(
            unitKey = "year",
            buckets = (1..12).map { PeriodBucket(label = it.toString(), expense = it * 10.0) },
        )
        assertEquals(120.0, yearBucketsOf(stats)[11].expense, 0.001)
    }

    @Test
    fun `年度区间 往年整年 今年到今天 未来年为空`() {
        val today = LocalDate.parse("2026-10-15")
        assertEquals(
            "2025-01-01" to "2025-12-31",
            FinancePeriodRange.yearRangeOf(2025, today),
        )
        assertEquals(
            "2026-01-01" to "2026-10-15",
            FinancePeriodRange.yearRangeOf(2026, today),
        )
        assertEquals(
            "未来年不给区间：退化为该年首日",
            "2027-01-01" to "2027-01-01",
            FinancePeriodRange.yearRangeOf(2027, today),
        )
    }
}
