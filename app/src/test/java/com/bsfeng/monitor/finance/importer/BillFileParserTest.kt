package com.bsfeng.monitor.finance.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [BillFileParser] 端到端单元测试（纯 JVM）：csv / xlsx / zip 三种容器 → BillRecord。
 *
 * 样例账单全部自造（商户写成「示例商户」，金额随手填），不含任何真实账单文本。
 */
class BillFileParserTest {

    private val gbk: Charset = Charset.forName("GBK")

    /** 自造的支付宝账单：4 行元信息 + 表头 + 5 条数据 */
    private val alipayCsv = listOf(
        "示例账单明细",
        "账号:[test***@example.com]",
        "起始日期:[2026-10-01] 终止日期:[2026-11-01]",
        "-----------------交易记录明细列表-----------------",
        "交易号,交易创建时间,商品名称,交易对方,金额（元）,收/支,交易状态,支付方式,支付宝交易分类",
        "2026110600000001,2026-11-06 20:40:51,示例外卖订单,示例商户,¥12.34,支出,交易成功,余额宝,餐饮美食",
        // 千分位金额必须按 CSV 规范加引号：不加引号时 RFC4180 会把 ¥1,234.56 切成两列
        // （真实导出的支付宝专用于分节，不会带千分位，这里是为了覆盖「引号内逗号」这条规则）
        "2026110600000002,2026-11-06 21:00:00,示例工资,示例公司,\"¥1,234.56\",收入,交易成功,银行卡,转账红包",
        "2026110600000003,2026-11-06 22:00:00,示例商品,示例商户,¥0.01,支出,交易关闭,余额宝,日用百货",
        "2026110600000004,2026-11-06 23:00:00,示例会员,示例商户,¥9.90,支出,已全额退款,余额宝,虚拟服务",
        "2026110700000005,2026-11-07 08:00:00,转账到本人银行卡,示例银行,¥100.00,,交易成功,余额宝,转账",
    ).joinToString("\n")

    private fun gbkBytes(text: String): ByteArray = text.toByteArray(gbk)

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `支付宝 GBK csv 逐行映射成 BillRecord`() {
        val records = BillFileParser.parse("alipay_record_example.csv", gbkBytes(alipayCsv))
        assertEquals(5, records.size)

        val first = records[0]
        assertEquals(BillChannel.ALIPAY, first.channel)
        assertEquals("2026110600000001", first.orderId)
        assertEquals("2026-11-06 20:40:51", first.tradeTime)
        assertEquals(12.34, first.amount, 0.0001)
        assertEquals(BillDirection.EXPENSE, first.direction)
        assertEquals("示例商户", first.counterparty)
        assertEquals("示例外卖订单", first.product)
        assertEquals("余额宝", first.payMethod)
        assertEquals("餐饮美食", first.platformCategory)
        assertEquals("交易成功", first.status)
        assertEquals("支出", first.directionText)
        assertEquals("行号指回原文件（含表头与元信息）", 6, first.lineNo)

        assertEquals(1234.56, records[1].amount, 0.0001)
        assertEquals(BillDirection.INCOME, records[1].direction)
        assertEquals(7, records[1].lineNo)
    }

    @Test
    fun `交易关闭 全额退款 本人账户转账 判为 SKIP 且金额取绝对值`() {
        val records = BillFileParser.parse("alipay_record_example.csv", gbkBytes(alipayCsv))
        assertEquals(BillDirection.SKIP, records[2].direction)
        assertEquals(BillDirection.SKIP, records[3].direction)
        assertEquals(BillDirection.SKIP, records[4].direction)
        assertEquals(0.01, records[2].amount, 0.0001)
        assertEquals(100.0, records[4].amount, 0.0001)
    }

