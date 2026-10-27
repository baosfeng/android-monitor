package com.bsfeng.monitor.finance.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 回归（batch2 走查 P0-1「s14a-save 连续点写两笔」）。
 *
 * 场景：保存成功到路由返回之间有 260ms（MainTabContent.FINANCE_BACK_DELAY_MS），表单仍在组合、
 * 「保存」仍可点。两层闸门都必须拒绝第二次：
 * 1. 屏幕层 [acceptSubmit]：提交态一旦置位，补点一律 false；
 * 2. 按钮层 [acceptSubmitClick]：不依赖重组时序，300ms 窗口内的补点直接丢弃。
 */
class AddTransactionSubmitGuardTest {

    @Test
    fun `连点窗口是 300ms`() {
        assertEquals(300L, SUBMIT_DOUBLE_TAP_MS)
    }

    @Test
    fun `300ms 内连点 10 次只放行第一次`() {
        var lastAccepted = -1L
        var writes = 0
        repeat(10) { index ->
            // 1_000ms 起每 30ms 点一次，10 次共 270ms，全部落在同一个 300ms 窗口内
            val now = 1_000L + index * 30L
            if (acceptSubmitClick(now, lastAccepted)) {
                lastAccepted = now
                writes++
            }
        }
        assertEquals("300ms 内连点 10 次只能写 1 笔", 1, writes)
    }

    @Test
    fun `窗口外再点放行`() {
        assertTrue(acceptSubmitClick(nowMillis = 1_000L, lastAcceptedMillis = -1L))
        assertFalse(acceptSubmitClick(nowMillis = 1_150L, lastAcceptedMillis = 1_000L))
        assertTrue(acceptSubmitClick(nowMillis = 1_300L, lastAcceptedMillis = 1_000L))
    }

    @Test
    fun `系统时钟回拨不会把保存按钮锁死`() {
        assertTrue(acceptSubmitClick(nowMillis = 500L, lastAcceptedMillis = 1_000L))
    }

    @Test
    fun `提交中再调用守卫直接拒绝`() {
        assertFalse(acceptSubmit(canSubmit = true, submitting = true))
    }

    @Test
    fun `条件不满足时不放行`() {
        assertFalse(acceptSubmit(canSubmit = false, submitting = false))
    }

    @Test
    fun `条件满足且未提交时连点只放行一次`() {
        var submitting = false
        var writes = 0
        repeat(5) {
            if (acceptSubmit(canSubmit = true, submitting)) {
                submitting = true
                writes++
            }
        }
        assertEquals("提交态置位后的补点必须被拒", 1, writes)
    }
}
