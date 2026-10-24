package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookEngine.kt 拆出：解析不出账单时的兜底识别（红包流程 + 备注弹窗）。
// 红包有两条路径：付款前页面只预采集，付款后可能回到聊天页（气泡不带金额）。

import android.util.Log

/**
 * 解析不出账单时的兜底识别：红包付款流程 + 备注弹窗。
 *
 * @return 识别到的草稿；备注弹窗已单独记下（[pendingRemark]）时返回 null。
 */
internal fun AutoBookEngine.fallbackDraft(rule: AutoBookRule, lines: List<String>, now: Long): AutoBookDraft? {

        // 记住「刚走过红包付款前页面」：聊天页兜底只用它的金额，并据此区分新旧红包气泡
        if (AutoBookParser.isRedPacketPrePayPage(lines)) {
            redPacketPaymentPending = true
            Log.d(AutoBookEngine.TAG, "红包付款前页面：等待发出后聊天页的红包气泡")
        }
        if (redPacketPaymentPending && AutoBookParser.isRedPacketFingerprintPage(lines)) {
            redPacketFingerprintArmed = true
            Log.d(AutoBookEngine.TAG, "红包付款已验证指纹/密码：允许从聊天页气泡补记")
        }
        // 微信「添加备注」弹窗：没有金额，不会进预采集；但用户填的备注只在这里可见。
        val dialogRemark = try {
            AutoBookParser.parseRemarkDialog(lines)
        } catch (e: Exception) {
            null
        }
        if (dialogRemark != null) {
            pendingRemark = dialogRemark
            preDraft = preDraft?.copy(remark = dialogRemark)
            Log.d(AutoBookEngine.TAG, "捕获备注弹窗: $dialogRemark")
            return null
        }

        // 支付宝红包兜底：付款成功后没有结果页，而是回到聊天页（气泡不带金额）。
        // 必须排在付款前页面预采集之前 —— 聊天页底部有「发红包」快捷入口，
        // 但它不含金额/付款页特征，不会进预采集；这里只用付款前缓存的金额。
        val draft = try {
            AutoBookParser.parseSentRedPacketChat(
                rule, lines, preDraft, now,
                redPacketFlowPending = redPacketPaymentPending,
                allowBubbleOnly = redPacketFingerprintArmed
            )
        } catch (e: Exception) {
            Log.w(AutoBookEngine.TAG, "红包聊天页解析失败: ${e.message}")
            null
        }
        if (draft != null) {
            Log.d(
                AutoBookEngine.TAG,
                "聊天页识别到已发出的红包: amount=${draft.amount} 领取人=${draft.merchant}"
            )
        }
        return draft
}

/**
 * 付款前页面预采集：**不弹窗**，只把收款方 / 付款方式 / 备注记进 preDraft。
 *
 * 这些信息只在付款前页面出现（转账输入 / 收银台 / 指纹 / 确认付款），成功页通常只给金额；
 * 成功页解析出的空白字段由 AutoBookDraftMerger.merge 用它补齐。
 */
internal fun AutoBookEngine.collectPrePayment(rule: AutoBookRule, lines: List<String>, now: Long, packageName: String) {
    // 付款前页面（转账输入 / 收银台 / 指纹 / 确认付款）：**不弹窗**，只预采集。
    // 收款方、付款方式、用户备注这些信息只在付款前页面出现；成功页通常只给金额。
    val pre = try {
        AutoBookParser.parsePrePayment(rule, lines, now)
    } catch (e: Exception) {
        Log.w(AutoBookEngine.TAG, "付款前页面预采集失败: ${e.message}")
        null
    }
    if (pre != null) {
        // 备注弹窗先于本页出现时，把备注补进来
        val savedRemark = pendingRemark
        val withRemark = if (pre.remark.isBlank() && !savedRemark.isNullOrBlank()) {
            pre.copy(remark = savedRemark)
        } else pre
        // 付款前是分步的（输入页 → 收银台 → 指纹）：按字段累积，后一页不能把备注冲掉
        preDraft = preDraft?.let { AutoBookDraftMerger.accumulate(it, withRemark) } ?: withRemark
        Log.d(
            AutoBookEngine.TAG,
            "付款前页面预采集: amount=${withRemark.amount} merchant=${withRemark.merchant} " +
                "payMethod=${withRemark.payMethod} remark=${withRemark.remark}"
        )
        return
    }

    // 诊断刚需：能区分「没收到事件」「不是账单页（门禁挡掉）」「是账单页但字段没认出来」。
    // 打印实际文本（截断），否则无法判断是页面结构变了还是节点被系统裁剪。
    val preview = lines.joinToString(" | ").take(400)
    val gate = if (AutoBookParser.isBillPage(lines)) "门禁通过,字段不足" else "非账单页,门禁拒绝"
    Log.d(AutoBookEngine.TAG, "未识别到账单: $packageName [$gate] 行数=${lines.size} 文本=[$preview]")
}
