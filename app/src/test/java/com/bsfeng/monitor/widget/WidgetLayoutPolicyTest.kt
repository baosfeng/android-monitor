package com.bsfeng.monitor.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 尺寸档判定（纯 JVM）。
 *
 * 边界值必须钉死：140 / 280 两个阈值决定「1 格 / 2 格 / 4 格」的内容密度，
 * 差 1dp 就会在真机上把三列指标挤成两列，而组件不会报错、只会看起来「挤成一团」。
 */
class WidgetLayoutPolicyTest {

    @Test
    fun `1 格宽（约 110dp）是 Tiny`() {
        assertEquals(WidgetTier.TINY, tierOf(110f, 110f))
        assertEquals(WidgetTier.TINY, tierOf(139.9f, 110f))
    }

    @Test
    fun `2 格宽（约 170dp）进入 Compact`() {
        assertEquals(WidgetTier.COMPACT, tierOf(140f, 110f))
        assertEquals(WidgetTier.COMPACT, tierOf(170f, 110f))
        assertEquals(WidgetTier.COMPACT, tierOf(279.9f, 170f))
    }

    @Test
    fun `4 格宽（约 350dp）才是 Wide`() {
        assertEquals(WidgetTier.WIDE, tierOf(280f, 170f))
        assertEquals(WidgetTier.WIDE, tierOf(350f, 170f))
        assertEquals(WidgetTier.WIDE, tierOf(500f, 320f))
    }

    @Test
    fun `被压扁到 45dp 以下一律降回 Tiny`() {
        assertEquals(WidgetTier.TINY, tierOf(350f, 40f))
        assertEquals(WidgetTier.TINY, tierOf(170f, 44.9f))
    }

    @Test
    fun `高度不影响主判档`() {
        // 4×1 与 4×2 同为 Wide：高度差异由具体布局决定是否省略进度条
        assertEquals(WidgetTier.WIDE, tierOf(350f, 110f))
        assertEquals(WidgetTier.WIDE, tierOf(350f, 170f))
    }
}