package com.bsfeng.monitor.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三个组件自有的派生文案（[WidgetFormat] 之外的、按档位拼出来的那一层）。
 *
 * 为什么单独钉一遍：桌面上「已用 100%」和「已超支」看起来几乎一样，
 * 而「确认率 0%」还是「—」直接决定用户要不要去调跳过规则 —— 这两类错误肉眼复核不出来，
 * 只能靠纯函数测试。
 */
class WidgetTextTest {

    // ===== 记账：进度条右侧 =====

    @Test
    fun `预算未满额报已用百分比`() {
        assertEquals("已用 71%", usedText(0.71f))
        assertEquals("已用 0%", usedText(0f))
    }

    @Test
    fun `预算满额与超支都改口说已超支`() {
        // percent(1.4) 会被夹成 100，再报「已用 100%」就分不出是刚好花完还是超了
        assertEquals("已超支", usedText(1f))
        assertEquals("已超支", usedText(1.4f))
    }

    // ===== 记账：预算剩余列 =====

    @Test
    fun `预算剩余优先给金额`() {
        assertEquals("¥1,713.50", remainingText(progress = 0.71f, remaining = 1713.5, hasBudget = true, hidden = false))
    }

    @Test
    fun `隐藏金额时预算剩余退回百分比`() {
        assertEquals("29%", remainingText(progress = 0.71f, remaining = 1713.5, hasBudget = true, hidden = true))
    }

    @Test
    fun `没有预算说未设预算_超支说已超支`() {
        assertEquals("未设预算", remainingText(progress = null, remaining = null, hasBudget = false, hidden = false))
        assertEquals("已超支", remainingText(progress = 1.2f, remaining = 0.0, hasBudget = true, hidden = false))
    }

    // ===== 广告 =====

    @Test
    fun `确认率四舍五入取整`() {
        assertEquals("98%", confirmRateText(1257, 1284))
        assertEquals("50%", confirmRateText(1, 2))
        assertEquals("100%", confirmRateText(5, 5))
    }

    @Test
    fun `没有样本时确认率是破折号`() {
        assertEquals("—", confirmRateText(0, 0))
    }
}