package com.bsfeng.monitor.finance.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [BillXlsx] 的单元测试（纯 JVM）。
 *
 * xlsx 文件在测试里**现造**：只写 xl/worksheets/sheet1.xml 与 xl/sharedStrings.xml，
 * 不引入任何 Office 库。验收点是「按 r 属性还原位置」，空单元格被省略也不会串列。
 */
class BillXlsxTest {

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sheet(inner: String): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
        $inner
        </sheetData></worksheet>
    """.trimIndent()

    @Test
    fun `共享字符串下标引用 内联字符串 空单元格不错位`() {
        val shared = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <si><t>交易号</t></si><si><t>交易创建时间</t></si><si><t>金额（元）</t></si><si><t>示例商户</t></si>
            </sst>
        """.trimIndent()
        val inner = """
            <row r="1">
              <c r="A1" t="s"><v>0</v></c>
              <c r="B1" t="s"><v>1</v></c>
              <c r="C1" t="inlineStr"><is><t>商品名称</t></is></c>
              <c r="D1" t="s"><v>2</v></c>
            </row>
            <row r="2">
              <c r="A2"><v>2026110600000001</v></c>
              <c r="C2" t="inlineStr"><is><t>示例外卖订单</t></is></c>
              <c r="D2"><v>12.34</v></c>
            </row>
            <row r="3">
              <c r="A3"><v>2026110600000002</v></c>
              <c r="B3" t="s"><v>3</v></c>
              <c r="D3"><v>0.01</v></c>
            </row>
        """.trimIndent()
        val bytes = zipOf(
            mapOf(
                "xl/worksheets/sheet1.xml" to sheet(inner),
                "xl/sharedStrings.xml" to shared,
            ),
        )
        val rows = BillXlsx.readRows(bytes)
        assertEquals(4, rows[0].size)
        assertEquals("交易号", rows[0][0])
        assertEquals("商品名称", rows[0][2])
        assertEquals("金额（元）", rows[0][3])
        assertEquals("被省略的 B2 不能把 C2 顶到第 3 列", "示例外卖订单", rows[1][2])
        assertEquals("12.34", rows[1][3])
        assertEquals("", rows[1][1])
        assertEquals("示例商户", rows[2][1])
        assertEquals("0.01", rows[2][3])
    }

    @Test
    fun `空行被省略时按 r 属性补齐`() {
        val inner = """
            <row r="1"><c r="A1"><v>1</v></c></row>
            <row r="3"><c r="C3"><v>3</v></c></row>
        """.trimIndent()
        val rows = BillXlsx.parseSheet(sheet(inner), emptyList())
        assertEquals(3, rows.size)
        assertEquals(listOf("1"), rows[0])
        assertEquals(listOf<String>(), rows[1])
        assertEquals(listOf("", "", "3"), rows[2])
    }

    @Test
    fun `自闭合单元格与 XML 实体都能读`() {
        val inner = """
            <row r="1">
              <c r="A1" t="inlineStr"><is><t>甲 &amp; 乙</t></is></c>
              <c r="B1"/>
              <c r="C1" t="inlineStr"><is><t>&#x4E2D;文</t></is></c>
            </row>
        """.trimIndent()
        val rows = BillXlsx.parseSheet(sheet(inner), emptyList())
        assertEquals(listOf("甲 & 乙", "", "中文"), rows[0])
    }

    @Test
    fun `列字母转下标`() {
        assertEquals(0, BillXlsx.columnIndexOf("A1"))
        assertEquals(2, BillXlsx.columnIndexOf("C9"))
        assertEquals(26, BillXlsx.columnIndexOf("AA4"))
        assertEquals(-1, BillXlsx.columnIndexOf(""))
        assertEquals(-1, BillXlsx.columnIndexOf("1"))
    }

    @Test
    fun `没有工作表时抛异常`() {
        val bytes = zipOf(mapOf("xl/sharedStrings.xml" to "<sst></sst>"))
        val error = runCatching { BillXlsx.readRows(bytes) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
