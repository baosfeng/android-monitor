package com.bsfeng.monitor.autobook

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
 * - 历史分类为空 → 只沿用类型。
 */
internal object AutoBookDefaults {

    fun apply(draft: AutoBookDraft, last: Transaction?): AutoBookDraft {
        if (last == null || draft.merchant.isBlank()) return draft
        val type = if (last.type in AutoBookDraft.TYPE_EXPENSE..AutoBookDraft.TYPE_TRANSFER) {
            last.type
        } else {
            draft.type
        }
        return draft.copy(
            type = type,
            categoryName = last.categoryName.ifBlank { draft.categoryName },
        )
    }
}
