package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.ui.components.FiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N5 / N6 回归（batch2 复走查：错误态既进不去也退不出、接上真实失败后把缓存值整块吞掉）。
 *
 * 两条判定都是「错误态跟缓存怎么共存」的问题，抽成纯函数以便 JVM 上锁住行为：
 * - [addTransactionState]（s05-error / s05-retry）：读库失败但两表都空 → 错态卡（可重试）；
 *   有缓存 → 照常出表单（缓存能读能存，封锁表单才是真丢数据）；
 * - [financeErrorAsCard]（s06-error / s07-error）：只有「无缓存」才允许整块替换列表区，
 *   有缓存时错误只能贴一条在列表首位，下方数值与图表仍可读。
 */
class FinanceErrorStateRulesTest {

    @Test
    fun `记一笔读库失败且无缓存时进错态卡`() {
        assertEquals(
            FiveState.ERROR,
            addTransactionState(loading = false, loadFailed = true, categoryCount = 0, accountCount = 0),
        )
    }

    @Test
    fun `记一笔读库失败但有缓存时照常出表单`() {
        assertEquals(
            FiveState.POPULATED,
            addTransactionState(loading = false, loadFailed = true, categoryCount = 6, accountCount = 2),
        )
    }

    @Test
    fun `记一笔加载中且无数据才出骨架`() {
        assertEquals(
            FiveState.LOADING,
            addTransactionState(loading = true, loadFailed = false, categoryCount = 0, accountCount = 0),
        )
        assertEquals(
            FiveState.POPULATED,
            addTransactionState(loading = true, loadFailed = false, categoryCount = 6, accountCount = 2),
        )
    }

    @Test
    fun `记一笔三清时是空态而不是错态`() {
        assertEquals(
            FiveState.EMPTY,
            addTransactionState(loading = false, loadFailed = false, categoryCount = 0, accountCount = 0),
        )
    }

    @Test
    fun `无缓存时错误才整块占列表区`() {
        assertTrue(financeErrorAsCard(FiveState.ERROR, hasCache = false))
    }

    @Test
    fun `有缓存时错误只贴条不吞数值`() {
        assertFalse(financeErrorAsCard(FiveState.ERROR, hasCache = true))
    }

    @Test
    fun `非错误态不受缓存影响`() {
        for (state in listOf(FiveState.POPULATED, FiveState.EMPTY, FiveState.LOADING, FiveState.EDGE)) {
            assertFalse(financeErrorAsCard(state, hasCache = false))
            assertFalse(financeErrorAsCard(state, hasCache = true))
        }
    }
}
