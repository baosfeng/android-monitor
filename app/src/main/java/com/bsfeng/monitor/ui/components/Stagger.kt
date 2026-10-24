package com.bsfeng.monitor.ui.components

// 从 ui/components/Motion.kt 拆出：列表错落入场 —— staggerDelayMs 计算、rememberStaggerProgress 进度、Modifier.staggerEnter / staggerEnterAt。负责「一列元素依次浮现」。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.bsfeng.monitor.ui.foundation.staggerStepsFor
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay

/** 第 [index] 项的延迟（ms）= index × stagger，封顶 [maxSteps] 步。 */
fun staggerDelayMs(index: Int, staggerMs: Int, maxSteps: Int = STAGGER_MAX_STEPS): Int =
    index.coerceAtLeast(0).coerceAtMost(maxSteps) * staggerMs.coerceAtLeast(0)

/**
 * 列表项入场进度 0→1：delay = index × --stagger，单条 [durationToken] + --ease-out。
 * 值层，可自行用于 alpha / scale / 自定义位移。
 *
 * [index] 传 `-1` = **不做入场动画**，直接落终态（J5：滚动中新挂载的项不该再起动画）。
 * [maxSteps] 默认取当前档位的 stagger 步数（[staggerStepsFor]）：只有 FULL 逐项延迟，
 * NORMAL / REDUCED 下 all-at-once（步数为 0 = 全部同时入场）。
 */
@Composable
fun rememberStaggerProgress(
    index: Int,
    animated: Boolean = rememberMotionEnabled(),
    durationToken: MotionToken = MotionToken.Page,
    maxSteps: Int = staggerStepsFor(rememberMotionTier()),
): Float {
    val t = LocalDesignTokens.current
    if (index < 0) return 1f
    val delayMs = staggerDelayMs(index, t.stagger, maxSteps)
    val anim = remember(index) { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(index, animated) {
        if (!animated) {
            anim.snapTo(1f)
        } else {
            anim.snapTo(0f)
            if (delayMs > 0) delay(delayMs.toLong())
            anim.animateTo(1f, tween(t.durationMsOf(durationToken), easing = t.easeOut))
        }
    }
    return anim.value
}

/** 把入场进度落到透明度 + 上浮位移（原型 od-rise：opacity 0→1、translateY 10px→0）。 */
fun Modifier.staggerEnter(progress: Float, riseFrom: Dp = STAGGER_RISE): Modifier = graphicsLayer {
    val p = progress.coerceIn(0f, 1f)
    alpha = p
    translationY = (1f - p) * riseFrom.toPx()
}

/**
 * 一步到位：`Modifier.staggerEnterAt(index)` 直接由 index 得到入场效果，内部已含 reduced-motion 兜底。
 * [index] 传 `-1` = 该项不做入场动画（直接可见），长列表滚动挂载新项时用。
 */
@Composable
fun Modifier.staggerEnterAt(
    index: Int,
    animated: Boolean = rememberMotionEnabled(),
    durationToken: MotionToken = MotionToken.Page,
    maxSteps: Int = staggerStepsFor(rememberMotionTier()),
    riseFrom: Dp = STAGGER_RISE,
): Modifier = this.staggerEnter(
    progress = rememberStaggerProgress(index, animated, durationToken, maxSteps),
    riseFrom = riseFrom,
)
