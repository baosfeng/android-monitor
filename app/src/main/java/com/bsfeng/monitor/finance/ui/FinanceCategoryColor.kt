package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的分类色映射；职责：分类名 → 色板槽位/颜色的唯一口径（三屏共用）。

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot

/**
 * 分类 → 颜色的**唯一**映射：s04 交易行色点 / s05 分类网格 / s07 图例色点共用同一函数，
 * 因此同一分类在任何一屏取到同一个颜色。
 *
 * 色板是跨主题固定的 chart-1..chart-16：切主题时分类身份色不变（只有序列色跟着主题走）。
 */
@Composable
internal fun categoryColorOf(name: String): Color =
    LocalDesignTokens.current.categoryDot(categorySlotOf(name))

/**
 * 分类槽位（分类色板 chart-1..chart-16 的下标）。
 *
 * 支出 / 收入分类按 [Category] 的预设顺序取槽 —— 该顺序与 finance_categories.sort_order
 * 一致，因此相邻分类天然不重色；自定义分类再按名称稳定哈希兜底（跨屏 / 跨进程结果一致）。
 */
internal fun categorySlotOf(name: String): Int {
    val expense = Category.defaultExpenseCategories.indexOf(name)
    if (expense >= 0) return expense
    val income = Category.defaultIncomeCategories.indexOf(name)
    if (income >= 0) return Category.defaultExpenseCategories.size + income
    var hash = 0
    for (ch in name) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
    return hash % CHART_SLOT_COUNT
}

/** 分类色板共 16 槽（chart-1..chart-16）。 */
private const val CHART_SLOT_COUNT = 16
