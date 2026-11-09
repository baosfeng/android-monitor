package com.bsfeng.monitor.finance

// 来源：记账「时间单位查询」需求（原型 s04）；职责：五档周期的纯计算 —— 日期区间、分桶标签、分桶键表达式。
// 不碰数据库：SQL 由 FinanceDao 侧拼接，标签与实际数据在此对齐，两边口径同源。

import com.bsfeng.monitor.finance.model.PeriodBucket
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** day 档分桶数（按小时） */
internal const val PERIOD_HOURS = 24

/** week 档分桶数（周一..周日） */
internal const val PERIOD_WEEK_DAYS = 7

/** year 档分桶数（1..12 月） */
internal const val PERIOD_YEAR_MONTHS = 12

/** week 档 x 轴文案：周一..周日（与原型 s04 week 档 data-chart-labels 一致的单字） */
internal val WEEK_LABELS: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")



/**
 * 五档周期的时间区间与桶边界计算。
 *
 * 口径（全部按宿主本地时区）：
 * - day：今天一天，桶 = 小时（24 格，取自 date_time 的小时位）；
 * - week：本周一..今天（**含今天**，不含未来），桶 = 周一..周日 7 格；
 * - month：本月 1 日..今天，桶 = 当月逐日（28~31 格）；
 * - year：本年 1 月 1 日..今天，桶 = 1..12 月；
 * - all：库内最早一笔的月份..今天，桶 = 逐月。
 */
internal object FinancePeriodRange {

    /** day 档 x 轴文案：0..23（两位数，保证刻度等宽） */
    fun hourLabels(): List<String> = (0 until PERIOD_HOURS).map { String.format(Locale.CHINA, "%02d", it) }

    /** 某档在本地的起止日（含两端）。[earliestDate] 只对 [FinancePeriod.ALL] 生效（库内最早一笔的 date）。 */
    fun rangeOf(period: FinancePeriod, today: LocalDate, earliestDate: String? = null): Pair<String, String> =
        when (period) {
            FinancePeriod.DAY -> today.toString() to today.toString()
            FinancePeriod.WEEK -> today.with(java.time.DayOfWeek.MONDAY).toString() to today.toString()
            FinancePeriod.MONTH -> today.withDayOfMonth(1).toString() to today.toString()
            FinancePeriod.YEAR -> LocalDate.of(today.year, 1, 1).toString() to today.toString()
            FinancePeriod.ALL -> (earliestDate ?: today.toString()) to today.toString()
        }

    /** 分桶标签序列（长度即图表桶数）。 */
    fun labelsOf(period: FinancePeriod, fromDate: String, toDate: String): List<String> = when (period) {
        FinancePeriod.DAY -> hourLabels()
        FinancePeriod.WEEK -> WEEK_LABELS
        FinancePeriod.MONTH -> {
            val start = LocalDate.parse(fromDate)
            (1..start.lengthOfMonth()).map { it.toString() }
        }
        FinancePeriod.YEAR -> (1..PERIOD_YEAR_MONTHS).map { it.toString() }
        FinancePeriod.ALL -> {
            val start = YearMonth.from(LocalDate.parse(fromDate))
            val end = YearMonth.from(LocalDate.parse(toDate))
            val months = mutableListOf<String>()
            var cursor = start
            while (!cursor.isAfter(end)) {
                months += cursor.toString()
                cursor = cursor.plusMonths(1)
            }
            if (months.isEmpty()) months += start.toString()
            months
        }
    }

    /** 把「标签 → 桶」的稀疏结果铺满成定长序列；未知标签不扩桶，直接丢弃。 */
    fun fill(labels: List<String>, rows: Map<String, PeriodBucket>): List<PeriodBucket> =
        labels.map { label -> rows[label] ?: PeriodBucket(label = label) }

    /** 分桶键表达式（SQL 用）。**必须与 [labelsOf] 的标签口径同源**。 */
    fun bucketKeyExpr(period: FinancePeriod): String = when (period) {
        FinancePeriod.DAY -> "SUBSTR(${TransactionEntity.COL_DATE_TIME}, 12, 2)"
        FinancePeriod.WEEK -> TransactionEntity.COL_DATE
        FinancePeriod.MONTH -> "CAST(substr(${TransactionEntity.COL_DATE}, 9, 2) AS INTEGER)"
        FinancePeriod.YEAR -> "CAST(substr(${TransactionEntity.COL_DATE}, 6, 2) AS INTEGER)"
        FinancePeriod.ALL -> "substr(${TransactionEntity.COL_DATE}, 1, 7)"
    }

    /** 分桶键 → 标签（SQL 里 CAST 出来的整数要转文案，week 由日期推星期几）。 */
    fun labelOfKey(period: FinancePeriod, rawKey: String): String? = when (period) {
        FinancePeriod.DAY -> rawKey.toIntOrNull()?.let { hourLabels().getOrNull(it) }
        FinancePeriod.WEEK -> dateToWeekLabel(rawKey)
        FinancePeriod.MONTH, FinancePeriod.YEAR -> rawKey.toIntOrNull()?.toString()
        FinancePeriod.ALL -> rawKey.takeIf { it.length >= 7 }?.take(7)
    }

    /** 交易日期 → week 档标签（周一..周日单字）；解析失败返回 null（该笔落在桶外）。 */
    fun dateToWeekLabel(date: String): String? =
        runCatching { LocalDate.parse(date).dayOfWeek }.getOrNull()?.let { WEEK_LABELS[it.value - 1] }
}
