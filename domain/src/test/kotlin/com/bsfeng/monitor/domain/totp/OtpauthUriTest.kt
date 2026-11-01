package com.bsfeng.monitor.domain.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * otpauth URI 是不可信输入，所以这里把「正常链接」与「必须被拒的链接」当成同等重要的两组用例：
 * 每一条拒绝都对应一个用户会真实遇到的情况（长链接、钓鱼 http、手抄错的密钥、7 位码）。
 */
class OtpauthUriTest {

    // ───────────────────────── 正常路径 ─────────────────────────

    @Test
    fun `解析 Google 认证器标准链接`() {
        val result = OtpauthUri.parse(
            "otpauth://totp/ACME%20Co:john.doe@email.com" +
                "?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30",
        )
        val account = success(result)
        assertEquals("ACME Co", account.issuer)
        assertEquals("john.doe@email.com", account.accountName)
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", account.secretBase32)
        assertEquals(TotpParams(), account.params)
        assertEquals(0L, account.id)
    }

    @Test
    fun `缺少 issuer 参数时从 label 前缀取`() {
        val account = success(OtpauthUri.parse("otpauth://totp/Example:alice@google.com?secret=JBSWY3DPEHPK3PXP"))
        assertEquals("Example", account.issuer)
        assertEquals("alice@google.com", account.accountName)
    }

    @Test
    fun `label 无 issuer 前缀时 issuer 为空`() {
        val account = success(OtpauthUri.parse("otpauth://totp/alice@google.com?secret=JBSWY3DPEHPK3PXP"))
        assertEquals("", account.issuer)
        assertEquals("alice@google.com", account.accountName)
    }

    @Test
    fun `解析非默认参数`() {
        val account = success(
            OtpauthUri.parse(
                "otpauth://totp/Corp:bob?secret=JBSWY3DPEHPK3PXP&algorithm=SHA512&digits=8&period=60",
            ),
        )
        assertEquals(TotpParams(algorithm = "SHA512", digits = 8, periodSec = 60), account.params)
    }

    @Test
    fun `scheme 与 type 大小写不敏感`() {
        val account = success(OtpauthUri.parse("OTPAUTH://TOTP/alice?secret=JBSWY3DPEHPK3PXP"))
        assertEquals("alice", account.accountName)
    }

    @Test
    fun `hotp 链接可解析按 TOTP 口径入库`() {
        val account = success(OtpauthUri.parse("otpauth://hotp/alice?secret=JBSWY3DPEHPK3PXP&counter=7"))
        assertEquals("alice", account.accountName)
        assertEquals(TotpParams(), account.params)
    }

    @Test
    fun `未知参数被忽略`() {
        val account = success(
            OtpauthUri.parse("otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&foo=bar&image=https://x/y.png"),
        )
        assertEquals("alice", account.accountName)
        assertEquals(TotpParams(), account.params)
    }

    @Test
    fun `密钥归一化为无填充大写`() {
        val spaced = success(OtpauthUri.parse("otpauth://totp/a?secret=jbsw y3dp-ehpk3pxp"))
        assertEquals("JBSWY3DPEHPK3PXP", spaced.secretBase32)
        val padded = success(OtpauthUri.parse("otpauth://totp/a?secret=MZXW6YTB===="))
        assertEquals("MZXW6YTB", padded.secretBase32)
    }

    @Test
    fun `重复参数以第一个为准`() {
        val account = success(
            OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&secret=MZXW6YTB"),
        )
        assertEquals("JBSWY3DPEHPK3PXP", account.secretBase32)
    }

    // ───────────────────────── 异常路径：全部走 Failure 不抛异常 ─────────────────────────

    @Test
    fun `空链接被拒`() {
        assertFailure(OtpauthUri.parse("   "), OtpauthUri.REASON_EMPTY)
    }

    @Test
    fun `超过 512 字符的链接被拒`() {
        val uri = "otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&pad=" + "y".repeat(500)
        assertTrue(uri.length > 512)
        assertFailure(OtpauthUri.parse(uri), OtpauthUri.REASON_TOO_LONG)
    }

    @Test
    fun `恰好 512 字符仍按正常链接解析`() {
        val prefix = "otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&pad="
        val uri = prefix + "y".repeat(512 - prefix.length)
        assertEquals(512, uri.length)
        success(OtpauthUri.parse(uri))
    }

