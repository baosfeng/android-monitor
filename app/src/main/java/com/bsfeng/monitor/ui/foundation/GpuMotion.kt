package com.bsfeng.monitor.ui.foundation

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * 绘制期动画：绘制期位移的唯一入口。
 *
 * 会变布局的动画走 [androidx.compose.foundation.layout.offset] 会让每帧 measure/layout；
 * 这里统一走 `graphicsLayer` 的 translation —— 位移只在绘制期合成，不触发测量与布局。
 *
 * 用法（[androidx.compose.animation.core.animateDpAsState] 等动画值在绘制期再读）：
 * ```
 * val offset = animateDpAsState(target)
 * Box(Modifier.gpuSlideX { offset.value })
 * ```
 * 传 lambda 的版本把状态读取放进 layer 块：动画推进只让 layer 失效，不触发重组。
 * 直接传值的版本适合静态位移或已在上层做过绘制的场景。
 *
 * 边界：translation 不改变布局占位，超出父容器会被父级裁剪（与 offset 一致）；
 * 需要真实模糊时**不**用 RenderEffect —— 全项目 blur/RenderEffect 为 0 处。
 */

/** 绘制期水平位移，单位 px（正值向右）。 */
fun Modifier.gpuSlideX(value: Float): Modifier = graphicsLayer { translationX = value }

/** 绘制期垂直位移，单位 px（正值向下）。 */
fun Modifier.gpuSlideY(value: Float): Modifier = graphicsLayer { translationY = value }

/**
 * 绘制期水平位移（dp，延迟读取）：位移值在 layer 块内取，动画推进不触发重组。
 * 用 [Dp.toPx] 在绘制期换算，不依赖 composition 期的 LocalDensity。
 */
fun Modifier.gpuSlideX(offset: () -> Dp): Modifier = graphicsLayer { translationX = offset().toPx() }

/** 绘制期垂直位移（dp，延迟读取），语义同 [gpuSlideX]。 */
fun Modifier.gpuSlideY(offset: () -> Dp): Modifier = graphicsLayer { translationY = offset().toPx() }
