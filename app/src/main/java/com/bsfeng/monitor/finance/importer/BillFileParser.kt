package com.bsfeng.monitor.finance.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 账单文件解析入口：文件名 + 内容猜渠道 → 容器解析（csv / xlsx / zip）→ 逐行映射成 [BillRecord]。
 *
 * 三件事只在这里做一次：
 * 1. **容器判定**按魔数（xlsx 与账单 zip 都以 `PK\x03\x04` 开头），扩展名只用于挑渠道提示；
 * 2. **编码**先按 UTF-8 严格解码、失败再 GBK —— 支付宝导出是 GBK、微信是 UTF-8 + BOM，
 *    文件名不可靠，看字节本身才稳；
 * 3. **跳过判定**（不计收支 / 交易关闭 / 已全额退款 / 本人账户转账）集中在这里，
 *    入库侧只管写入，不重复解释业务规则。
 *
 * 金额解析失败的行不产出记录（不猜金额），调用方用「数据行数 − 返回条数」提示即可。
 */
object BillFileParser {

    /** 默认上限 50 MB：正常账单 csv 几 MB，超了基本是选错文件 */
    const val DEFAULT_MAX_BYTES: Long = 50L * 1024 * 1024

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val UTF8: Charset = Charset.forName("UTF-8")
    private val GBK: Charset = Charset.forName("GBK")

    private val SKIP_STATUS_KEYWORDS = listOf("交易关闭", "已全额退款")
    private val SKIP_SUBJECT_KEYWORDS = listOf("转账到本人", "提现", "充值")

    /**
     * 解析账单文件。
     *
     * @param fileName 只用于猜渠道（[BillChannel.guess]）与从 zip 里挑 csv，不参与格式判定
     * @throws IllegalArgumentException 文件为空/过大、压缩包里没有 csv、表头识别失败
     */
    fun parse(fileName: String, bytes: ByteArray, maxBytes: Long = DEFAULT_MAX_BYTES): List<BillRecord> {
        require(bytes.isNotEmpty()) { "文件为空：$fileName" }
        require(bytes.size.toLong() <= maxBytes) {
            "文件过大：${bytes.size} 字节（上限 $maxBytes 字节）"
        }
        val payload = unwrap(fileName, bytes)
        val table = readTable(payload)
        val headerIndex = BillColumns.detectHeaderRowIn(table.rows)
            ?: throw IllegalArgumentException("表头识别失败：未找到含金额与时间的列")
        val columns = BillColumns.normalizeHeader(table.rows[headerIndex])
        val channel = resolveChannel(BillChannel.guess(payload.name), table.rows[headerIndex])
        return table.rows.drop(headerIndex + 1)
            .mapIndexedNotNull { offset, cells ->
                val rowIndex = headerIndex + 1 + offset
                toRecord(channel, cells, columns, table.lineNos.getOrElse(rowIndex) { rowIndex + 1 })
            }
    }

    // ---------- 容器 ----------

    private class Payload(val name: String, val bytes: ByteArray, val xlsx: Boolean)

    private class Table(val rows: List<List<String>>, val lineNos: List<Int>)

    private fun unwrap(fileName: String, bytes: ByteArray): Payload {
        if (!isZip(bytes)) return Payload(fileName, bytes, xlsx = false)
        val entries = BillZip.entries(bytes)
        if (entries.keys.any { it.startsWith("xl/worksheets/") }) return Payload(fileName, bytes, xlsx = true)
        val csv = BillZip.firstCsvEntry(entries)
            ?: throw IllegalArgumentException("压缩包里没有 csv 账单：$fileName")
        require(!isZip(csv.second)) { "压缩包嵌套层级过深：$fileName" }
        return Payload(csv.first, csv.second, xlsx = false)
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

    private fun readTable(payload: Payload): Table =
        if (payload.xlsx) {
            val rows = BillXlsx.readRows(payload.bytes)
            Table(rows, rows.indices.map { it + 1 })
        } else {
            val records = BillCsv.parseWithLines(decodeText(payload.bytes))
            Table(records.map { it.cells }, records.map { it.lineNo })
        }

    /**
     * 渠道：表头说了算，文件名只兜底。
     *
     * 文件名的渠道关键字可能是用户随手改的（`账单.csv` / `alipay.csv` 里装着微信账单），
     * 而「交易创建时间 / 交易时间」这两列的差异是导出格式自带的，更可信。
     */
    private fun resolveChannel(nameHint: BillChannel, headerCells: List<String>): BillChannel {
        val normalized = headerCells.map(BillColumns::normalizeHeaderText)
        val byHeader = when {
            normalized.contains("交易创建时间") -> BillChannel.ALIPAY
            normalized.contains("交易时间") -> BillChannel.WECHAT
            normalized.any { it.contains("支付宝") } -> BillChannel.ALIPAY
            else -> null
        }
        return byHeader ?: nameHint
    }

    /**
     * 先 UTF-8 严格解码（非法字节立即失败），失败再按 GBK。
     *
     * 反过来（先 GBK）会把 UTF-8 中文解成乱码却不报错，所以顺序不能换。
     */
    internal fun decodeText(bytes: ByteArray): String {
        val decoded = try {
            UTF8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, GBK)
        }
        return decoded.removePrefix("\uFEFF")
    }

