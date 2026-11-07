package com.bsfeng.monitor.finance.importer

// 账单身份键：决定「这一行算不算已经记过的账」。
// 与 autobook/AutoBookIdentity.buildSourceId 同口径：**身份键不含商户名**（商户名会抖）。

/**
 * 三档身份键（见 docs/记账/账单导入.md「身份键」）。
 *
 * | 优先级 | 键 | 何时用 |
 * |---|---|---|
 * | 1 | `order:<渠道>:<交易单号>` | 有单号时（绝大多数） |
 * | 2 | `time:<渠道>:<方向>:<金额>:<yyyy-MM-dd HH:mm>` | 老版本导出没有单号 |
 * | 3 | null | 两条都不成立：调用方计入失败，不猜 |
 *
 * **刻意不含商户名**：同一笔在不同导出里商户名写法会变（「某某科技」/「某某科技有限公司」），
 * 带进去就会算出两个键、同一笔记两次。
 */
internal object BillIdentity {

    /** 时间解析：兼容 `2026-11-06 20:40:51` / `2026/11/06 20:40` / `2026年11月6日 20:40` */
    private val TIME_PATTERNS = listOf(
        Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"),
        Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"),
    )

    /**
     * 生成身份键。
     *
     * @return 身份键；[BillRecord.orderId] 与可用交易时间都没有时返回 null
     */
    fun sourceId(record: BillRecord): String? {
        val prefix = record.channel.sourcePrefix
        val orderId = record.orderId.trim()
        if (orderId.isNotEmpty()) return "order:" + prefix + ":" + orderId
        val minute = normalizeMinute(record.tradeTime) ?: return null
        if (record.amount <= 0.0) return null
        return "time:" + prefix + ":" + directionTag(record) + ":" + amountText(record.amount) + ":" + minute
    }

    /** 交易时间 → `yyyy-MM-dd HH:mm`；认不出返回 null */
    fun normalizeMinute(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        for (pattern in TIME_PATTERNS) {
            val m = pattern.find(text) ?: continue
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            val h = m.groupValues[4].toInt()
            val mi = m.groupValues[5].toInt()
            if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
            return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d %02d:%02d", y, mo, d, h, mi)
        }
        return null
    }

    /** 金额一律两位小数：3.4 与 3.40 必须算出同一个键 */
    fun amountText(amount: Double): String =
        String.format(java.util.Locale.ROOT, "%.2f", amount)

    /** 方向标签：与 Transaction.type 同口径（0 支出 / 1 收入） */
    fun directionTag(record: BillRecord): String =
        if (record.direction == BillDirection.INCOME) "1" else "0"

    /** 交易时间 → 日期部分（`yyyy-MM-dd`），落库的 date 列用 */
    fun dateOf(record: BillRecord): String = normalizeMinute(record.tradeTime)?.substring(0, 10) ?: ""

    /** 交易时间 → 完整时间戳（`yyyy-MM-dd HH:mm:ss`），落库的 dateTime 列用；缺秒补 00 */
    fun dateTimeOf(record: BillRecord): String {
        val minute = normalizeMinute(record.tradeTime) ?: return ""
        val m = TIME_PATTERNS.firstNotNullOfOrNull { it.find(record.tradeTime.trim()) }
        val second = m?.groupValues?.getOrNull(6)?.takeIf { it.length == 2 } ?: "00"
        return minute + ":" + second
    }
}