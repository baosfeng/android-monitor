package com.bsfeng.monitor.totp.ui

// 按账户形态派生码（TOTP / HOTP / Steam 三路分流）+ 形态标签 + 倒计时警示线。
// 三条路径的出码口径本身在 domain 有专测，这里只钉「UI 选了哪条路径」——
// 选错路径会让 HOTP 账户按时间变码、Steam 账户出成数字码。

import com.bsfeng.monitor.domain.totp.Countdown
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpAlgorithm
import com.bsfeng.monitor.domain.totp.TotpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpFormatAccountTest {

    // ───────────────────────── TOTP 路径 ─────────────────────────

    @Test
    fun `标准账户按时间出六位数字码`() {
        val account = standardAccount()
        val code = deriveAccountCode(account, EPOCH)
        assertEquals("626363", code)
        assertEquals(TotpAlgorithm.totp(HELLO_SECRET, EPOCH, account.params), code)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `标准账户按周期换码`() {
        val account = standardAccount()
        // 从窗口起点算：0–29 秒同一窗口，第 30 秒进位换码
        val windowStart = EPOCH - EPOCH % 30
        assertEquals(deriveAccountCode(account, windowStart), deriveAccountCode(account, windowStart + 29))
        assertTrue(deriveAccountCode(account, windowStart) != deriveAccountCode(account, windowStart + 30))
    }

    @Test
    fun `八位账户出八位数字码`() {
        val account = standardAccount().copy(params = TotpParams(digits = 8))
        assertEquals(8, deriveAccountCode(account, EPOCH).length)
    }

    @Test
    fun `七位账户按真值出七位且 UI 不分段`() {
        // 新口径：位数按真实参数出码，7 位码原样显示（不切成 "123 4567" 也不回落成 6 位）
        val account = standardAccount().copy(params = TotpParams(digits = 7))
        val code = deriveAccountCode(account, EPOCH)
        assertEquals(7, code.length)
        assertEquals(code, formatCode(code, 7))
    }

    // ───────────────────────── HOTP 路径 ─────────────────────────

    @Test
    fun `HOTP 账户按计数器出码且不随时间变`() {
        val account = standardAccount().copy(
            params = TotpParams(type = TotpParams.TYPE_HOTP),
            counter = 7L,
        )
        assertEquals("790198", deriveAccountCode(account, EPOCH))
        // 时间窗口翻过去也不该换码（HOTP 只能由计数器推进）
        assertEquals(deriveAccountCode(account, EPOCH), deriveAccountCode(account, EPOCH + 3600))
    }

    @Test
    fun `HOTP 计数器推进后换码`() {
        val account = standardAccount().copy(
            params = TotpParams(type = TotpParams.TYPE_HOTP),
            counter = 7L,
        )
        assertEquals("790198", deriveAccountCode(account, EPOCH))
        assertEquals("085181", deriveAccountCode(account.copy(counter = 8L), EPOCH))
    }

    @Test
    fun `HOTP 与 TOTP 同一时刻不同码`() {
        val totp = deriveAccountCode(standardAccount(), EPOCH)
        val hotp = deriveAccountCode(
            standardAccount().copy(params = TotpParams(type = TotpParams.TYPE_HOTP), counter = EPOCH / 30),
            EPOCH,
        )
        // counter = 同一时间窗口号时两者应一致（证明分流没有改变算法口径）
        assertEquals(totp, hotp)
    }

    // ───────────────────────── Steam 路径 ─────────────────────────

    @Test
    fun `Steam 账户出五位字母码`() {
        val account = TotpAccount(
            issuer = "Steam",
            accountName = "steamuser",
            // Steam 的密钥原文是标准 Base64，Base32 解不开：必须走 SecretCodec
            secretBase32 = STEAM_SECRET_BASE64,
            variant = TotpAccount.VARIANT_STEAM,
            params = TotpParams(digits = 5),
        )
        assertEquals("2JB2B", deriveAccountCode(account, STEAM_EPOCH))
        assertEquals(TotpAlgorithm.steam(STEAM_SECRET, STEAM_EPOCH), deriveAccountCode(account, STEAM_EPOCH))
        assertEquals(5, deriveAccountCode(account, STEAM_EPOCH).length)
        assertTrue("Steam 码是字母表码，不该是纯数字", deriveAccountCode(account, STEAM_EPOCH).any { !it.isDigit() })
    }

    @Test
    fun `Steam 账户按三十秒窗口换码`() {
        val account = TotpAccount(
            issuer = "Steam",
            accountName = "steamuser",
            secretBase32 = STEAM_SECRET_BASE64,
            variant = TotpAccount.VARIANT_STEAM,
        )
        val windowStart = STEAM_EPOCH - STEAM_EPOCH % 30
        assertEquals(deriveAccountCode(account, windowStart), deriveAccountCode(account, windowStart + 29))
        assertTrue(deriveAccountCode(account, windowStart) != deriveAccountCode(account, windowStart + 30))
    }

    @Test
    fun `Steam 变体优先于 HOTP 判定`() {
        // 同时标了 STEAM 且 type=HOTP：Steam 分支先命中（Steam 只有 30 秒字母码这一种形态）
        val account = TotpAccount(
            issuer = "Steam",
            accountName = "steamuser",
            secretBase32 = STEAM_SECRET_BASE64,
            variant = TotpAccount.VARIANT_STEAM,
            params = TotpParams(type = TotpParams.TYPE_HOTP),
        )
        assertEquals(TotpAlgorithm.steam(STEAM_SECRET, STEAM_EPOCH), deriveAccountCode(account, STEAM_EPOCH))
    }

    // ───────────────────────── 异常 / 空密钥 ─────────────────────────

    @Test
    fun `密钥非法或为空时返回空串而不抛异常`() {
        val bad = standardAccount().copy(secretBase32 = "!!!!")
        assertEquals("", deriveAccountCode(bad, EPOCH))
        assertEquals("", deriveAccountCode(standardAccount().copy(secretBase32 = ""), EPOCH))
        assertEquals("", deriveAccountCode(standardAccount().copy(secretBase32 = "   "), EPOCH))
    }

    @Test
    fun `负时间按零处理且不抛异常`() {
        // domain 的夹紧口径：负时间当 0；UI 不额外拦
        assertEquals(deriveAccountCode(standardAccount(), 0L), deriveAccountCode(standardAccount(), -1L))
        assertEquals(6, deriveAccountCode(standardAccount(), -1L).length)
    }

    // ───────────────────────── codeKindLabel ─────────────────────────

    @Test
    fun `形态标签三路各自成句`() {
        assertEquals("TOTP 时间码", codeKindLabel(standardAccount()))
        assertEquals(
            "HOTP 计数器",
            codeKindLabel(standardAccount().copy(params = TotpParams(type = TotpParams.TYPE_HOTP))),
        )
        assertEquals(
            "Steam 字母码",
            codeKindLabel(
                TotpAccount(
                    issuer = "Steam", accountName = "steamuser", secretBase32 = STEAM_SECRET_BASE64,
                    variant = TotpAccount.VARIANT_STEAM,
                ),
            ),
        )
    }

    @Test
    fun `变体字段大小写不影响标签`() {
        assertEquals("Steam 字母码", codeKindLabel(standardAccount().copy(variant = "steam")))
        assertEquals("Steam 字母码", codeKindLabel(standardAccount().copy(variant = " STEAM ")))
        assertEquals("TOTP 时间码", codeKindLabel(standardAccount().copy(variant = "STANDARD")))
        assertEquals("TOTP 时间码", codeKindLabel(standardAccount().copy(variant = "")))
    }

    // ───────────────────────── isCountdownWarning ─────────────────────────

    @Test
    fun `剩余五秒及以内切警告色`() {
        assertTrue(isCountdownWarning(Countdown(5, 0.16f)))
        assertTrue(isCountdownWarning(Countdown(1, 0.03f)))
        assertTrue(TOTP_WARN_SECONDS == 5)
    }

    @Test
    fun `剩余六秒不算警告`() {
        assertFalse(isCountdownWarning(Countdown(6, 0.2f)))
        assertFalse(isCountdownWarning(Countdown(30, 1.0f)))
    }

    @Test
    fun `与真实倒计时串联时只在末尾五秒内告警`() {
        // 30 秒周期：第 0 秒满格不告警，第 25 秒剩 5 秒开始告警
        val fresh = com.bsfeng.monitor.domain.totp.TotpCountdown.of(0L, 30)
        assertFalse(isCountdownWarning(fresh))
        val tail = com.bsfeng.monitor.domain.totp.TotpCountdown.of(25_000L, 30)
        assertEquals(5, tail.remainingSec)
        assertTrue(isCountdownWarning(tail))
        assertTrue(isCountdownWarning(com.bsfeng.monitor.domain.totp.TotpCountdown.of(29_000L, 30)))
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private fun standardAccount(): TotpAccount = TotpAccount(
        issuer = "GitHub",
        accountName = "alice@acme.com",
        secretBase32 = HELLO_SECRET_B32,
    )

    private companion object {
        const val EPOCH = 1_700_000_000L
        const val STEAM_EPOCH = 1_794_321_949L

        /** "hello world 124"；Base32 = NBSWY3DPEB3W64TMMQQDCMRU。 */
        val HELLO_SECRET: ByteArray = "hello world 124".toByteArray(Charsets.UTF_8)
        const val HELLO_SECRET_B32 = "NBSWY3DPEB3W64TMMQQDCMRU"

        /** Steam 的标准 Base64 密钥（20 字节），Base32 解不开。 */
        const val STEAM_SECRET_BASE64 = "cnOgvVunvLFR1gZAwzDxDrgbXWM="
        val STEAM_SECRET: ByteArray = byteArrayOf(
            *"7273a0bd5ba7bcb151d60640c330f10eb81b5d63".chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        )
    }
}
