package com.bsfeng.monitor.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FinanceInsertSignal]：自动记账落库的「一次性新行」信号。
 *
 * 高亮只在新增时亮一次 —— 消费方 clear 后切 Tab / 回前台重载都不该再亮，
 * 所以这里把「mark → peek → clear」的语义钉死。
 */
class FinanceInsertSignalTest {

    @Test
    fun `落库标记后可读到新行 id`() {
        FinanceInsertSignal.clear()
        FinanceInsertSignal.mark(42L)
        assertEquals(42L, FinanceInsertSignal.peek())
        assertEquals(42L, FinanceInsertSignal.latestId.value)
    }

    @Test
    fun `消费后清空，不会重复高亮`() {
        FinanceInsertSignal.mark(7L)
        FinanceInsertSignal.clear()
        assertNull(FinanceInsertSignal.peek())
        assertNull(FinanceInsertSignal.latestId.value)
    }

    @Test
    fun `非法 id 不点亮`() {
        FinanceInsertSignal.clear()
        FinanceInsertSignal.mark(0L)
        FinanceInsertSignal.mark(-1L)
        assertNull(FinanceInsertSignal.peek())
    }
}
