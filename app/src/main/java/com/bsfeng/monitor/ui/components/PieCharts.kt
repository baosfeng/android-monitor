package com.bsfeng.monitor.ui.components

// 从 ui/components/Charts.kt 拆出：占比类图表 —— RingChart（环形）、PieChart（实心扇形）、RingProgress（圆环进度）。负责「整体中各部分占比」的扇形绘制。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp

/**
 * 图表基元（原 ui/components/Charts.kt 的文件级约定，随拆分保留）：
 * 全部自绘 Canvas，零第三方图表/动画库（契约 §6）；绘制时长走 --dur-chart / --dur-chart-fast，
 * 只动几何与 opacity。
 *
 * 热路径（J4）：动画值 `Animatable` 只在 **绘制期** 读（drawWithCache 的 build 块在
 * observeReads 作用域内执行，状态失效只触发重绘、不触发重组）；Path / dashPathEffect 这类
 * 每帧重建的对象放进缓存块，仅 values 或 size 变化时重建。
 */

/** 环形图：从 -90° 逐段扫出，段间错峰。 */
@Composable
fun RingChart(
    segments: List<Float>,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
    strokeWidth: Dp? = null,
    /** 环直径；默认 128.dp = token ring-size（128px→128dp），默认行为与改动前一致。 */
    size: Dp = 128.dp,
    centerLabel: String? = null,
    animated: Boolean = rememberMotionEnabled(),
) {
    val t = LocalDesignTokens.current
    val ramp = colors ?: t.chartRamp
    val sw = strokeWidth ?: 14.dp
    val track = t.borderSoft
    val anim = chartAnim(segments, animated, t.durChart)
    Box(modifier, contentAlignment = Alignment.Center) {
        Spacer(
            Modifier.fillMaxWidth().height(size).drawWithCache {
                val strokePx = sw.toPx()
                val total = segments.sum().takeIf { it > 0f } ?: 1f
                // 段宽与取色只与数据有关，逐帧不变 → 缓存；每帧只按进度扫出
                val plan = segments.mapIndexed { i, v ->
                    Triple(i, 360f * (v / total), ramp[i % ramp.size])
                }
                onDrawBehind {
                    val radius = (this.size.minDimension - strokePx) / 2f
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    drawCircle(track, radius = radius, center = center, style = Stroke(width = strokePx))
                    val p = anim.value
                    var start = -90f
                    plan.forEach { (i, sweep, color) ->
                        val per = ((p * plan.size) - i).coerceIn(0f, 1f)
                        if (per > 0f) {
                            drawArc(
                                color = color,
                                startAngle = start,
                                sweepAngle = sweep * per,
                                useCenter = false,
                                topLeft = Offset(center.x - radius, center.y - radius),
                                size = Size(radius * 2f, radius * 2f),
                                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                            )
                        }
                        start += sweep
                    }
                }
            },
        )
        if (centerLabel != null) {
            Text(centerLabel, style = MaterialTheme.typography.titleMedium, color = t.fg)
        }
    }
}

/**
 * 扇形图（占比）：**实心**扇形，角度从 -90°（12 点方向）起算、顺时针，逐片错峰扫出。
 * 分片之间用卡片底色 [DesignTokens.surface] 描 2dp 缝隙（原型 .chart-pie .pie-slice 配方），
 * 因此相邻分片不会糊成一团。分片取色默认走**分类色板** [DesignTokens.chartRamp]
 * （跨主题固定，切主题时同一分类保持同色）——不要与序列色混用。
 *
 * 与 [RingChart] 的区别：这是实心饼、无中心文字（原型的扇形图数值放在外部图例里）；
 * 需要环形进度用 [RingProgress]。
 */
@Composable
fun PieChart(
    segments: List<Float>,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
    /** 扇形直径；默认 128.dp = token ring-size，与 [RingChart] 一致。 */
    size: Dp = 128.dp,
    animated: Boolean = rememberMotionEnabled(),
) {
    val t = LocalDesignTokens.current
    val ramp = colors ?: t.chartRamp
    val seam = t.surface
    val anim = chartAnim(segments, animated, t.durChart)
    Spacer(
        modifier.fillMaxWidth().height(size).drawWithCache {
            val seamPx = PIE_SEAM.toPx()
            val total = segments.sum().takeIf { it > 0f } ?: 1f
            // 分片角度与取色只与数据有关 → 缓存；每帧只按进度扫出
            val plan = segments.mapIndexed { i, v ->
                Triple(i, 360f * (v.coerceAtLeast(0f) / total), ramp[i % ramp.size])
            }
            onDrawBehind {
                val radius = this.size.minDimension / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                val p = anim.value
                var start = -90f
                plan.forEach { (i, sweep, color) ->
                    val per = ((p * plan.size) - i).coerceIn(0f, 1f)
                    val sw = sweep * per
                    if (sw > 0f) {
                        drawArc(
                            color = color,
                            startAngle = start,
                            sweepAngle = sw,
                            useCenter = true,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Fill,
                        )
                        // 缝隙：再用卡片底色描 2dp 边（相邻两片各描一次，交界处即 2dp 缝）
                        drawArc(
                            color = seam,
                            startAngle = start,
                            sweepAngle = sw,
                            useCenter = true,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = seamPx),
                        )
                    }
                    start += sweep
                }
            }
        },
    )
}

/** 圆环进度（预算使用率）：单弧 + 轨道。 */
@Composable
fun RingProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    centerLabel: String? = null,
    animated: Boolean = rememberMotionEnabled(),
) {
    RingChart(
        segments = listOf(fraction.coerceIn(0f, 1f), (1f - fraction).coerceIn(0f, 1f)),
        modifier = modifier,
        colors = listOf(color ?: LocalDesignTokens.current.accent, LocalDesignTokens.current.borderSoft),
        centerLabel = centerLabel,
        animated = animated,
    )
}
