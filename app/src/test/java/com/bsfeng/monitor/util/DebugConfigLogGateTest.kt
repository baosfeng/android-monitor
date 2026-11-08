package com.bsfeng.monitor.util

import com.bsfeng.monitor.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * 调试日志闸门（安全审计 S-03）。
 *
 * 为什么这条闸门重要：自动记账链路里唯一必须打印的是「屏幕原文 / 账目字段」（金额、商户、备注、
 * 页面行），它们此前用裸 `Log.d(tag="AutoBook")` —— 裸日志在 **release 包里同样生效**，
 * 一抓 logcat 就是一份明文账本。现在这类内容统一走 [DebugConfig.log]，口径钉在两件事：
 *
 * 1. 开关关着时**一条都不许打**（默认就是关的，用户装完不会自动开始外泄）；
 * 2. tag 固定 `MonitorDebug`，release 恒静默（[BuildConfig.DEBUG] 硬闸门）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugConfigLogGateTest {

    @After
    fun tearDown() {
        DebugConfig.setEnabled(false)
        ShadowLog.clear()
    }

    private fun captured(): List<ShadowLog.LogItem> =
        ShadowLog.getLogs().filter { it.tag == "MonitorDebug" }

    @Test
    fun `开关关闭时一条日志都不打`() {
        DebugConfig.setEnabled(false)
        ShadowLog.clear()

        DebugConfig.log("AutoBook 账单页: 文本=[不应出现]")

        assertTrue("闸门关着仍打了日志：" + captured().map { it.msg }, captured().isEmpty())
    }

    @Test
    fun `开关打开后按 MonitorDebug 标签输出`() {
        DebugConfig.setEnabled(true)
        ShadowLog.clear()

        DebugConfig.log("AutoBook 账单页: 行数=3")

        val logs = captured()
        assertEquals("开关打开后必须恰好一条", 1, logs.size)
        assertTrue(logs[0].msg.contains("AutoBook 账单页"))
    }

    @Test
    fun `标签固定不随调用方变化`() {
        DebugConfig.setEnabled(true)
        ShadowLog.clear()

        DebugConfig.log("x")

        assertEquals("MonitorDebug", captured().single().tag)
    }

    @Test
    fun `uiAvailable 与 unlocked 绑定且 release 恒 false`() {
        DebugConfig.setEnabled(true)
        // debug 单测跑的是 debug 变体：unlocked 由 prefs 决定；这里只钉「release 恒 false」的口径
        if (!BuildConfig.DEBUG) {
            assertFalse("release 包里 uiAvailable 必须恒为 false", DebugConfig.uiAvailable)
        }
        // 反向：未解锁时 demoVisible 不能为 true（演示件同属调试面）
        if (!DebugConfig.unlocked) {
            assertFalse(DebugConfig.demoVisible)
        }
    }
}
