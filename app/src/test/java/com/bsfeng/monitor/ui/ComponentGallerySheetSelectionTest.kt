package com.bsfeng.monitor.ui

// s12 抽屉 chip 的选中口径自测（纯 JVM）。
//
// 契约 docs/交互设计/s12-组件与状态-交互契约.md 行 42 的结果列写「多选：每个 chip 独立切换」，
// 同组「确定」的 toast 却是单数「已选择：前台切换」——两处矛盾，按契约口径
// 「确认文案是单数 → 单选」已裁决为单选。这些断言锁住裁决后的行为：
// 点新 chip 只留 1 个选中、再点已选中的 chip 不取消、「确定」至多回传 1 个类型。

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentGallerySheetSelectionTest {

    @Test
    fun `再点已选中的 chip 保持选中`() {
        assertEquals("充电", sheetSelectedAfter("充电", "充电"))
        assertEquals("前台切换", sheetSelectedAfter("前台切换", "前台切换"))
    }

    @Test
    fun `点新 chip 替换旧选中`() {
        assertEquals("广告跳过", sheetSelectedAfter("前台切换", "广告跳过"))
        assertEquals("亮屏解锁", sheetSelectedAfter(null, "亮屏解锁"))
    }

    @Test
    fun `确定只回传一个类型`() {
        assertEquals(listOf("前台切换"), sheetConfirmPayload("前台切换"))
        assertEquals(1, sheetConfirmPayload(sheetSelectedAfter("前台切换", "充电")).size)
        assertEquals(listOf("充电"), sheetConfirmPayload(sheetSelectedAfter("前台切换", "充电")))
    }

    @Test
    fun `没有可选项时确定禁用并给原因文案`() {
        assertTrue("选中为 null 时「确定」必须禁用（空列表）", sheetConfirmPayload(null).isEmpty())
        assertEquals("请先选择至少 1 个类型", SHEET_NO_SELECTION_HINT)
    }

    @Test
    fun `单选后确认 toast 是单数文案`() {
        assertEquals("已选择：充电", sheetConfirmToast(sheetConfirmPayload("充电")))
        assertEquals("已选择：前台切换", sheetConfirmToast(sheetConfirmPayload(sheetSelectedAfter(null, "前台切换"))))
    }
}
