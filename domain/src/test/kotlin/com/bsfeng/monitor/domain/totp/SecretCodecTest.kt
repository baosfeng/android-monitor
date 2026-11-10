package com.bsfeng.monitor.domain.totp

// SecretCodec 的口径：Base32 严格优先 → 标准 Base64 → URL-safe Base64；空串 = Empty、非法 = Invalid。
// 这里钉住的不是「能解开」，而是**优先顺序**——顺序反了 Steam 密钥会把规范 Base32 当成 Base64 解成另一串字节。

import com.bsfeng.monitor.domain.totp.SecretCodec.decodeOrNull
import com.bsfeng.monitor.domain.totp.SecretCodec.decodeToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretCodecTest {

    // ───────────────────────── Base32 分支（规范优先） ─────────────────────────

    @Test
    fun `规范 Base32 密钥走 Base32 分支`() {
        val decoded = ok("JBSWY3DPEHPK3PXP", "48656c6c6f21deadbeef", SECRET_KIND_BASE32)
        assertArrayEquals(bytes("48656c6c6f21deadbeef"), decoded.bytes)
    }

    @Test
    fun `小写 Base32 解出同一字节`() {
        val lower = ok("jbswy3dpehpk3pxp", "48656c6c6f21deadbeef", SECRET_KIND_BASE32)
        val upper = ok("JBSWY3DPEHPK3PXP", "48656c6c6f21deadbeef", SECRET_KIND_BASE32)
        assertArrayEquals(upper.bytes, lower.bytes)
    }

    @Test
    fun `带空格与连字符的手抄分组同样走 Base32`() {
        val decoded = ok("JBSW Y3DP-EHPK 3PXP", "48656c6c6f21deadbeef", SECRET_KIND_BASE32)
        assertArrayEquals(bytes("48656c6c6f21deadbeef"), decoded.bytes)
    }

    @Test
    fun `Base32 带等号 padding 仍然优先`() {
        val decoded = ok("JBSWY3DPEHPK3PXP=====", "48656c6c6f21deadbeef", SECRET_KIND_BASE32)
        assertArrayEquals(bytes("48656c6c6f21deadbeef"), decoded.bytes)
    }

    // ───────────────────────── Base64 分支（兜底） ─────────────────────────

    @Test
    fun `Steam 的标准 Base64 密钥回落 Base64 分支`() {
        // 20 字节密钥，长度余数 3：Base32 的合法余数里没有 3，严格 Base32 必然拒绝
        val decoded = ok(
            "cnOgvVunvLFR1gZAwzDxDrgbXWM=",
            "7273a0bd5ba7bcb151d60640c330f10eb81b5d63",
            SECRET_KIND_BASE64,
        )
        assertArrayEquals(bytes("7273a0bd5ba7bcb151d60640c330f10eb81b5d63"), decoded.bytes)
    }

    @Test
    fun `URL-safe Base64 的减号下划线字母表可用`() {
        val urlSafe = ok("-_-_", "fbffbf", SECRET_KIND_BASE64)
        val standard = ok("+/+/", "fbffbf", SECRET_KIND_BASE64)
        assertArrayEquals(bytes("fbffbf"), urlSafe.bytes)
        assertArrayEquals(bytes("fbffbf"), standard.bytes)
    }

    @Test
    fun `无 padding 的 Base64 自动补齐后解码`() {
        // "Zm9vYg" 缺 2 个等号：补成 8 位后是 "foo b"
        assertArrayEquals(bytes("666f6f62"), ok("Zm9vYg", "666f6f62", SECRET_KIND_BASE64).bytes)
        assertArrayEquals(bytes("666f6f"), ok("Zm9v", "666f6f", SECRET_KIND_BASE64).bytes)
    }

    // ───────────────────────── Base64 回落的可信门槛 ─────────────────────────
    // 回落不是「Base32 不通过就试 Base64」：Base64 字母表几乎覆盖 [A-Za-z0-9]，
    // 手抄错一位的 Base32（JBSW1Y3P）会被误收，界面照样出码但永远不对。
    // 判据（见 SecretCodec.looksLikeBase64）：URL-safe 专有字符 / padding / 大小写混排，任一命中才回落。

    @Test
    fun `纯大写的短串不回落 Base64 而是 Invalid`() {
        // "ABC" 只有 3 字符、无 padding、纯大写：更像手抄漏了几位的 Base32
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("ABC"))
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("AB"))
    }

    @Test
    fun `手抄错的 Base32 一律 Invalid 而不被 Base64 收下`() {
        // '1' 不在 Base32 字母表内（易被误抄成 I 或 1），但它在 Base64 里合法：
        // 若不设门槛，这条会被当成 base64 解出 8 字节然后永远算出错的码
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("JBSW1Y3P"))
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("JBSWY3D0"))
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("ABCDEF"))
        assertNull(decodeToBytes("JBSW1Y3P"))
    }

    @Test
    fun `纯小写的串同样不回落 Base64`() {
        // 全小写更像手抄的 Base32（RFC 4648 的 Base32 大小写不敏感）
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("zm9vyg"))
    }

    @Test
    fun `大小写混排即认为像 Base64 并允许回落`() {
        // "Zm9vYg" 无 padding、无 URL-safe 字符，只靠大小写混排命中门槛
        assertArrayEquals(bytes("666f6f62"), ok("Zm9vYg", "666f6f62", SECRET_KIND_BASE64).bytes)
    }

    @Test
    fun `带 padding 的串允许回落`() {
        // "AAAAAA==" 去掉 padding 后 6 个字符（Base32 的合法余数只有 0/2/4/5/7，余数 6 必拒），
        // 且纯大写、无 URL-safe 字符 —— 只有 padding 这一条门槛命中，仍应回落到 Base64
        assertArrayEquals(bytes("00000000"), ok("AAAAAA==", "00000000", SECRET_KIND_BASE64).bytes)
    }

    @Test
    fun `看着像 Base64 但两种字母表都解不出时归 Invalid`() {
        // 含 URL-safe 专有字符（命中门槛），但 '#' 不是任何 Base64 字母表里的字符：
        // 标准解码器与 URL-safe 解码器都抛 IllegalArgumentException → 最终 Invalid，而不是崩出去
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("+/+#"))
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("ab/c*d"))
    }

    @Test
    fun `太短的串即使带 padding 也不回落`() {
        // 去掉 padding 后只剩 2–3 个字符（低于 MIN_BASE64_CHARS=4）：只可能是手抄错的 Base32
        // 注意："AA==" 本身是合法 Base32（2 字符 → 1 字节），会被 Base32 分支收下，不是这条用例的对象；
        // 这里只挑 Base32 余数非法（长度 1 / 3）的串
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("A==="))
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("AAA=="))
    }

    @Test
    fun `URL-safe 专有字符命中门槛`() {
        assertArrayEquals(bytes("fbffbf"), ok("-_-_", "fbffbf", SECRET_KIND_BASE64).bytes)
    }

    // ───────────────────────── 顺序口径：两边都能解时必须 Base32 ─────────────────────────

    @Test
    fun `两种字母表都能解时优先 Base32`() {
        // "JBSWY3DX" 同时满足 Base32（8 字符，余数 0）与 Base64 字母表，两种解出的字节完全不同：
        // Base32 → 48 65 6c 6c 77；Base64 → 24 14 96 63 70 d7
        val decoded = ok("JBSWY3DX", "48656c6c77", SECRET_KIND_BASE32)
        assertArrayEquals(bytes("48656c6c77"), decoded.bytes)
        assertTrue("Base32 结果必须与 Base64 结果不同，否则这条用例没有分辨力", decoded.bytes.size == 5)
    }

    @Test
    fun `长密钥同样以 Base32 为准`() {
        // 24 字符：Base32 余数 0（合法）且整串也在 Base64 字母表内
        val decoded = ok("NBSWY3DPEB3W64TMMQQDCMRU", "68656c6c6f20776f726c6420313234", SECRET_KIND_BASE32)
        assertArrayEquals(bytes("68656c6c6f20776f726c6420313234"), decoded.bytes)
    }

    // ───────────────────────── 空输入 ─────────────────────────

    @Test
    fun `空串归 Empty 而不是 Invalid`() {
        assertEquals(SecretDecoded.Empty, decodeOrNull(""))
        assertNull("Empty 不产生字节", decodeToBytes(""))
    }

    @Test
    fun `纯空白归 Empty`() {
        assertEquals(SecretDecoded.Empty, decodeOrNull("   "))
        assertEquals(SecretDecoded.Empty, decodeOrNull("\t\n  "))
        assertEquals(SecretDecoded.Empty, decodeOrNull(" - "))
    }

    @Test
    fun `只有等号归 Empty`() {
        assertEquals(SecretDecoded.Empty, decodeOrNull("===="))
        assertEquals(SecretDecoded.Empty, decodeOrNull("=="))
        assertEquals(SecretDecoded.Empty, decodeOrNull(" = "))
    }

    @Test
    fun `只有分隔符的输入归 Empty 而不是 Invalid`() {
        // 只有连字符/等号/空白的串不携带任何密钥信息：含义是「没写密钥」，不是「密钥写错了」
        assertEquals(SecretDecoded.Empty, decodeOrNull("-"))
        assertEquals(SecretDecoded.Empty, decodeOrNull("----"))
        assertEquals(SecretDecoded.Empty, decodeOrNull(" - - - "))
        assertEquals(SecretDecoded.Empty, decodeOrNull("-=-="))
        assertNull(decodeToBytes("----"))
    }

    @Test
    fun `URL-safe 字母表的减号与下划线仍按数据参与解码`() {
        // 判空时忽略连字符，但解码时连字符是 URL-safe Base64 的数据（"-_-_" = fb ff bf）
        assertArrayEquals(bytes("fbffbf"), decodeToBytes("-_-_"))
    }

    // ───────────────────────── 非法输入 ─────────────────────────

    @Test
    fun `两边都解不出时归 Invalid 且 reason 恒为 secret_invalid`() {
        val invalid = decodeOrNull("!!!!") as SecretDecoded.Invalid
        assertEquals(OtpauthUri.REASON_SECRET_INVALID, invalid.reason)
    }

    @Test
    fun `含 Base32 非法字符的串按 Invalid 处理`() {
        // 0 / 1 / 8 / 9 不在 Base32 字母表内；"0189" 又恰好是 Base64 合法字母表 → 需要更强的坏串
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("JBSWY3DP!PXP"))
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("密钥"))
    }

    @Test
    fun `Base32 合法长度但尾部非零位时归 Invalid`() {
        // "AAB" 长度 3 不是合法余数 → Base32 拒；Base64 也不合法 → Invalid
        assertEquals(SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), decodeOrNull("AAB!"))
    }

    // ───────────────────────── decodeToBytes 直连 ─────────────────────────

    @Test
    fun `decodeToBytes 只要字节不要来源标记`() {
        assertArrayEquals(bytes("48656c6c6f21deadbeef"), decodeToBytes("JBSWY3DPEHPK3PXP"))
        assertArrayEquals(
            bytes("7273a0bd5ba7bcb151d60640c330f10eb81b5d63"),
            decodeToBytes("cnOgvVunvLFR1gZAwzDxDrgbXWM="),
        )
    }

    @Test
    fun `decodeToBytes 对非法串返回 null 而不抛异常`() {
        assertNull(decodeToBytes("!!!!"))
        assertNull(decodeToBytes(" "))
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private fun ok(text: String, expectedHex: String, kind: String): SecretDecoded.Ok {
        val decoded = decodeOrNull(text) as? SecretDecoded.Ok
            ?: throw AssertionError("期望 Ok，实际是 " + decodeOrNull(text) + "（输入：$text）")
        assertArrayEquals("字节与期望不一致（输入：$text）", bytes(expectedHex), decoded.bytes)
        assertEquals("来源标记（输入：$text）", kind, decoded.kind)
        return decoded
    }

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
