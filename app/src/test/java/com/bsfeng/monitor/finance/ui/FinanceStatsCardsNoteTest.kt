package com.bsfeng.monitor.finance.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s07 图表卡文案与图例口径的回归（[heatNoteText] / [CATEGORY_LIMIT]）。
 *
 * 都是纯函数 / 常量：无 Android 依赖、无时间随机，可 JVM 直跑。
 */
class FinanceStatsCardsNoteTest {

    private fun hourly(vararg peaks: Int): List<Float> =
        MutableList(HOURS) { 0f }.also { list -> peaks.forEach { list[it] = 3f } }

    @Test
    fun `峰值文案用原型原句 不再说柱高归一`() {
        val note = heatNoteText(hourly(12, 19), listOf(12, 19), null)
        // 原型 s07:182「峰值出现在 12:00（9 笔）与 19:00（8 笔），夜间无消费。」
        assertEquals("峰值出现在 12:00（3 笔）与 19:00（3 笔），夜间无消费。", note)
        assertFalse("热力是色阶不是柱，不能写柱高归一", note.contains("柱高"))
    }

    @Test
    fun `夜间有消费时不给夜间无消费`() {
        val list = hourly(2, 19).toMutableList().also { it[0] = 1f }
        val note = heatNoteText(list, listOf(19, 0), null)
        assertTrue(note.endsWith("。"))
        assertFalse(note.contains("夜间无消费"))
    }

    @Test
    fun `无交易与选中某小时的文案`() {
        assertEquals("本月还没有交易记录，热力图为空。", heatNoteText(List(HOURS) { 0f }, emptyList(), null))
        assertEquals("12:00 · 3 笔", heatNoteText(hourly(12), listOf(12), 12))
    }

    @Test
    fun `图例上限取原型六行`() {
        // 原型 s07:123-130 的图例是 6 行
        assertEquals(6, CATEGORY_LIMIT)
        // s04 分类占比环与 s07 同口径，图例行数（4）必须小于等于上限，否则会出现「环里没有的行」
        assertTrue(FINANCE_RING_LEGEND_ROWS <= CATEGORY_LIMIT)
    }
}
