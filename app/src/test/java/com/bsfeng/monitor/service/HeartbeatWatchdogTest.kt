package com.bsfeng.monitor.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程冻结判定的纯逻辑边界（[HeartbeatWatchdog.isStale]）。
 *
 * 背景见 `docs/踩坑/进程被系统冻结导致无障碍全部失效.md`：冻结时「授权 / 绑定 / 静态标志」
 * 全部显示正常，唯一能看见的是心跳停更，所以这条纯函数的边界一旦错，要么误报骚扰用户、
 * 要么漏报回到「静默失效」。
 *
 * 注意：判定「是否发生过中断」用的是同一个 [HeartbeatWatchdog.isStale]——心跳恢复时回看
 * 上次间隔，超阈值即记中断。冻结后用户打开 App 会自动解冻，所以只有记录历史才有意义。
 */
class HeartbeatWatchdogTest {

    private val threshold = HeartbeatWatchdog.STALE_THRESHOLD_MS

    @Test
    fun `从未记录心跳时不判中断`() {
        // 全新安装 / 首次运行：不能一进设置页就告警
        assertFalse(HeartbeatWatchdog.isStale(lastBeatMs = 0L, nowMs = 10_000_000L))
    }

    @Test
    fun `阈值内视为正常`() {
        assertFalse(HeartbeatWatchdog.isStale(lastBeatMs = 1_000L, nowMs = 1_000L + threshold))
    }

    @Test
    fun `刚超过阈值即判中断`() {
        assertTrue(HeartbeatWatchdog.isStale(lastBeatMs = 1_000L, nowMs = 1_000L + threshold + 1))
    }

    @Test
    fun `now 小于 last 不判中断`() {
        // 设备重启后 elapsedRealtime 归零，此时 now < last 是正常现象
        assertFalse(HeartbeatWatchdog.isStale(lastBeatMs = 9_000_000L, nowMs = 1_000L))
    }

    @Test
    fun `跳过一次心跳不判中断`() {
        // 轮询链每 1 秒一次，1 秒的空档不能算停摆
        assertFalse(HeartbeatWatchdog.isStale(lastBeatMs = 5_000L, nowMs = 6_000L))
    }

    @Test
    fun `阈值是三分钟`() {
        // 设置页文案写「约 N 分钟」，改动阈值要同步审视这条断言
        assertTrue(HeartbeatWatchdog.STALE_THRESHOLD_MS == 3 * 60 * 1000L)
    }

    // ===== 设置页提示文案（纯函数）=====

    @Test
    fun `中断计时按分钟展示`() {
        val text = HeartbeatWatchdog.gapNoticeOf(12 * 60_000L, "22:08")
        assertTrue("应含「约 12 分钟」：$text", text.contains("约 12 分钟"))
        assertTrue("应含恢复时刻：$text", text.contains("（22:08 恢复）"))
        assertTrue("应提示省电策略：$text", text.contains("省电策略"))
    }

    @Test
    fun `不足一分钟按秒展示且至少 1 秒`() {
        // 心跳抖动造成的极短中断不能显示「约 0 秒」
        assertTrue(HeartbeatWatchdog.gapNoticeOf(20_000L, null).contains("约 20 秒"))
        assertTrue(HeartbeatWatchdog.gapNoticeOf(100L, null).contains("约 1 秒"))
    }

    @Test
    fun `无恢复时刻时不出现空括号`() {
        val text = HeartbeatWatchdog.gapNoticeOf(5 * 60_000L, null)
        assertFalse("不该出现空括号：$text", text.contains("（）"))
        assertTrue(text.contains("约 5 分钟"))
    }
}
