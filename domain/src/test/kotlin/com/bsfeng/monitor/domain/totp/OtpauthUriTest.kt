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
    fun `hotp 链接保留类型与计数器`() {
        val account = success(OtpauthUri.parse("otpauth://hotp/alice?secret=JBSWY3DPEHPK3PXP&counter=7"))
        assertEquals("alice", account.accountName)
        assertEquals(TotpParams.TYPE_HOTP, account.params.type)
        assertEquals(7L, account.counter)
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
    fun `超过长度上限的链接被拒`() {
        val prefix = "otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&pad="
        val uri = prefix + "y".repeat(OtpauthUri.MAX_URI_LENGTH)
        assertTrue(uri.length > OtpauthUri.MAX_URI_LENGTH)
        assertFailure(OtpauthUri.parse(uri), OtpauthUri.REASON_TOO_LONG)
    }

    @Test
    fun `恰好等于长度上限仍按正常链接解析`() {
        val prefix = "otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&pad="
        val uri = prefix + "y".repeat(OtpauthUri.MAX_URI_LENGTH - prefix.length)
        assertEquals(OtpauthUri.MAX_URI_LENGTH, uri.length)
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
        assertFailure(OtpauthUri.parse("otpauth://totp/?secret=JBSWY3DPEHPK3PXP&issuer="), OtpauthUri.REASON_LABEL_MISSING)
    }

    @Test
    fun `缺少密钥被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?issuer=b"), OtpauthUri.REASON_SECRET_MISSING)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret="), OtpauthUri.REASON_SECRET_INVALID)
    }

    @Test
    fun `非法 Base32 密钥被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DP!"), OtpauthUri.REASON_SECRET_INVALID)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSW*Y3DP"), OtpauthUri.REASON_SECRET_INVALID)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=%C3%A9%C3%A9"), OtpauthUri.REASON_SECRET_INVALID)
    }

    @Test
    fun `不支持的位数被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=11"), OtpauthUri.REASON_DIGITS_UNSUPPORTED)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=0"), OtpauthUri.REASON_DIGITS_UNSUPPORTED)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=abc"), OtpauthUri.REASON_DIGITS_UNSUPPORTED)
    }

    @Test
    fun `不支持的周期被拒`() {
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&period=86401"), OtpauthUri.REASON_PERIOD_UNSUPPORTED)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&period=0"), OtpauthUri.REASON_PERIOD_UNSUPPORTED)
    }

    @Test
    fun `不支持的算法被拒`() {
        assertFailure(
            OtpauthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&algorithm=SHA384"),
            OtpauthUri.REASON_ALGORITHM_UNSUPPORTED,
        )
    }

    // ───────────────────────── Steam：两种真实导出形态 ─────────────────────────

    /** Steam Guard 的 base64 密钥（真实样本，长度余数过不了严格 Base32）。 */
    private companion object {
        const val STEAM_SECRET = "cnOgvVunvLFR1gZAwzDxDrgbXWM="
    }

    @Test
    fun `steam host 被认成 Steam 变体且固定五位`() {
        val account = success(OtpauthUri.parse("otpauth://steam/alice?secret=$STEAM_SECRET&period=30"))
        assertEquals("alice", account.accountName)
        assertEquals(TotpAccount.VARIANT_STEAM, account.variant)
        assertEquals(5, account.params.digits)
        assertEquals(TotpParams.TYPE_TOTP, account.params.type)
        assertTrue(SecretCodec.decodeToBytes(account.secretBase32)!!.isNotEmpty())
    }

    @Test
    fun `steam host 大小写不敏感`() {
        // scheme 是 otpauth://，能大写的只有 host 那一段：大写 STEAM 必须与 steam 同义
        val account = success(OtpauthUri.parse("OTPAUTH://STEAM/alice?secret=$STEAM_SECRET"))
        assertEquals(TotpAccount.VARIANT_STEAM, account.variant)
        assertEquals(5, account.params.digits)
    }

    @Test
    fun `steam host 即使显式给别的位数也按五位入库`() {
        val account = success(OtpauthUri.parse("otpauth://steam/alice?secret=$STEAM_SECRET&digits=6"))
        assertEquals(5, account.params.digits)
    }

    @Test
    fun `标准 totp 形态的 Steam 链接位数也归一成五位`() {
        val account = success(
            OtpauthUri.parse("otpauth://totp/Steam:alice?secret=$STEAM_SECRET&issuer=Steam"),
        )
        assertEquals(TotpAccount.VARIANT_STEAM, account.variant)
        assertEquals(5, account.params.digits)
        // 显式 digits=6 也收敛成 5：Steam 码长由字母表决定
        assertEquals(5, success(OtpauthUri.parse("otpauth://totp/Steam:alice?secret=$STEAM_SECRET&issuer=Steam&digits=6")).params.digits)
    }

    @Test
    fun `Steam 变体解析结果的位数恒为五位`() {
        // 不变量：variant == STEAM ⟹ params.digits == 5，任意入口都必须收敛
        val inputs = listOf(
            "otpauth://steam/alice?secret=$STEAM_SECRET&digits=8",
            "OTPAUTH://STEAM/alice?secret=$STEAM_SECRET",
            "otpauth://totp/Steam:alice?secret=$STEAM_SECRET&issuer=Steam",
            "otpauth://totp/Steam:alice?secret=$STEAM_SECRET&issuer=Steam&digits=6",
            "otpauth://totp/steam:alice?secret=$STEAM_SECRET&digits=10",
        )
        for (input in inputs) {
            val account = success(OtpauthUri.parse(input))
            assertEquals(input, TotpAccount.VARIANT_STEAM, account.variant)
            assertEquals(input, 5, account.params.digits)
        }
        // 反例：只有 digits=5 而 issuer/label 都不含 steam → 仍是 STANDARD，位数保持 5
        val valve = success(OtpauthUri.parse("otpauth://totp/Valve:alice?secret=$STEAM_SECRET&digits=5"))
        assertEquals(TotpAccount.VARIANT_STANDARD, valve.variant)
        assertEquals(5, valve.params.digits)
    }

    @Test
    fun `build 只产标准 totp 形态且往返后仍是 Steam 变体`() {
        val account = TotpAccount(
            issuer = "Valve",
            accountName = "alice",
            secretBase32 = STEAM_SECRET,
            params = TotpParams(digits = 5),
            variant = TotpAccount.VARIANT_STEAM,
        )
        val uri = OtpauthUri.build(account)
        assertTrue(uri, uri.startsWith("otpauth://totp/"))
        assertTrue(uri, uri.contains("digits=5"))
        val parsed = success(OtpauthUri.parse(uri))
        assertEquals(TotpAccount.VARIANT_STEAM, parsed.variant)
        assertEquals(5, parsed.params.digits)
        assertEquals("alice", parsed.accountName)
    }

    // ───────────────────────── Google 迁移串：data 里的 '+' 不能被当空格 ─────────────────────────

    /** payload hex → 标准 Base64 / URL-safe / 手抄换行，覆盖 data 参数的全部真实形态。 */
    private val migrationPayload = "ChUKB/s+Pj4+Pj4SAVgaAVggASgBMAIQAQ=="
    private val migrationPayloadUrlSafe = "ChUKB_s-Pj4-Pj4SAVgaAVggASgBMAIQAQ=="

    @Test
    fun `迁移串里字面加号仍能解析`() {
        val batch = OtpauthMigration.parse("otpauth-migration://offline?data=" + migrationPayload)
        assertEquals(1, batch.accounts.size)
        assertEquals(emptyList<String>(), batch.skipped)
        assertEquals("X", batch.accounts[0].accountName)
        assertEquals("X", batch.accounts[0].issuer)
        assertEquals(TotpParams.TYPE_TOTP, batch.accounts[0].params.type)
    }

    @Test
    fun `迁移串里的加号被百分号编码成 %2B 同样能解析`() {
        val batch = OtpauthMigration.parse("otpauth-migration://offline?data=" + migrationPayload.replace("+", "%2B"))
        assertEquals(1, batch.accounts.size)
        assertEquals(emptyList<String>(), batch.skipped)
    }

    @Test
    fun `迁移串的 URL-safe 字母表同样能解析`() {
        val batch = OtpauthMigration.parse("otpauth-migration://offline?data=" + migrationPayloadUrlSafe)
        assertEquals(1, batch.accounts.size)
        assertEquals(emptyList<String>(), batch.skipped)
        assertEquals(migrationPayload.let { OtpauthMigration.parse("otpauth-migration://offline?data=" + it) }.accounts[0].secretBase32, batch.accounts[0].secretBase32)
    }

    @Test
    fun `迁移串带换行的手抄形态仍能解析`() {
        val wrapped = migrationPayload.chunked(20).joinToString("\n")
        val batch = OtpauthMigration.parse("otpauth-migration://offline?data=" + wrapped)
        assertEquals(1, batch.accounts.size)
        assertEquals(emptyList<String>(), batch.skipped)
    }

    // ───────────────────────── Base64 回落的可信门槛（手抄错的 Base32 不得被收下） ─────────────────────────

    @Test
    fun `手抄错的 Base32 不得被当成 base64 收下`() {
        // 回归：Base64 字母表几乎覆盖 [A-Za-z0-9]，回落若无门槛，"解不开" 就不再等于 "写错了"
        assertFailure(OtpauthUri.parse("otpauth://totp/C:a?secret=ABC"), OtpauthUri.REASON_SECRET_INVALID)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=JBSW1Y3P"), OtpauthUri.REASON_SECRET_INVALID)
        assertFailure(OtpauthUri.parse("otpauth://totp/a?secret=ABCDEF"), OtpauthUri.REASON_SECRET_INVALID)
        assertEquals(
            SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID),
            SecretCodec.decodeOrNull("JBSW1Y3P"),
        )
    }

    @Test
    fun `纯 Base32 字母表但长度非法时直接拒绝不回落`() {
        // 纯 [A-Z2-7] 且大小写统一：只可能是手抄错的 Base32，不是 base64 密钥
        for (raw in listOf("A", "ABC", "ABCDEF", "ABCDEFG")) {
            assertEquals(raw, SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID), SecretCodec.decodeOrNull(raw))
        }
    }

    @Test
    fun `Steam 的 base64 密钥仍走回落分支`() {
        val decoded = SecretCodec.decodeOrNull(STEAM_SECRET) as? SecretDecoded.Ok
            ?: throw AssertionError("Steam 密钥必须仍能解码")
        assertEquals(SECRET_KIND_BASE64, decoded.kind)
        assertEquals(20, decoded.bytes.size)
        val account = success(OtpauthUri.parse("otpauth://steam/alice?secret=$STEAM_SECRET"))
        assertEquals(Base32.encode(decoded.bytes), account.secretBase32)
    }

    @Test
    fun `规范 Base32 仍走 Base32 分支不被回落干扰`() {
        val decoded = SecretCodec.decodeOrNull("JBSWY3DPEHPK3PXP") as? SecretDecoded.Ok
            ?: throw AssertionError("规范 Base32 必须可解")
        assertEquals(SECRET_KIND_BASE32, decoded.kind)
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