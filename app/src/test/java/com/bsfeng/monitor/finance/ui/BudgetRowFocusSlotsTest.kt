package com.bsfeng.monitor.finance.ui

// P0-1 回归：预算页「第 7 个分类设预算就崩页」。
//
// 崩因（修复前）：BudgetSections.kt 用 remember { List(ROW_TARGET) } 建定长焦点落点列表，
// 再按行下标直取 requesters[index]；而 budgetRowsFor 在「有预算的分类 ≥ ROW_TARGET」时
// **不截断**（支出一级分类默认 18 个），于是第 7 行在组合期抛 IndexOutOfBoundsException。
//
// 这里锁住三件事：
//  1. 行数 ≥ ROW_TARGET 时不截断（这就是崩溃的入口，不能被悄悄改成截断来「绕开」）；
//  2. 每一行的取槽位下标都落在 [0, rows.size) 内（等价于旧实现里的 requesters[index]）；
//  3. 越界下标返回 null 而不是抛异常（双保险：requesterSlotFor 取代裸下标取值）。
//
// 纯 JVM 用例：只碰取行规则与取槽规则，不组合 Compose（组合期崩只能真机/UI 测试复现）。
// 分类名用项目自带默认分类，不写任何真实账目。

import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRowFocusSlotsTest {

    private fun categories(count: Int): List<Category> = (0 until count).map { i ->
        Category(id = (i + 1).toLong(), name = "分类$i", type = 0, parentId = -1)
    }

    private fun budgets(vararg names: String): Map<String, Budget> = names.associateWith { name ->
        Budget(month = "2026-10", categoryName = name, budgetAmount = 100.0, spentAmount = 10.0)
    }

    @Test
    fun `有预算分类达到上限时不截断`() {
        val rows = budgetRowsFor(categories(18), budgets("分类0", "分类1", "分类2", "分类3", "分类4", "分类5"))
        assertEquals("恰好 6 个有预算 → 6 行", ROW_TARGET, rows.size)
    }

    @Test
    fun `第 7 个分类设预算后行数为 7 而不是截断成 6`() {
        val rows = budgetRowsFor(
            categories(18),
            budgets("分类0", "分类1", "分类2", "分类3", "分类4", "分类5", "分类6"),
        )
        assertEquals("这是崩页的入口：行数必须如实是 7", 7, rows.size)
    }

    @Test
    fun `8 行时每一行都能取到焦点槽位且不越界`() {
        // 修复前：requesters 只有 ROW_TARGET=6 个槽，index=6/7 直接 IndexOutOfBoundsException
        val rows = budgetRowsFor(
            categories(18),
            budgets("分类0", "分类1", "分类2", "分类3", "分类4", "分类5", "分类6", "分类7"),
        )
        assertEquals(8, rows.size)
        val slotCount = focusSlotCountFor(rows.size) // 与 BudgetSections 共用的分配口径
        rows.indices.forEach { index ->
            val slot = requesterSlotFor(index, slotCount)
            assertNotNull("第 $index 行必须有槽位", slot)
            assertTrue("槽位下标要落在 [0, $slotCount)", slot!! in 0 until slotCount)
            assertEquals(index, slot)
        }
    }

    @Test
    fun `按旧口径的定长 6 个槽取第 7 行返回 null 而不是抛异常`() {
        // 双保险：即使有人把 requesters 改回定长列表，取槽也只会得到 null（不挂 requester），
        // 不会抛 IndexOutOfBoundsException 把整页组合炸掉
        assertNull(requesterSlotFor(6, ROW_TARGET))
        assertNull(requesterSlotFor(7, ROW_TARGET))
    }

    @Test
    fun `槽位数按实际行数分配而不是按原型固定 6`() {
        // 这一条直接锁住分配口径本身：回退成 List(ROW_TARGET) 时它会红
        assertEquals(7, focusSlotCountFor(7))
        assertEquals(8, focusSlotCountFor(8))
        assertEquals(18, focusSlotCountFor(18))
        assertEquals(ROW_TARGET, focusSlotCountFor(ROW_TARGET))
        // 空列表不建槽（不留 6 个空壳）；负数按 0 兜底，不产生负长度异常
        assertEquals(0, focusSlotCountFor(0))
        assertEquals(0, focusSlotCountFor(-3))
    }

    @Test
    fun `负下标与空列表都安全`() {
        assertNull(requesterSlotFor(-1, ROW_TARGET))
        val rows = budgetRowsFor(emptyList(), emptyMap())
        assertTrue(rows.isEmpty())
        assertNull(requesterSlotFor(0, rows.size))
    }

    @Test
    fun `不足上限时仍补齐到 6 行`() {
        val rows = budgetRowsFor(categories(18), budgets("分类0"))
        assertEquals(ROW_TARGET, rows.size)
        assertEquals("有预算的排在最前", "分类0", rows.first().name)
    }
}
