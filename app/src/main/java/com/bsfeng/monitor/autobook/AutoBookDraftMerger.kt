package com.bsfeng.monitor.autobook

import kotlin.math.abs

/**
 * 把「支付成功页解析结果」与「付款前页面预采集」合并（纯逻辑，可 JVM 单测）。
 *
 * 真机背景（2026-10-17，微信转账）：支付成功页只有 `支付成功 + 金额 + 完成`，
 * 收款方、付款方式、用户填的转账说明全都没有 —— 这些只在付款前页面出现。
 * 因此付款前页面不弹窗、只缓存 [PrePaymentDraft]；成功页到了以后再合并。
 *
 * 匹配条件（宁可不用也不能配错）：
 * - 同一渠道；
 * - 金额一致（±0.01，页面可能有四舍五入）；
 * - 预采集未过期（[PRE_DRAFT_TTL_MS]）。
 *
 * 字段策略：**成功页有的优先，成功页空白的才用预采集补** —— 成功页是最终账
 * 单口径，预采集只补它缺的信息。
 */
internal object AutoBookDraftMerger {

    /** 预采集有效期：从付款页到成功页通常几秒~几十秒，给 2 分钟余量。 */
    const val PRE_DRAFT_TTL_MS = 120_000L

    /** 金额容差：两端页面可能一个有 `¥` 一个没有，但数值应一致。 */
    private const val AMOUNT_TOLERANCE = 0.01

    /**
     * 付款前页面是分步的：输入页有收款方 + 用户备注，收银台/指纹页有付款方式。
     * 后一页覆盖前一页会把备注丢掉，因此按字段**累积**：新页有的用新页，空白的沿用旧页。
     */
    fun accumulate(previous: PrePaymentDraft, newer: PrePaymentDraft): PrePaymentDraft {
        if (previous.channel != newer.channel) return newer
        return newer.copy(
            merchant = newer.merchant.ifBlank { previous.merchant },
            payMethod = newer.payMethod.ifBlank { previous.payMethod },
            remark = newer.remark.ifBlank { previous.remark },
        )
    }

    /**
     * @return 合并后的草稿；不满足匹配条件时原样返回 [result]（不做任何修改）。
     */
    fun merge(result: AutoBookDraft, pre: PrePaymentDraft?, nowMs: Long): AutoBookDraft {
        if (pre == null) return result
        if (nowMs - pre.capturedAtMs > PRE_DRAFT_TTL_MS) return result
        if (pre.channel != result.channel) return result
        if (abs(pre.amount - result.amount) > AMOUNT_TOLERANCE) return result

        return result.copy(
            merchant = result.merchant.ifBlank { pre.merchant },
            payMethod = result.payMethod.ifBlank { pre.payMethod },
            userRemark = result.userRemark.ifBlank { pre.remark },
        )
    }
}
