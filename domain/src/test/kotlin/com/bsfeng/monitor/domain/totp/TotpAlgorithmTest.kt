package com.bsfeng.monitor.domain.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 6238 附录 B 的全部官方向量，外加 RFC 4226 的计数器语义。
 *
 * 这些向量是唯一能证明「我们算的码和服务端算的是同一个码」的证据，
 * 所以逐条断言、不用循环糊过去——某一条挂了要能立刻定位到是哪一组时间/算法。
 */
class TotpAlgorithmTest {

    @Test
    fun `T 等于 59 时 SHA1 出 287082`() {
        assertEquals("287082", totp6(RFC_SHA1, 59L))
    }

    @Test
    fun `T 等于 1111111109 时 SHA1 出 081804`() {
        assertEquals("081804", totp6(RFC_SHA1, 1111111109L))
    }

    @Test
    fun `T 等于 1111111111 时 SHA1 出 050471`() {
        assertEquals("050471", totp6(RFC_SHA1, 1111111111L))
    }

    @Test
    fun `T 等于 1234567890 时 SHA1 出 005924`() {
        assertEquals("005924", totp6(RFC_SHA1, 1234567890L))
    }

    @Test
    fun `T 等于 2000000000 时 SHA1 出 279037`() {
        assertEquals("279037", totp6(RFC_SHA1, 2000000000L))
    }

    @Test
    fun `T 等于 20000000000 时 SHA1 出 353130`() {
        assertEquals("353130", totp6(RFC_SHA1, 20000000000L))
    }

    @Test
    fun `八位 SHA1 与 RFC 八位输出一致`() {
        val expected = listOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        for ((second, code) in expected) {
            assertEquals(
                "SHA1 @" + second,
                code,
                TotpAlgorithm.totp(RFC_SHA1, second, TotpParams(digits = 8)),
            )
        }
    }

    @Test
    fun `八位 SHA256 与 RFC 八位输出一致`() {
        val expected = listOf(
            59L to "46119246",
            1111111109L to "68084774",
            1111111111L to "67062674",
            1234567890L to "91819424",
            2000000000L to "90698825",
            20000000000L to "77737706",
        )
        for ((second, code) in expected) {
            assertEquals(
                "SHA256 @" + second,
                code,
                TotpAlgorithm.totp(RFC_SHA256, second, TotpParams(algorithm = "SHA256", digits = 8)),
            )
        }
    }

    @Test
    fun `八位 SHA512 与 RFC 八位输出一致`() {
        val expected = listOf(
            59L to "90693936",
            1111111109L to "25091201",
            1111111111L to "99943326",
            1234567890L to "93441116",
            2000000000L to "38618901",
            20000000000L to "47863826",
        )
        for ((second, code) in expected) {
            assertEquals(
                "SHA512 @" + second,
                code,
                TotpAlgorithm.totp(RFC_SHA512, second, TotpParams(algorithm = "SHA512", digits = 8)),
            )
        }
    }

    // ───────────────────────── 窗口与计数器语义 ─────────────────────────

    @Test
    fun `同一窗口内换秒钟不换码`() {
        val atStart = totp6(RFC_SHA1, 30L)
        assertEquals(atStart, totp6(RFC_SHA1, 31L))
        assertEquals(atStart, totp6(RFC_SHA1, 59L))
    }

    @Test
    fun `跨窗口换码`() {
        assertTrue(totp6(RFC_SHA1, 59L) != totp6(RFC_SHA1, 60L))
    }

    @Test
    fun `60 秒周期的窗口是 60 秒`() {
        val params = TotpParams(periodSec = 60)
        val first = TotpAlgorithm.totp(RFC_SHA1, 0L, params)
        assertEquals(first, TotpAlgorithm.totp(RFC_SHA1, 59L, params))
        assertTrue(first != TotpAlgorithm.totp(RFC_SHA1, 60L, params))
        assertEquals(TotpAlgorithm.hotp(RFC_SHA1, 1L, 6, "SHA1"), TotpAlgorithm.totp(RFC_SHA1, 60L, params))
    }

