package com.bsfeng.monitor.finance

// 来源：记账「时间单位查询」需求（原型 s04）；职责：把五档周期的 DAO 查询组装成 PeriodStats（三值 + 笔数 + 分桶）。
// 纯组装：区间与标签口径来自 FinancePeriodRange，SQL 来自 FinancePeriodAggregator，本层不做金额计算。

import com.bsfeng.monitor.finance.model.PeriodStats
import java.time.LocalDate

/** 五档周期统计（与 [FinanceStatsService] 的月度口径同源，新增今日/本周/本年/全部）。 */
internal class FinancePeriodService(private val dao: FinanceDao) {

    /**
     * 某一档的完整统计。
     *
     * @param today 宿主本地日期（默认取系统时区今天）；单测注入固定日期，避免跨零点抖动
     */
    fun periodStats(unit: FinancePeriod, today: LocalDate = LocalDate.now()): PeriodStats {
        // all 档的区间起点要问库（最早一笔），否则空库时区间退化成「今天」而不是「无数据」
        val earliest = if (unit == FinancePeriod.ALL) dao.earliestTransactionDate() else null
        val (fromDate, toDate) = FinancePeriodRange.rangeOf(unit, today, earliest)

        // 三值走 SQL SUM：与月度 KPI 同口径（支出含负额退款抵扣）
        val expense = dao.sumByPeriod(fromDate, toDate, FinanceManager.TYPE_EXPENSE)
        val income = dao.sumByPeriod(fromDate, toDate, FinanceManager.TYPE_INCOME)
        val count = dao.countByPeriod(fromDate, toDate)
        val buckets = dao.bucketsByPeriod(unit, fromDate, toDate)

        return PeriodStats(
            unitKey = unit.key,
            unitLabel = unit.label,
            fromDate = fromDate,
            toDate = toDate,
            totalExpense = expense,
            totalIncome = income,
            transactionCount = count,
            buckets = buckets,
        )
    }

    /** 五档一次取齐（「多周期总花费」卡用）。 */
    fun allPeriods(today: LocalDate = LocalDate.now()): List<PeriodStats> =
        FinancePeriod.entries.map { periodStats(it, today) }
}
