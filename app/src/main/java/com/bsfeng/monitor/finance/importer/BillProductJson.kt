package com.bsfeng.monitor.finance.importer

// 支付宝导出的「商品说明」列可能是一整段 JSON（内部带「支付宝交易分类」键），而不是独立列。
// 本文件只做一件事：从这段文本里把平台分类捞出来。

/**
 * 从商品说明里提取平台分类。
 *
 * 真实支付宝导出把交易分类塞在商品说明的 JSON 里，例如
 * `{"商品说明":"收钱码收款","支付宝交易分类":"美容美发"}`；
 * 列级别名匹配不到这种形态，平台分类会恒为空、分类推断只能降级到关键词档。
 * 这里做一次宽松提取（不引 JSON 库）：找键名后面的第一个引号串。
 *
 * 认不出的文本原样返回空串 —— 调用方已有兜底，不做二次猜测。
 */
internal object BillProductJson {

    /** 键名按「长的在前」排列：「支付宝交易分类」必须先于「交易分类」 */
    private val CATEGORY_KEYS = listOf("支付宝交易分类", "交易分类", "商品分类")

    /**
     * @return 平台分类原文；文本里没有这类键时返回空串
     */
    fun platformCategory(product: String): String {
        val text = product.trim()
        // 不要求出现 `{`：等号写法（`支付宝交易分类=日用百货`）没有 JSON 结构，
        // 真正的防误判在 valueOf —— 键名后面必须有分隔符，正文里提到分类字样不会命中。
        if (text.isEmpty()) return ""
        for (key in CATEGORY_KEYS) {
            val value = valueOf(text, key)
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /**
     * 取 `"key":"value"` / `key=value` 里的值。
     *
     * 不要求文本是合法 JSON：导出里的转义、缺引号、多余空白都出现过，
     * 这里只找「键名 + 分隔符 + 一段非分隔符内容」。
     */
    private fun valueOf(text: String, key: String): String {
        var from = 0
        while (true) {
            val at = text.indexOf(key, from)
            if (at < 0) return ""
            from = at + key.length
            // 键名后面允许的写法："key":"value" / "key": "value" / key=value
            val rest = text.substring(from).trimStart()
            val cut = rest.removePrefix("\"").trimStart().removePrefix(":").trimStart()
                .removePrefix("=").trimStart()
            if (cut.length == rest.length) {
                // 键名后面没有冒号/等号：可能是「商品说明含支付宝交易分类」这种正文，跳过继续找
                continue
            }
            val opened = cut.startsWith("\"")
            val value = if (opened) cut.removePrefix("\"").substringBefore('"') else cut.substringBeforeAny('"', ',', '}', '，')
            val cleaned = value.trim().trimEnd('\\')
            if (cleaned.isNotEmpty()) return cleaned.take(24)
        }
    }

    private fun String.substringBeforeAny(vararg stops: Char): String {
        var end = length
        for (ch in stops) {
            val at = indexOf(ch)
            if (at in 0 until end) end = at
        }
        return substring(0, end)
    }
}
