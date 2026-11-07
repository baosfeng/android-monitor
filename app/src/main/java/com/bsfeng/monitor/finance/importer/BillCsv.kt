package com.bsfeng.monitor.finance.importer

/**
 * RFC 4180 风格 CSV 解析：引号包裹、引号内逗号与换行、`""` 转义、BOM 容错。
 *
 * 返回**记录**而不是行：一条记录可以跨多个物理行（引号内换行）。要回指文件时用 [Record.lineNo]
 * （记录起始的物理行号，1 基），失败明细才能指回原始文件。
 */
object BillCsv {

    /** 一条 CSV 记录：单元格 + 起始物理行号（1 基） */
    data class Record(val cells: List<String>, val lineNo: Int)

    /** 解析整份文本；末尾没有换行符的最后一条记录也会返回 */
    fun parseWithLines(text: String): List<Record> {
        val source = text.removePrefix("\uFEFF")
        val records = mutableListOf<Record>()
        var cells = mutableListOf<String>()
        val buffer = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            if (inQuotes) {
                when {
                    ch == '"' && index + 1 < source.length && source[index + 1] == '"' -> {
                        buffer.append('"')
                        index += 2
                    }
                    ch == '"' -> {
                        inQuotes = false
                        index++
                    }
                    else -> {
                        if (ch == '\n') line++
                        buffer.append(ch)
                        index++
                    }
                }
                continue
            }
            when {
                ch == '"' -> {
                    inQuotes = true
                    index++
                }
                ch == ',' -> {
                    cells.add(buffer.toString())
                    buffer.setLength(0)
                    index++
                }
                ch == '\r' || ch == '\n' -> {
                    if (ch == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                    cells.add(buffer.toString())
                    buffer.setLength(0)
                    appendIfNotBlank(records, cells, recordLine)
                    cells = mutableListOf()
                    line++
                    recordLine = line
                    index++
                }
                else -> {
                    buffer.append(ch)
                    index++
                }
            }
        }
        if (buffer.isNotEmpty() || cells.isNotEmpty()) {
            cells.add(buffer.toString())
            appendIfNotBlank(records, cells, recordLine)
        }
        return records
    }

    /** 微信账单末尾常有空行；单格且空的记录当作空行跳过，否则会多出一条「解析失败」 */
    private fun appendIfNotBlank(records: MutableList<Record>, cells: List<String>, lineNo: Int) {
        if (cells.size == 1 && cells[0].isBlank()) return
        records.add(Record(cells, lineNo))
    }

    /** 只要单元格（表头探测与测试用） */
    fun parse(text: String): List<List<String>> = parseWithLines(text).map { it.cells }

    /** 单个物理行 → 单元格（表头行不含引号内换行，够用） */
    fun parseLine(line: String): List<String> = parseWithLines(line).firstOrNull()?.cells.orEmpty()
}
