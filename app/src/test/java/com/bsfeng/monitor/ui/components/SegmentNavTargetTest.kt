package com.bsfeng.monitor.ui.components

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分段控件键盘导航的纯函数回归（契约 s12-chip-seg「键盘」行）。
 *
 * 看护两条最容易回归的口径：① ← / → 到边界**夹紧不回绕**（改成取模就会从末位跳回首位）；
 * ② 跳首尾用 [Key.MoveHome] / [Key.MoveEnd]（键盘 Home / End），不是 [Key.Home]（安卓系统 Home 键）。
 */
class SegmentNavTargetTest {

    @Test
    fun `左方向键每次左移一格`() {
        assertEquals(2, segmentNavTarget(Key.DirectionLeft, current = 3, count = 5))
        assertEquals(0, segmentNavTarget(Key.DirectionLeft, current = 1, count = 5))
    }

    @Test
    fun `左方向键到首位后夹紧不回绕`() {
        // 回绕实现会返回 count - 1 = 4；契约要求停在 0
        assertEquals(0, segmentNavTarget(Key.DirectionLeft, current = 0, count = 5))
    }

    @Test
    fun `右方向键每次右移一格`() {
        assertEquals(4, segmentNavTarget(Key.DirectionRight, current = 3, count = 5))
        assertEquals(1, segmentNavTarget(Key.DirectionRight, current = 0, count = 5))
    }

    @Test
    fun `右方向键到末位后夹紧不回绕`() {
        // 回绕实现会返回 0；契约要求停在 count - 1 = 4
        assertEquals(4, segmentNavTarget(Key.DirectionRight, current = 4, count = 5))
    }

    @Test
    fun `Home 与 End 跳到首尾`() {
        assertEquals(0, segmentNavTarget(Key.MoveHome, current = 3, count = 5))
        assertEquals(4, segmentNavTarget(Key.MoveEnd, current = 1, count = 5))
    }

    @Test
    fun `系统 Home 键不归本组处理`() {
        // 组内跳首尾只认键盘 Home（MoveHome）；系统 Home（SystemHome）必须交还上层，否则按 Home 会改选中段
        assertNull(segmentNavTarget(Key.SystemHome, current = 2, count = 5))
    }

    @Test
    fun `Tab 与 Esc 等非导航键交还上层`() {
        listOf(Key.Tab, Key.Escape, Key.DirectionUp, Key.DirectionDown).forEach { key ->
            assertNull(key.toString(), segmentNavTarget(key, current = 2, count = 5))
        }
    }

    @Test
    fun `空组不接受任何键盘操作`() {
        listOf(Key.DirectionLeft, Key.DirectionRight, Key.MoveHome, Key.MoveEnd).forEach { key ->
            assertNull(key.toString(), segmentNavTarget(key, current = 0, count = 0))
            assertNull(key.toString(), segmentNavTarget(key, current = 0, count = -3))
        }
    }

    @Test
    fun `单段控件按方向键原地不动`() {
        listOf(Key.DirectionLeft, Key.DirectionRight, Key.MoveHome, Key.MoveEnd).forEach { key ->
            assertEquals(0, segmentNavTarget(key, current = 0, count = 1))
        }
    }

    @Test
    fun `越界当前下标先夹到组内再移动`() {
        // current 来自外部状态，可能暂时越界：不得算出组外下标，也不得抛异常
        assertEquals(0, segmentNavTarget(Key.DirectionLeft, current = -1, count = 5))
        assertEquals(4, segmentNavTarget(Key.DirectionRight, current = 7, count = 5))
        assertEquals(0, segmentNavTarget(Key.MoveHome, current = 9, count = 5))
        assertEquals(4, segmentNavTarget(Key.MoveEnd, current = 9, count = 5))
    }
}
