package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视图模式（聚合 / 单家）↔ 分段下标的两个方向。
 *
 * 这两个换算原先各写一遍（`if (aggregateMode) 0 else 1` 与 `index == 0`），
 * 改了一边不改另一边就会让「点聚合」选中「单家」，而且没有任何用例变红（基建审计 I4）。
 */
class QuotaSegmentTest {

    @Test
    fun `聚合对应下标 0、单家对应下标 1`() {
        assertEquals(0, viewModeIndex(aggregate = true))
        assertEquals(1, viewModeIndex(aggregate = false))
    }

    @Test
    fun `下标回读与正换算互为逆运算`() {
        // onSelect 把下标换回语义时必须回到原值，否则点已选中的段会重复触发 onSelect
        assertTrue(isAggregateIndex(viewModeIndex(true)))
        assertFalse(isAggregateIndex(viewModeIndex(false)))
    }

    @Test
    fun `分段项相等性只看文案与锚点`() {
        val a = QuotaSegment("聚合", Modifier)
        assertEquals(a, QuotaSegment("聚合", Modifier))
        assertNotEquals(a, QuotaSegment("单家", Modifier))
    }
}
