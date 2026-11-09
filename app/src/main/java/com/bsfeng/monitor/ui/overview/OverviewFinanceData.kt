package com.bsfeng.monitor.ui

// s01 概览「记账图表」卡的数据装配（原型 s01-今日概览 #s01-finance 区块）。
// 数据源一律走 FinanceManager 的既有口径（SQL SUM + 一级分类聚合），不在这里重算金额：
// 卡上三值必须与记账页月度 KPI 逐位一致，否则同一笔钱在两页显示成两个数。
//
// 取数按「首页记账卡」开关分流：关着就不查库（关掉的卡不该继续每 5s 拉聚合）。
// 分类图例取前 3 一级分类 + 「其他 N 类」：卡片只有一屏宽，列满 6 行会把整卡撑高。

import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.ui.live.localDateOf
import java.time.YearMonth
import java.util.Locale

/** 卡头右上「2026 年 11 月」：与记账页费用月份同源（本地时区）。 */
internal fun financeMonthLabel(now: java.time.LocalDate): String =
    String.format(Locale.CHINA, "%d 年 %d 月", now.year, now.monthValue)

/**
 * 概览记账卡快照。
 *
 * 近 7 天逐日支出走 7 次 `SUM`（按日区间，与记账页月度 KPI 同一 SQL 口径）：
 * 不捞交易列表再在内存里 sum —— 「最近 N 笔」的截断会悄悄改小金额（见 [FinanceManager.sumByDateRange]）。
 *
 * @param now 展示月与近 7 天都以它为准（与概览页头同源，跨零点后一起翻篇）
 */
internal suspend fun loadFinanceCard(
    finance: FinanceManager,
    now: Long,
): FinanceCardState {
    val today = localDateOf(now)
    // X 轴文案与概览「近 7 天」面板同一份实现（dayLabels），两处日标签不会各写一套。
    val dailyLabels = dayLabels(7, now)
    val dailyExpense7 = (6 downTo 0).map { back ->
        val day = today.minusDays(back.toLong()).toString()
        finance.sumByDateRange(day, day, expense = true).toFloat()
    }
    val month = YearMonth.from(today).toString()
    val expense = finance.sumByDateRange(
        today.withDayOfMonth(1).toString(), today.toString(), expense = true,
    )
    val income = finance.sumByDateRange(
        today.withDayOfMonth(1).toString(), today.toString(), expense = false,
    )
    // 分类聚合按「本月至今」的月度口径：与记账页统计页的占比图同一份实现（含二级归一级）。
    val breakdown = finance.getCategoryBreakdown(month, FinanceManager.TYPE_EXPENSE)
    val slices = breakdown.entries
        .filter { it.value > 0.0 }
        .sortedByDescending { it.value }
        .map { FinanceCategorySlice(it.key, it.value) }
    return FinanceCardState(
        monthLabel = financeMonthLabel(today),
        monthExpense = expense,
        monthIncome = income,
        monthBalance = income - expense,
        categorySlices = slices,
        dailyExpense7 = dailyExpense7,
        // 图表按「首 = 最早那天」画：dayLabels 与 dailyExpense7 都是同一顺序
        dailyLabels7 = dailyLabels,
    )
}