    @Test
    fun `非 otpauth scheme 被拒`() {
        assertFailure(OtpauthUri.parse("http://totp/a?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_BAD_SCHEME)
        assertFailure(OtpauthUri.parse("https://totp/a?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_BAD_SCHEME)
        assertFailure(OtpauthUri.parse("totp://a?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_BAD_SCHEME)
    }

    @Test
    fun `未知 type 被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://motp/a?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_BAD_TYPE)
        assertFailure(OtpauthUri.parse("otpauth:///a?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_BAD_TYPE)
    }

    @Test
    fun `非法百分号编码被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a%ZZ?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_BAD_ENCODING)
    }

    @Test
    fun `缺少账户名被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_LABEL_MISSING)
        assertFailure(OtpauthUri.parse("otpauth://totp/ACME%20Co:?secret=JBSWY3DPEHPK3PXP"), OtpauthUri.REASON_LABEL_MISSING)
    }

    @Test
    fun `缺少密钥被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?issuer=b"), OtpauthUri.REASON_SECRET_MISSING)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret="), OtpauthUri.REASON_SECRET_INVALID)
    }

    @Test
    fun `非法 Base32 密钥被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=11111111"), OtpauthUri.REASON_SECRET_INVALID)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=ABC"), OtpauthUri.REASON_SECRET_INVALID)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DP!"), OtpauthUri.REASON_SECRET_INVALID)
    }

    @Test
    fun `不支持的位数被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=7"), OtpauthUri.REASON_DIGITS_UNSUPPORTED)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=abc"), OtpauthUri.REASON_DIGITS_UNSUPPORTED)
    }

    @Test
    fun `不支持的周期被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&period=45"), OtpauthUri.REASON_PERIOD_UNSUPPORTED)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&period=0"), OtpauthUri.REASON_PERIOD_UNSUPPORTED)
    }

    @Test
    fun `不支持的算法被拒`() {
        assertFailure(
            OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&algorithm=MD5"),
            OtpauthUri.REASON_ALGORITHM_UNSUPPORTED,
        )
    }

    // ───────────────────────── 生成与往返 ─────────────────────────

    @Test
    fun `生成的链接可被自身解析回等价账户`() {
        val account = TotpAccount(
            issuer = "ACME Co",
            accountName = "john.doe@email.com",
            secretBase32 = "JBSWY3DPEHPK3PXP",
            params = TotpParams(algorithm = "SHA256", digits = 8, periodSec = 60),
        )
        val uri = OtpauthUri.build(account)
        assertTrue(uri.startsWith("otpauth://totp/"))
        val parsed = success(OtpauthUri.parse(uri))
        assertEquals(account.issuer, parsed.issuer)
        assertEquals(account.accountName, parsed.accountName)
        assertEquals(account.secretBase32, parsed.secretBase32)
        assertEquals(account.params, parsed.params)
    }

    @Test
    fun `默认参数不写进链接`() {
        val uri = OtpauthUri.build(
            TotpAccount(issuer = "Corp", accountName = "bob", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        assertEquals("otpauth://totp/Corp%3Abob?secret=JBSWY3DPEHPK3PXP&issuer=Corp", uri)
    }

    @Test
    fun `无 issuer 时不生成 issuer 参数`() {
        val uri = OtpauthUri.build(
            TotpAccount(issuer = "  ", accountName = "bob", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        assertEquals("otpauth://totp/bob?secret=JBSWY3DPEHPK3PXP", uri)
    }

    @Test
    fun `空格编码为百分号二十而非加号`() {
        val uri = OtpauthUri.build(
            TotpAccount(issuer = "ACME Co", accountName = "a b", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        assertTrue(uri.contains("ACME%20Co"))
        assertTrue(!uri.contains("+"))
    }

    @Test
    fun `密钥含小写与空格时生成链接仍归一化`() {
        val uri = OtpauthUri.build(
            TotpAccount(issuer = "Corp", accountName = "bob", secretBase32 = "jbsw y3dp ehpk 3pxp"),
        )
        assertTrue(uri.contains("secret=JBSWY3DPEHPK3PXP"))
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private fun success(result: OtpauthParseResult): TotpAccount = when (result) {
        is OtpauthParseResult.Success -> result.account
        is OtpauthParseResult.Failure -> throw AssertionError("期望解析成功，实际失败：" + result.reason)
    }

    private fun assertFailure(result: OtpauthParseResult, expectedReason: String) {
        val failure = result as? OtpauthParseResult.Failure
            ?: throw AssertionError("期望失败（" + expectedReason + "），实际成功")
        assertEquals(expectedReason, failure.reason)
    }
}