    // ---------- 行 ----------

    private fun toRecord(
        channel: BillChannel,
        cells: List<String>,
        columns: Map<BillField, Int>,
        lineNo: Int,
    ): BillRecord? {
        val signedAmount = parseAmount(cellValue(cells, columns, BillField.AMOUNT)) ?: return null
        val directionText = cellValue(cells, columns, BillField.DIRECTION)
        val status = cellValue(cells, columns, BillField.STATUS)
        val product = cellValue(cells, columns, BillField.PRODUCT)
        val counterparty = cellValue(cells, columns, BillField.COUNTERPARTY)
        val orderId = cellValue(cells, columns, BillField.ORDER_ID)
        val tradeTime = cellValue(cells, columns, BillField.TRADE_TIME)
        if (orderId.isEmpty() && tradeTime.isEmpty()) return null
        val direction = directionOf(directionText, signedAmount)
        return BillRecord(
            channel = channel,
            orderId = orderId,
            tradeTime = tradeTime,
            amount = kotlin.math.abs(signedAmount),
            direction = if (shouldSkip(directionText, status, product, counterparty)) BillDirection.SKIP else direction,
            counterparty = counterparty,
            product = product,
            payMethod = cellValue(cells, columns, BillField.PAY_METHOD),
            // 平台分类两处来源：独立列（部分版本有）；或商品说明是 JSON 时内嵌的「支付宝交易分类」
            platformCategory = cellValue(cells, columns, BillField.PLATFORM_CATEGORY)
                .ifEmpty { BillProductJson.platformCategory(product) },
            status = status,
            directionText = directionText,
            lineNo = lineNo,
        )
    }

    /**
     * 收/支列 → 方向；列为空时按金额符号兜底（负=支出、正=收入）。
     *
     * 兜底只在列缺失时生效：微信账单金额一律是正数，方向全靠这一列表达。
     */
    internal fun directionOf(directionText: String, signedAmount: Double): BillDirection {
        val text = directionText.trim()
        return when {
            isNotCounted(text) -> BillDirection.SKIP
            text.contains("收入") || text == "入" -> BillDirection.INCOME
            text.contains("支出") || text == "出" -> BillDirection.EXPENSE
            text.isEmpty() -> if (signedAmount < 0) BillDirection.EXPENSE else BillDirection.INCOME
            else -> BillDirection.EXPENSE
        }
    }

    /** 「不计收支」的几种写法：微信把这类行的收/支格写成 `/` */
    private fun isNotCounted(directionText: String): Boolean {
        val text = directionText.trim()
        return text.contains("不计收支") || text == "/"
    }

    /** 这几种行不进账本：不计收支、交易关闭/全额退款、本人账户转账（提现/充值） */
    internal fun shouldSkip(directionText: String, status: String, product: String, counterparty: String): Boolean {
        if (isNotCounted(directionText)) return true
        if (SKIP_STATUS_KEYWORDS.any(status::contains)) return true
        // 本人账户转账：收/支列在这类行上是空的，只能看商品/对方名
        if (directionText.isBlank()) {
            val subject = product + counterparty
            if (SKIP_SUBJECT_KEYWORDS.any(subject::contains)) return true
        }
        return false
    }

    /**
     * 金额文本 → 有符号数值：去 `¥` / `￥` / 千分位逗号 / `元` / 空白；解析不出返回 null。
     *
     * 保留符号（负号决定兜底方向），入库侧拿到的 amount 用绝对值。
     * 清洗后仍解析不出时，再剥掉所有非数字字符试一次 —— 编码不匹配时人民币符号会变成 `?`，
     * 那不该让整行被丢掉。
     */
    internal fun parseAmount(raw: String): Double? {
        val cleaned = raw
            .replace(",", "")
            .replace("，", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace("元", "")
            .replace("\u00A0", "")
            .filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        cleaned.toDoubleOrNull()?.let { return it }
        val digits = cleaned.filter { it.isDigit() || it == '.' || it == '-' || it == '+' }
        return digits.toDoubleOrNull()
    }
}
