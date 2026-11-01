package com.bsfeng.monitor.domain.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Base32 容错口径：抄写差异必须容忍，可疑密钥必须拒绝。 */
class Base32Test {

    // ───────────────────────── RFC 4648 §10 官方向量 ─────────────────────────

    @Test
    fun `解码 RFC 4648 八组标准向量`() {
        val vectors = listOf(
            "" to "",
            "f" to "MY======",
            "fo" to "MZXQ====",
            "foo" to "MZXW6===",
            "foob" to "MZXW6YQ=",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI======",
        )
        for ((plain, encoded) in vectors) {
            assertArrayEquals(
                "解码 " + encoded,
                plain.toByteArray(Charsets.US_ASCII),
                Base32.decodeOrNull(encoded) ?: ByteArray(0),
            )
        }
    }

    @Test
    fun `编码输出不带填充`() {
        assertEquals("", Base32.encode(ByteArray(0)))
        assertEquals("MY", Base32.encode("f".toByteArray(Charsets.US_ASCII)))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `编码解码往返一致`() {
        val raw = ByteArray(32) { (it * 7 + 3).toByte() }
        val encoded = Base32.encode(raw)
        assertEquals(52, encoded.length)
        assertArrayEquals(raw, Base32.decodeOrNull(encoded))
    }

    // ───────────────────────── 容错：大小写 / 空格 / 连字符 / 填充 ─────────────────────────

    @Test
    fun `大小写不敏感`() {
        val expected = Base32.decodeOrNull("JBSWY3DPEHPK3PXP")
        assertArrayEquals(expected, Base32.decodeOrNull("jbswy3dpehpk3pxp"))
    }

    @Test
    fun `忽略空格与连字符`() {
        val expected = Base32.decodeOrNull("JBSWY3DPEHPK3PXP")
        assertArrayEquals(expected, Base32.decodeOrNull("JBSW Y3DP EHPK 3PXP"))
        assertArrayEquals(expected, Base32.decodeOrNull("JBSW-Y3DP-EHPK-3PXP"))
        assertArrayEquals(expected, Base32.decodeOrNull("  JBSWY3DPEHPK3PXP\n"))
    }

    @Test
    fun `容忍填充字符`() {
        assertArrayEquals(
            Base32.decodeOrNull("MZXW6YTB"),
            Base32.decodeOrNull("MZXW6YTB===="),
        )
    }

    // ───────────────────────── 拒绝：非法字母 / 长度 / 尾部位 ─────────────────────────

    @Test
    fun `非法字母返回 null`() {
        assertNull(Base32.decodeOrNull("JBSWY3DP!"))
        assertNull(Base32.decodeOrNull("JBSW1Y3P"))
        assertNull(Base32.decodeOrNull("JBSW0Y3P"))
        assertNull(Base32.decodeOrNull("JBSW8Y3P"))
        assertNull(Base32.decodeOrNull("JBSW9Y3P"))
    }

    @Test
    fun `空输入与纯填充返回 null`() {
        assertNull(Base32.decodeOrNull(""))
        assertNull(Base32.decodeOrNull("   "))
        assertNull(Base32.decodeOrNull("===="))
    }

    @Test
    fun `非法长度余数返回 null`() {
        assertNull(Base32.decodeOrNull("A"))
        assertNull(Base32.decodeOrNull("ABC"))
        assertNull(Base32.decodeOrNull("ABCDEF"))
    }

    @Test
    fun `尾部填充位非零返回 null`() {
        // AB = 00000 00001，末 2 位非零 → 密钥被截断或改坏
        assertNull(Base32.decodeOrNull("AB"))
        assertArrayEquals(byteArrayOf(0), Base32.decodeOrNull("AA"))
    }
}
