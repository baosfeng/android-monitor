package com.bsfeng.monitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 诊断计数器（纯 JVM）：reset 清零全部槽位，snapshot 把四条链路的计数拼成一段可读报告。
 *
 * 单例 object，用例之间用 reset 隔离状态；条件行（异常 / 上次结果 / 有 Token）分别覆盖「有值」「无值」两条分支。
 */
class DiagnosticCountersTest {

    @Before
    fun setUp() {
        DiagnosticCounters.reset()
    }

    @Test
    fun `reset 清零全部计数与文本`() {
        DiagnosticCounters.accessibilityEventCount = 3
        DiagnosticCounters.lastErrorMessage = "boom"
        DiagnosticCounters.isTokenAvailable = true
        DiagnosticCounters.uploadEventCount = 9
        DiagnosticCounters.lastRefreshTimestamp = 42L

        DiagnosticCounters.reset()

        assertEquals(0, DiagnosticCounters.accessibilityEventCount)
        assertEquals("", DiagnosticCounters.lastErrorMessage)
        assertFalse(DiagnosticCounters.isTokenAvailable)
        assertEquals(0, DiagnosticCounters.uploadEventCount)
        // reset 不动最近刷新时间（它不是计数器，是页面刷新节拍）
        assertEquals(42L, DiagnosticCounters.lastRefreshTimestamp)
    }

    @Test
    fun `快照在无异常时省略可选行`() {
        val text = DiagnosticCounters.snapshot()
        assertTrue(text.startsWith("=== 诊断报告 ==="))
        assertTrue(text.contains("【无障碍服务】"))
        assertTrue(text.contains("【EventCollector】"))
        assertTrue(text.contains("【DatabaseHelper】"))
        assertTrue(text.contains("【上传到服务器】"))
        assertFalse(text.contains("最近异常"))
        assertFalse(text.contains("上次上传事件数"))
        assertFalse(text.contains("上次结果"))
        assertFalse(text.contains("上次错误"))
        assertTrue(text.contains("有 Token: false"))
    }

    @Test
    fun `快照带异常与上传明细时补出可选行`() {
        DiagnosticCounters.emitCalledCount = 4
        DiagnosticCounters.emitFailCount = 1
        DiagnosticCounters.lastErrorMessage = "emit 失败"
        DiagnosticCounters.dbInsertCalledCount = 4
        DiagnosticCounters.dbLastException = "db 锁"
        DiagnosticCounters.uploadAttemptCount = 2
        DiagnosticCounters.uploadSuccessCount = 1
        DiagnosticCounters.uploadEventCount = 12
        DiagnosticCounters.lastUploadResult = "HTTP 200"
        DiagnosticCounters.lastUploadError = "上一次 HTTP 500"
        DiagnosticCounters.isTokenAvailable = true

        val text = DiagnosticCounters.snapshot()
        assertTrue(text.contains("emit 被调用: 4 次"))
        assertTrue(text.contains("写入失败: 1 次"))
        assertTrue(text.contains("最近异常: emit 失败"))
        assertTrue(text.contains("最近异常: db 锁"))
        assertTrue(text.contains("上次上传事件数: 12"))
        assertTrue(text.contains("上次结果: HTTP 200"))
        assertTrue(text.contains("上次错误: 上一次 HTTP 500"))
        assertTrue(text.contains("有 Token: true"))
    }

    @Test
    fun `toString 与 snapshot 同口径`() {
        DiagnosticCounters.accessibilityEventCount = 7
        assertEquals(DiagnosticCounters.snapshot(), DiagnosticCounters.toString())
    }
}
