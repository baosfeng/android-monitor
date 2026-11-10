package com.bsfeng.monitor.totp.ui

// TotpParamEditor 里的纯逻辑（不碰 Compose 组合函数）：
// 常用档下标 / HOTP 判定 / Steam 判定 —— 这些决定「选中态落在哪一段」「要不要显示计数器」，
// 口径错了界面会显示成另一档（例如 7 位码显示成「6 位」而不是「自定义」）。

import com.bsfeng.monitor.domain.totp.TotpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpParamEditorLogicTest {

    // ───────────────────────── digitsSegmentIndex ─────────────────────────

    @Test
    fun `位数落在常用档时指向对应段`() {
        assertEquals(0, digitsSegmentIndex(6))
        assertEquals(1, digitsSegmentIndex(8))
    }

    @Test
    fun `位数不在常用档时指向自定义段`() {
        // 自定义段的下标 = 常用档数量（分段控件最后一段）
        assertEquals(TOTP_DIGITS_PRESETS.size, digitsSegmentIndex(4))
        assertEquals(TOTP_DIGITS_PRESETS.size, digitsSegmentIndex(5))
        assertEquals(TOTP_DIGITS_PRESETS.size, digitsSegmentIndex(7))
        assertEquals(TOTP_DIGITS_PRESETS.size, digitsSegmentIndex(9))
        assertEquals(TOTP_DIGITS_PRESETS.size, digitsSegmentIndex(10))
    }

    @Test
    fun `越界位数同样落到自定义段而不越界`() {
        // 下标必须恒在 0..size-1 之外的那一档（即 <= size），否则 SegmentedControl 会取不到选中项
        listOf(Int.MIN_VALUE, -1, 0, 1, 99, Int.MAX_VALUE).forEach { digits ->
            val index = digitsSegmentIndex(digits)
            assertEquals(TOTP_DIGITS_PRESETS.size.toLong(), index.toLong())
        }
    }

    // ───────────────────────── periodSegmentIndex ─────────────────────────

    @Test
    fun `周期落在常用档时指向对应段`() {
        assertEquals(0, periodSegmentIndex(15))
        assertEquals(1, periodSegmentIndex(30))
        assertEquals(2, periodSegmentIndex(60))
        assertEquals(3, periodSegmentIndex(300))
    }

    @Test
    fun `周期不在常用档时指向自定义段`() {
        listOf(1, 45, 90, 86400).forEach { period ->
            assertEquals(TOTP_PERIOD_PRESETS.size.toLong(), periodSegmentIndex(period).toLong())
        }
        assertEquals(TOTP_PERIOD_PRESETS.size, periodSegmentIndex(0))
        assertEquals(TOTP_PERIOD_PRESETS.size, periodSegmentIndex(-30))
    }

    @Test
    fun `下标永远不超出分段控件范围`() {
        // 分段控件总段数 = 常用档数 + 1（最后一段是「自定义」）；任何输入的返回值都必须落在 0..size-1
        val digitsSegmentCount = TOTP_DIGITS_PRESETS.size + 1
        listOf(Int.MIN_VALUE, -1, 0, 1, 4, 5, 6, 7, 8, 9, 10, 11, Int.MAX_VALUE).forEach { digits ->
            assertTrue("digits=" + digits, digitsSegmentIndex(digits) in 0..digitsSegmentCount - 1)
        }
        val periodSegmentCount = TOTP_PERIOD_PRESETS.size + 1
        listOf(Int.MIN_VALUE, -30, 0, 1, 15, 30, 45, 60, 300, 301, 86_400, Int.MAX_VALUE).forEach { period ->
            assertTrue("period=" + period, periodSegmentIndex(period) in 0..periodSegmentCount - 1)
        }
        // 常用档逐个钉住返回值：落在它自己的下标上
        TOTP_DIGITS_PRESETS.forEachIndexed { index, digits ->
            assertEquals(index, digitsSegmentIndex(digits))
        }
        TOTP_PERIOD_PRESETS.forEachIndexed { index, period ->
            assertEquals(index, periodSegmentIndex(period))
        }
    }

    // ───────────────────────── isHotp ─────────────────────────

    @Test
    fun `HOTP 判据大小写与空白不敏感`() {
        assertTrue(isHotp(TotpParams(type = "HOTP")))
        assertTrue(isHotp(TotpParams(type = "hotp")))
        assertTrue(isHotp(TotpParams(type = " HoTp ")))
    }

    @Test
    fun `TOTP 与未知类型都不算 HOTP`() {
        assertFalse(isHotp(TotpParams()))
        assertFalse(isHotp(TotpParams(type = "TOTP")))
        assertFalse(isHotp(TotpParams(type = "")))
        assertFalse(isHotp(TotpParams(type = "HTOP")))
        assertFalse(isHotp(TotpParams(type = "STEAM")))
    }

    @Test
    fun `isHotp 与 domain 的 isHotpType 同口径`() {
        listOf("HOTP", "hotp", " HoTp ", "TOTP", "", "STEAM", "HTOP").forEach { type ->
            assertEquals(
                "type=" + type,
                TotpParams.isHotpType(type),
                isHotp(TotpParams(type = type)),
            )
        }
    }

    // ───────────────────────── isSteam ─────────────────────────

    @Test
    fun `Steam 变体判据大小写与空白不敏感`() {
        assertTrue(isSteam("STEAM"))
        assertTrue(isSteam("steam"))
        assertTrue(isSteam(" Steam "))
    }

    @Test
    fun `标准变体与未知变体都不算 Steam`() {
        assertFalse(isSteam(""))
        assertFalse(isSteam("STANDARD"))
        assertFalse(isSteam("STEAM1"))
        assertFalse(isSteam("STEAMPUNK"))
    }

    @Test
    fun `Steam 常量与 domain 的变体常量同值`() {
        assertEquals(com.bsfeng.monitor.domain.totp.TotpAccount.VARIANT_STEAM, TotpAccountVariantSteam)
    }

    // ───────────────────────── 与选项清单的自洽 ─────────────────────────

    @Test
    fun `常用档清单与 domain 的范围不冲突`() {
        TOTP_DIGITS_PRESETS.forEach {
            assertTrue("位数档越界：" + it, TotpParams.isSupportedDigits(it))
        }
        TOTP_PERIOD_PRESETS.forEach {
            assertTrue("周期档越界：" + it, TotpParams.isSupportedPeriod(it))
        }
        assertTrue("类型段必须只有 TOTP / HOTP", TOTP_TYPE_OPTIONS == listOf(TotpParams.TYPE_TOTP, TotpParams.TYPE_HOTP))
        assertTrue("自定义段标签不能与任何常用档文案重名", TOTP_CUSTOM_LABEL == "自定义")
        assertTrue("算法段不含 MD5（手动输入不引导）", TOTP_ALGORITHM_OPTIONS.none { it == TotpParams.ALGORITHM_MD5 })
    }

    @Test
    fun `profile 编辑的选中态能由参数反推`() {
        // 详情页把参数直接塞回编辑区：选中态必须能从参数算回来
        assertEquals(0, digitsSegmentIndex(TotpParams().digits))
        assertEquals(1, periodSegmentIndex(TotpParams().periodSec))
        assertFalse(isHotp(TotpParams()))
        assertEquals(
            TOTP_TYPE_OPTIONS.indexOf(TotpParams().type.trim().uppercase()),
            TOTP_TYPE_OPTIONS.indexOf(TotpParams.TYPE_TOTP),
        )
    }
}
