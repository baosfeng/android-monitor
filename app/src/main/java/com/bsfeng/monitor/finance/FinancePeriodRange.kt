package com.bsfeng.monitor.finance

// 来源：记账「时间单位查询」需求（原型 s04）；职责：五档周期的纯计算 —— 日期区间、分桶标签、分桶键表达式。
// 不碰数据库：SQL 由 FinanceDao 侧拼接，标签与实际数据在此对齐，两边口径同源。

import com.bsfeng.monitor.finance.model.PeriodBucket
import java.time.LocalDate
import java.util.Locale

/** day 档分桶数（按小时） */
internal const val PERIOD_HOURS = 24

/** week 档分桶数（周一..周日） */
internal const val PERIOD_WEEK_DAYS = 7

/** year 档分桶数（1..12 月） */
internal const val PERIOD_YEAR_MONTHS = 12

/** 滚动区间的天数（s04「近 7 天收支」= 今天 + 往前 6 天，含今天） */
internal const val LAST_DAYS = 7

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
 * - all：库内最早一笔的日期..今天，桶 = **逐年**（2018 / 2019 …；原型 s04:222 的 all 档横轴就是年份）。
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

    /**
     * 指定自然年的区间（含两端）。s07 的「年度」区块跟月份条走，所以年可以被翻到往年。
     *
     * - 往年：1 月 1 日 .. 12 月 31 日（整年已过完）；
     * - 今年：1 月 1 日 .. 今天（与 [rangeOf] 的 YEAR 档同口径，未到的月份不算）；
     * - 未来年：退化为该年 1 月 1 日这一天 —— 区间里没有账，年度趋势自然是 12 个 0 桶，
     *   而不是把「未来月份」也当成统计范围（那样会与「年累计」的语义冲突）。
     */
    fun yearRangeOf(year: Int, today: LocalDate): Pair<String, String> {
        val start = LocalDate.of(year, 1, 1)
        return when {
            year < today.year -> start.toString() to LocalDate.of(year, 12, 31).toString()
            year == today.year -> start.toString() to today.toString()
            else -> start.toString() to start.toString()
        }
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
            // 按年分桶：原型 s04:222 的 labels 是 2018..2026（9 根），不是逐月 36 根。
            val startYear = LocalDate.parse(fromDate).year
            val endYear = LocalDate.parse(toDate).year
            if (endYear < startYear) {
                listOf(startYear.toString())
            } else {
                (startYear..endYear).map { it.toString() }
            }
        }
    }

    /** 把「标签 → 桶」的稀疏结果铺满成定长序列；未知标签不扩桶，直接丢弃。 */
    fun fill(labels: List<String>, rows: Map<String, PeriodBucket>): List<PeriodBucket> =
        labels.map { label -> rows[label] ?: PeriodBucket(label = label) }

    /**
     * 近 [LAST_DAYS] 天的日期序列（含今天，从最早的往前排）：2026-11-03 → 10-28 .. 11-03。
     *
     * 跨月 / 跨年靠 [LocalDate.minusDays] 自然进位，不做月份拼接。s04「近 7 天收支」卡的
     * 口径固定是「今天往前 7 天」，与月份条无关（原型 s04:274-278 的 data-chart-labels 08..14）。
     */
    fun lastDaysDates(today: LocalDate): List<String> =
        (LAST_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }

    /** 日期标签 yyyy-MM-dd → MM-dd（原型给 08..14，补上月份避免跨月时两个「05」撞车）。 */
    fun shortDateLabel(date: String): String = if (date.length >= 7) date.substring(5) else date

    /**
     * 近 7 天按日铺满：SQL 侧分桶键是完整日期（[FinancePeriodAggregator.dailyRows]），
     * x 轴标签是 MM-dd，两边靠 [lastDaysDates] 的顺序对齐，标签在这里改写。
     */
    fun fillDays(dates: List<String>, rows: Map<String, PeriodBucket>): List<PeriodBucket> =
        dates.map { date ->
            val label = shortDateLabel(date)
            val bucket = rows[date]
            if (bucket == null) PeriodBucket(label = label) else bucket.copy(label = label)
        }

    /** 分桶键表达式（SQL 用）。**必须与 [labelsOf] 的标签口径同源**。 */
    fun bucketKeyExpr(period: FinancePeriod): String = when (period) {
        FinancePeriod.DAY -> "SUBSTR(${TransactionEntity.COL_DATE_TIME}, 12, 2)"
        FinancePeriod.WEEK -> TransactionEntity.COL_DATE
        FinancePeriod.MONTH -> "CAST(substr(${TransactionEntity.COL_DATE}, 9, 2) AS INTEGER)"
        FinancePeriod.YEAR -> "CAST(substr(${TransactionEntity.COL_DATE}, 6, 2) AS INTEGER)"
        FinancePeriod.ALL -> "SUBSTR(${TransactionEntity.COL_DATE}, 1, 4)"
    }

    /** 分桶键 → 标签（SQL 里 CAST 出来的整数要转文案，week 由日期推星期几）。 */
    fun labelOfKey(period: FinancePeriod, rawKey: String): String? = when (period) {
        FinancePeriod.DAY -> rawKey.toIntOrNull()?.let { hourLabels().getOrNull(it) }
        FinancePeriod.WEEK -> dateToWeekLabel(rawKey)
        FinancePeriod.MONTH, FinancePeriod.YEAR -> rawKey.toIntOrNull()?.toString()
        FinancePeriod.ALL -> rawKey.takeIf { it.length >= 4 }?.take(4)
    }

    /** 交易日期 → week 档标签（周一..周日单字）；解析失败返回 null（该笔落在桶外）。 */
    fun dateToWeekLabel(date: String): String? =
        runCatching { LocalDate.parse(date).dayOfWeek }.getOrNull()?.let { WEEK_LABELS[it.value - 1] }
}
