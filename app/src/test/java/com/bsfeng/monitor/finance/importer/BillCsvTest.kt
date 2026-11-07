package com.bsfeng.monitor.finance.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * [BillCsv] 与编码解码的单元测试（纯 JVM）。
 *
 * 验收点：引号内逗号 / 引号内换行 / `""` 转义 / BOM / GBK 中文 / 物理行号。
 * 样例文本全部自造。
 */
class BillCsvTest {

    private val gbk: Charset = Charset.forName("GBK")

    @Test
    fun `引号内的逗号不切列`() {
        val rows = BillCsv.parse("a,\"b,c\",d")
        assertEquals(listOf(listOf("a", "b,c", "d")), rows)
    }

    @Test
    fun `引号内的换行属于同一格 行号按物理行计`() {
        val records = BillCsv.parseWithLines("表头\n\"第一行\n第二行\",x\n尾行")
        assertEquals(3, records.size)
        assertEquals(listOf("第一行\n第二行", "x"), records[1].cells)
        assertEquals(2, records[1].lineNo)
        assertEquals("跨两行之后的记录行号要跳过被吃掉的那一行", 4, records[2].lineNo)
    }

    @Test
    fun `双引号转义还原成一个引号`() {
        val rows = BillCsv.parse("\"他说\"\"你好\"\"\",普通")
        assertEquals(listOf(listOf("他说\"你好\"", "普通")), rows)
    }

    @Test
    fun `BOM 被剥掉`() {
        val rows = BillCsv.parse("\uFEFF交易号,金额")
        assertEquals("交易号", rows[0][0])
    }

    @Test
    fun `CRLF 与末尾无换行都能解析`() {
        val rows = BillCsv.parse("a,b\r\nc,d")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), rows)
    }

    @Test
    fun `空字段与末尾空列保留`() {
        val rows = BillCsv.parse("a,,c\n\n")
        assertEquals(listOf(listOf("a", "", "c")), rows)
    }

    @Test
    fun `GBK 中文按字节解码`() {
        val bytes = "交易号,金额\n20260101,¥12.34".toByteArray(gbk)
        val text = BillFileParser.decodeText(bytes)
        assertTrue(text.contains("交易号"))
        assertEquals("交易号", BillCsv.parse(text)[0][0])
    }

    @Test
    fun `UTF-8 带 BOM 解码后不留 BOM`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "交易时间".toByteArray(Charsets.UTF_8)
        assertEquals("交易时间", BillFileParser.decodeText(bytes))
    }
}
