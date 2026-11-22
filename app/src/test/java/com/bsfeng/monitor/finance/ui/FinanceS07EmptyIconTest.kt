package com.bsfeng.monitor.finance.ui

import org.junit.Assert.assertEquals
import org.junit.Test

//
// s07 空态图标的形态回归（原型 .open-design/src/screens/s07-统计分析.html:195-200）：
// 原型 .st-icon svg = 一条基线 + 三根竖柱，24 视口、stroke 1.7 / fill none（20-base.html:419）。
//
// VectorGroup.children 是私有实现细节，单测摸不到；这里钉住对外的形态口径（视口 / 标识 / 线宽），
// 自绘代码必须用 S07_EMPTY_ICON_STROKE，改线宽就绕不过这条断言。
//
class FinanceS07EmptyIconTest {

    @Test
    fun `空态图标按原型自绘为线描柱图`() {
        assertEquals(24f, S07_EMPTY_ICON.defaultWidth.value, 0.01f)
        assertEquals(24f, S07_EMPTY_ICON.defaultHeight.value, 0.01f)
        assertEquals(24f, S07_EMPTY_ICON.viewportWidth, 0.01f)
        assertEquals("s07_empty_bars", S07_EMPTY_ICON.name)
        assertEquals(1.7f, S07_EMPTY_ICON_STROKE, 0.01f)
    }
}
