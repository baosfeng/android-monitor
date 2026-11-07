package com.bsfeng.monitor.finance.importer

// 表头探测与列名归一。两家的列名与列数在版本间变过（支付宝 16 → 17 列），
// 所以只按**列名**匹配，绝不按列序号：按序号会在某个版本整体错位，结果是「金额读成备注」这类静默错误。

/**
 * 归一后的字段；[aliases] 是同一字段在各家导出里的写法。
 *
 * 比较前两边都会先过 [BillColumns.normalizeHeaderText]，所以别名里写全角括号也不影响匹配。
 */
enum class BillField(vararg aliases: String) {
    ORDER_ID("交易号", "交易单号"),
    TRADE_TIME("交易创建时间", "交易时间"),
    PRODUCT("商品名称", "商品", "商品说明"),
    COUNTERPARTY("交易对方", "对方"),
    AMOUNT("金额", "金额(元)", "金额（元）"),
    DIRECTION("收/支", "收支类型"),
    STATUS("交易状态", "当前状态"),
    PAY_METHOD("支付方式", "付款方式"),
    PLATFORM_CATEGORY("支付宝交易分类", "交易分类"),
    ;

    val aliases: List<String> = aliases.toList()
}

/**
 * 表头探测 + 列名归一。
 *
 * 探测依据是「同时含金额列与时间列」而不是行号：元信息行数与文案会变（支付宝 4 行、微信 5 行），
 * 写死行号必然在某个导出版本上失效。
 */
object BillColumns {

    /** 只在前多少行里找表头 —— 元信息行不会超过这个数量 */
    const val HEADER_SCAN_LINES: Int = 20

    /** 表头行下标（0 基）；找不到返回 null */
    fun detectHeaderRow(lines: List<String>): Int? = detectHeaderRowIn(lines.map(BillCsv::parseLine))

    /**
     * 表头行下标（0 基），输入是已切好的单元格行（xlsx 与 csv 共用）。
     *
     * 同一行里同时出现金额列与时间列才算表头。
     */
    fun detectHeaderRowIn(rows: List<List<String>>): Int? {
        val limit = minOf(rows.size, HEADER_SCAN_LINES)
        for (index in 0 until limit) {
            val cells = rows[index]
            if (cells.isEmpty()) continue
            val columns = normalizeHeader(cells)
            if (columns.containsKey(BillField.AMOUNT) && columns.containsKey(BillField.TRADE_TIME)) return index
        }
        return null
    }

    /**
     * 表头单元格 → 字段下标。
     *
     * 先精确匹配，再对没命中的字段做包含匹配（列名带后缀时用），避免「金额(元)」这类写法漏掉。
     * 包含匹配只在缺列时兜底：有「交易号」列时不会再去认「商家订单号」。
     */
    fun normalizeHeader(cells: List<String>): Map<BillField, Int> {
        val normalized = cells.map(::normalizeHeaderText)
        val result = LinkedHashMap<BillField, Int>()
        for (field in BillField.entries) {
            val aliases = field.aliases.map(::normalizeHeaderText).filter { it.isNotEmpty() }
            val exact = normalized.indexOfFirst { cell -> cell.isNotEmpty() && cell in aliases }
            if (exact >= 0) result[field] = exact
        }
        for (field in BillField.entries) {
            if (result.containsKey(field)) continue
            val aliases = field.aliases.map(::normalizeHeaderText).filter { it.isNotEmpty() }
            val fuzzy = normalized.indexOfFirst { cell -> cell.isNotEmpty() && aliases.any(cell::contains) }
            if (fuzzy >= 0) result[field] = fuzzy
        }
        return result
    }

    /** 去掉 BOM / 全角括号 / 空白，让同一字段的不同写法落到同一个键上 */
    fun normalizeHeaderText(raw: String): String = raw
        .replace("\uFEFF", "")
        .replace('（', '(')
        .replace('）', ')')
        .replace("\u00A0", "")
        .filterNot { it.isWhitespace() }
}

/** 表头 → 字段下标；缺列返回空串（BillRecord 的字段语义就是「缺失即空串」） */
internal fun cellValue(cells: List<String>, columns: Map<BillField, Int>, field: BillField): String =
    columns[field]?.let { cells.getOrNull(it) }?.trim().orEmpty();
