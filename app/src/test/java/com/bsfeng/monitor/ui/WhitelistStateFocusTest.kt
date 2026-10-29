package com.bsfeng.monitor.ui

import com.bsfeng.monitor.ui.components.FiveState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s11 白名单的状态与一次性回位判据（「去授权」假跳转与焦点归位的回归）。
 *
 * 假跳转的回归用 action 字符串锁死：只要有人把真跳转换回「只 toast」，action 这条断言对应的
 * 常量就没人用了，静态检查同样能看出来（跳转调用点见 WhitelistScreen.kt 的 onAction）。
 */
class WhitelistStateFocusTest {

    @Test
    fun `去授权跳的是系统使用情况访问设置页`() {
        assertEquals("android.settings.USAGE_ACCESS_SETTINGS", USAGE_ACCESS_SETTINGS_ACTION)
    }

    @Test
    fun `仍在空态时焦点回授权按钮`() {
        assertEquals(WhitelistFocusDecision.REQUEST, emptyCtaFocusDecision(FiveState.EMPTY))
    }

    @Test
    fun `返回后重新扫描期间等待落定`() {
        assertEquals(WhitelistFocusDecision.WAIT, emptyCtaFocusDecision(FiveState.LOADING))
    }

    @Test
    fun `扫描出结果后不抢空态按钮的焦点`() {
        listOf(FiveState.POPULATED, FiveState.ERROR, FiveState.EDGE).forEach { state ->
            assertEquals(WhitelistFocusDecision.DROP, emptyCtaFocusDecision(state))
        }
    }

    @Test
    fun `重新扫描再次失败焦点回重新扫描`() {
        assertEquals(WhitelistFocusDecision.REQUEST, retryFocusDecision(FiveState.ERROR))
    }

    @Test
    fun `重新扫描成功退出错态后作废回焦`() {
        assertEquals(WhitelistFocusDecision.DROP, retryFocusDecision(FiveState.POPULATED))
    }

    @Test
    fun `重新扫描期间不重复请求焦点`() {
        assertEquals(WhitelistFocusDecision.WAIT, retryFocusDecision(FiveState.LOADING))
    }
}
