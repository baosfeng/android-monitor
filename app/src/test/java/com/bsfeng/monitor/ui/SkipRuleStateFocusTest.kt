package com.bsfeng.monitor.ui

import com.bsfeng.monitor.ui.components.FiveState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s10 五态来源与一次性焦点回位的判据（第 3 批走查 P1-2 / P1-3 / P1-7 的回归）。
 *
 * 这里只测纯函数：Compose 的焦点请求本身无法在 JVM 单测里组合，但「什么时候该请求、落到哪个控件、
 * 什么时候作废」这段判断是走查返工最容易写错的部分（空态不进、错态抢焦点、按钮禁用时空消费信号）。
 */
class SkipRuleStateFocusTest {

    @Test
    fun `规则库为空时进空态`() {
        assertEquals(
            FiveState.EMPTY,
            skipRuleEffectiveState(demoState = FiveState.POPULATED, hasError = false, ruleCount = 0),
        )
    }

    @Test
    fun `有订阅源但规则为 0 时仍是空态`() {
        // 契约 s10-st-empty · 边界：有订阅源但规则为 0 时用同一文案，此时计数卡仍显示 0。
        // 因此判据只看规则数，不看订阅源数。
        assertEquals(
            FiveState.EMPTY,
            skipRuleEffectiveState(demoState = FiveState.POPULATED, hasError = false, ruleCount = 0),
        )
    }

    @Test
    fun `有规则时是有数据态`() {
        assertEquals(
            FiveState.POPULATED,
            skipRuleEffectiveState(demoState = FiveState.POPULATED, hasError = false, ruleCount = 42),
        )
    }

    @Test
    fun `真实错误优先于空态`() {
        assertEquals(
            FiveState.ERROR,
            skipRuleEffectiveState(demoState = FiveState.POPULATED, hasError = true, ruleCount = 0),
        )
    }

    @Test
    fun `演示态覆盖真实数据态`() {
        listOf(FiveState.LOADING, FiveState.EMPTY, FiveState.ERROR, FiveState.EDGE).forEach { demo ->
            assertEquals(
                demo,
                skipRuleEffectiveState(demoState = demo, hasError = false, ruleCount = 42),
            )
        }
    }

    @Test
    fun `确认浮层打开时不回焦`() {
        assertEquals(
            UpdateFocusGate.WAIT,
            updateButtonFocusGate(dialogOpen = true, updating = false, state = FiveState.POPULATED),
        )
    }

    @Test
    fun `取消确认后焦点回更新订阅`() {
        assertEquals(
            UpdateFocusGate.REQUEST,
            updateButtonFocusGate(dialogOpen = false, updating = false, state = FiveState.POPULATED),
        )
    }

    @Test
    fun `更新中按钮禁用时等更新结束再回焦`() {
        // AppButton(enabled = false) 不可聚焦：提前请求只会白白消费一次信号
        assertEquals(
            UpdateFocusGate.WAIT,
            updateButtonFocusGate(dialogOpen = false, updating = true, state = FiveState.POPULATED),
        )
    }

    @Test
    fun `更新失败落错态时作废旧回焦`() {
        assertEquals(
            UpdateFocusGate.DROP,
            updateButtonFocusGate(dialogOpen = false, updating = false, state = FiveState.ERROR),
        )
    }

    @Test
    fun `空态也渲染订阅源卡因此可以回焦更新订阅`() {
        // 空态（规则为 0）复用同一张订阅源卡：按钮真实存在，不能把信号空等成永久挂起
        assertEquals(
            UpdateFocusGate.REQUEST,
            updateButtonFocusGate(dialogOpen = false, updating = false, state = FiveState.EMPTY),
        )
    }

    @Test
    fun `边界态没有订阅源卡时作废回焦`() {
        assertEquals(
            UpdateFocusGate.DROP,
            updateButtonFocusGate(dialogOpen = false, updating = false, state = FiveState.EDGE),
        )
    }

    @Test
    fun `重试后仍失败焦点回重试按钮`() {
        assertEquals(RetryFocus.RETRY_BUTTON, retryFocusTarget(state = FiveState.ERROR, updating = false))
    }

    @Test
    fun `重试成功恢复有数据态焦点回更新订阅`() {
        assertEquals(RetryFocus.UPDATE_BUTTON, retryFocusTarget(state = FiveState.POPULATED, updating = false))
    }

    @Test
    fun `重试后更新中等待结果落定`() {
        assertEquals(RetryFocus.WAIT, retryFocusTarget(state = FiveState.POPULATED, updating = true))
        assertEquals(RetryFocus.WAIT, retryFocusTarget(state = FiveState.LOADING, updating = false))
    }

    @Test
    fun `重试后落空态焦点也回更新订阅`() {
        assertEquals(RetryFocus.UPDATE_BUTTON, retryFocusTarget(state = FiveState.EMPTY, updating = false))
        assertEquals(RetryFocus.WAIT, retryFocusTarget(state = FiveState.EMPTY, updating = true))
    }

    @Test
    fun `重试后落边界态时作废回焦`() {
        assertEquals(RetryFocus.DROP, retryFocusTarget(state = FiveState.EDGE, updating = false))
    }
}
