package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重置时刻策略：两档语义 + 默认档 + 「正常行折叠 when」的纯判据。 */
class QuotaResetModeTest {

    @Test
    fun `默认是只显示需处理的行`() {
        assertEquals(QuotaResetMode.URGENT, QuotaResetMode.of(null))
        assertEquals(QuotaResetMode.URGENT, QuotaResetMode.of("junk"))
        assertEquals(QuotaResetMode.URGENT, QuotaResetMode.of("urgent"))
    }

    @Test
    fun `落盘值能原样读回`() {
        assertEquals(QuotaResetMode.ALL, QuotaResetMode.of("all"))
        assertEquals("all", QuotaResetMode.ALL.storageValue)
        assertEquals("urgent", QuotaResetMode.URGENT.storageValue)
    }

    @Test
    fun `每行都显示档不看是否紧急`() {
        assertTrue(quotaShowsResetWhen(QuotaResetMode.ALL, urgent = false))
        assertTrue(quotaShowsResetWhen(QuotaResetMode.ALL, urgent = true))
    }

    @Test
    fun `只显示需处理的行会折叠正常行`() {
        assertFalse(quotaShowsResetWhen(QuotaResetMode.URGENT, urgent = false))
        assertTrue(quotaShowsResetWhen(QuotaResetMode.URGENT, urgent = true))
    }
}
