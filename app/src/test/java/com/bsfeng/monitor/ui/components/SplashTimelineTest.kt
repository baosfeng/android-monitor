package com.bsfeng.monitor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开屏时间轴单测：守住「等比加速」与「不留空等」这两条本次优化的不变量。
 *
 * 看护的是回归风险最高的两处：以后有人只改某个元素的常量会把相对节奏改乱；有人把 HIDDEN_AT
 * 拉回一个大数字又会重新出现静止尾巴。
 */
class SplashTimelineTest {

    private val eps = 1.0f

    @Test
    fun `等比缩放 - 各元素仍保持原型的相对起点`() {
        // 原型起点 / 时长（ms），出处见 SplashTimeline 注释
        assertEquals(60 * SplashTimeline.SPEED, SplashTimeline.RING_DELAY.toFloat(), eps)
        assertEquals(720 * SplashTimeline.SPEED, SplashTimeline.RING_MS.toFloat(), eps)
        assertEquals(300 * SplashTimeline.SPEED, SplashTimeline.BAR_DELAY.toFloat(), eps)
        assertEquals(1100 * SplashTimeline.SPEED, SplashTimeline.BAR_MS.toFloat(), eps)
        assertEquals(420 * SplashTimeline.SPEED, SplashTimeline.BEAD_DELAY.toFloat(), eps)
        assertEquals(520 * SplashTimeline.SPEED, SplashTimeline.WORD_DELAY.toFloat(), eps)
        assertEquals(620 * SplashTimeline.SPEED, SplashTimeline.TAG_DELAY.toFloat(), eps)
        assertEquals(1600 * SplashTimeline.SPEED, SplashTimeline.DONE_AT.toFloat(), eps)
    }

    @Test
    fun `错落入场 - 各元素起点互不相同且保持原型先后`() {
        // 环最早，然后珠 → 字标 → 副标；不能因为缩放被取整压成同一时刻
        val order = listOf(
            SplashTimeline.RING_DELAY,
            SplashTimeline.BEAD_DELAY,
            SplashTimeline.WORD_DELAY,
            SplashTimeline.TAG_DELAY,
        )
        assertEquals(order.sorted(), order)
        assertEquals(order.size, order.toSet().size)
    }

    @Test
    fun `不留空等 - 淡出起点在最后落位之后、且尾巴只有几帧`() {
        // 元素先全部落位，再有短暂的静止呼吸（原型 200ms），然后才开始淡出
        assertTrue(SplashTimeline.DONE_AT > SplashTimeline.LAST_ELEMENT_END_MS)
        val breath = SplashTimeline.DONE_AT - SplashTimeline.LAST_ELEMENT_END_MS
        assertTrue("静止呼吸不该超过 200ms 等比后的量", breath <= 120)

        // 移除时刻 = 淡出结束 + 3 帧，不再有原型那段 60ms 的纯空等
        assertEquals(
            SplashTimeline.DONE_AT + SplashTimeline.DONE_FADE_MS + SplashTimeline.FADE_TAIL_MS,
            SplashTimeline.HIDDEN_AT,
        )
        val tailAfterFade = SplashTimeline.HIDDEN_AT - (SplashTimeline.DONE_AT + SplashTimeline.DONE_FADE_MS)
        assertEquals(SplashTimeline.FADE_TAIL_MS, tailAfterFade)
    }

    @Test
    fun `总时长落在优化目标区间`() {
        // 目标 900ms 左右：太短会压成「闪一下」，太长就白等
        assertTrue("HIDDEN_AT=${SplashTimeline.HIDDEN_AT}", SplashTimeline.HIDDEN_AT in 800..1000)
        // 相对原型的 1900ms 至少省掉 40%
        assertTrue(SplashTimeline.HIDDEN_AT < 1900 * 0.6)
    }

    @Test
    fun `减弱动效不参与缩放`() {
        assertEquals(400, SplashTimeline.REDUCED_DONE_AT)
        assertEquals(700, SplashTimeline.REDUCED_HIDDEN_AT)
        // 减弱动效仍然比完整动效快，二者不会倒挂
        assertTrue(SplashTimeline.REDUCED_HIDDEN_AT < SplashTimeline.HIDDEN_AT)
    }

    @Test
    fun `强度柱三根按原型的延迟差依次长出`() {
        val delays = SplashTimeline.GROW_DELAYS
        assertEquals(3, delays.size)
        // 原型 200 / 100 / 280：第 2 根最早、第 3 根最晚
        assertTrue(delays[1] < delays[0])
        assertTrue(delays[0] < delays[2])
        assertEquals((380 * SplashTimeline.SPEED).toInt(), SplashTimeline.GROW_MS)
    }
}
