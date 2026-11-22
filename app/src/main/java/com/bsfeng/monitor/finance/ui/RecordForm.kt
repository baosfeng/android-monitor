package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.finance.model.Transaction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 保存前的阻塞原因（对应原型 s04-记账.html:738-741 的 data-save 分支）。
 *
 * 拆成三条而不是「能 / 不能」两态：金额是可当场纠正的输入错误（原型给抖动 + toast），
 * 分类、转账账户是结构性问题（沿用禁用态即可）。反馈方式不同，但判定口径只有一处 ——
 * [RecordForm.canSave] 恒等于 `saveCheck(...) == null`。
 */
internal enum class SaveBlock {
    /** 金额为空 / 为 0 / 非数字（[RecordForm.amountOf] 已把非法输入归 0） */
    AMOUNT,

    /** 支出 / 收入没选分类 */
    CATEGORY,

    /** 转账的转出、转入缺失或相同 */
    TRANSFER_ACCOUNTS,
}

/**
 * 「记一笔」表单的纯逻辑（无 Compose 依赖，便于单测）。
 *
 * 对应定版原型 `26-a-quan-chouti.html` 的 s14a 屏：一屏放得下的表单 + 四个抽屉。
 * 这里只放**可测的取值/判定规则**，UI 组合留在 [AddTransactionScreen] / [RecordSheets]。
 */
internal object RecordForm {

    /** 交易类型：支出（与 [Transaction.isExpense] 同一口径） */
    const val TYPE_EXPENSE = 0

    /** 交易类型：收入 */
    const val TYPE_INCOME = 1

    /** 交易类型：转账（两个账户一进一出，见 FinanceManager.applyTransactionEffect） */
    const val TYPE_TRANSFER = 2

    /** 类型分段控件的选项顺序（原型 s14a-kind：支出 / 收入 / 转账） */
    val TYPE_OPTIONS = listOf("支出", "收入", "转账")

    /** 时间行落在「日期 + 时刻」上；秒不单独选，默认沿用当前秒 / 原记录秒 */
    data class TimeParts(val date: String, val hour: Int, val minute: Int, val second: Int)

    /** 日期快捷 chip：label 是给人看的（今天 / 昨天 / 前天 / 10 月 14 日），date 是 ISO 日期 */
    data class DateShortcut(val label: String, val date: String)

    /** 「今天 / 昨天 / 前天」三个相对标签 */
    private val RELATIVE_LABELS = listOf("今天", "昨天", "前天")

    /** 时间行与日期 chip 用的日期格式：10 月 14 日 */
    private fun monthDayLabel(date: LocalDate): String =
        date.monthValue.toString() + " 月 " + date.dayOfMonth + " 日"

