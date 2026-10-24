package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookEngine.kt 拆出：账单识别之后的两条出口 ——
// 「交易详情页补全已记账的那笔」与「查库兜底去重后弹浮窗」。

import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 交易详情页：匹配已记账的那笔并补全字段。
 *
 * 用户需求（2026-10-17）：详情页信息更全（订单号 / 精确时间 / 商品说明 / 商户全称），
 * 已经记过账的那笔不要再弹记账浮窗，而是补全已有记录，并弹 Toast「详情信息已补全」。
 *
 * 匹配不到已记账记录时，说明这笔是第一次见 —— 走原来的弹窗流程（含去重）。
 * 详情页会连续抛多个事件，用 [detailEnriching] 保证同一时间只跑一个；补全后重复事件
 * 不会再有字段变化，因此不会重复 Toast。
 */
internal fun AutoBookEngine.handleDetailPage(draft: AutoBookDraft) {
    if (!detailEnriching.compareAndSet(false, true)) return
    scope.launch {
        try {
            val source = AutoBookEngine.SOURCE_PREFIX + draft.channel
            val candidates = financeManager.dao.findAutoTransactionsByAmount(source, draft.amount)
            val match = AutoBookDetailEnricher.findMatch(
                candidates, draft, System.currentTimeMillis()
            )
            if (match == null) {
                Log.d(AutoBookEngine.TAG, "详情页没有匹配到已记账记录，按新账单处理")
                val blocked = dedup.blockedReason(draft.dedupKeys, System.currentTimeMillis())
                if (blocked != null) {
                    Log.d(AutoBookEngine.TAG, "去重命中，本次不弹窗：$blocked")
                    return@launch
                }
                showOverlay(draft)
                return@launch
            }

            val result = AutoBookDetailEnricher.enrich(
                match, draft, System.currentTimeMillis()
            )
            if (!result.changed) {
                Log.d(AutoBookEngine.TAG, "详情页没有新增信息，不提示: id=${match.id}")
                return@launch
            }
            financeManager.dao.updateTransaction(result.transaction)
            // 详情补全只补字段、不走 FinanceManager.updateTransaction（那会重算账户余额）；
            // 但 UI 也要立刻看到补全后的商户 / 商品说明，所以显式发一次数据变更通知。
            financeManager.notifyChanged()
            Log.i(
                AutoBookEngine.TAG,
                "已记账笔补全详情: id=${match.id} merchant=${result.transaction.merchant} " +
                    "payMethod=${result.transaction.payMethod} orderId=${result.transaction.orderId} " +
                    "time=${result.transaction.dateTime}"
            )
            service.mainExecutor.execute {
                Toast.makeText(service, "详情信息已补全", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(AutoBookEngine.TAG, "详情页补全失败: ${e.message}")
        } finally {
            detailEnriching.set(false)
        }
    }
}

/**
 * 补分类数据、查库兜底去重，然后弹出浮窗。
 *
 * 查库这步是为了覆盖**进程重启**：内存去重表随服务重启清空，但「这一笔已经记过账」
 * 是数据库里的事实，据此可以继续挡住重复弹窗。
 */
internal fun AutoBookEngine.showOverlay(draft: AutoBookDraft) {
    // 同步占位：同一笔的连续事件（详情页后加载商品说明）在第一次 display 完成前就到，
    // 靠 overlay.currentDraft 拦不住（窗口还没挂上），这里先占住 sourceId。
    if (draft.sourceId == pendingOverlaySourceId) return
    pendingOverlaySourceId = draft.sourceId
    scope.launch {
        val loaded = categoryTree ?: loadCategoryTree().also { categoryTree = it }
        val categoriesAt = System.currentTimeMillis()
        // 已记账查询与「同商户上次选择」互不依赖：并行查，少等一次 SQLite 往返
        val booked: Boolean
        val history: Transaction?
        coroutineScope {
            val bookedDeferred = async { isAlreadyBooked(draft) }
            val historyDeferred = async {
                try {
                    if (draft.merchant.isBlank()) null
                    else financeManager.getLastTransactionByMerchant(draft.merchant)
                } catch (e: Exception) {
                    Log.w(AutoBookEngine.TAG, "查询商户历史失败（按无历史处理）: ${e.message}")
                    null
                }
            }
            booked = bookedDeferred.await()
            history = historyDeferred.await()
        }
        if (booked) {
            Log.d(AutoBookEngine.TAG, "该笔已记账（查库命中），不再弹窗: ${draft.sourceId}")
            return@launch
        }
        if (ignoreStore.isIgnored(draft.sourceId)) {
            Log.d(AutoBookEngine.TAG, "该笔已被用户忽略，不再弹窗: ${draft.sourceId}")
            return@launch
        }
        val enriched = AutoBookDefaults.apply(draft, history)
        if (history != null) {
            Log.d(
                AutoBookEngine.TAG,
                "商户「${draft.merchant}」上次选择: type=${history.type} " +
                    "category=${history.categoryName} → 默认勾选"
            )
        }
        // 回到主线程挂 View（WindowManager 操作要求在同一线程，无障碍服务的主线程即主 Looper）
        val dbAt = System.currentTimeMillis()
        Log.i(
            AutoBookEngine.TAG,
            "延迟追踪: 事件→文本=${traceLinesAtMs - traceEventAtMs}ms " +
                "文本→解析=${traceParsedAtMs - traceLinesAtMs}ms " +
                "解析→分类=${categoriesAt - traceParsedAtMs}ms " +
                "分类→查库=${dbAt - categoriesAt}ms"
        )
        service.mainExecutor.execute { display(enriched, loaded, dbAt) }
    }
}
