package com.bsfeng.monitor.totp.ui

// TotpFormat 的纯逻辑用例：分段显示 / 平台首字 / 取色下标 / 校验文案 / 边界与异常输入。
// 覆盖正常、边界、异常三路径；不依赖 Compose、不读时钟。

import com.bsfeng.monitor.domain.totp.TotpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpFormatTest {

    // ---------- formatCode ----------

    @Test
    fun formatCode_sixDigits_splitsIntoThreePlusThree() {
        assertEquals("123 456", formatCode("123456", 6))
    }

    @Test
    fun formatCode_eightDigits_splitsIntoFourPlusFour() {
        assertEquals("1234 5678", formatCode("12345678", 8))
    }

    @Test
    fun formatCode_empty_returnsEmpty() {
        assertEquals("", formatCode("", 6))
    }

    @Test
    fun formatCode_singleDigit_isNotSplit() {
        assertEquals("7", formatCode("7", 6))
    }

    @Test
    fun formatCode_lengthMismatch_fallsBackToHalfSplit() {
        // 声明 8 位但只给了 6 位：按对半切，不抛异常
        assertEquals("123 456", formatCode("123456", 8))
    }

    // ---------- iconInitial ----------

    @Test
    fun iconInitial_latin_isUppercased() {
        assertEquals("G", iconInitial("github"))
    }

    @Test
    fun iconInitial_cjk_keepsFirstChar() {
        assertEquals("腾", iconInitial("腾讯云"))
    }

    @Test
    fun iconInitial_blank_fallsBackToQuestionMark() {
        assertEquals("？", iconInitial("   "))
    }

    // ---------- issuerColorIndex ----------

    @Test
    fun issuerColorIndex_isStableAndInChartRange() {
        repeat(50) { i ->
            val index = issuerColorIndex("平台 $i")
            assertEquals(index, issuerColorIndex("平台 $i"))
            assertTrue("index 越界: $index", index in 0..15)
        }
    }

    @Test
    fun issuerColorIndex_differentIssuersDifferSomewhere() {
        assertNotEquals(issuerColorIndex("GitHub"), issuerColorIndex("腾讯云"))
    }

    @Test
    fun issuerColorIndex_isCaseAndSpaceInsensitive() {
        assertEquals(issuerColorIndex("GitHub"), issuerColorIndex("  github "))
    }

    // ---------- validateManualEntry ----------

    @Test
    fun validateManualEntry_validInput_returnsNull() {
        assertNull(validateManualEntry("GitHub", "JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun validateManualEntry_validWithSpaces_returnsNull() {
        assertNull(validateManualEntry("GitHub", "JBSWY 3DPE HPK3 PXP"))
    }

    @Test
    fun validateManualEntry_blankIssuer_reportsIssuer() {
        assertEquals("请填写平台名", validateManualEntry("  ", "JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun validateManualEntry_blankSecret_reportsSecret() {
        assertEquals("请填写密钥", validateManualEntry("GitHub", "   "))
    }

    @Test
    fun validateManualEntry_badBase32_reportsFormat() {
        val message = validateManualEntry("GitHub", "!!!!")
        assertTrue(message != null && message.contains("密钥格式不对"))
    }

    // ---------- validateBackupPassword ----------

    @Test
    fun validateBackupPassword_short_isRejected() {
        assertEquals("备份密码至少 8 位", validateBackupPassword("1234567"))
    }

    @Test
    fun validateBackupPassword_eight_isAccepted() {
        assertNull(validateBackupPassword("12345678"))
    }

    // ---------- deriveCode ----------

    @Test
    fun deriveCode_invalidSecret_returnsEmptyInsteadOfThrowing() {
        assertEquals("", deriveCode("!!!!", 0L, TotpParams()))
    }

    @Test
    fun deriveCode_sameSecond_isDeterministicAndSixDigits() {
        val a = deriveCode("JBSWY3DPEHPK3PXP", 1_700_000_000L, TotpParams())
        val b = deriveCode("JBSWY3DPEHPK3PXP", 1_700_000_000L, TotpParams())
        assertEquals(a, b)
        assertEquals(6, a.length)
        assertTrue(a.all { it.isDigit() })
    }

    @Test
    fun deriveCode_eightDigitParams_producesEightDigits() {
        val code = deriveCode("JBSWY3DPEHPK3PXP", 1_700_000_000L, TotpParams(digits = 8))
        assertEquals(8, code.length)
    }
}
