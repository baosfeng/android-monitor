package com.bsfeng.monitor.skip.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JSON5 解析器单元测试。
 *
 * 覆盖：
 * - 正常路径：GKD 订阅规则片段、三种引号、转义、注释、尾随逗号、未加引号键
 * - 边界条件：空对象/空数组、深层嵌套、Unicode/十六进制转义、大整数、数字类型归一化
 * - 异常场景：未闭合字符串、缺少冒号、非法字符、超深嵌套等
 */
class Json5ParserTest {

    // ===== 正常路径 =====

    @Test
    fun `parse gkd subscription sample`() {
        val text = """
            // GKD 订阅规则片段
            {
              id: 666,
              name: '示例规则',
              /* 块注释：版本号 */
              version: 1.5,
              enable: true,
              apps: [
                { id: 'com.example.app', name: "示例应用", },
                { id: `com.demo.app`, name: '演示', },
              ],
              extra: null,
              hex: 0x1F,
            }
        """.trimIndent()
        val root = parseObject(text)
        assertEquals(666, root["id"])
        assertEquals("示例规则", root["name"])
        assertEquals(1.5, root["version"])
        assertEquals(true, root["enable"])
        assertNull(root["extra"])
        assertEquals(31, root["hex"])
        val apps = root["apps"] as List<*>
        assertEquals(2, apps.size)
        val first = apps[0] as Map<*, *>
        assertEquals("com.example.app", first["id"])
        assertEquals("示例应用", first["name"])
        assertEquals("com.demo.app", (apps[1] as Map<*, *>)["id"])
    }

    @Test
    fun `parse three quote styles`() {
        val root = parseObject("""{ a: 'single', b: "double", c: `back` }""")
        assertEquals("single", root["a"])
        assertEquals("double", root["b"])
        assertEquals("back", root["c"])
    }

    @Test
    fun `parse string escapes`() {
        val text = """'a\\b\'c\"d\/e\nf\rg\th\bi\fj'"""
        assertEquals("a\\b'c\"d/e\nf\rg\th\bi\u000Cj", Json5Parser.parse(text))
    }

    @Test
    fun `parse unicode and hex escapes`() {
        assertEquals("中文", Json5Parser.parse("""'\u4e2d\u6587'"""))
        assertEquals("AB", Json5Parser.parse("""'\x41\x42'"""))
    }

    @Test
    fun `parse line and block comments`() {
        val text = """
            // 行注释
            {
              a: /* 行内块注释 */ 1, // 尾部行注释
              b: 2,
            }
            // 结束注释
        """.trimIndent()
        val root = parseObject(text)
        assertEquals(1, root["a"])
        assertEquals(2, root["b"])
    }

    @Test
    fun `parse trailing commas`() {
        assertEquals(3, parseArray("[1, 2, 3,]").size)
        assertEquals(1, parseObject("{ a: 1, }").size)
    }

    @Test
    fun `parse hex integer`() {
        assertEquals(31, Json5Parser.parse("0x1F"))
        assertEquals(255, Json5Parser.parse("0Xff"))
        assertEquals(-16, Json5Parser.parse("-0x10"))
    }

    @Test
    fun `parse scientific notation`() {
        assertEquals(1500, Json5Parser.parse("1.5e3"))
        assertEquals(0.0015, Json5Parser.parse("1.5e-3"))
        assertEquals(1000, Json5Parser.parse("10E+2"))
    }

    @Test
    fun `parse nan and infinity`() {
        assertEquals(Double.NaN, Json5Parser.parse("NaN"))
        assertEquals(Double.POSITIVE_INFINITY, Json5Parser.parse("Infinity"))
        assertEquals(Double.NEGATIVE_INFINITY, Json5Parser.parse("-Infinity"))
        assertTrue(Json5Parser.parse("+Infinity") is Double)
    }

    @Test
    fun `parse plus prefix and negative numbers`() {
        assertEquals(5, Json5Parser.parse("+5"))
        assertEquals(-5, Json5Parser.parse("-5"))
        assertEquals(-1.25, Json5Parser.parse("-1.25"))
        assertEquals(2, Json5Parser.parse("2."))
        assertEquals(0.5, Json5Parser.parse(".5"))
    }

    @Test
    fun `parse null true false and top level scalar`() {
        assertNull(Json5Parser.parse("null"))
        assertEquals(true, Json5Parser.parse("true"))
        assertEquals(false, Json5Parser.parse("false"))
        assertEquals("hello", Json5Parser.parse("  'hello'  "))
    }

