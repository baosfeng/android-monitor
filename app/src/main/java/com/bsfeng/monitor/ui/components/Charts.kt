package com.bsfeng.monitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.heatRamp

/**
 * 图表基元：全部自绘 Canvas，零第三方图表/动画库（契约 §6）。
 * 面积图 / 折线图 / 环形图 / 柱状图 / 热力条 / sparkline / 圆环进度。
 * 绘制时长走 --dur-chart / --dur-chart-fast；只动几何与 opacity。
 *
 * 热路径（J4）：动画值 [Animatable] 只在 **绘制期** 读（drawWithCache 的 build 块在
 * observeReads 作用域内执行，状态失效只触发重绘、不触发重组）；normalize / Path /
 * dashPathEffect 这类每帧重建的对象放进缓存块，仅 values 或 size 变化时重建。
 * 动画时长与曲线仍取 token，观感与改动前一致。
 */

private fun normalize(values: List<Float>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    return values.map { (it - min) / span }
}

/**
 * 图表动画进度：返回 [Animatable] 而不是当帧 Float —— 调用点在 drawWithCache 的
 * build 块里读 progress.value，读取落在绘制作用域，动画期间不再逐帧重组（J4）。
 */
@Composable
private fun chartAnim(key: Any, animated: Boolean, durMs: Int): Animatable<Float, *> {
    val anim = remember(key) { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(key, animated) {
        if (animated) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(durMs))
        } else {
            anim.snapTo(1f)
        }
    }
    return anim
}

/** 网格虚线画笔：PathEffect 是常量对象，不再每帧新建（J4）。 */
private val GRID_DASH = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)

private fun DrawScope.gridLines(color: Color, rows: Int) {
    for (i in 0..rows) {
        val y = size.height * i / rows
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = GRID_DASH)
    }
}

/** 折线图：单序列，末端圆点。 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    animated: Boolean = true,
    showGrid: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val line = color ?: t.chart1
    val border = t.border
    val anim = chartAnim(values, animated, t.durChart)
    Spacer(
        modifier.drawWithCache {
            val pts = normalize(values)
            val stroke = 2.dp.toPx()
            val dot = 4.dp.toPx()
            val halo = 8.dp.toPx()
            val haloTint = line.copy(alpha = 0.16f)
            val path = if (pts.size < 2) null else Path().apply {
                pts.forEachIndexed { i, v ->
                    val x = size.width * i / (pts.size - 1)
                    val y = size.height * (1f - v)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            onDrawBehind {
                if (showGrid) gridLines(border, 4)
                if (path == null) return@onDrawBehind
                drawPath(path, line, style = Stroke(width = stroke, cap = StrokeCap.Round))
                val last = pts.last()
                drawCircle(line, radius = dot, center = Offset(size.width, size.height * (1f - last)))
                drawCircle(
                    haloTint,
                    radius = halo,
                    center = Offset(size.width * anim.value, size.height * (1f - last)),
                )
            }
        },
    )
}

/** 面积图：折线下方面积渐显。 */
@Composable
fun AreaChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    animated: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val line = color ?: t.chart1
    val border = t.border
    val anim = chartAnim(values, animated, t.durChart)
    Spacer(
        modifier.drawWithCache {
            val pts = normalize(values)
            val stroke = 2.dp.toPx()
            val dot = 4.dp.toPx()
            val areaTop = line.copy(alpha = 0.34f)
            val areaBottom = line.copy(alpha = 0f)
            val linePath = if (pts.size < 2) null else Path().apply {
                pts.forEachIndexed { i, v ->
                    val x = size.width * i / (pts.size - 1)
                    val y = size.height * (1f - v)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            onDrawBehind {
                gridLines(border, 4)
                val path = linePath ?: return@onDrawBehind
                val p = anim.value
                val areaPath = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    areaPath,
                    Brush.verticalGradient(listOf(areaTop.copy(alpha = areaTop.alpha * p), areaBottom)),
                )
                drawPath(path, line, style = Stroke(width = stroke, cap = StrokeCap.Round))
                drawCircle(line, radius = dot, center = Offset(size.width, size.height * (1f - pts.last())))
            }
        },
    )
}

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
    animated: Boolean = true,
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

