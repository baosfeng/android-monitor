package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt 的派生计算；职责：近 7 天收支、24 小时分布、峰值小时文案与日期标签。

import com.bsfeng.monitor.finance.model.Transaction
import java.time.LocalDate

/** 24 小时 */
internal const val HOURS = 24

/** 环形图与图例最多展示的分类数 */
internal const val CATEGORY_LIMIT = 8

/** 日期标签：2026-10-08 → 08。 */
internal fun dayLabelOf(date: String): String = date.takeLast(2)

/**
 * 近 7 天（含当月为当前月时的今天 / 非当前月时的月末）的支出与收入。
 * 每项为 Triple(支出, 收入, yyyy-MM-dd)。
 */
internal fun last7DayTotals(
    transactions: List<Transaction>,
    yearMonth: String,
): List<Triple<Float, Float, String>> {
    val today = LocalDate.now()
    val anchor = if (yearMonth == FinanceViewModel.currentMonthStr()) {
        today
    } else {
        val parts = yearMonth.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: today.year
        val month = parts.getOrNull(1)?.toIntOrNull() ?: today.monthValue
        LocalDate.of(year, month, 1).withDayOfMonth(
            LocalDate.of(year, month, 1).lengthOfMonth()
        )
    }
    val byDate = transactions.groupBy { it.date }
    return (6 downTo 0).map { offset ->
        val day = anchor.minusDays(offset.toLong())
        val key = day.toString()
        var expense = 0f
        var income = 0f
        byDate[key]?.forEach { tx ->
            val magnitude = if (tx.isRefundEntry) tx.amount else kotlin.math.abs(tx.amount)
            if (tx.isExpense) expense += magnitude.toFloat() else if (tx.isIncome) income += magnitude.toFloat()
        }
        Triple(expense, income, key)
    }
}

/** 24 小时笔数分布（取 dateTime 的小时位）。 */
internal fun hourlyCounts(transactions: List<Transaction>): List<Float> {
    val buckets = MutableList(HOURS) { 0f }
    transactions.forEach { tx ->
        val hour = tx.dateTime.substringAfter(' ', "").take(2).toIntOrNull()
        if (hour != null && hour in 0 until HOURS) buckets[hour] += 1f
    }
    return buckets
}

/** 峰值小时（最多两个）。 */
internal fun hourlyPeaks(hourly: List<Float>): List<Int> =
    hourly.withIndex()
        .filter { it.value > 0f }
        .sortedByDescending { it.value }
        .take(2)
        .map { it.index }

internal fun peakText(peaks: List<Int>): String {
    if (peaks.isEmpty()) return "本月还没有交易记录，热力图为空。"
    val parts = peaks.map { hour ->
        val label = if (hour < 10) "0" + hour + ":00" else hour.toString() + ":00"
        label
    }
    return "峰值出现在 " + parts.joinToString(" 与 ") + "；柱高按当月最大值归一。"
}
