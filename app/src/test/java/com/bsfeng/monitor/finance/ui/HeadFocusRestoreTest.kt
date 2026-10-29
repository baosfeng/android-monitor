package com.bsfeng.monitor.finance.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N1 回归（「s04-head 长按两次标题直接崩」）。
 *
 * 现场：页头的 focusRequester 只在演示件可见时才挂到节点上（FinanceMainScreen 的 head item），
 * 但「五态条收起 → 请求页头焦点」原来是无条件执行的。正式包 / 未解锁 debug 包上，用户在
 * 「记账」标题上长按两次（展开再收起）就会对没挂载的请求器 requestFocus()，抛
 * IllegalStateException: FocusRequester is not initialized.
 *
 * 修法：先判「落点是否挂载」（[shouldRestoreHeadFocus]）+ 长按入口本身只在演示件可见时接线。
 * 这里用与 composable 完全相同的推演（toggle → 判定 → 记 barWasShown）跑两轮长按，断言
 * 正式包上一次请求都不发，debug 包上收起那一次恰好发一次。
 */
class HeadFocusRestoreTest {

    /** 与 FinanceMainScreen 的 LaunchedEffect(control.barShown) 同构：返回本轮是否发出焦点请求。 */
    private class LongPressHarness(private val headFocusAttached: Boolean) {
        var barShown = false
        var barWasShown = false
        var focusRequests = 0

        fun longPress() {
            barShown = !barShown
            val restore = shouldRestoreHeadFocus(barWasShown, barShown, headFocusAttached)
            barWasShown = barShown
            if (restore) focusRequests++
        }
    }

    @Test
    fun `正式包长按两次不请求焦点`() {
        val harness = LongPressHarness(headFocusAttached = false)
        harness.longPress()
        harness.longPress()
        assertEquals(
            "落点未挂载时一次请求都不能发：发了就是 IllegalStateException",
            0,
            harness.focusRequests,
        )
    }

    @Test
    fun `debug 包长按两次只在收起那一次回焦标题`() {
        val harness = LongPressHarness(headFocusAttached = true)
        harness.longPress()
        assertEquals("展开切换条时不该抢焦点", 0, harness.focusRequests)
        harness.longPress()
        assertEquals("收起切换条后焦点回标题", 1, harness.focusRequests)
    }

    @Test
    fun `落点未挂载时即使收起也不请求`() {
        assertFalse(shouldRestoreHeadFocus(barWasShown = true, barShown = false, headFocusAttached = false))
        assertTrue(shouldRestoreHeadFocus(barWasShown = true, barShown = false, headFocusAttached = true))
    }

    @Test
    fun `不是收起动作时不请求`() {
        // 展开
        assertFalse(shouldRestoreHeadFocus(barWasShown = false, barShown = true, headFocusAttached = true))
        // 一直展开着
        assertFalse(shouldRestoreHeadFocus(barWasShown = true, barShown = true, headFocusAttached = true))
        // 一直收起着
        assertFalse(shouldRestoreHeadFocus(barWasShown = false, barShown = false, headFocusAttached = true))
    }
}