    @Test
    fun `parse nested object and array`() {
        val root = parseObject("{ list: [ { deep: [1, { x: 'y' }] } ] }")
        val list = root["list"] as List<*>
        val first = list[0] as Map<*, *>
        val deep = first["deep"] as List<*>
        assertEquals(1, deep[0])
        assertEquals("y", (deep[1] as Map<*, *>)["x"])
    }

    // ===== 边界条件 =====

    @Test
    fun `parse empty object and array`() {
        assertTrue(parseObject("{}").isEmpty())
        assertTrue(parseArray("[]").isEmpty())
        assertTrue(parseObject("{ /* 只有注释 */ }").isEmpty())
        assertTrue(parseArray("[ /* 只有注释 */ ]").isEmpty())
    }

    @Test
    fun `number types normalized by value`() {
        assertTrue(Json5Parser.parse("42") is Int)
        assertTrue(Json5Parser.parse("2147483647") is Int)
        assertTrue(Json5Parser.parse("2147483648") is Long)
        assertTrue(Json5Parser.parse("-9223372036854775808") is Long)
        assertTrue(Json5Parser.parse("1.5") is Double)
        assertTrue(Json5Parser.parse("2e3") is Int)
    }

    @Test
    fun `parse large integers`() {
        assertEquals(9223372036854775807L, Json5Parser.parse("9223372036854775807"))
        assertEquals(2147483648L, Json5Parser.parse("2147483648"))
        assertTrue(Json5Parser.parse("12345678901234567890") is Double)
        assertTrue(Json5Parser.parse("0xFFFFFFFFFFFFFFFFFFFF") is Double)
    }

    @Test
    fun `parse deep nesting within limit`() {
        val depth = 200
        var node: Any? = Json5Parser.parse("[".repeat(depth) + "1" + "]".repeat(depth))
        repeat(depth) { node = (node as List<*>)[0] }
        assertEquals(1, node)
    }

    // ===== 异常场景 =====

    @Test(expected = IllegalArgumentException::class)
    fun `reject unclosed string`() {
        Json5Parser.parse("{ a: 'abc }")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject string with raw newline`() {
        Json5Parser.parse("{ a: 'ab\nc' }")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject missing colon`() {
        Json5Parser.parse("{ a 1 }")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject illegal character`() {
        Json5Parser.parse("{ a: @ }")
    }

    @Test
    fun `reject illegal character in array`() {
        assertOffsetError("[1, %]")
    }

    @Test
    fun `reject empty or comment only input`() {
        assertOffsetError("")
        assertOffsetError("   ")
        assertOffsetError("// 只有注释")
    }

    @Test
    fun `reject unterminated block comment`() {
        assertOffsetError("{ a: 1 /* 未闭合")
    }

    @Test
    fun `reject nesting deeper than limit`() {
        val depth = 500
        assertOffsetError("[".repeat(depth) + "]".repeat(depth))
    }

    @Test
    fun `reject unclosed object and array`() {
        assertOffsetError("{ a: 1")
        assertOffsetError("[1, 2")
    }

    @Test
    fun `reject trailing content`() {
        assertOffsetError("{ a: 1 } extra")
    }

    @Test
    fun `reject unknown literal`() {
        assertOffsetError("{ a: undefined }")
    }

    @Test
    fun `reject bad escape`() {
        assertOffsetError("""'\u12'""")
        assertOffsetError("""'\xZ'""")
    }

    @Test
    fun `reject number without digits`() {
        assertOffsetError("0x")
        assertOffsetError("1e")
    }

    // ===== 辅助方法 =====

    /** 解析为对象视图。 */
    private fun parseObject(text: String): Map<*, *> = Json5Parser.parse(text) as Map<*, *>

    /** 解析为数组视图。 */
    private fun parseArray(text: String): List<*> = Json5Parser.parse(text) as List<*>

    /** 断言解析失败，且异常信息包含出错偏移。 */
    private fun assertOffsetError(text: String) {
        try {
            Json5Parser.parse(text)
            fail("期望抛出 IllegalArgumentException：$text")
        } catch (e: IllegalArgumentException) {
            val message = e.message
            assertTrue("异常信息应包含偏移：$message", message != null && message.contains("偏移"))
        }
    }
}
