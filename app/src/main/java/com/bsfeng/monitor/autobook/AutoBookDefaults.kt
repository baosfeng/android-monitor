package com.bsfeng.monitor.autobook

import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 自动记账的「商户默认值」（纯逻辑，可 JVM 单测）。
 *
 * 需求（2026-10-17）：「如果我上次扫过一家店，选的类型是什么，现在默认帮我勾选，否则就和现在一样」。
 *
 * 规则：
 * - 只按商户名精确匹配（[FinanceDao.findLastByMerchant]）；查的是**已确认落库**的记录，
 *   忽略 / 只是弹过没记的不会影响默认值；
 * - 商户为空 / 没有历史 → 完全保持解析器的结果（与现状一致）；
 * - 历史记录的类型非法（不在 0..2）→ 只沿用分类，类型仍用解析器的；
 * - 历史分类为空或**不在当前分类表里**（迁移前的「日用」「手机数码」等旧扁平名）→ 忽略，
 *   回退解析结果；分类名与类型不同侧（历史是收入分类却要套到支出上）同样忽略；
 * - 历史分类是二级名时原样沿用，浮窗仍能按名称高亮到二级。
 */
internal object AutoBookDefaults {

    /** 支出侧合法分类名（一级 + 二级） */
    private val expenseNames: Set<String> by lazy {
        CategoryCatalog.expense.flatMap { listOf(it.name) + it.children }.toSet()
    }

    /** 收入侧合法分类名（一级 + 二级） */
    private val incomeNames: Set<String> by lazy {
        CategoryCatalog.income.flatMap { listOf(it.name) + it.children }.toSet()
    }

    fun apply(draft: AutoBookDraft, last: Transaction?): AutoBookDraft {
        if (last == null || draft.merchant.isBlank()) return draft
        val type = if (last.type in AutoBookDraft.TYPE_EXPENSE..AutoBookDraft.TYPE_TRANSFER) {
            last.type
        } else {
            draft.type
        }
        val historyCategory = last.categoryName.trim()
        val category = historyCategory.takeIf { isValidCategory(it, type) } ?: draft.categoryName
        return draft.copy(
            type = type,
            categoryName = category,
        )
    }

    /** 分类名必须属于该类型的分类树；转账按支出侧处理（与浮窗分类格同一口径） */
    private fun isValidCategory(name: String, type: Int): Boolean = when {
        name.isEmpty() -> false
        type == AutoBookDraft.TYPE_INCOME -> name in incomeNames
        else -> name in expenseNames
    }
}
