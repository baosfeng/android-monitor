package com.bsfeng.monitor.finance.ui

/**
 * 交易来源徽标的判定（纯逻辑，可 JVM 单测）。
 *
 * 原型 s04：自动记账落库的行在标题右侧、金额左边带一个小号「自动」徽标，
 * 用户一眼区分「系统帮我记的」与「我自己写的」。判定口径按落库字段来，不猜分类：
 * - `source` 以 `auto_` 开头（`AutoBookEngine.SOURCE_PREFIX` + 渠道，如 `auto_alipay`）；
 * - 或带 `sourceId` 的非手动来源（导入行同样有外部单据 id，同属「非手写」）。
 *
 * 手动录入（`source = "manual"`、无 sourceId）恒为 false —— 用户手写的编辑态不受影响。
 */
object TransactionSourceBadge {

    /** 徽标文案（原型 .tx-auto「自动」） */
    const val AUTO_LABEL = "自动"

    /** 自动记账落库来源前缀，与 [com.bsfeng.monitor.autobook.AutoBookEngine] 同一口径 */
    const val AUTO_SOURCE_PREFIX = "auto_"

    /** 手动录入来源标记 */
    private const val MANUAL_SOURCE = "manual"

    /** 这一行是否要显示「自动」徽标 */
    fun isAutoBooked(source: String, sourceId: String): Boolean =
        source.startsWith(AUTO_SOURCE_PREFIX) ||
            (source != MANUAL_SOURCE && sourceId.isNotBlank())
}
