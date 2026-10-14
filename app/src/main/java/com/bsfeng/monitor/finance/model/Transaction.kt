package com.bsfeng.monitor.finance.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 单笔交易记录数据类。
 *
 * @param type 0=支出, 1=收入, 2=转账, 3=退款
 * @param source 来源标记, 如 "manual"（手动录入）, "panda_import"（熊猫记账导入）
 */
data class Transaction(
    val id: Long = 0,
    val type: Int = 0,
    val categoryName: String = "",
    val amount: Double = 0.0,
    val accountName: String = "",
    val remark: String = "",
    val tag: String = "",
    val date: String = "",
    val dateTime: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "manual",
    val sourceId: String = ""
) {
    val isExpense: Boolean get() = type == 0
    val isIncome: Boolean get() = type == 1
    val isTransfer: Boolean get() = type == 2
    val isRefund: Boolean get() = type == 3

    /** 负数支出：熊猫记账退款导入后为此形态，用于抵扣当月支出而非虚增支出 */
    val isRefundEntry: Boolean get() = isExpense && amount < 0

    /** 展示用金额（绝对值），符号由 [isExpense] / [isIncome] 决定 */
    val displayAmount: String get() = String.format(java.util.Locale.CHINA, "%.2f", kotlin.math.abs(amount))

    /** 带符号金额：支出/退款为负，收入为正 */
    val signedAmount: Double get() = if (isExpense || isRefund) -amount else amount

    companion object {
        /**
         * 组合「日期 + 时刻」为交易时间戳（yyyy-MM-dd HH:mm）。
         *
         * ⚠️ 时刻必须取自 [LocalDateTime] / [LocalTime]。用 [java.time.LocalDate] 去
         * `format("HH:mm")` 会抛 UnsupportedTemporalTypeException（Unsupported field: HourOfDay），
         * 导致记账页一点「保存」就崩溃；App 崩溃后系统会把焦点还给后台其它任务，
         * 表面现象很像「点保存跳到了别的 App」（2026-10 真机踩坑）。
         */
        fun buildDateTime(date: String, now: LocalDateTime = LocalDateTime.now()): String =
            "$date ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
}
