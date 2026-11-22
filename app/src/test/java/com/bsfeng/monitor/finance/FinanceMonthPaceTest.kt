package com.bsfeng.monitor.finance

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 本月日均口径的回归（[monthElapsedDays] / [monthDailyAverage]）。
 *
 * 纯函数：不读库、不取 now()（today 由测试传入），可 JVM 直跑。
 * 原型口径见 .open-design/src/screens/s07-统计分析.html:62-70 与卡内「本月累计 ¥2,468 · 已过 14 天」。
 */
class FinanceMonthPaceTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 22)

    @Test
    fun `本月已过天数含今天`() {
        // 今天 11 月 22 日 → 分母 22（含今天），不是 21 也不是整月 30
        assertEquals(22, monthElapsedDays("2026-11", today))
    }

    @Test
    fun `已经过完的月份取当月天数`() {
        assertEquals(31, monthElapsedDays("2026-10", today))
        assertEquals(30, monthElapsedDays("2026-09", today))
        assertEquals(31, monthElapsedDays("2026-07", today))
        assertEquals(28, monthElapsedDays("2026-02", today))
        // 闰年 2 月：按 YearMonth.lengthOfMonth 自动给 29（2024 是过完的闰年）
        assertEquals(29, monthElapsedDays("2024-02", today))
        // 跨年也算「已过完」
        assertEquals(31, monthElapsedDays("2025-12", today))
    }

    @Test
    fun `未来月份与脏字符串返回零`() {
        // 月份条能翻到未来：分母 0 → 界面显示占位「—」，不能算成整月或无穷大
        assertEquals(0, monthElapsedDays("2026-12", today))
        assertEquals(0, monthElapsedDays("2027-01", today))
        assertEquals(0, monthElapsedDays("2026-11-22", today))
        assertEquals(0, monthElapsedDays("", today))
        assertEquals(0, monthElapsedDays("不是月份", today))
    }

    @Test
    fun `日均四舍五入到元`() {
        // 原型样张：¥2,468 ÷ 14 天 = 176.28… → ¥176
        assertEquals(176.0, monthDailyAverage(2468.0, 14), 0.0)
        // 2468 ÷ 31 = 79.61… → 80（四舍五入，不是截断成 79）
        assertEquals(80.0, monthDailyAverage(2468.0, 31), 0.0)
        // 与同屏「总花费 / 周均 / 年累计」同为 0 位小数
        assertEquals(0.0, monthDailyAverage(0.6, 1), 1.0)
    }

    @Test
    fun `分母为零或分子非正时给零`() {
        assertEquals(0.0, monthDailyAverage(2468.0, 0), 0.0)
        assertEquals(0.0, monthDailyAverage(2468.0, -1), 0.0)
        assertEquals(0.0, monthDailyAverage(0.0, 30), 0.0)
        assertEquals(0.0, monthDailyAverage(-5.0, 10), 0.0)
    }
}
