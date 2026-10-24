package com.bsfeng.monitor.quota.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/** 极简 JSON reader 的解析与取值口径（provider 的解析全靠它）。 */
class QuotaJsonTest {

    @Test
    fun `嵌套对象数组与转义字符串`() {
        val body = """
            {"a":{"b":[1,2.5,-3e2,true,false,null],"c":"x\"y\\z\n"},
             "u":"\u4e2d\u6587",
             "empty":{},"list":[]}
        """.trimIndent()
        val root = QuotaJson.parse(body)!!

        val array = root.obj("a")!!.items("b")
        assertEquals(6, array.size)
        assertEquals(0, array[0].asDecimal()!!.compareTo(BigDecimal("1")))
        assertEquals(0, array[2].asDecimal()!!.compareTo(BigDecimal("-300")))
        assertEquals(JsonNode.Bool(true), array[3])
        assertEquals(JsonNode.Null, array[5])
        assertEquals("x\"y\\z\n", root.obj("a").str("c"))
        assertEquals("中文", root.str("u"))
        assertTrue(root.items("list").isEmpty())
        assertTrue(root.obj("empty")!!.fields.isEmpty())
    }

    @Test
    fun `字段类型不符或缺字段一律返回 null`() {
        val root = QuotaJson.parse("""{"num":"12.5","text":"abc","flag":"true","arr":[1]}""")!!

        assertEquals(0, root.dec("num")!!.compareTo(BigDecimal("12.5")))
        assertNull(root.dec("text"))
        assertNull(root.dec("missing"))
        assertNull(root.long("text"))
        // 数字字段按字符串取值时也拿得到（DeepSeek 的 code 有时是数字）
        assertEquals("12.5", root.str("num"))
        assertEquals(true, root.bool("flag"))
        assertNull(root.bool("text"))
        assertNull(root.obj("arr"))
        assertEquals(1, root.items("arr").size)
    }

    @Test
    fun `非法 JSON 返回 null 而不是抛错`() {
        listOf(
            "",
            "   ",
            "{",
            """{"a":1""",
            """{"a":1}x""",
            "{a:1}",
            """{"a":}""",
            """{"a":"unterminated}""",
            "[1,2,]",
            "nul",
            """{"a":"\q"}""",
        ).forEach { body ->
            assertNull("body=$body 应当解析失败", QuotaJson.parse(body))
        }
    }

    @Test
    fun `时间字段支持秒毫秒与 ISO 字符串`() {
        val root = QuotaJson.parse(
            """
            {"sec":1792783200,"ms":1792783200000,
             "iso":"2026-10-23T19:20:00Z",
             "offset":"2026-10-24T03:20:00+08:00",
             "bad":"yesterday","null":null}
            """.trimIndent(),
        )!!

        val expected = Instant.parse("2026-10-23T19:20:00Z").epochSecond
        assertEquals(expected, root.epochSecond("sec"))
        assertEquals(expected, root.epochSecond("ms"))
        assertEquals(expected, root.epochSecond("iso"))
        assertEquals(expected, root.epochSecond("offset"))
        assertNull(root.epochSecond("bad"))
        assertNull(root.epochSecond("null"))
        assertNull(root.epochSecond("missing"))
    }

    @Test
    fun `oneLine 压平换行并截断`() {
        assertNull(QuotaJson.oneLine(null))
        assertNull(QuotaJson.oneLine("  \n "))
        assertEquals("a b", QuotaJson.oneLine("a\n\t b"))
        val long = "x".repeat(400)
        assertEquals(160, QuotaJson.oneLine(long)!!.length)
    }
}
