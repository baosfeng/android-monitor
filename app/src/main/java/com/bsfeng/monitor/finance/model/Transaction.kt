package com.bsfeng.monitor.finance.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 单笔交易记录数据类。
 *
 * @param type 0=支出, 1=收入, 2=转账, 3=退款
 * @param source 来源标记, 如 "manual"（手动录入）, "panda_import"（熊猫记账导入）
 * @param merchant 商户名（自动记账从页面上取，手动录入为空）
 * @param productDesc 商品说明（对账信息量最大的字段，如「某某湘菜馆(示例店)外卖订单」）
 * @param payMethod 实际付款方式（如「招商银行信用卡(8888)」/「余额宝」/「零钱」），与 [accountName] 不同：
 *                  [accountName] 是记账账户（支付宝 / 微信钱包，用于算余额），本字段是资金来源（用于按卡统计）
 * @param orderId 订单号 / 交易单号，跨源对账用
 * @param platformCategory 平台自带分类原文（如「餐饮美食」），用于校准我们自己的分类映射
 * @param tradeTime 页面上的**原始交易时间**（如 `2026-10-16 18:32:21`）；[date] / [dateTime] 是它的规范化结果
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
    val sourceId: String = "",
    val merchant: String = "",
    val productDesc: String = "",
    val payMethod: String = "",
    val orderId: String = "",
    val platformCategory: String = "",
    val tradeTime: String = ""
) {
    val isExpense: Boolean get() = type == 0
    val isIncome: Boolean get() = type == 1
    val isTransfer: Boolean get() = type == 2
    val isRefund: Boolean get() = type == 3

    /** 负数支出：熊猫记账退款导入后为此形态，用于抵扣当月支出而非虚增支出 */
    val isRefundEntry: Boolean get() = isExpense && amount < 0

    /**
     * 展示用金额（绝对值），符号由 [isExpense] / [isIncome] 决定。
     *
     * 金额口径（全屏唯一）：交易行 = 千分位分组 + 固定两位小数，对齐原型 s04-tx-1 的 -¥38.00、
     * s04-tx-6 的 +¥1,200.00；汇总 / KPI / 预算 = 千分位分组 + 0 位小数（FinanceUIKit.yen(value, 0)），
     * 对齐原型 s04-krow 的 ¥2,468 与 s07-kv 的 ¥2,468。
     * 两者同源（String.format 的 %,.Nf），差异只在上下文小数位，不再有第二套格式。
     */
    val displayAmount: String
        get() = String.format(java.util.Locale.CHINA, "%,.2f", kotlin.math.abs(amount))

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
