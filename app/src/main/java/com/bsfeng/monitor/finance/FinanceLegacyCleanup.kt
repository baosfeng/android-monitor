package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceManager.kt 的旧版拼接说明清理；职责：判定并清空 v1 自动记账拼接出来的 remark。

/** 旧版自动记账「说明」清理（拆分自 [FinanceManager]） */
internal class FinanceLegacyCleanup(private val dao: FinanceDao) {

    /**
     * 清理旧版拼接说明（幂等，可重复调用；单测直接调它）。
     *
     * 判定完全在 [LegacyRemarkCleaner] 的纯逻辑里：只有 `source` 以 `auto_` 开头、
     * 旧拼接至少两段且与字段重算结果完全一致才清空。只写 remark 列，不动金额 / 余额 / 时间。
     *
     * @return 实际清空的条数
     */
    fun cleanLegacyAggregatedRemarks(): Int {
        val rows = dao.getAutoTransactionsWithRemark()
        if (rows.isEmpty()) return 0
        var cleaned = 0
        dao.runInTransaction {
            for (tx in rows) {
                val channelLabel = LegacyRemarkCleaner.channelLabelOf(tx.source)
                if (LegacyRemarkCleaner.isLegacyAggregate(
                        tx.remark, tx.merchant, tx.productDesc, tx.payMethod, channelLabel
                    )
                ) {
                    cleaned += dao.clearTransactionRemark(tx.id)
                }
            }
        }
        return cleaned
    }
}
