package com.bsfeng.monitor.ui.components

// 从 ui/components/Motion.kt 拆出：动效 token —— MotionToken 枚举（对应 DesignTokenTable 的时长变量）、EASE_SPRING_TOKEN、错峰常量，以及按 token 取时长的 durationMsOf / tweenOf。负责「动效参数取哪个 token」。

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.FULL_STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.theme.DesignTokens

/** 动效时长 token 名（与 DesignTokenTable 的 CSS 变量一一对应）。 */
enum class MotionToken { Press, State, OverlayIn, OverlayOut, Page, Chart, ChartFast }

/** --ease-spring：位置/缩放/旋转类 spring 观感的近似曲线（交互口径 §7）。 */
const val EASE_SPRING_TOKEN: String = "ease-spring"

/** 错峰步数上限：长列表末尾不至于等太久（24ms × 12 = 288ms）；唯一口径在 [FULL_STAGGER_MAX_STEPS]。 */
const val STAGGER_MAX_STEPS: Int = FULL_STAGGER_MAX_STEPS

/** 列表项入场位移起点：原型 @keyframes od-rise 的 translate3d(0,10px,0)。 */
val STAGGER_RISE: Dp = 10.dp

/** 按 token 取时长（ms）。 */
fun DesignTokens.durationMsOf(token: MotionToken): Int = when (token) {
    MotionToken.Press -> durPress
    MotionToken.State -> durState
    MotionToken.OverlayIn -> durOverlayIn
    MotionToken.OverlayOut -> durOverlayOut
    MotionToken.Page -> durPage
    MotionToken.Chart -> durChart
    MotionToken.ChartFast -> durChartFast
}

/** 通用 tween：颜色/透明度/位移一律 --ease-out（交互口径 §7）。 */
fun <T> DesignTokens.tweenOf(token: MotionToken = MotionToken.Press): TweenSpec<T> =
    tween(durationMillis = durationMsOf(token), easing = easeOut)
