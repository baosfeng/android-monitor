package com.bsfeng.monitor.finance.importer

// 账单导入的**共享契约**：中间模型 + 结果类型 + 渠道枚举。
// 解析侧（BillFileParser 及容器解析）与入库侧（BillImporter）都只依赖本文件，
// 两边可以并行开发；改这里等于改两边的接口，改前先看 BillRecordTest。

/**
 * 账单来源渠道。
 *
 * [fileHint] 只用于**识别**（文件名 / 首行关键字），不参与入库；入库身份键用 [sourcePrefix]。
 */
enum class BillChannel(val label: String, val sourcePrefix: String, val fileHint: List<String>) {
    ALIPAY("支付宝", "alipay_import", listOf("alipay", "支付宝")),
    WECHAT("微信", "wechat_import", listOf("wechat", "微信", "tenpay")),
    UNKNOWN("未知", "bill_import", emptyList()),
    ;

    companion object {
        /** 按文件名 / 首行猜测渠道；认不出返回 [UNKNOWN]（由表头探测兜底） */
        fun guess(fileName: String): BillChannel {
            val lower = fileName.lowercase()
            return entries.firstOrNull { channel ->
                channel != UNKNOWN && channel.fileHint.any { lower.contains(it) }
            } ?: UNKNOWN
        }
    }
}

/** 收/支方向；[SKIP] 表示这一行不该进账本（不计收支 / 交易关闭 / 本人账户转账） */
enum class BillDirection { EXPENSE, INCOME, SKIP }

/**
 * 一行账单（**已归一**，与来源格式解耦）。
 *
 * 字段全部允许为空串：两家导出的列集不同（支付宝有「商品名称」，微信叫「商品」；
 * 有的版本没有平台分类列），缺失就是空串，绝不用占位符填空 —— 空串在入库侧有明确语义（不写该列）。
 *
 * @param orderId 交易单号（身份键首选）
 * @param tradeTime 原始交易时间文本，形如 `2026-11-06 20:40:51` 或 `2026/11/06 20:40`
 * @param amount 金额**绝对值**（方向由 [direction] 表达，不要在这里带符号）
 * @param counterparty 交易对方（商户名）
 * @param product 商品名称 / 商品说明
 * @param payMethod 支付方式 / 付款方式
 * @param platformCategory 平台自带的分类原文（可能为空）
 * @param status 交易状态原文（交易成功 / 已全额退款 …），用于判定跳过
 * @param directionText 收/支列原文（收入 / 支出 / 不计收支 / 空）
 */
data class BillRecord(
    val channel: BillChannel,
    val orderId: String = "",
    val tradeTime: String = "",
    val amount: Double = 0.0,
    val direction: BillDirection = BillDirection.EXPENSE,
    val counterparty: String = "",
    val product: String = "",
    val payMethod: String = "",
    val platformCategory: String = "",
    val status: String = "",
    val directionText: String = "",
    /** 原始行号（1 基，含表头）—— 失败明细要能指回文件里的哪一行 */
    val lineNo: Int = 0,
)

/** 一次导入的统计（UI 结果卡直接读它）。 */
data class BillImportResult(
    val totalRows: Int = 0,
    val inserted: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val categoriesResolved: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()

    /** 有写入（插入或更新）才算成功，用来决定要不要 notifyChanged */
    val hasWrites: Boolean get() = inserted > 0 || updated > 0
}
