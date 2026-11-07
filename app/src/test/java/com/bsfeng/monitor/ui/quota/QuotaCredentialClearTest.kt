package com.bsfeng.monitor.ui.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清除凭据」入口判据（回归防线）。
 *
 * 两条判据错了都不会崩，只会让用户困惑：
 * - 未配置的家也显示入口 → 点了没有任何可清的东西（假入口）；
 * - 在途还允许点 → 保存与清除交叉执行，出现「刚清掉又被保存写回」。
 *
 * 纯 JVM 用例，不组合 Compose（渲染由 Compose UI 测试覆盖）。
 */
class QuotaCredentialClearTest {

    @Test
    fun `已配置且空闲时显示并可点`() {
        val action = credentialClearAction(configured = true, busy = false)

        assertTrue("已配置的家必须给清除入口", action.visible)
        assertTrue("空闲时必须可点", action.enabled)
    }

    @Test
    fun `未配置的家不显示清除入口`() {
        val action = credentialClearAction(configured = false, busy = false)

        assertFalse("没有可清的东西，入口本身就是噪音", action.visible)
        assertFalse("不可见的入口不可能可点", action.enabled)
    }

    @Test
    fun `在途时显示但禁用（与保存共用同一把锁）`() {
        val action = credentialClearAction(configured = true, busy = true)

        assertTrue("在途只是置灰，不把入口收走（收走会让布局跳动）", action.visible)
        assertFalse("连点只执行一次", action.enabled)
    }

    @Test
    fun `未配置且在途时同样不可点`() {
        val action = credentialClearAction(configured = false, busy = true)

        assertFalse(action.visible)
        assertFalse(action.enabled)
    }

    @Test
    fun `确认弹窗写清后果与恢复路径`() {
        // 破坏性操作不能只问「确定吗」：要说清删的是什么、删完怎么恢复
        assertTrue(CLEAR_CREDENTIALS_MESSAGE.contains("删除"))
        assertTrue(CLEAR_CREDENTIALS_MESSAGE.contains("重新填写"))
        assertTrue("标题要明确这是断开/清除，不是普通确认", CLEAR_CREDENTIALS_TITLE.contains("清除"))
        assertEquals("按钮文案与锚点 button.inline 的语义一致", "清除凭据", CLEAR_CREDENTIALS_LABEL)
    }

    @Test
    fun `确认正文没有拼接留下的多余空白`() {
        // 回归：正文是多行字符串拼接，漏写续行会把缩进/空格吃进文案，真机上显示成一串空洞
        assertFalse("拼接不能留下连续空格", CLEAR_CREDENTIALS_MESSAGE.contains("  "))
        assertFalse("首尾不能有空白", CLEAR_CREDENTIALS_MESSAGE != CLEAR_CREDENTIALS_MESSAGE.trim())
    }
}
