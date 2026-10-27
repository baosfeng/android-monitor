package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「滚到这一条」一次性聚焦请求的消费时机（纯 JVM，无 Robolectric）。
 *
 * 回归的是返工引入的时序 bug（复走查 P1-02）：`onFocusConsumed()` 曾写在两步滚动之前 ——
 * 消费 → `pendingLiveFocusId` 置空 → `focusRecordId` 变 null → `LaunchedEffect` 的 key 变化 →
 * 正在跑的 `animateScrollToItem` 被取消，列表最多动一两帧、目标行进不了可视区。
 * 判据：**滚动没跑完就不得消费信号**。
 */
class LiveFocusConsumeTimingTest {

    @Test
    fun `滚动进行中不得消费聚焦信号`() {
        assertFalse(liveFocusConsumesSignal(LiveFocusStage.SCROLLING))
    }

    @Test
    fun `目标行还没落地时不得消费聚焦信号`() {
        // 首帧数据未到就消费，会把「切 Tab 同时触发加载」这条常见路径的定位彻底丢掉
        assertFalse(liveFocusConsumesSignal(LiveFocusStage.WAITING_DATA))
    }

    @Test
    fun `两步滚动跑完后才允许消费聚焦信号`() {
        assertTrue(liveFocusConsumesSignal(LiveFocusStage.SETTLED))
    }

    @Test
    fun `超时兜底在滚动进行中不得消费`() {
        // R3-P2-01：兜底是第二个消费者，滚动中清空请求 = 把主 effect 的两步滚动取消掉
        assertFalse(liveFocusConsumesSignal(liveFocusTimeoutStage(scrolling = true, targetPresent = true)))
    }

    @Test
    fun `超时兜底在目标刚落地时不得消费`() {
        // 数据晚到：目标已在 groups 里、主 effect 即将开滚，兜底不许抢在它前面清空请求
        assertFalse(liveFocusConsumesSignal(liveFocusTimeoutStage(scrolling = false, targetPresent = true)))
    }

    @Test
    fun `超时兜底在目标始终没出现且无滚动时丢弃请求`() {
        // 兜底的原职责：确认无处可滚，丢掉失效请求，避免每次进实时页都抢一次滚动
        assertTrue(liveFocusConsumesSignal(liveFocusTimeoutStage(scrolling = false, targetPresent = false)))
    }

    @Test
    fun `只有滚动结束这一个阶段允许消费`() {
        // 穷举兜底：以后新增阶段默认必须「不消费」，不许悄悄重新放开这个口子
        assertEquals(
            listOf(LiveFocusStage.SETTLED),
            LiveFocusStage.entries.filter { liveFocusConsumesSignal(it) },
        )
    }
}
