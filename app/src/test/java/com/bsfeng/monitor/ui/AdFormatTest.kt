package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广告跳过页文案格式化的单元测试。
 *
 * 重点锁 [reviewExtras]：它决定「回顾性数据缺项时不出现占位」这条产品判据 ——
 * 真机上「平均耗时 / 累计节省」两格长期为空，原实现留了两张写着「—」的空盒子，
 * 用户点名要改。没有测试钉住，下次改版很容易退回「均耗 —」的形态。
 *
 * 其余三个（[methodSubtitle] / [formatSaved] / [peakNote]）是页面副标题与峰值说明的取数口径，
 * 此前裸着没有保护，随本次一并在同一文件内覆盖。
 */
class AdFormatTest {

    // ── reviewExtras：缺项不出现 / 全缺整行不渲染 ──────────────────────────

    @Test
    fun `三项齐备时按序全出现`() {
        val text = reviewExtras(confirmedCount = 30, avgCost = 128L, savedTotal = 408_000L)
        assertEquals("已确认 30 次 · 均耗 128 ms · 共省 6m48s", text)
    }

    @Test
    fun `缺少耗时与节省时只留已确认`() {
        val text = reviewExtras(confirmedCount = 12, avgCost = null, savedTotal = null)
        assertEquals("已确认 12 次", text)
        assertFalse("缺项不得留占位：$text", text.contains("—"))
        assertFalse("缺项不得留空标签：$text", text.contains("均耗"))
        assertFalse("缺项不得留空标签：$text", text.contains("共省"))
    }

    @Test
    fun `缺中间一项时前后两项仍保留且用中点连接`() {
        val text = reviewExtras(confirmedCount = 7, avgCost = null, savedTotal = 60_000L)
        assertEquals("已确认 7 次 · 共省 1m00s", text)
    }

    @Test
    fun `三项全缺时返回空串由调用方整行不渲染`() {
        assertEquals("", reviewExtras(confirmedCount = 0, avgCost = null, savedTotal = null))
    }

    @Test
    fun `已确认为零不产生已确认零次的噪声`() {
        val text = reviewExtras(confirmedCount = 0, avgCost = 96L, savedTotal = null)
        assertFalse("不该出现 0 次：$text", text.contains("已确认"))
        assertEquals("均耗 96 ms", text)
    }

    @Test
    fun `节省时长负数被夹到零不出现负值`() {
        val text = reviewExtras(confirmedCount = 0, avgCost = null, savedTotal = -5_000L)
        assertEquals("共省 0m00s", text)
        assertFalse("结余类不出现负号：$text", text.contains("-"))
    }

    // ── methodSubtitle：记录行副标题 ────────────────────────────────────

    @Test
    fun `规则命中带耗时`() {
        assertEquals("规则匹配 · 128 ms", methodSubtitle(SkipMethod.RULE, 128L))
    }

    @Test
    fun `坐标兜底与无障碍兜底文案各不相同`() {
        assertEquals("坐标点击兜底 · 342 ms", methodSubtitle(SkipMethod.COORD, 342L))
        assertEquals("无障碍事件兜底 · 512 ms", methodSubtitle(SkipMethod.A11Y, 512L))
    }

    @Test
    fun `耗时缺失写占位而不写 null`() {
        val text = methodSubtitle(SkipMethod.RULE, null)
        assertTrue(text, text.contains("—"))
        assertFalse(text, text.contains("null"))
    }

    // ── formatSaved：累计节省 ───────────────────────────────────────────

    @Test
    fun `节省时长按分秒两位补零`() {
        assertEquals("0m00s", formatSaved(0L))
        assertEquals("0m05s", formatSaved(5_000L))
        assertEquals("1m00s", formatSaved(60_000L))
        assertEquals("6m48s", formatSaved(408_000L))
    }

    @Test
    fun `节省时长丢弃毫秒余数不做四舍五入`() {
        assertEquals("1m00s", formatSaved(60_999L))
    }

    // ── peakNote：趋势卡峰值说明 ────────────────────────────────────────

    @Test
    fun `峰值取最大值的序号一基并报告次数`() {
        assertEquals("峰值 7 日 · 10 次", peakNote(listOf(4f, 4f, 1f, 4f, 7f, 3f, 10f)))
    }

    @Test
    fun `并列最大值取最早出现的那天`() {
        assertEquals("峰值 2 日 · 8 次", peakNote(listOf(3f, 8f, 8f)))
    }

    @Test
    fun `全零与空列表都提示近七天暂无记录`() {
        assertEquals("近 7 天暂无跳过记录", peakNote(listOf(0f, 0f, 0f)))
        assertEquals("近 7 天暂无跳过记录", peakNote(emptyList()))
    }
}
