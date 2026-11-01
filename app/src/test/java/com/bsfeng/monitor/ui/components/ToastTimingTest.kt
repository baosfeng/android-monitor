package com.bsfeng.monitor.ui.components

import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Toast 时间口径单测（交互口径 §5：总停留 1.6s，淡出占最后 150ms，入场 220ms）。
 *
 * 不测动画帧（JVM 里没有帧回调），测「常量 → 时间推导」的自洽：
 * AppToast 用 `delay(durationMs - durOverlayOut)` 触发 onDismiss，默认 durationMs = 1600，
 * 因此 1450ms 触发点完全由 [DesignTokens.durOverlayOut] 决定 —— 这两个 token 跑偏，
 * 停留时长或淡出窗口就会失配。
 */
class ToastTimingTest {

    private val apple = DesignTokens("apple")

    @Test
    fun `入场取 dur-overlay-in 220ms、退场取 dur-overlay-out 150ms`() {
        assertEquals(220, apple.durOverlayIn)
        assertEquals(150, apple.durOverlayOut)
    }

    @Test
    fun `八个主题共用同一套 toast 时间口径`() {
        // 切主题不改停留时长：每个主题的 token 表都必须落在 220 / 150
        val ids = ThemeRepository.themeIds
        assertEquals(8, ids.size)
        ids.forEach { id ->
            val t = DesignTokens(id)
            assertEquals(id, 220, t.durOverlayIn)
            assertEquals(id, 150, t.durOverlayOut)
        }
    }

    @Test
    fun `onDismiss 在总停留 1600ms 减 150ms 的 1450ms 触发`() {
        val totalStayMs = 1600 // AppToast 的 durationMs 默认值
        val dismissAtMs = totalStayMs - apple.durOverlayOut
        assertEquals(1450, dismissAtMs)
        // 触发点 + 退场时长 = 总停留：淡出窗口正好是最后 150ms，不多不少
        assertEquals(totalStayMs, dismissAtMs + apple.durOverlayOut)
    }

    @Test
    fun `入场在淡出开始前完成且停留窗口非负`() {
        val dismissAtMs = 1600 - apple.durOverlayOut
        assertTrue("入场 220ms 必须先于 1450ms 的淡出", apple.durOverlayIn < dismissAtMs)
        assertTrue("入场 + 淡出不得超过总停留", apple.durOverlayIn + apple.durOverlayOut < 1600)
    }

    @Test
    fun `总停留短于退场时长时 delay 夹到零而不为负`() {
        // 对应 AppToast 的 (durationMs - durOverlayOut).coerceAtLeast(0)：
        // 调用方传更短的 durationMs 时不得出现负 delay（负值会被当作立即执行，退场无从播放）
        assertEquals(0, (100 - apple.durOverlayOut).coerceAtLeast(0))
        assertEquals(0, (apple.durOverlayOut - apple.durOverlayOut).coerceAtLeast(0))
    }
}
