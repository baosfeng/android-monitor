package com.bsfeng.monitor.domain.totp

// TotpParams 的降级口径：算法层「越界夹紧后仍按夹紧值出码」，导入层才是「范围外一律拒绝」。
// 两套判据必须分开测，否则很容易把夹紧改回「回落默认值」（那会让 7 位码静默算成 6 位）。

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpParamsTest {

    // ───────────────────────── clampDigits ─────────────────────────

    @Test
    fun `位数在范围内时原值返回`() {
        assertEquals(6, TotpParams.clampDigits(6))
        assertEquals(8, TotpParams.clampDigits(8))
        for (digits in TotpParams.MIN_DIGITS..TotpParams.MAX_DIGITS) {
            assertEquals(digits, TotpParams.clampDigits(digits))
        }
    }

    @Test
    fun `位数越界时夹紧到边界而不是回落默认 6`() {
        assertEquals(TotpParams.MIN_DIGITS, TotpParams.clampDigits(0))
        assertEquals(TotpParams.MIN_DIGITS, TotpParams.clampDigits(-1))
        assertEquals(TotpParams.MIN_DIGITS, TotpParams.clampDigits(Int.MIN_VALUE))
        assertEquals(TotpParams.MAX_DIGITS, TotpParams.clampDigits(11))
        assertEquals(TotpParams.MAX_DIGITS, TotpParams.clampDigits(99))
        assertEquals(TotpParams.MAX_DIGITS, TotpParams.clampDigits(Int.MAX_VALUE))
        // 关键：越界值不能变成 6
        assertTrue("越界不能回落默认位数", TotpParams.clampDigits(11) != TotpParams.DEFAULT_DIGITS)
    }

    // ───────────────────────── clampPeriod ─────────────────────────

    @Test
    fun `周期在范围内时原值返回`() {
        assertEquals(30, TotpParams.clampPeriod(30))
        assertEquals(15, TotpParams.clampPeriod(15))
        assertEquals(45, TotpParams.clampPeriod(45))
        assertEquals(TotpParams.MAX_PERIOD_SEC, TotpParams.clampPeriod(TotpParams.MAX_PERIOD_SEC))
    }

    @Test
    fun `周期越界时夹紧到边界而不是回落默认 30`() {
        assertEquals(TotpParams.MIN_PERIOD_SEC, TotpParams.clampPeriod(0))
        assertEquals(TotpParams.MIN_PERIOD_SEC, TotpParams.clampPeriod(-30))
        assertEquals(TotpParams.MIN_PERIOD_SEC, TotpParams.clampPeriod(Int.MIN_VALUE))
        assertEquals(TotpParams.MAX_PERIOD_SEC, TotpParams.clampPeriod(TotpParams.MAX_PERIOD_SEC + 1))
        assertEquals(TotpParams.MAX_PERIOD_SEC, TotpParams.clampPeriod(Int.MAX_VALUE))
        // 关键：越界值不能变成 30
        assertTrue("越界不能回落默认周期", TotpParams.clampPeriod(0) != TotpParams.DEFAULT_PERIOD_SEC)
    }

    // ───────────────────────── isSupportedDigits / isSupportedPeriod ─────────────────────────

    @Test
    fun `导入准入判据只认闭区间`() {
        assertTrue(TotpParams.isSupportedDigits(1))
        assertTrue(TotpParams.isSupportedDigits(10))
        assertFalse("0 位不合法", TotpParams.isSupportedDigits(0))
        assertFalse("11 位不合法", TotpParams.isSupportedDigits(11))

        assertTrue(TotpParams.isSupportedPeriod(1))
        assertTrue(TotpParams.isSupportedPeriod(86_400))
        assertFalse("0 秒不合法", TotpParams.isSupportedPeriod(0))
        assertFalse("超过上限不合法", TotpParams.isSupportedPeriod(86_401))
    }

    // ───────────────────────── isHotpType ─────────────────────────

    @Test
    fun `type 大小写与前后空白不敏感`() {
        assertTrue(TotpParams.isHotpType("HOTP"))
        assertTrue(TotpParams.isHotpType("hotp"))
        assertTrue(TotpParams.isHotpType(" HoTp "))
        assertFalse(TotpParams.isHotpType("TOTP"))
        assertFalse(TotpParams.isHotpType("totp"))
    }

    @Test
    fun `未知 type 一律按 TOTP 处理`() {
        assertFalse(TotpParams.isHotpType(""))
        assertFalse(TotpParams.isHotpType("   "))
        assertFalse(TotpParams.isHotpType("HTOP"))
        assertFalse(TotpParams.isHotpType("HOTP2"))
        assertFalse("null 由非空 String 类型挡在编译期，这里只覆盖空与未知字面量", TotpParams.isHotpType("steam"))
    }

    // ───────────────────────── 默认值 ─────────────────────────

    @Test
    fun `默认参数与主流网站一致`() {
        val defaults = TotpParams()
        assertEquals(TotpParams.ALGORITHM_SHA1, defaults.algorithm)
        assertEquals(TotpParams.DEFAULT_DIGITS, defaults.digits)
        assertEquals(TotpParams.DEFAULT_PERIOD_SEC, defaults.periodSec)
        assertEquals(TotpParams.TYPE_TOTP, defaults.type)
    }

    // ───────────────────────── 夹紧口径与算法层一致 ─────────────────────────

    @Test
    fun `夹紧后的位数与周期能被算法层直接使用`() {
        val secret = "12345678901234567890".toByteArray(Charsets.US_ASCII)
        // 99 位 → 夹到 10 位：出码长度必须是 10，不是 6
        assertEquals(10, TotpAlgorithm.totp(secret, 59L, TotpParams(digits = 99)).length)
        // 0 位 → 夹到 1 位
        assertEquals(1, TotpAlgorithm.totp(secret, 59L, TotpParams(digits = 0)).length)
        // 0 秒周期 → 夹到 1 秒：与显式写 1 秒等价
        assertEquals(
            TotpAlgorithm.totp(secret, 59L, TotpParams(periodSec = 1)),
            TotpAlgorithm.totp(secret, 59L, TotpParams(periodSec = 0)),
        )
    }
}
