package com.bsfeng.monitor.finance.importer

// 账单分类自动推断：平台分类列 → 商品名称 → 交易对方 → 兜底。
// 纯逻辑，不碰数据库；关键词表见 BillCategoryKeywords。

/**
 * 把一行账单推断成分类名。
 *
 * **顺序即优先级**（见 docs/记账/账单导入.md「分类推断」）：信息量越大的字段越先看。
 * 商品名称排在交易对方之前，是因为后者常是平台泛称（「淘宝闪购」「财付通」），
 * 而商品名带品类（「某某湘菜馆外卖订单」）。
 *
 * 每一档都要求命中值落在 [com.bsfeng.monitor.finance.model.CategoryCatalog] 内 —— 关键词表
 * 写了已下线的分类名时会继续往后找，不会把分类名写到表外。
 */
internal object BillCategoryResolver {

    /** @return 分类名（一定落在分类表内）；三档都没命中时按方向兜底 */
    fun resolve(record: BillRecord): String {
        val isIncome = record.direction == BillDirection.INCOME
        val candidates = listOf(record.platformCategory, record.product, record.counterparty)
        for (candidate in candidates) {
            val hit = BillCategoryKeywords.lookup(candidate, isIncome)
            if (hit != null) return hit
        }
        return fallback(isIncome)
    }

    /** 兜底分类名：支出「其他」/ 收入「其他收入」 */
    fun fallback(isIncome: Boolean): String =
        if (isIncome) BillCategoryKeywords.FALLBACK_INCOME else BillCategoryKeywords.FALLBACK_EXPENSE

    /** 是否为兜底分类（导入结果卡的「已识别分类数」不算兜底项） */
    fun isFallback(categoryName: String): Boolean =
        categoryName == BillCategoryKeywords.FALLBACK_EXPENSE ||
            categoryName == BillCategoryKeywords.FALLBACK_INCOME
}
