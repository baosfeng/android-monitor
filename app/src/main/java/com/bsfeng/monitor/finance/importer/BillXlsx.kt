package com.bsfeng.monitor.finance.importer

import java.nio.charset.Charset

/**
 * 纯 JVM 读 xlsx（零第三方）。
 *
 * 不引 POI、也不用 android 的 XmlPullParser（单测是纯 JVM，`org.xmlpull.v1` 不在 classpath），
 * 所以用系统 [java.util.zip] 取出 xml，再用 [BillXmlScan] 这个只认 `<row>/<c>/<v>/<t>/<is>` 的
 * 极简扫描器读值。只解析第一个工作表 —— 账单导出只有一张表。
 *
 * 单元格位置一律按 `r` 属性（如 `C5`）还原：xlsx 会**省略空单元格**，按出现顺序数必然错位，
 * 「金额读成备注」就是这么来的。
 */
object BillXlsx {

    private val UTF8 = Charset.forName("UTF-8")

    /** 读第一个工作表 → 行 × 列文本（缺值为空串） */
    fun readRows(bytes: ByteArray): List<List<String>> {
        val entries = BillZip.entries(bytes)
        val sheetPath = sheetPathOf(entries.keys)
            ?: throw IllegalArgumentException("xlsx 里没有工作表：xl/worksheets/sheet1.xml")
        val shared = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(String(it, UTF8)) }
            .orEmpty()
        return parseSheet(String(entries.getValue(sheetPath), UTF8), shared)
    }

    /** 优先 sheet1.xml，其次任意工作表（有些导出把表命名为 sheet2） */
    internal fun sheetPathOf(names: Set<String>): String? {
        if (names.contains("xl/worksheets/sheet1.xml")) return "xl/worksheets/sheet1.xml"
        return names.firstOrNull { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
    }

    /** xl/sharedStrings.xml → 字符串表（`t="s"` 的单元格存的是这张表的下标） */
    fun parseSharedStrings(xml: String): List<String> {
        val out = mutableListOf<String>()
        val scanner = BillXmlScan.Scanner(xml)
        val buffer = StringBuilder()
        var inItem = false
        var collectText = false
        var phoneticDepth = 0
        while (true) {
            val token = scanner.next() ?: break
            // 文本永远属于「上一个标签之后」：先收文本，再处理标签
            if (collectText && token.text.isNotEmpty()) buffer.append(BillXmlScan.decode(token.text))
            val tag = token.tag
            if (tag.isEmpty()) continue
            val closing = tag.startsWith("/")
            val selfClosing = !closing && tag.endsWith("/")
            when (BillXmlScan.tagName(tag)) {
                "si" -> {
                    if (closing) {
                        out.add(buffer.toString())
                        buffer.setLength(0)
                        inItem = false
                        collectText = false
                    } else {
                        buffer.setLength(0)
                        inItem = !selfClosing
                        if (selfClosing) out.add("")
                    }
                }
                "rPh" -> phoneticDepth = if (closing) (phoneticDepth - 1).coerceAtLeast(0) else phoneticDepth + 1
                "t" -> collectText = inItem && !closing && !selfClosing && phoneticDepth == 0
            }
        }
        return out
    }

    /** 工作表 xml → 行 × 列文本；行号与列号以 `r` 属性为准 */
    fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        val scanner = BillXmlScan.Scanner(xml)
        val state = SheetState(shared)
        while (true) {
            val token = scanner.next() ?: break
            // 文本永远属于「上一个标签之后」：先收文本，再处理标签
            if (state.collectText && token.text.isNotEmpty()) {
                state.cellText.append(BillXmlScan.decode(token.text))
            }
            val tag = token.tag
            if (tag.isEmpty()) continue
            val closing = tag.startsWith("/")
            val selfClosing = !closing && tag.endsWith("/")
            when (BillXmlScan.tagName(tag)) {
                "row" -> state.onRow(rows, tag, closing, selfClosing)
                "c" -> state.onCell(tag, closing, selfClosing)
                "v" -> state.collectText = !closing && !selfClosing
                // inlineStr 的 <is><t> 与 sharedStrings 的 <si><t> 都走这条文本收集路径
                "t" -> state.collectText = !closing && !selfClosing && state.phoneticDepth == 0
                "rPh" -> state.phoneticDepth =
                    if (closing) (state.phoneticDepth - 1).coerceAtLeast(0) else state.phoneticDepth + 1
            }
        }
        return rows
    }

    /** `C5` → 2（列字母转 0 基下标）；没有 r 属性返回 -1 */
    internal fun columnIndexOf(cellRef: String): Int {
        if (cellRef.isEmpty()) return -1
        var value = 0
        var seen = false
        for (ch in cellRef) {
            if (ch in 'A'..'Z') {
                value = value * 26 + (ch - 'A' + 1)
                seen = true
            } else if (ch in 'a'..'z') {
                value = value * 26 + (ch - 'a' + 1)
                seen = true
            } else {
                break
            }
        }
        return if (seen) value - 1 else -1
    }

    /** `5` → 下标 4（0 基）；缺失或非法返回 -1（由调用方顺序追加） */
    private fun rowIndexOf(rawAttribute: String): Int {
        val n = rawAttribute.trim().toIntOrNull() ?: return -1
        return if (n <= 0) -1 else n - 1
    }

    /**
     * 解析一份工作表时的游标状态。
     *
     * 抽成类只为让 [parseSheet] 的主循环读起来是一个 when 分支一个标签，
     * 不用在循环外摆七个互相牵制的 var。
     */
    private class SheetState(private val shared: List<String>) {
        var row: MutableList<String>? = null
        var rowIndex = -1
        var nextColumn = 0
        var cellType = ""
        var cellColumn = -1
        var cellText = StringBuilder()
        var collectText = false
        var phoneticDepth = 0

        fun onRow(rows: MutableList<MutableList<String>>, tag: String, closing: Boolean, selfClosing: Boolean) {
            if (closing) {
                row?.let { commitRow(rows, rowIndex, it) }
                row = null
                rowIndex = -1
                return
            }
            val declared = rowIndexOf(BillXmlScan.attribute(tag, "r"))
            rowIndex = if (declared >= 0) declared else rows.size
            nextColumn = 0
            if (selfClosing) {
                commitRow(rows, rowIndex, mutableListOf())
                row = null
            } else {
                row = mutableListOf()
            }
        }

        fun onCell(tag: String, closing: Boolean, selfClosing: Boolean) {
            if (closing) {
                val target = if (cellColumn >= 0) cellColumn else nextColumn
                putCell(row, target, resolveCell(cellType, cellText.toString(), shared))
                nextColumn = target + 1
                reset()
                return
            }
            cellType = BillXmlScan.attribute(tag, "t")
            cellColumn = columnIndexOf(BillXmlScan.attribute(tag, "r"))
            cellText = StringBuilder()
            collectText = false
            if (selfClosing) {
                // <c r="B1"/>：空单元格，也要占住它的列，否则后面的值整体左移
                val target = if (cellColumn >= 0) cellColumn else nextColumn
                putCell(row, target, "")
                nextColumn = target + 1
                reset()
            }
        }

        private fun reset() {
            cellType = ""
            cellColumn = -1
            cellText = StringBuilder()
            collectText = false
        }
    }

    private fun commitRow(rows: MutableList<MutableList<String>>, index: Int, cells: MutableList<String>) {
        while (rows.size < index) rows.add(mutableListOf())
        if (index >= rows.size) rows.add(cells) else rows[index] = cells
    }

    private fun resolveCell(type: String, raw: String, shared: List<String>): String = when (type) {
        "s" -> shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }
        else -> raw
    }

    private fun putCell(row: MutableList<String>?, column: Int, value: String) {
        if (row == null || column < 0) return
        while (row.size < column) row.add("")
        if (row.size == column) row.add(value) else row[column] = value
    }
}
