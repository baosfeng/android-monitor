package com.bsfeng.monitor.ui.components

// 列表行按压反馈（原型 s03 .tl-item:active → scale(.99) / opacity(.9)）：
// 与 PressFeedback.kt 的按钮 0.96 区分——列表行面积大，缩太多会晃眼。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 列表行按压缩放目标（原型 s03：scale(.99)）。 */
const val ROW_PRESS_SCALE: Float = 0.99f

/** 列表行按压透明度（原型 s03：opacity .9）。 */
const val ROW_PRESS_ALPHA: Float = 0.9f

/**
 * 列表行按压反馈：按下缩到 [ROW_PRESS_SCALE] 且透明度降到 [ROW_PRESS_ALPHA]，时长取
 * `--dur-press`、缓动取 `--ease-out`；精简档直接返回原 Modifier（不播无意义动画）。
 *
 * [interaction] 必须与触发行点击的 `clickable` 共用同一实例，否则收不到 Press 事件。
 */
@Composable
fun Modifier.rowPressFeedback(interaction: MutableInteractionSource): Modifier {
    if (rememberMotionTier() == MotionTier.REDUCED) return this
    val pressed by interaction.collectIsPressedAsState()
    val t = LocalDesignTokens.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) ROW_PRESS_SCALE else 1f,
        animationSpec = tween(t.durPress, easing = t.easeOut),
        label = "rowPressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) ROW_PRESS_ALPHA else 1f,
        animationSpec = tween(t.durPress, easing = t.easeOut),
        label = "rowPressAlpha",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}
