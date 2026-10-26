package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「桌面小组件」设置卡的结果判定文案单元测试。
 *
 * 这里只有三句话，但它们是小米 / 红米上「到底加没加上」的唯一反馈：说错会把「没加上」
 * 说成「已添加」，用户回到桌面找不到组件也定位不到原因，因此把文案与判定固定住。
 */
class SettingsWidgetSectionTest {

    @Test
    fun `数量增加时提示已添加`() {
        assertEquals("已添加到桌面", PIN_ADDED_TOAST)
        assertEquals(PIN_ADDED_TOAST, pinVerdictToast(added = true))
    }

    @Test
    fun `数量没变时回落到手动添加路径`() {
        assertEquals("请在桌面长按 → 小组件 里手动添加", PIN_MANUAL_TOAST)
        assertEquals(PIN_MANUAL_TOAST, pinVerdictToast(added = false))
    }

    @Test
    fun `不支持一键添加时给出长按桌面的手动路径`() {
        assertTrue(PIN_UNSUPPORTED_TOAST, PIN_UNSUPPORTED_TOAST.contains("不支持一键添加"))
        assertTrue(PIN_UNSUPPORTED_TOAST, PIN_UNSUPPORTED_TOAST.contains("长按桌面"))
    }

    @Test
    fun `成功与失败必须是两句不同的话`() {
        // 两句一致等于把判定抹平：用户无法从提示看出系统到底有没有把组件放上去
        assertNotEquals(PIN_ADDED_TOAST, PIN_MANUAL_TOAST)
        assertNotEquals(PIN_UNSUPPORTED_TOAST, PIN_MANUAL_TOAST)
    }
}