    @Test
    fun `微信 UTF-8 BOM csv 认得出渠道`() {
        val csv = listOf(
            "微信支付账单明细,,,,,,,,",
            "微信昵称:[测试昵称],,,,,,,,",
            "起始时间:[2026-10-01],,,,,,,,",
            "共 2 笔记录,,,,,,,,",
            ",,,,,,,,",
            "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号",
            "2026-11-06 20:40:51,商户消费,示例商户,示例商品,支出,¥12.34,零钱,支付成功,1000000000001",
            "2026-11-06 21:00:00,转账,/,/,/,¥0.00,零钱,已收钱,1000000000002",
        ).joinToString("\n")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray(Charsets.UTF_8)
        val records = BillFileParser.parse("微信支付账单(示例).csv", bytes)
        assertEquals(2, records.size)
        assertEquals(BillChannel.WECHAT, records[0].channel)
        assertEquals("示例商户", records[0].counterparty)
        assertEquals(12.34, records[0].amount, 0.0001)
        assertEquals(BillDirection.EXPENSE, records[0].direction)
        assertEquals("收/支为 / 视为不计收支", BillDirection.SKIP, records[1].direction)
    }

    @Test
    fun `zip 里取 csv 并解出记录`() {
        val bytes = zipOf(
            mapOf(
                "__MACOSX/._alipay.csv" to byteArrayOf(1, 2, 3),
                "说明.txt" to "这个包是示例".toByteArray(Charsets.UTF_8),
                "alipay_record_2026.csv" to gbkBytes(alipayCsv),
            ),
        )
        val records = BillFileParser.parse("示例账单.zip", bytes)
        assertEquals(5, records.size)
        assertEquals(BillChannel.ALIPAY, records[0].channel)
    }

    @Test
    fun `zip 里没有 csv 时明确报错`() {
        val bytes = zipOf(mapOf("说明.txt" to "没有账单".toByteArray(Charsets.UTF_8)))
        val error = assertThrows(IllegalArgumentException::class.java) {
            BillFileParser.parse("示例.zip", bytes)
        }
        assertTrue(error.message!!.contains("没有 csv"))
    }

    @Test
    fun `xlsx 端到端`() {
        val shared = """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <si><t>交易号</t></si><si><t>交易创建时间</t></si><si><t>商品名称</t></si>
            <si><t>交易对方</t></si><si><t>金额（元）</t></si><si><t>收/支</t></si><si><t>交易状态</t></si>
            </sst>
        """.trimIndent()
        val sheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1">
              <c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c>
              <c r="D1" t="s"><v>3</v></c><c r="E1" t="s"><v>4</v></c><c r="F1" t="s"><v>5</v></c>
              <c r="G1" t="s"><v>6</v></c>
            </row>
            <row r="2">
              <c r="A2" t="inlineStr"><is><t>2026110600000009</t></is></c>
              <c r="B2" t="inlineStr"><is><t>2026-11-06 20:40:51</t></is></c>
              <c r="C2" t="inlineStr"><is><t>示例商品</t></is></c>
              <c r="D2" t="inlineStr"><is><t>示例商户</t></is></c>
              <c r="E2"><v>12.34</v></c>
              <c r="F2" t="inlineStr"><is><t>支出</t></is></c>
              <c r="G2" t="inlineStr"><is><t>交易成功</t></is></c>
            </row>
            </sheetData></worksheet>
        """.trimIndent()
        val bytes = zipOf(
            mapOf(
                "xl/worksheets/sheet1.xml" to sheet.toByteArray(Charsets.UTF_8),
                "xl/sharedStrings.xml" to shared.toByteArray(Charsets.UTF_8),
            ),
        )
        val records = BillFileParser.parse("示例账单.xlsx", bytes)
        assertEquals(1, records.size)
        assertEquals("2026110600000009", records[0].orderId)
        assertEquals(12.34, records[0].amount, 0.0001)
        assertEquals(BillDirection.EXPENSE, records[0].direction)
        assertEquals("行号指回原文件（xlsx 无元信息行，表头是第 1 行）", 2, records[0].lineNo)
    }

    @Test
    fun `认不出表头时报错 不是静默返回空列表`() {
        val csv = "随便什么,不是账单\n1,2"
        val error = assertThrows(IllegalArgumentException::class.java) {
            BillFileParser.parse("示例.csv", gbkBytes(csv))
        }
        assertTrue(error.message!!.contains("表头识别失败"))
    }

    @Test
    fun `超过上限直接拒绝`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BillFileParser.parse("示例.csv", gbkBytes(alipayCsv), maxBytes = 10)
        }
        assertTrue(error.message!!.contains("文件过大"))
    }
}
