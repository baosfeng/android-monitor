package com.bsfeng.monitor.finance.ui.proto

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * proto 记账屏（定版原型 s14a「记一笔 · 一屏 · 全抽屉」）的纯逻辑与样例数据。
 *
 * 原型出处：`.open-design/26-a-quan-chouti.html` —— 主屏 2430-2486 行、四个抽屉 2488-2663 行。
 * 本文件不含 Compose 依赖，便于单测；UI 组合见 [ProtoRecordScreen] / [ProtoRecordSheets]。
 */

/** 收支类型（原型 `s14a-kind` 的三段：支出 / 收入 / 转账）。 */
internal enum class ProtoKind(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    TRANSFER("转账"),
}

/** 表单时间：日期 + 时分秒。「秒」不单独选（原型滚轮只有时 / 分两列），随表单透传。 */
internal data class ProtoTimeParts(
    val date: String,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/** 日期快捷项：[label] 是给人看的（今天 / 昨天 / 前天 / 10 月 14 日），[date] 是 ISO 日期。 */
internal data class ProtoDateShortcut(val label: String, val date: String)

/**
 * 账户样例：原型 `s14a-acct-list` 每行是「钱包名 + 余额」两段（余额宝 / 零钱 / 余额）。
 *
 * 项目 `com.bsfeng.monitor.finance.model.Account` 只有 name + balance，没有「钱包名」，
 * 而这三行的副标题是原型上实打实的界面文案，故在 proto 层按原型样例建模。
 */
internal data class ProtoAccount(val name: String, val wallet: String, val balance: Double) {
    /** 行副标题：`余额宝 ¥4,120.55`（原型 .row-sub） */
    val subtitle: String get() = wallet + " ¥" + ProtoRecordLogic.formatMoney(balance)
}

/** 单据样例：原型 `s14a-receipt-sheet` 的 6 行（自动记账落库的结构化字段）。 */
internal data class ProtoReceipt(
    val merchant: String,
    val productDesc: String,
    val payMethod: String,
    val tradeTime: String,
    val orderId: String,
    val platformCategory: String,
) {
    /** 表单行摘要：`淘宝闪购 · 招商银行信用卡(8888)`（原型 s14a-receipt-row） */
    val summary: String get() = merchant + " · " + payMethod

    /** 抽屉里的 6 行「标签 to 值」，顺序与原型 `s14a-receipt-sheet` 一致。 */
    val items: List<Pair<String, String>>
        get() = listOf(
            "商户" to merchant,
            "商品说明" to productDesc,
            "付款方式" to payMethod,
            "交易时间" to tradeTime,
            "订单号" to orderId,
            "平台分类" to platformCategory,
        )
}

/** 取值 / 文案规则（纯函数，可单测）。 */
internal object ProtoRecordLogic {

    /** 相对日期标签（原型时间抽屉日期条的 今天 / 昨天 / 前天）。 */
    private val RELATIVE_LABELS = listOf("今天", "昨天", "前天")

    /** 日期条 chip 数：3 个相对标签 + 2 个具体日期（原型给了 5 个）。 */
    const val SHORTCUT_COUNT = 5

    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** `10 月 14 日` */
    fun monthDayLabel(date: LocalDate): String =
        date.monthValue.toString() + " 月 " + date.dayOfMonth + " 日"

    /** 日期快捷条：今天 / 昨天 / 前天 / 10 月 14 日 / 10 月 13 日。 */
    fun dateShortcuts(today: LocalDate): List<ProtoDateShortcut> =
        (0 until SHORTCUT_COUNT).map { offset ->
            val day = today.minusDays(offset.toLong())
            ProtoDateShortcut(
                label = RELATIVE_LABELS.getOrNull(offset) ?: monthDayLabel(day),
                date = day.format(ISO_DATE),
            )
        }

    /**
     * 日期显示：今天 / 昨天 / 前天，再往前用 `10 月 14 日`。
     *
     * 解析不了的日期原样返回（不抛异常），与表单行「宁可降级也不显示空值」的口径一致。
     */
    fun dateLabel(isoDate: String, today: LocalDate): String {
        val parsed = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
        val offset = ChronoUnit.DAYS.between(parsed, today).toInt()
        return RELATIVE_LABELS.getOrNull(offset) ?: monthDayLabel(parsed)
    }

    /** `18:32:21`（两位补零）。 */
    fun clockText(parts: ProtoTimeParts): String =
        String.format(Locale.CHINA, "%02d:%02d:%02d", parts.hour, parts.minute, parts.second)

    /** 时间行与抽屉底部的「已选」文本：`今天 18:32:21`（原型 s14a-time-row / s14a-picked）。 */
    fun timeText(parts: ProtoTimeParts, today: LocalDate): String =
        dateLabel(parts.date, today) + " " + clockText(parts)

    /** 两位补零（滚轮 00…59）。 */
    fun twoDigits(value: Int): String = String.format(Locale.CHINA, "%02d", value)

    /** 千分位两位小数：`4,120.55`。 */
    fun formatMoney(value: Double): String = String.format(Locale.CHINA, "%,.2f", value)

    /** 金额输入 → Double（空串 / 非数字按 0 处理，不抛异常）。 */
    fun amountOf(text: String): Double = text.toDoubleOrNull() ?: 0.0

    /** 能否保存：金额 > 0 且分类、账户都选了（与原型保存按钮的可用性一致）。 */
    fun canSave(amountText: String, categoryName: String, accountName: String): Boolean =
        amountOf(amountText) > 0.0 && categoryName.isNotBlank() && accountName.isNotBlank()
}

/** 原型 s14a 屏的样例数据：逐条对齐原型文案（不用占位符）。 */
internal object ProtoRecordSample {

    /**
     * 原型 `s14a-cat-grid` 的 21 个分类。
     *
     * 顺序即取色槽 —— 原型给每格写死 `.s1 .s2 … .s16`（按位置循环），
     * 因此这里按下标取 `DesignTokens.categoryDot(index)` 即可复现同样的配色。
     */
    val categories: List<String> = listOf(
        "餐饮", "出行", "购物", "日用", "零食", "水果",
        "水电", "就医", "通讯", "衣物", "美妆", "房租还贷",
        "休闲娱乐", "打车", "住宿", "旅游度假", "汽车", "手机数码",
        "居家生活", "教育", "宽带话费",
    )

    /** 原型 `s14a-acct-list` 的三行。 */
    val accounts: List<ProtoAccount> = listOf(
        ProtoAccount("支付宝", "余额宝", 4120.55),
        ProtoAccount("微信支付", "零钱", 326.40),
        ProtoAccount("现金", "余额", 932.00),
    )

    /** 主屏默认值：原型首屏即「有数据」态（金额 23.90 / 餐饮 / 支付宝 / 长备注）。 */
    val defaultKind: ProtoKind = ProtoKind.EXPENSE
    const val DEFAULT_AMOUNT = "23.90"
    const val DEFAULT_CATEGORY = "餐饮"
    const val DEFAULT_ACCOUNT = "支付宝"
    const val DEFAULT_NOTE = "某某湘菜馆(示例店)外卖订单"

    /**
     * 主屏时间的时分秒：取原型主屏的静态样值 `18:32:21`。
     *
     * 真机接入时应改成 `LocalDateTime.now()`（「记一笔」的默认时间就是当下）；
     * 这里保留原型样值是为了「原型 ↔ 实现」的文字级对照。
     */
    const val DEFAULT_HOUR = 18
    const val DEFAULT_MINUTE = 32
    const val DEFAULT_SECOND = 21

    /** 原型 `s14a-receipt` 卡与 `s14a-receipt-sheet` 抽屉的样值。 */
    val receipt: ProtoReceipt = ProtoReceipt(
        merchant = "淘宝闪购",
        productDesc = "某某湘菜馆(示例店)外卖订单",
        payMethod = "招商银行信用卡(8888)",
        tradeTime = "2026-10-16 18:32:21",
        orderId = "CFP000000000000000000",
        platformCategory = "餐饮美食",
    )
}

/**
 * 锚点表：原型 `data-od-id` → `Modifier.testTag(...)`，名字逐字一致。
 *
 * 这是「原型 ↔ 实现」机器对照的支点（本屏共 28 个真实锚点）。
 * 注：testTag 在本项目为首次引入（此前 ui 层无任何 testTag 用法）。
 */
internal object ProtoAnchors {

    // ── 主屏（原型 2430-2486 行） ──
    const val CONTENT = "s14a-content"
    const val AMOUNT = "s14a-amount"
    const val KIND = "s14a-kind"
    const val FIELDS = "s14a-fields"
    const val CAT_ROW = "s14a-cat-row"
    const val ACCT_ROW = "s14a-acct-row"
    const val TIME_ROW = "s14a-time-row"
    const val NOTE_ROW = "s14a-note-row"
    const val RECEIPT = "s14a-receipt"
    const val RECEIPT_ROW = "s14a-receipt-row"
    const val ACTIONS = "s14a-actions"
    const val SAVE = "s14a-save"

    // ── 抽屉 1/2：分类、账户 ──
    const val CAT_SHEET = "s14a-cat-sheet"
    const val CAT_GRID = "s14a-cat-grid"
    const val ACCT_SHEET = "s14a-acct-sheet"
    const val ACCT_LIST = "s14a-acct-list"

    // ── 抽屉 3：时间 ──
    const val TIME_SHEET = "s14a-time-sheet"
    const val DATE_STRIP = "s14a-date-strip"
    const val TIME_PICK = "s14a-timepick"
    const val WHEEL_HOUR = "s14a-wheel-hour"
    const val WHEEL_MINUTE = "s14a-wheel-minute"
    const val PICKED = "s14a-picked"
    const val TIME_OK = "s14a-time-ok"

    // ── 抽屉 4：单据 ──
    const val RECEIPT_SHEET = "s14a-receipt-sheet"
    const val RECEIPT_CLOSE = "s14a-receipt-close"

    /** 类型段三个按钮各自的锚点。 */
    fun kind(kind: ProtoKind): String = when (kind) {
        ProtoKind.EXPENSE -> "s14a-kind-expense"
        ProtoKind.INCOME -> "s14a-kind-income"
        ProtoKind.TRANSFER -> "s14a-kind-transfer"
    }
}
