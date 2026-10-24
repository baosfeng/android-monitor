package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的格式化函数；职责：金额/计数/月份标签的文本格式化（五屏共用同一口径）。

import java.util.Locale

/** 千分位金额，[decimals] 位小数（原型 kpi 用 0 位、列表行用 2 位）。 */
internal fun formatMoney(value: Double, decimals: Int = 0): String =
    String.format(Locale.CHINA, "%,.${decimals.coerceIn(0, 6)}f", value)

/** 千分位整数（笔数 / 记录数）。 */
internal fun formatCount(value: Int): String = String.format(Locale.CHINA, "%,d", value)

/** 带 ¥ 前缀的金额。 */
internal fun yen(value: Double, decimals: Int = 0): String = "¥" + formatMoney(value, decimals)

/** 月份标签：2026-10 → 2026 年 10 月（与原型一致，保留空格）。 */
internal fun monthLabelOf(yearMonth: String): String {
    val parts = yearMonth.split("-")
    if (parts.size < 2) return yearMonth
    val month = parts[1].toIntOrNull() ?: return yearMonth
    return parts[0] + " 年 " + month + " 月"
}
