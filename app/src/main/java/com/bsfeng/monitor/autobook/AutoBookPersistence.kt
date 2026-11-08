package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookEngine.kt 拆出：分类名归一与幂等落库。
// 落库口径：同 source + sourceId 已存在则跳过；交易时间优先用页面上的，退不到才用记账时刻。

import android.util.Log
import com.bsfeng.monitor.finance.FinanceInsertSignal
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.util.DebugConfig
import java.time.LocalDateTime

/**
 * 落库前的分类校验：名字必须属于该类型的分类树（一级或二级），否则回退一级兜底。
 *
 * 分类名可能来自三处：识别映射（应已是树内名字）、浮窗里用户的选择（树内）、
 * 或「商户上次选择」的历史值（可能已被分类体系升级后的迁移改掉）。最后一种如果原样落库，
 * 统计与预算就会遇到分类表里不存在的名字 —— 这里统一兜住。
 *
 * 树加载失败（空树）时保留原名：宁可分类名游离，也不能让用户确认过的这笔账记不下来。
 */
internal fun AutoBookEngine.sanitizeCategoryName(tree: CategoryTree, type: Int, name: String): String {
    val pickerType = if (type == AutoBookDraft.TYPE_INCOME) 1 else 0
    val names = tree.topLevel(pickerType).flatMap { top ->
        listOf(top.name) + tree.childrenOf(top.name).map { it.name }
    }
    if (names.isEmpty()) return name
    if (names.contains(name)) return name
    val fallback = if (pickerType == 1) INCOME_FALLBACK else CategoryCatalog.FALLBACK
    val picked = if (names.contains(fallback)) fallback else names.first()
    // 分类名来自用户账本，属于账目内容：只在调试闸门后输出
    DebugConfig.log("AutoBook 分类名不在分类树中，回退: [$name] → [$picked]")
    return picked
}

/**
 * 幂等落库：同 source + sourceId 已存在时直接跳过（同一笔被反复确认只记一次）。
 *
 * @param editedRemark 用户在浮窗「说明」行里填的文字（空串 = 不写说明）
 */
internal suspend fun AutoBookEngine.persist(
    draft: AutoBookDraft,
    type: Int,
    categoryName: String,
    editedRemark: String,
    tree: CategoryTree,
) {
    try {
        val source = AutoBookEngine.SOURCE_PREFIX + draft.channel
        if (financeManager.dao.transactionExists(source, draft.sourceId)) {
            Log.d(AutoBookEngine.TAG, "该笔已记账，跳过: ${draft.sourceId}")
            return
        }

        val bookedCategory = sanitizeCategoryName(tree, type, categoryName)
        val now = LocalDateTime.now()
        // 交易时间优先用**页面上的**（对账口径）；页面没给或格式不认识才退回记账时刻。
        // 用「点记一笔的时刻」会让同一笔在不同时间被识别时算出不同的落库键
        // （真机出现过同一笔 18:32 的交易被记成 20:51、21:00 两条）。
        val dateTime = AutoBookParser.normalizeTradeTime(draft.timeText)
            ?: Transaction.buildDateTime(now.toLocalDate().toString(), now)
        val date = dateTime.substringBefore(" ")
        val transaction = Transaction(
            type = type,
            categoryName = bookedCategory,
            amount = draft.amount,
            accountName = draft.accountName,
            // 账本「说明」：只用用户在浮窗里填的文字（编辑值优先、空则不写）。
            // 商户 / 商品说明 / 付款方式已是独立字段，不再拼进说明。
            remark = draft.bookedRemark(editedRemark),
            date = date,
            dateTime = dateTime,
            source = source,
            sourceId = draft.sourceId,
            merchant = draft.merchant,
            productDesc = draft.productDesc,
            payMethod = draft.payMethod,
            orderId = draft.orderId,
            platformCategory = draft.platformCategory,
            tradeTime = draft.timeText
        )

        val id = financeManager.addTransaction(transaction)
        // 记账列表靠这个一次性信号做「顶部新行高亮 1.6s」：只有自动记账落库才点亮，
        // 手写记录 / 切 Tab / 回前台重载都不点亮（见 FinanceInsertSignal）
        FinanceInsertSignal.mark(id)
        // 只留「写成功没有 + 落在哪一类」：金额与商户是隐私红线，不进 logcat。
        Log.i(AutoBookEngine.TAG, "自动记账已写入 id=$id 分类=$bookedCategory 渠道=${draft.channel}")
    } catch (e: Exception) {
        Log.e(AutoBookEngine.TAG, "自动记账落库失败: ${e.message}", e)
    }
}
