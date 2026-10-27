package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s18 / s19 的跨页焦点回收（第 4 批走查 P1-6）。
 *
 * 额度设置是宿主导航用覆盖层打开的，额度页收不到「覆盖层已关闭」的回调；
 * 用最小代次信号衔接（与 AppearanceScreen 的 AppearanceFocusReturn 同口径）。
 * 三点必须成立：退页必 +1、代次没变不抢焦点、进页面前的旧代次不抢焦点。
 */
class QuotaFocusReturnTest {

    @Test
    fun `额度设置退出一次代次前进一位`() {
        val before = QuotaSettingsFocusReturn.generation
        QuotaSettingsFocusReturn.request()
        assertEquals(before + 1, QuotaSettingsFocusReturn.generation)
    }

    @Test
    fun `连续退出按次累加`() {
        val before = QuotaSettingsFocusReturn.generation
        QuotaSettingsFocusReturn.request()
        QuotaSettingsFocusReturn.request()
        assertEquals(before + 2, QuotaSettingsFocusReturn.generation)
    }

    @Test
    fun `代次未变化时不回焦`() {
        assertFalse(quotaReturnFocusPending(currentGeneration = 4, seenGeneration = 4))
    }

    @Test
    fun `代次前进时回焦到齿轮`() {
        assertTrue(quotaReturnFocusPending(currentGeneration = 5, seenGeneration = 4))
    }

    @Test
    fun `进页面前遗留的旧代次不抢焦点`() {
        // 从设置 Tab 打开额度设置再回来：额度页不在组合中，组合时把当前代次当基线
        val baseline = QuotaSettingsFocusReturn.generation
        assertFalse(quotaReturnFocusPending(baseline, seenGeneration = baseline))
    }

    @Test
    fun `没点过重试时不抢焦点`() {
        assertEquals(
            QuotaFocusAction.IDLE,
            retryFocusAction(armed = false, loading = false, sawLoading = true, hasError = false),
        )
    }

    @Test
    fun `重试请求在途时等错误条有结果`() {
        assertEquals(
            QuotaFocusAction.WAIT,
            retryFocusAction(armed = true, loading = true, sawLoading = true, hasError = false),
        )
    }

    @Test
    fun `重试请求还没起来时继续等`() {
        assertEquals(
            QuotaFocusAction.WAIT,
            retryFocusAction(armed = true, loading = false, sawLoading = false, hasError = true),
        )
    }

    @Test
    fun `重试成功错误条收起后焦点回刷新额度`() {
        assertEquals(
            QuotaFocusAction.REQUEST,
            retryFocusAction(armed = true, loading = false, sawLoading = true, hasError = false),
        )
    }

    @Test
    fun `重试仍失败时作废回焦`() {
        assertEquals(
            QuotaFocusAction.DROP,
            retryFocusAction(armed = true, loading = false, sawLoading = true, hasError = true),
        )
    }
}
