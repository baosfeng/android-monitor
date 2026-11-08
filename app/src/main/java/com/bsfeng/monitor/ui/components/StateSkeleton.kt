package com.bsfeng.monitor.ui.components

// 从 ui/components/States.kt 拆出：加载骨架 —— StateSkeleton、SkeletonRow、SkeletonBlock 与 shimmer 常量（原型 .sk）。负责「数据还没到」时的占位。

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 原型 .sk：height 12px（.sk.tall 20px）、radius-sm、1.4s 线性 shimmer。 */
private val SKELETON_H = 12.dp

private val SKELETON_H_TALL = 20.dp

private val SKELETON_WIDTHS = listOf(1f, 0.8f, 0.6f, 0.4f)

private const val SHIMMER_MS = 1400

private const val SHIMMER_PHASE_STATIC = 0.5f

/** 骨架屏：细行 shimmer（原型 .sk：12 / 20px 高、宽 40/60/80/100%、radius-sm）。 */
@Composable
fun StateSkeleton(modifier: Modifier = Modifier, rows: Int = 3) {
    val t = LocalDesignTokens.current
    // 骨架是基元，必须自己订阅动效档位：「精简」档下全站骨架仍在无限扫动，
    // 是整个 App 里唯一没有降档出口的动画（其余基元都已订阅）。
    val phase = if (rememberMotionEnabled()) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "skeletonShimmer",
        )
        animated
    } else {
        SHIMMER_PHASE_STATIC
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space2)) {
        repeat(rows) { i ->
            SkeletonRow(
                height = if (i % 3 == 1) SKELETON_H_TALL else SKELETON_H,
                widthFraction = SKELETON_WIDTHS[i % SKELETON_WIDTHS.size],
                phase = phase,
            )
        }
    }
}

/** 细行骨架（原型 .sk）：底 = fg 6% → 13% → 6% 横向渐变，按 [phase] 位移出 shimmer。 */
@Composable
fun SkeletonRow(
    height: Dp,
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    phase: Float = SHIMMER_PHASE_STATIC,
    alpha: Float = 1f,
) {
    val t = LocalDesignTokens.current
    val base = t.fgFade(6).let { if (alpha == 1f) it else it.copy(alpha = it.alpha * alpha) }
    val peak = t.fgFade(13).let { if (alpha == 1f) it else it.copy(alpha = it.alpha * alpha) }
    // 三色表只在颜色变化时重建：shimmer 每帧重跑的是 drawRect，不该顺带 new listOf。
    val colors = remember(base, peak) { listOf(base, peak, base) }
    Box(
        modifier
            .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f))
            .height(height)
            .clip(RoundedCornerShape(t.radiusSm))
            // Brush 只随「尺寸 + 配色」重建一次，shimmer 每帧只做 translate + drawRect。
            // 原写法在 drawBehind 里每帧 new Brush.horizontalGradient（UI 规范门禁 check 2）。
            .drawWithCache {
                val span = size.width * 2.2f
                val brush = Brush.horizontalGradient(colors, startX = 0f, endX = span)
                onDrawBehind {
                    val start = size.width * 1.2f - span * phase.coerceIn(0f, 1f)
                    translate(left = start) { drawRect(brush) }
                }
            }
    )
}

/** 单块骨架（兼容旧调用点：满宽）。 */
@Composable
fun SkeletonBlock(height: Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    SkeletonRow(height = height, widthFraction = 1f, alpha = alpha, modifier = modifier)
}
