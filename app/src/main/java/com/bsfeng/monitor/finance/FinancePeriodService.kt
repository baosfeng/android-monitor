package com.bsfeng.monitor.finance

// 来源：记账「时间单位查询」需求（原型 s04）；职责：把五档周期的 DAO 查询组装成 PeriodStats（三值 + 笔数 + 分桶）。
// 纯组装：区间与标签口径来自 FinancePeriodRange，SQL 来自 FinancePeriodAggregator，本层不做金额计算。

import com.bsfeng.monitor.finance.model.PeriodStats
import java.time.LocalDate

/**
 * 近 7 天滚动统计的 unitKey（不属五档，单独一个键；界面按它取 [PeriodStats] 时不会与 WEEK 撞车）。
 */
internal const val LAST_DAYS_UNIT_KEY: String = "last7"

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

    /**
     * 近 7 天滚动统计（含今天往前 6 天，跨月 / 跨年都对）：s04「近 7 天收支」卡的数据源。
     *
     * 为什么不复用五档的 WEEK：WEEK 是「本周一..今天」，周二看只有 2 根柱；原型 s04:274-278
     * 的近 7 天是**滚动 7 天**（08..14 连续 7 个日期）。两者口径不同且都成立，所以并列存在。
     * 口径固定跟「今天」走，不跟月份条走 —— 翻到往年月份时这张卡仍回答「最近 7 天花了多少」。
     *
     * 金额一律 SQL SUM（与五档 / 月度 KPI 同口径），按日分桶也走 SQL（[FinanceDao.dailyBuckets]），
     * 不捞交易列表回内存累加。
     *
     * @param today 宿主本地日期（单测注入固定值，避免跨零点抖动）
     */
    fun lastDaysStats(today: LocalDate = LocalDate.now()): PeriodStats {
        val dates = FinancePeriodRange.lastDaysDates(today)
        val fromDate = dates.first()
        val toDate = dates.last()
        val expense = dao.sumByPeriod(fromDate, toDate, FinanceManager.TYPE_EXPENSE)
        val income = dao.sumByPeriod(fromDate, toDate, FinanceManager.TYPE_INCOME)
        val count = dao.countByPeriod(fromDate, toDate)
        val rows = dao.dailyBuckets(fromDate, toDate)

        return PeriodStats(
            unitKey = LAST_DAYS_UNIT_KEY,
            unitLabel = "近 7 天",
            fromDate = fromDate,
            toDate = toDate,
            totalExpense = expense,
            totalIncome = income,
            transactionCount = count,
            buckets = FinancePeriodRange.fillDays(dates, rows),
        )
    }

    /** 五档一次取齐（「多周期总花费」卡用）。 */
    fun allPeriods(today: LocalDate = LocalDate.now()): List<PeriodStats> =
        FinancePeriod.entries.map { periodStats(it, today) }

    /**
     * 指定自然年的年度统计（s07 年度趋势 / 年度 Top 分类 / 年累计 / 周均共用）。
     *
     * 与 [periodStats] 的 YEAR 档只差区间：YEAR 档恒为「今天所在的年」，这里按调用方给的年份取 ——
     * 统计页的月份条能翻到往年，年度区块必须跟着浏览月走，否则翻到 2025 年看到的仍是今年的趋势。
     *
     * 分桶键仍复用 [FinancePeriod.YEAR]（12 个月、标签 1..12），与五档图表同一套口径。
     */
    fun yearStats(year: Int, today: LocalDate = LocalDate.now()): PeriodStats {
        val (fromDate, toDate) = FinancePeriodRange.yearRangeOf(year, today)
        val expense = dao.sumByPeriod(fromDate, toDate, FinanceManager.TYPE_EXPENSE)
        val income = dao.sumByPeriod(fromDate, toDate, FinanceManager.TYPE_INCOME)
        val count = dao.countByPeriod(fromDate, toDate)
        val buckets = dao.bucketsByPeriod(FinancePeriod.YEAR, fromDate, toDate)

        return PeriodStats(
            unitKey = FinancePeriod.YEAR.key,
            unitLabel = year.toString() + " 年",
            fromDate = fromDate,
            toDate = toDate,
            totalExpense = expense,
            totalIncome = income,
            transactionCount = count,
            buckets = buckets,
        )
    }
}