/** 圆环进度（预算使用率）：单弧 + 轨道。 */
@Composable
fun RingProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    centerLabel: String? = null,
    animated: Boolean = true,
) {
    RingChart(
        segments = listOf(fraction.coerceIn(0f, 1f), (1f - fraction).coerceIn(0f, 1f)),
        modifier = modifier,
        colors = listOf(color ?: LocalDesignTokens.current.accent, LocalDesignTokens.current.borderSoft),
        centerLabel = centerLabel,
        animated = animated,
    )
}

/** 柱状图：底部锚定生长，逐根错峰。 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
    animated: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val ramp = colors ?: t.chartRamp
    val border = t.border
    val anim = chartAnim(values, animated, t.durChartFast)
    Spacer(
        modifier.drawWithCache {
            val pts = normalize(values)
            val gap = size.width * 0.02f
            val bw = if (pts.isEmpty()) 0f else (size.width - gap * (pts.size + 1)) / pts.size
            val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            onDrawBehind {
                gridLines(border, 4)
                if (pts.isEmpty()) return@onDrawBehind
                val p = anim.value
                pts.forEachIndexed { i, v ->
                    val per = ((p * pts.size) - i).coerceIn(0f, 1f)
                    val h = (size.height - 2f) * v * per
                    drawRoundRect(
                        color = ramp[i % ramp.size],
                        topLeft = Offset(gap + i * (bw + gap), size.height - h),
                        size = Size(bw, h),
                        cornerRadius = corner,
                    )
                }
            }
        },
    )
}

/** 热力条：24h 逐格淡入。色阶 = 原型 .h0..h5，越活跃越深（00-shell.html:496-501）。 */
@Composable
fun HeatBar(
    values: List<Float>,
    modifier: Modifier = Modifier,
    levels: Int = 6,
    animated: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val ramp = t.heatRamp
    val anim = chartAnim(values, animated, t.durChartFast)
    Spacer(
        modifier.drawWithCache {
            if (values.isEmpty()) {
                onDrawBehind { }
            } else {
                val gap = 2.dp.toPx()
                val cw = (size.width - gap * (values.size - 1)) / values.size
                val max = values.max().takeIf { it > 0f } ?: 1f
                val steps = (levels - 1).coerceAtLeast(1)
                val last = (ramp.size - 1).coerceAtLeast(0)
                // 色阶与格宽只与数据和尺寸有关 → 缓存；每帧只乘淡入进度
                val cells = values.map { v ->
                    val level = ((v / max) * steps).toInt().coerceIn(0, steps)
                    ramp[(level * last) / steps]
                }
                val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                onDrawBehind {
                    val p = anim.value
                    cells.forEachIndexed { i, cell ->
                        val per = ((p * cells.size) - i).coerceIn(0f, 1f)
                        // per 必须乘到色阶自身的 alpha 上（用 copy(alpha = per) 会覆盖色阶：
                        // 动画结束 per=1 后每格都变成 fg 100% 不透明，h0..h5 全丢 → 24 格同色满柱）
                        drawRoundRect(
                            color = cell.copy(alpha = cell.alpha * per),
                            topLeft = Offset(i * (cw + gap), 0f),
                            size = Size(cw, size.height),
                            cornerRadius = corner,
                        )
                    }
                }
            }
        },
    )
}

/** sparkline：KPI 卡内迷你走势。 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    animated: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val line = color ?: t.chart1
    val anim = chartAnim(values, animated, t.durChartFast)
    Spacer(
        modifier.drawWithCache {
            val pts = normalize(values)
            val stroke = 2.dp.toPx()
            val step = if (pts.size > 1) size.width / (pts.size - 1) else 0f
            onDrawBehind {
                if (pts.size < 2) return@onDrawBehind
                val p = anim.value
                val path = Path()
                val shown = (pts.size * p).toInt().coerceAtLeast(2).coerceAtMost(pts.size)
                for (i in 0 until shown) {
                    val x = step * i
                    val y = size.height * (1f - pts[i])
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        },
    )
}

/** 图表占位高度辅助。 */
@Composable
fun ChartSlot(modifier: Modifier = Modifier, height: Dp? = null, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    Box(modifier.fillMaxWidth().height(height ?: t.chartH)) { content() }
}

/** 图表 x 轴标签行（11px token 字号，避免 Canvas 内文字度量）。 */
@Composable
fun ChartAxisLabels(labels: List<String>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labels.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = t.muted)
        }
    }
}
