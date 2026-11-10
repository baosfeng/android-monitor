package com.bsfeng.monitor.totp.ui

// formatCode 的**新口径**：只分三档（4 / 6 / 8 位），其余原样；字母码不分段；按码的实际长度判而不看 digits。
//
// 旧口径是一律对半切——5 / 7 / 9 / 10 位会被切成两段不等长的碎片，用户对不上网站给的格式；
// Steam 的 5 位字母码分段更会打断字母串连读。这里逐档钉住，防止 else 分支被改回对半切。

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpFormatCodeTest {

    // ───────────────────────── 正常路径：三档分段 ─────────────────────────

    @Test
    fun `四位分成两段两字符`() {
        assertEquals("12 34", formatCode("1234", 4))
    }

    @Test
    fun `六位分成三段三字符`() {
        assertEquals("123 456", formatCode("123456", 6))
    }

    @Test
    fun `八位分成四段四字符`() {
        assertEquals("1234 5678", formatCode("12345678", 8))
    }

    @Test
    fun `前导零按字符串保留`() {
        // 码是字符串不是数字：前导 0 不能被吃掉，切点也不能变
        assertEquals("00 12", formatCode("0012", 4))
        assertEquals("000 001", formatCode("000001", 6))
        assertEquals("0000 0001", formatCode("00000001", 8))
    }

    @Test
    fun `分段只插一个空格且总长增加一`() {
        listOf(4, 6, 8).forEach { length ->
            val code = "1".repeat(length)
            val formatted = formatCode(code, length)
            assertEquals(length + 1, formatted.length)
            assertEquals(1, formatted.count { it == ' ' })
            assertEquals(code, formatted.replace(" ", ""))
        }
    }

    // ───────────────────────── 边界：不分段的位数 ─────────────────────────

    @Test
    fun `五位原样不分段`() {
        // 对半切会得到 "12 345"（两段不等长），网站给的是一整串
        assertEquals("12345", formatCode("12345", 5))
    }

    @Test
    fun `七位原样不分段`() {
        assertEquals("1234567", formatCode("1234567", 7))
    }

    @Test
    fun `九位原样不分段`() {
        assertEquals("123456789", formatCode("123456789", 9))
    }

    @Test
    fun `十位原样不分段`() {
        assertEquals("1234567890", formatCode("1234567890", 10))
    }

    @Test
    fun `一至三位原样返回`() {
        assertEquals("7", formatCode("7", 1))
        assertEquals("12", formatCode("12", 2))
        assertEquals("123", formatCode("123", 3))
    }

    @Test
    fun `超长的数字串原样返回`() {
        assertEquals("123456789012", formatCode("123456789012", 12))
    }

    // ───────────────────────── 异常输入 / 形态不匹配 ─────────────────────────

    @Test
    fun `空串原样返回空串`() {
        assertEquals("", formatCode("", 6))
        assertEquals("", formatCode("", 8))
    }

    @Test
    fun `Steam 五位字母码不分段`() {
        assertEquals("2JB2B", formatCode("2JB2B", 5))
        // 与 Steam 输出长度一致的口径：五位且含字母，既不切也不改大小写
        assertEquals("ABC23", formatCode("ABC23", 5))
    }

    @Test
    fun `长度正好是六或八的字母码同样不分段`() {
        // 非纯数字一律原样：分段只对数字码有意义
        assertEquals("ABC123", formatCode("ABC123", 6))
        assertEquals("abcdefgh", formatCode("abcdefgh", 8))
    }

    @Test
    fun `含分隔符或空格的码原样返回`() {
        assertEquals("12-34", formatCode("12-34", 4))
        assertEquals("1 2 3", formatCode("1 2 3", 6))
    }

    @Test
    fun `全角数字不当作数字分段`() {
        // isDigit() 对全角数字返回 true，但用户不会把全角码对到网站上；
        // 这里只钉住「不抛异常、长度不变」，不锁定是否分段
        val formatted = formatCode("１２３４", 4)
        assertEquals(4, formatted.replace(" ", "").length)
        assertTrue(formatted.isNotEmpty())
    }

    @Test
    fun `声明位数与实际长度不一致时按实际长度分段`() {
        // 声明 8 位却只给出 6 位数字 → 按 6 位分段（不是按 digits 对半切）
        assertEquals("123 456", formatCode("123456", 8))
        // 声明 6 位却给出 8 位 → 按 8 位分段
        assertEquals("1234 5678", formatCode("12345678", 6))
        // 声明 10 位、实际 5 位 → 5 位不分段
        assertEquals("12345", formatCode("12345", 10))
        // 声明 4 位、实际 5 位 → 5 位不分段（若按 digits 切会得到 "1234 5"）
        assertEquals("12345", formatCode("12345", 4))
    }

    @Test
    fun `digits 参数不影响分段结果`() {
        assertEquals(formatCode("12345678", 8), formatCode("12345678", 6))
        assertEquals(formatCode("1234", 4), formatCode("1234", 10))
        assertEquals(formatCode("1234567", 7), formatCode("1234567", 30))
    }
}
