package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的派生数据纯函数；职责：分组标题、最近 N 天笔数、当月判断、今日收支。

import com.bsfeng.monitor.finance.model.Transaction
import java.time.LocalDate

/** 交易明细右侧「最近 N 天」的天数（原型 s04-listmeta：最近 3 天 · 8 笔）。 */
internal const val LIST_META_DAYS = 3

/** 自动记账新行高亮时长（原型 s04「高亮 1.6s」） */
internal const val HIGHLIGHT_DURATION_MS = 1600L

/** 高亮淡出时长（与原 animateColorAsState 的 tween(240) 一致） */
internal const val HIGHLIGHT_FADE_MS = 240

/** 高亮底色不透明度（原 t.accent.copy(alpha = 0.12f)） */
internal const val HIGHLIGHT_ALPHA = 0.12f

/** 分组标题：10 月 14 日 · 今天 / 昨天 / 前天。 */
internal fun groupLabel(date: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val today = LocalDate.now()
    val suffix = when (parsed) {
        today -> " · 今天"
        today.minusDays(1) -> " · 昨天"
        today.minusDays(2) -> " · 前天"
        else -> ""
    }
    return parsed.monthValue.toString() + " 月 " + parsed.dayOfMonth + " 日" + suffix
}

/** 最近 [days] 天（含今天）的笔数：日期为 ISO yyyy-MM-dd，可直接做字典序比较。 */
internal fun recentDayCount(transactions: List<Transaction>, days: Int): Int {
    if (days <= 0) return 0
    val from = LocalDate.now().minusDays((days - 1).toLong()).toString()
    return transactions.count { it.date >= from }
}

internal fun isCurrentMonth(yearMonth: String): Boolean =
    yearMonth == FinanceViewModel.currentMonthStr()

/** 今日（当月为当前月时）的支出 / 收入。 */
internal fun todayTotals(transactions: List<Transaction>, yearMonth: String): Pair<Double, Double> {
    if (!isCurrentMonth(yearMonth)) return 0.0 to 0.0
    val today = LocalDate.now().toString()
    var expense = 0.0
    var income = 0.0
    transactions.filter { it.date == today }.forEach { tx ->
        val magnitude = if (tx.isRefundEntry) tx.amount else kotlin.math.abs(tx.amount)
        if (tx.isExpense) expense += magnitude else if (tx.isIncome) income += magnitude
    }
    return expense to income
}
