package com.bsfeng.monitor.skip

import com.bsfeng.monitor.util.DebugConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.junit.runner.RunWith

/**
 * 判据日志的**可追溯性**契约。
 *
 * 判据拒绝时用户完全无感（不弹跳过、不落记录），「没有广告」与「有广告但被误杀」在用户视角
 * 无法区分。所以日志必须能唯一定位到「哪个 App 的哪个页面、什么文案、为什么被拒、已过多久」——
 * 只给原因的话，排查要靠时间戳对齐隔壁「进入 App」日志，一次冷启动几十条根本对不上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SplashGateLogTest {

    private val context = SplashGate.ScreenContext(
        packageName = "com.xingin.xhs",
        activity = "com.xingin.matrix.notedetail.NoteDetailActivity"
    )

    @After
    fun tearDown() {
        DebugConfig.setEnabled(false)
    }

    private fun captureLogs(block: () -> Unit): List<String> {
        DebugConfig.setEnabled(true)
        ShadowLog.clear()
        try {
            block()
        } finally {
            DebugConfig.setEnabled(false)
        }
        return ShadowLog.getLogs().filter { it.tag == "MonitorDebug" }.map { it.msg }
    }

    @Test
    fun `拒绝日志五要素齐全可直接 grep 定位`() {
        val candidate = SplashCandidate(text = "跳过此步", elapsedSinceEnterMs = 9_000L)
        val logs = captureLogs {
            val evidence = SplashGate.check(candidate, "跳过文本", "[text*=\"跳过\"]", context)
            assertTrue("不合规文案必须被拒", evidence == null)
        }

        val line = logs.single { it.contains("点击前判据拒绝") }
        assertTrue("缺包名: " + line, line.contains("pkg=com.xingin.xhs"))
        assertTrue("缺 Activity: " + line, line.contains("activity=com.xingin.matrix.notedetail.NoteDetailActivity"))
        assertTrue("缺命中文案: " + line, line.contains("text=跳过此步"))
        assertTrue("缺拒绝原因: " + line, line.contains("reason="))
        assertTrue("缺已过毫秒数: " + line, line.contains("elapsed=9000ms"))
        assertTrue("缺选择器，事后无法复盘规则: " + line, line.contains("选择器="))
    }

    @Test
    fun `放行日志同样带包名与页面`() {
        val candidate = SplashCandidate(text = "跳过5", elapsedSinceEnterMs = 300L)
        val logs = captureLogs {
            val evidence = SplashGate.check(candidate, "跳过文本", "[text*=\"跳过\"]", context)
            assertTrue("正例必须放行", evidence != null)
        }

        val line = logs.single { it.contains("点击前判据放行") }
        assertTrue("验证正例也要知道是哪家: " + line, line.contains("pkg=com.xingin.xhs"))
        assertTrue("缺 Activity: " + line, line.contains("activity="))
        assertTrue("缺已过毫秒数: " + line, line.contains("elapsed=300ms"))
    }

    @Test
    fun `Activity 未知时用占位符而不是空字段`() {
        val candidate = SplashCandidate(text = "跳过此步", elapsedSinceEnterMs = 1_000L)
        val logs = captureLogs {
            SplashGate.check(candidate, "跳过文本", "[text*=\"跳过\"]", SplashGate.ScreenContext("com.test.app", ""))
        }

        val line = logs.single { it.contains("点击前判据拒绝") }
        assertTrue("空 Activity 必须显式标出来，否则日志里是两个连续分隔符: " + line, line.contains("· activity=? ·"))
        assertFalse(line.contains("· activity= ·"))
    }

    @Test
    fun `结构化候选无文案时用 view-id 充当证据文案`() {
        val candidate = SplashCandidate(
            viewId = "com.demo:id/btn_skip",
            elapsedSinceEnterMs = 5_000L
        )
        val logs = captureLogs {
            SplashGate.check(candidate, "跳过按钮 ID", "[vid*=\"skip\"]", context)
        }

        val line = logs.single { it.contains("点击前判据拒绝") }
        assertTrue("没有文案时不能留空证据: " + line, line.contains("text=com.demo:id/btn_skip"))
    }
}