    @Test
    fun `HOTP 计数器直接映射为码`() {
        assertEquals(TotpAlgorithm.hotp(RFC_SHA1, 0L, 6, "SHA1"), totp6(RFC_SHA1, 0L))
        assertEquals("287082", TotpAlgorithm.hotp(RFC_SHA1, 1L, 6, "SHA1"))
        assertEquals("94287082", TotpAlgorithm.hotp(RFC_SHA1, 1L, 8, "SHA1"))
    }

    // ───────────────────────── 降级口径（不抛异常） ─────────────────────────

    @Test
    fun `空密钥返回空串`() {
        assertEquals("", TotpAlgorithm.hotp(ByteArray(0), 1L, 6, "SHA1"))
        assertEquals("", TotpAlgorithm.totp(ByteArray(0), 59L, TotpParams()))
    }

    @Test
    fun `非法位数与未知算法降级为六位 SHA1`() {
        val baseline = totp6(RFC_SHA1, 59L)
        assertEquals(baseline, TotpAlgorithm.totp(RFC_SHA1, 59L, TotpParams(digits = 7)))
        assertEquals(baseline, TotpAlgorithm.totp(RFC_SHA1, 59L, TotpParams(algorithm = "MD5")))
        assertEquals(baseline, TotpAlgorithm.totp(RFC_SHA1, 59L, TotpParams(algorithm = "")))
    }

    @Test
    fun `非法周期降级为三十秒`() {
        assertEquals(totp6(RFC_SHA1, 59L), TotpAlgorithm.totp(RFC_SHA1, 59L, TotpParams(periodSec = 45)))
        assertEquals(totp6(RFC_SHA1, 59L), TotpAlgorithm.totp(RFC_SHA1, 59L, TotpParams(periodSec = 0)))
    }

    @Test
    fun `算法名大小写与连字符不敏感`() {
        val expected = TotpAlgorithm.totp(RFC_SHA256, 59L, TotpParams(algorithm = "SHA256", digits = 8))
        assertEquals(expected, TotpAlgorithm.totp(RFC_SHA256, 59L, TotpParams(algorithm = "sha-256", digits = 8)))
        assertEquals(expected, TotpAlgorithm.totp(RFC_SHA256, 59L, TotpParams(algorithm = "Sha256", digits = 8)))
    }

    @Test
    fun `负时间与负计数器按零处理`() {
        assertEquals(TotpAlgorithm.hotp(RFC_SHA1, 0L, 6, "SHA1"), TotpAlgorithm.totp(RFC_SHA1, -1L, TotpParams()))
        assertEquals(TotpAlgorithm.hotp(RFC_SHA1, 0L, 6, "SHA1"), TotpAlgorithm.hotp(RFC_SHA1, -99L, 6, "SHA1"))
    }

    @Test
    fun `密钥来自 Base32 时可正常出码`() {
        val secret = Base32.decodeOrNull("JBSWY3DPEHPK3PXP")
        val code = TotpAlgorithm.totp(secret!!, 59L, TotpParams())
        assertEquals(6, code.length)
        assertTrue(code.all { it in '0'..'9' })
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private fun totp6(secret: ByteArray, epochSecond: Long): String =
        TotpAlgorithm.totp(secret, epochSecond, TotpParams())

    private companion object {
        /** RFC 6238 附录 B：SHA1 用 20 字节种子，SHA256 用 32 字节，SHA512 用 64 字节。 */
        val RFC_SHA1: ByteArray = "12345678901234567890".toByteArray(Charsets.US_ASCII)
        val RFC_SHA256: ByteArray = "12345678901234567890123456789012".toByteArray(Charsets.US_ASCII)
        val RFC_SHA512: ByteArray =
            "1234567890123456789012345678901234567890123456789012345678901234".toByteArray(Charsets.US_ASCII)
    }
}
