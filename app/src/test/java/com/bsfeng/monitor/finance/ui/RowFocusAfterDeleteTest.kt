package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.finance.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-6 回归（「删除后焦点没有落到相邻行」）。
 *
 * 锚点 s05-del-confirm · 移除后：「焦点落在被删行的下一行，若为末行则落在上一行」。
 * 删除发生在编辑页、返回列表后才复位焦点，所以两件事必须先算准：
 * 1. [neighborRowIdAfterDelete] —— 用**删除前**的明细顺序选出目标行（末行回退到上一行，只剩一笔时无相邻行）；
 * 2. [financeMainItemIndexOf] —— 目标行在 LazyColumn 里的项下标（懒加载列表只能滚到下标，
 *    没组合出来的项请求不到焦点）。
 */
class RowFocusAfterDeleteTest {

    private fun tx(id: Long, date: String) = Transaction(id = id, date = date)

    @Test
    fun `删除中间一行焦点落下一行`() {
        assertEquals(3L, neighborRowIdAfterDelete(listOf(1L, 2L, 3L), deletedId = 2L))
    }

    @Test
    fun `删除末行焦点落上一行`() {
        assertEquals(2L, neighborRowIdAfterDelete(listOf(1L, 2L, 3L), deletedId = 3L))
    }

    @Test
    fun `只剩一笔时没有相邻行`() {
        assertNull(neighborRowIdAfterDelete(listOf(7L), deletedId = 7L))
    }

    @Test
    fun `不在明细里的主键不猜落点`() {
        assertNull(neighborRowIdAfterDelete(listOf(1L, 2L), deletedId = 99L))
    }

    @Test
    fun `跨日期分组仍按明细平铺顺序取下一行`() {
        // 明细顺序 = 月度列表顺序（groupBy 保留首次出现次序）：10-03 的两笔在前，10-02 的一笔在后
        assertEquals(20L, neighborRowIdAfterDelete(listOf(10L, 11L, 20L), deletedId = 11L))
    }

    @Test
    fun `目标行的 item 下标含页头与日期头`() {
        val grouped = linkedMapOf(
            "2026-10-03" to listOf(tx(1L, "2026-10-03"), tx(2L, "2026-10-03")),
            "2026-10-02" to listOf(tx(3L, "2026-10-02")),
        )
        // 页头 3 项 → [3]=日期头, [4]=tx-1, [5]=tx-2, [6]=日期头, [7]=tx-3
        assertEquals(4, financeMainItemIndexOf(grouped, 1L))
        assertEquals(5, financeMainItemIndexOf(grouped, 2L))
        assertEquals(7, financeMainItemIndexOf(grouped, 3L))
    }

    @Test
    fun `找不到的目标行返回 null 由调用方兜底`() {
        val grouped = linkedMapOf("2026-10-03" to listOf(tx(1L, "2026-10-03")))
        assertNull(financeMainItemIndexOf(grouped, 999L))
    }

    @Test
    fun `页头 item 数量跟随明细 pane 的页头清单`() {
        // 明细 pane 只有 head / month / listhead 三项（原型 s04:115-175 是纯列表，
        // KPI 卡与双入口卡只属于统计 pane）；下标口径由这份清单直接派生，改动时不会各说各话。
        assertEquals(listOf("head", "month", "listhead"), FINANCE_DETAIL_PANE_ITEMS)
        assertEquals(FINANCE_DETAIL_PANE_ITEMS.size, FINANCE_MAIN_HEADER_ITEMS)
    }
}
