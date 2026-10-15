package com.bsfeng.monitor.ui

import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.cubicBezierOvershoot
import com.bsfeng.monitor.ui.components.dampingRatioForOvershoot
import com.bsfeng.monitor.ui.components.durationMsOf
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.components.springSpecOf
import com.bsfeng.monitor.ui.components.staggerDelayMs
import com.bsfeng.monitor.ui.components.stiffnessForDuration
import com.bsfeng.monitor.ui.components.tweenOf
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.ThemeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动效基元的纯函数回归：spring 配方必须由 token 推导（不是拍脑袋常数），
 * stagger 延迟必须严格 = index × --stagger。
 */
class MotionPrimitivesTest {

    private val apple = DesignTokens("apple")

    // ── 1 · spring 配方：cubic-bezier 原文 → 阻尼比 / 刚度 ──

    @Test
    fun `ease-spring 的过冲由 cubic-bezier 控制点算出`() {
        val o = cubicBezierOvershoot(apple.easeSpringSpecForTest())
        // cubic-bezier(0.34, 1.56, 0.64, 1) → 峰值约 1.096
        assertEquals(0.096f, o, 0.005f)
    }

    @Test
    fun `无过冲曲线返回零过冲与临界阻尼`() {
        assertEquals(0f, cubicBezierOvershoot("cubic-bezier(0.2, 0, 0, 1)"), 0.0001f)
        assertEquals(0f, cubicBezierOvershoot("0.2 0 0 1"), 0.0001f)
        assertEquals(1f, dampingRatioForOvershoot(0f), 0.0001f)
    }

    @Test
    fun `过冲约百分之十对应阻尼比约零点六`() {
        assertEquals(0.598f, dampingRatioForOvershoot(0.096f), 0.01f)
    }

    @Test
    fun `刚度由时长推出且随时长单调变软`() {
        val k120 = stiffnessForDuration(120, 0.598f)
        val k340 = stiffnessForDuration(340, 0.598f)
        val k600 = stiffnessForDuration(600, 0.598f)
        assertEquals(1067f, k120, 30f)
        assertTrue("时长越长弹簧越软: " + k120 + " > " + k340 + " > " + k600, k120 > k340 && k340 > k600)
    }

    @Test
    fun `springSpecOf 用 token 原文而非硬编码`() {
        val spec = springSpecOf<Float>(120, "cubic-bezier(0.34, 1.56, 0.64, 1)")
        assertEquals(0.598f, spec.dampingRatio, 0.01f)
        assertEquals(1067f, spec.stiffness, 30f)
        // 换一条无过冲曲线 → 阻尼比必须跟着变，证明是推导不是常数
        val flat = springSpecOf<Float>(120, "cubic-bezier(0.2, 0, 0, 1)")
        assertEquals(1f, flat.dampingRatio, 0.001f)
    }

    @Test
    fun `ease-spring token 有真实消费者`() {
        val spec = apple.springOf<Float>(MotionToken.State)
        assertEquals(0.598f, spec.dampingRatio, 0.01f)
        assertEquals(1067f, spec.stiffness, 30f)
    }

    // ── 2 · 时长 token 映射 ──

    @Test
    fun `七个时长 token 与运行时 token 表逐一相符`() {
        val pairs = listOf(
            MotionToken.Press to "dur-press",
            MotionToken.State to "dur-state",
            MotionToken.OverlayIn to "dur-overlay-in",
            MotionToken.OverlayOut to "dur-overlay-out",
            MotionToken.Page to "dur-page",
            MotionToken.Chart to "dur-chart",
            MotionToken.ChartFast to "dur-chart-fast",
        )
        pairs.forEach { (token, name) ->
            val raw = ThemeRepository.rawToken("apple", name).removeSuffix("ms").toInt()
            assertEquals(name, raw, apple.durationMsOf(token))
        }
        assertEquals(600, apple.durationMsOf(MotionToken.Chart))
        assertEquals(340, apple.durationMsOf(MotionToken.Page))
    }

    // ── 3 · stagger ──

    @Test
    fun `stagger token 值为 24ms 且有消费者`() {
        assertEquals(24, apple.stagger)
        assertEquals(24, ThemeRepository.rawToken("apple", "stagger").removeSuffix("ms").toInt())
    }

    @Test
    fun `逐项延迟等于 index 乘 stagger 并封顶`() {
        assertEquals(0, staggerDelayMs(0, 24))
        assertEquals(24, staggerDelayMs(1, 24))
        assertEquals(72, staggerDelayMs(3, 24))
        assertEquals(24 * STAGGER_MAX_STEPS, staggerDelayMs(STAGGER_MAX_STEPS, 24))
        assertEquals(24 * STAGGER_MAX_STEPS, staggerDelayMs(500, 24))
        assertEquals(0, staggerDelayMs(-3, 24))
        assertEquals(0, staggerDelayMs(5, 0))
    }

    // ── 4 · 数字滚动 ──

    @Test
    fun `数字滚动用 dur-chart 加 ease-out 且不引第三方曲线`() {
        val tween = apple.tweenOf<Float>(MotionToken.Chart)
        assertEquals(600, tween.durationMillis)
        // 曲线取 --ease-out 原文（原型 App.count 的 1-(1-p)^3 等价物）
        assertEquals("cubic-bezier(0.2, 0, 0, 1)", ThemeRepository.rawToken("apple", "ease-out"))
    }
}

/** --ease-spring 的运行时原文（基元内部即读此值，测试同源）。 */
private fun DesignTokens.easeSpringSpecForTest(): String =
    ThemeRepository.rawToken(themeId, "ease-spring")