    /**
     * 日期快捷 chips：今天 / 昨天 / 前天 + 再往前 [extraDays] 天（原型给了 5 个）。
     *
     * @param extraDays 相对天之外的补充天数；为 0 时只给三个相对标签
     */
    fun dateShortcuts(today: LocalDate, extraDays: Int = 2): List<DateShortcut> {
        val safeExtra = extraDays.coerceAtLeast(0)
        return (0 until RELATIVE_LABELS.size + safeExtra).map { offset ->
            val day = today.minusDays(offset.toLong())
            val label = if (offset < RELATIVE_LABELS.size) RELATIVE_LABELS[offset] else monthDayLabel(day)
            DateShortcut(label = label, date = day.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }
    }

    /**
     * 时间行的显示文本：`今天 18:32:21` / `10 月 14 日 10:30:00`（原型 s14a-time-row 到秒）。
     *
     * 日期解析不了时直接用原文，不抛异常（编辑老数据时 date 可能是空串）。
     */
    fun timeRowText(parts: TimeParts, today: LocalDate): String {
        val parsed = runCatching { LocalDate.parse(parts.date) }.getOrNull()
        val prefix = when {
            parsed == null -> if (parts.date.isBlank()) RELATIVE_LABELS[0] else parts.date
            parsed == today -> RELATIVE_LABELS[0]
            parsed == today.minusDays(1) -> RELATIVE_LABELS[1]
            parsed == today.minusDays(2) -> RELATIVE_LABELS[2]
            else -> monthDayLabel(parsed)
        }
        return prefix + " " + clockText(parts)
    }

    /** 时刻文本 HH:mm:ss */
    fun clockText(parts: TimeParts): String = String.format(
        java.util.Locale.CHINA, "%02d:%02d:%02d", parts.hour, parts.minute, parts.second
    )

    /** 落库用的交易时间戳 yyyy-MM-dd HH:mm:ss（精确到秒） */
    fun timestampOf(parts: TimeParts): String =
        parts.date + " " + clockText(parts)

    /**
     * 从已有交易还原表单的时间：日期优先取 [date]，时刻取 [dateTime] 的时分秒。
     *
     * 解析不出来时退回 [now] 的时分秒（宁可时间降级，也不能让表单显示空值）。
     */
    fun timePartsOf(date: String, dateTime: String, now: LocalDateTime = LocalDateTime.now()): TimeParts {
        val parsedDateTime = parseDateTime(dateTime)
        val resolvedDate = date.ifBlank { parsedDateTime?.toLocalDate()?.toString() ?: now.toLocalDate().toString() }
        val time = parsedDateTime ?: now
        return TimeParts(resolvedDate, time.hour, time.minute, time.second)
    }

    /**
     * 解析 yyyy-MM-dd HH:mm(:ss) 两种形态（老记录只到分）。
     *
     * 用 [java.time.format.DateTimeFormatter] 的宽松模式逐个尝试，不做正则——正则版在秒可选时
     * 分组下标极易错位（[com.bsfeng.monitor.autobook.AutoBookParser.normalizeTradeTime] 用的是另一种口径）。
     */
    private fun parseDateTime(text: String): LocalDateTime? {
        if (text.isBlank()) return null
        return runCatching { LocalDateTime.parse(text, DATE_TIME_SECONDS) }
            .recoverCatching { LocalDateTime.parse(text, DATE_TIME_MINUTES) }
            .getOrNull()
    }

    private val DATE_TIME_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE_TIME_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 金额输入 → Double（非数字 / 空串按 0 处理，不抛异常） */
    fun amountOf(amountText: String): Double = amountText.toDoubleOrNull() ?: 0.0

    /**
     * 保存校验：通过返回 null，否则给出第一条阻塞原因。
     *
     * 顺序固定「金额 → 结构」：原型点保存先拦金额（抖动 + toast「请输入大于 0 的金额」，
     * s04-记账.html:738-741），金额合法后才轮到分类 / 转账账户。
     */
    fun saveCheck(
        type: Int,
        amountText: String,
        categoryName: String,
        accountName: String,
        toAccountName: String,
    ): SaveBlock? {
        if (amountOf(amountText) <= 0.0) return SaveBlock.AMOUNT
        if (type == TYPE_TRANSFER) {
            val accountsOk = accountName.isNotBlank() &&
                toAccountName.isNotBlank() &&
                accountName != toAccountName
            return if (accountsOk) null else SaveBlock.TRANSFER_ACCOUNTS
        }
        return if (categoryName.isNotBlank()) null else SaveBlock.CATEGORY
    }

    /**
     * 能否保存（= [saveCheck] 通过）。
     *
     * - 支出 / 收入：金额 > 0 且选中了**当前类型**下的分类（切类型后残留的旧分类不算）
     * - 转账：金额 > 0 且转出、转入账户都选了、且不是同一个账户
     */
    fun canSave(
        type: Int,
        amountText: String,
        categoryName: String,
        accountName: String,
        toAccountName: String,
    ): Boolean = saveCheck(type, amountText, categoryName, accountName, toAccountName) == null

    /**
     * 保存按钮能否点（= UI 的 enabled）—— 与 [canSave] 不是同一件事。
     *
     * - 金额无效（[SaveBlock.AMOUNT]）：**可点**。按钮保持可点，点下去抖金额卡 + toast，
     *   否则用户只看到一个点不动的灰按钮，既没反馈也问不出还差什么（原型 s04:735-749）。
     * - 分类未选 / 转账账户不合法：禁用。用户改得动，但这是结构性缺失，与 s14a「必须选分类」口径一致。
     * - 提交中：一律禁用（连点闸门，锚点 s14a-save）。
     */
    fun saveButtonEnabled(block: SaveBlock?, submitting: Boolean): Boolean =
        !submitting && (block == null || block == SaveBlock.AMOUNT)

    /**
     * 切换类型后校正分类：只在当前类型的分类里找，找不到就退回第一个。
     *
     * 不校正会出现「芯片全未选中却仍能保存支出分类的收入记录」（原 s05 已踩过）。
     */
    fun correctCategory(current: String, namesOfType: List<String>): String = when {
        namesOfType.isEmpty() -> ""
        namesOfType.contains(current) -> current
        else -> namesOfType.first()
    }

    /** 转账在交易列表里的标题：转出账户 → 转入账户（转入缺失时退回「转账」） */
    fun transferTitle(tx: Transaction): String {
        val from = tx.accountName
        val to = tx.toAccountName
        return if (from.isNotBlank() && to.isNotBlank()) from + " → " + to else "转账"
    }

    // ===== 单据（自动记账落库的结构化字段，原型 s14a-receipt-sheet 的 6 行） =====

    /** 单据明细的展示项；没有的字段不占位，避免满屏「—」 */
    fun receiptItems(tx: Transaction?): List<Pair<String, String>> {
        if (tx == null) return emptyList()
        return listOf(
            "商户" to tx.merchant,
            "商品说明" to tx.productDesc,
            "付款方式" to tx.payMethod,
            "交易时间" to tx.tradeTime,
            "订单号" to tx.orderId,
            "平台分类" to tx.platformCategory,
        ).filter { it.second.isNotBlank() }
    }

    /** 单据行摘要：商户 · 付款方式（两者都没有时退回商品说明） */
    fun receiptSummary(tx: Transaction?): String {
        if (tx == null) return ""
        val parts = listOf(tx.merchant, tx.payMethod).filter { it.isNotBlank() }
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return tx.productDesc
    }

    /**
     * 交易列表行的副标题。
     *
     * 「说明」列只存用户手写内容（自动记账的商户 / 商品说明 / 付款方式各自留在独立字段），
     * 所以副标题先看 [Transaction.remark]，空的时候才回退 [receiptSummary]：
     * 自动记账记下的那笔即使没写说明，也能看出是「商户 · 付款方式」。
     */
    fun subtitle(tx: Transaction?): String {
        if (tx == null) return ""
        val note = tx.remark.trim()
        if (note.isNotEmpty()) return note
        return receiptSummary(tx)
    }

    /** 是否有可展示的单据信息 —— 决定单据卡是否出现在表单里（手动记账没有，就不摆一个空行） */
    fun hasReceipt(tx: Transaction?): Boolean = receiptItems(tx).isNotEmpty()
}
