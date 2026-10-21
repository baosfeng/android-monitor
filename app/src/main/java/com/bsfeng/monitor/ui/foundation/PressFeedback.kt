package com.bsfeng.monitor.ui.foundation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.rememberMotionTier
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 按下反馈基元：按下瞬间缩到 [PRESS_SCALE_TARGET]，松手弹回 1f。
 *
 * 全项目只有这一份实现。此前 `ComponentGalleryScreen`(0.96) / `AddTransactionScreen`(0.97) /
 * `FinanceMainScreen`(0.88) 各写了一份，值互不相同、动画 spec 也各异，同一 App 里按压手感被拆成三种。
 *
 * 三条硬约束：
 *  1. **只在绘制期缩放**：走 [Modifier.graphicsLayer] 的 scaleX / scaleY，不进 measure/layout；
 *  2. **动画 spec 来自 token**：`t.springOf(MotionToken.Press)`，过冲量由 `--ease-spring` 推导、
 *     刚度由 `--dur-press` 推导，不手写 `spring(stiffness = 500f)` 这类魔数；
 *  3. **减弱动效时返回 1f**：精简档（[MotionTier.REDUCED]）的用户不该看到无意义的缩放。
 */

/** 按压缩放目标（原型 `.btn:active` 的 transform: scale(0.96)）。 */
const val PRESS_SCALE_TARGET: Float = 0.96f

/**
 * 缩放目标值（纯函数，便于 JVM 单测把「REDUCED 档返回 1f」钉死）。
 *
 * @param motionEnabled 系统 / 档位是否允许播动画（false = REDUCED，直接落终态）
 * @param pressed 该控件是否参与按下缩放（禁用态传 false）
 * @param isPressedNow 当前是否处于按下状态
 */
fun pressScaleTargetFor(
    motionEnabled: Boolean,
    pressed: Boolean,
    isPressedNow: Boolean,
): Float = if (motionEnabled && pressed && isPressedNow) PRESS_SCALE_TARGET else 1f

/**
 * 按下缩放值（1f = 未按下）。
 *
 * [interaction] 必须同时传给触发点击的 `clickable` / `combinedClickable`，否则收不到 Press 事件，
 * 表现为「怎么按都不缩」。
 *
 * @param pressed false = 该控件不参与按下缩放（禁用态、或卡片这类大面积容器），恒返回 1f。
 */
@Composable
fun rememberPressScale(interaction: MutableInteractionSource, pressed: Boolean = true): Float {
    // 档位口径：REDUCED（用户选精简 / 系统动画缩放 = 0）不做缩放，FULL 与 NORMAL 都缩放 ——
    // 按压缩放属于「NORMAL 保留」的三项之一，只有精简档才省略。
    val motionEnabled = rememberMotionTier() != MotionTier.REDUCED
    val isPressed by interaction.collectIsPressedAsState()
    val t = LocalDesignTokens.current
    val scale by animateFloatAsState(
        // 目标值恒为 1f / 0.96f 两种，不做条件分支：条件调用 animate*AsState 会让状态在
        // 「进入按压」那一刻重建，初始值直接落在目标上，表现为瞬跳而不是弹回。
        targetValue = pressScaleTargetFor(motionEnabled, pressed, isPressed),
        animationSpec = t.springOf(MotionToken.Press),
        label = "pressScale",
    )
    return scale
}

/**
 * 把已算好的缩放值落到绘制期（供 [rememberPressScale] 的调用方或自定义动画复用）。
 *
 * 只改 scaleX / scaleY：不改变布局占位，因此相邻元素不会因按压而抖动。
 */
fun Modifier.pressFeedbackScale(scale: Float): Modifier = graphicsLayer {
    scaleX = scale
    scaleY = scale
}

/**
 * 一步到位：`Modifier.pressFeedback(interaction)` 直接由按压状态得到缩放效果。
 *
 * 调用方仍需把同一个 [interaction] 交给 `clickable`：
 * ```
 * val interaction = remember { MutableInteractionSource() }
 * Box(
 *     Modifier
 *         .pressFeedback(interaction)
 *         .clickable(interactionSource = interaction, indication = LocalIndication.current) { ... }
 * )
 * ```
 *
 * @param enabled false = 不参与按下缩放（禁用态），返回 [Modifier] 本身。
 */
@Composable
fun Modifier.pressFeedback(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    return pressFeedbackScale(rememberPressScale(interaction))
}
