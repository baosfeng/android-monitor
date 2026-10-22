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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.rememberDrawCache
import com.bsfeng.monitor.ui.foundation.scaleDuration
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.heatRamp
import com.bsfeng.monitor.ui.theme.monitorMonoBadge
import kotlin.math.abs
import kotlin.math.round

/**
 * 图表基元：全部自绘 Canvas，零第三方图表/动画库（契约 §6）。
 * 扇形图 / 面积图 / 折线图 / 环形图 / 柱状图 / 热力条 / sparkline / 圆环进度。
 * 绘制时长走 --dur-chart / --dur-chart-fast；只动几何与 opacity。
 *
 * 热路径（J4）：动画值 [Animatable] 只在 **绘制期** 读（drawWithCache 的 build 块在
 * observeReads 作用域内执行，状态失效只触发重绘、不触发重组）；normalize / Path /
 * dashPathEffect 这类每帧重建的对象放进缓存块，仅 values 或 size 变化时重建。
 * 动画时长与曲线仍取 token，观感与改动前一致。
 *
 * 色彩三角色（交接文档 §2.1）：序列色 [DesignTokens.chartSeq] 用于柱状 / 折线 / 面积 /
 * sparkline（跟随主题强调色，同一图表内同色）；分类色板 [DesignTokens.chartRamp] 用于扇形
 * 图分片（跨主题固定）。两者不得混用。
 */

private fun normalize(values: List<Float>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    return values.map { (it - min) / span }
}

/**
 * 柱状图归一化：按**最大值**缩放（不是 min-max）。
 * 柱高必须与柱顶标注的数值同口径（原型 App.chart.bars：bh = (h-padB-padT) * val / maxOf(v)）。
 * min-max 拉伸是给折线 / 面积 / sparkline 用的（强调波动）；用在柱状图上会让最小值那根
 * 柱高为 0 却标着真实数值，自相矛盾。
 */
private fun normalizeByMax(values: List<Float>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val max = values.max().takeIf { it > 0f } ?: 1f
    return values.map { (it / max).coerceIn(0f, 1f) }
}

/**
 * 图表动画进度：返回 [Animatable] 而不是当帧 Float —— 调用点在 drawWithCache 的
 * build 块里读 progress.value，读取落在绘制作用域，动画期间不再逐帧重组（J4）。
 *
 * 时长按动效档位缩放（`ui/foundation/MotionTier.kt` 的 [MotionTier.scaleDuration]）：
 * FULL 全长、NORMAL ×0.75、REDUCED 由 [animated] = false 走「直接落终态」分支。
 */
@Composable
private fun chartAnim(key: Any, animated: Boolean, durMs: Int): Animatable<Float, *> {
    val anim = remember(key) { Animatable(if (animated) 0f else 1f) }
    val tier = rememberMotionTier()
    val scaledDur = tier.scaleDuration(durMs)
    LaunchedEffect(key, animated, scaledDur) {
        if (animated) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(scaledDur))
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

/* ---------- 图表数值直标（原型 .chart-value）的几何常量 ---------- */

/** 柱状图顶部留白：原型 bars 的 padT 由 14 加到 22，给柱顶数值让位。 */
private val BAR_PAD_TOP = 22.dp

/** 面积图顶部留白：原型 area 的 padT 由 14 加到 24，给峰值标注让位。 */
private val AREA_PAD_TOP = 24.dp

/** 面积 Path 在 [rememberDrawCache] 里的 key（同图例只持有一个实例）。 */
private const val AREA_PATH_KEY = "area"

/** sparkline 折线 Path 的缓存 key（每帧重写同一实例，避免每帧 new Path）。 */
private const val SPARK_PATH_KEY = "sparkline"

/** 柱顶数值距柱顶 6px（原型 bars：y = h - padB - bh - 6）。 */
private val BAR_VALUE_OFFSET = 6.dp

/** 峰值标注距峰值点 9px（原型 area：y = Y(peak) - 9）。 */
private val PEAK_LABEL_OFFSET = 9.dp

/** 扇形分片缝隙宽度 2px（原型 .chart-pie .pie-slice{stroke:var(--surface);stroke-width:2}）。 */
private val PIE_SEAM = 2.dp

/**
 * 数值文本：整数去掉小数尾巴（原型把数值原样写进 SVG）。
 * 与 --font-mono / 11px / 600 对齐的样式见 [monitorMonoBadge]（Type.kt，避免屏内硬编码字号）。
 */
private fun chartValueLabel(value: Float): String {
    val rounded = round(value)
    return if (abs(value - rounded) < 0.05f) {
        rounded.toLong().toString()
    } else {
        (round(value * 10f) / 10f).toString()
    }
}

/** 文本左上角：水平以 centerX 居中、垂直落在 topY，并夹在绘制区内避免溢出卡片。 */
private fun TextLayoutResult.centeredAt(centerX: Float, topY: Float, width: Float) = Offset(
    (centerX - size.width / 2f).coerceIn(0f, (width - size.width).coerceAtLeast(0f)),
    topY.coerceAtLeast(0f),
)

/** 折线图：单序列，末端圆点。 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    animated: Boolean = rememberMotionEnabled(),
    showGrid: Boolean = true,
) {
    val t = LocalDesignTokens.current
    // 序列色（--chart-seq）：跟随主题强调色（交接文档 §2.1）
    val line = color ?: t.chartSeq
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

/**
 * 面积图：折线下方面积渐显；峰值点上方直标「峰值 N」（原型 App.chart.area）。
 * 顶部留白 padT = 24dp 容纳标注，曲线绘制在 padT..height 区间。
 */
@Composable
fun AreaChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    animated: Boolean = rememberMotionEnabled(),
    /** 峰值点上方 9px 标「峰值 N」；关掉可省一次文本测量（原型 d.peak !== false）。 */
    markPeak: Boolean = true,
) {
    val t = LocalDesignTokens.current
    // 序列色（--chart-seq）：跟随主题强调色（交接文档 §2.1）
    val line = color ?: t.chartSeq
    val border = t.border
    val peakStyle = monitorMonoBadge.copy(color = t.muted)
    val measurer = rememberTextMeasurer()
    val anim = chartAnim(values, animated, t.durChart)
    // 面积 Path / 渐变 Brush 跨帧复用：尺寸不变时只重写坐标，不再每帧 new。
    val cache = rememberDrawCache()
    Spacer(
        modifier.drawWithCache {
            cache.onSize(size)
            val pts = normalize(values)
            val padT = AREA_PAD_TOP.toPx()
            val plot = (size.height - padT).coerceAtLeast(1f)
            val stroke = 2.dp.toPx()
            val dot = 4.dp.toPx()
            val areaTop = line.copy(alpha = 0.34f)
            val areaBottom = line.copy(alpha = 0f)
            fun xOf(i: Int) = if (pts.size > 1) size.width * i / (pts.size - 1) else size.width / 2f
            fun yOf(v: Float) = padT + plot * (1f - v)
            val linePath = if (pts.size < 2) null else Path().apply {
                pts.forEachIndexed { i, v ->
                    val x = xOf(i)
                    val y = yOf(v)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            val areaPath = if (linePath == null) null else cache.path(AREA_PATH_KEY) {
                addPath(linePath)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            // 渐变只建一次：进度动画交给 drawPath 的 alpha 参数，不再每帧重建 Brush + listOf。
            val areaBrush = cache.brush(line) { Brush.verticalGradient(listOf(areaTop, areaBottom)) }
            // 峰值索引取第一个最大值；文本布局与落点只在 build 块算一次，
            // 绘制期只 drawText（不逐帧 measure / layout，避免滚动掉帧）。
            val peak = if (markPeak && pts.size >= 2) {
                val pi = values.indices.maxByOrNull { values[it] } ?: 0
                val layout = measurer.measure(
                    text = "峰值 " + chartValueLabel(values[pi]),
                    style = peakStyle,
                )
                MeasuredText(
                    layout,
                    layout.centeredAt(
                        centerX = xOf(pi),
                        topY = yOf(pts[pi]) - PEAK_LABEL_OFFSET.toPx() - layout.size.height,
                        width = size.width,
                    ),
                )
            } else {
                null
            }
            onDrawBehind {
                gridLines(border, 4)
                if (areaPath == null || linePath == null) return@onDrawBehind
                val p = anim.value
                drawPath(areaPath, areaBrush, alpha = p)
                drawPath(linePath, line, style = Stroke(width = stroke, cap = StrokeCap.Round))
                drawCircle(line, radius = dot, center = Offset(size.width, yOf(pts.last())))
                if (peak != null) drawText(peak.layout, topLeft = peak.offset)
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

/**
 * 柱状图：底部锚定生长，逐根错峰；柱顶直标数值（原型 App.chart.bars）。
 * **同一图表内所有柱子同色**（序列色 [DesignTokens.chartSeq]，跟随主题强调色）——
 * 原型早期逐柱循环取色会画成「彩虹柱」，交接文档 §2.1 硬性要求 1 明确不允许。
 * 顶部留白 padT = 22dp 容纳数值，柱体绘制在 padT..height 区间。
 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    /** 传多色会破坏「柱状图单色」约束，仅在特殊对照场景使用；默认 null = 序列色单色。 */
    colors: List<Color>? = null,
    animated: Boolean = rememberMotionEnabled(),
    /** 柱顶直标数值（原型 d.vlabels !== false）。 */
    showValues: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val ramp = colors ?: listOf(t.chartSeq)
    val border = t.border
    val valueStyle = monitorMonoBadge.copy(color = t.muted)
    val measurer = rememberTextMeasurer()
    val anim = chartAnim(values, animated, t.durChartFast)
    Spacer(
        modifier.drawWithCache {
            // 柱高按最大值缩放，与柱顶数值同口径（原型 bars 的 val/mx）
            val pts = normalizeByMax(values)
            val padT = BAR_PAD_TOP.toPx()
            val plot = (size.height - padT).coerceAtLeast(1f)
            val gap = size.width * 0.02f
            val bw = if (pts.isEmpty()) 0f else (size.width - gap * (pts.size + 1)) / pts.size
            val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            val valueGap = BAR_VALUE_OFFSET.toPx()
            // 数值文本的测量与落点在 build 块内一次算好，绘制期只 drawText（T3 性能要求）
            val labels = if (!showValues) {
                emptyList()
            } else {
                pts.mapIndexed { i, v ->
                    val layout = measurer.measure(
                        text = chartValueLabel(values.getOrElse(i) { 0f }),
                        style = valueStyle,
                    )
                    val top = size.height - plot * v - valueGap - layout.size.height
                    MeasuredText(layout, layout.centeredAt(gap + i * (bw + gap) + bw / 2f, top, size.width))
                }
            }
            onDrawBehind {
                gridLines(border, 4)
                if (pts.isEmpty()) return@onDrawBehind
                val p = anim.value
                pts.forEachIndexed { i, v ->
                    val per = ((p * pts.size) - i).coerceIn(0f, 1f)
                    val h = plot * v * per
                    drawRoundRect(
                        color = ramp[i % ramp.size],
                        topLeft = Offset(gap + i * (bw + gap), size.height - h),
                        size = Size(bw, h),
                        cornerRadius = corner,
                    )
                }
                labels.forEach { drawText(it.layout, topLeft = it.offset) }
            }
        },
    )
}

/** 已测量的数值文本与其落点（[BarChart] 绘制期直接复用，不再测量）。 */
private class MeasuredText(val layout: TextLayoutResult, val offset: Offset)

/** 热力条：24h 逐格淡入。色阶 = 原型 .h0..h5，越活跃越深（00-shell.html:496-501）。 */
@Composable
fun HeatBar(
    values: List<Float>,
    modifier: Modifier = Modifier,
    levels: Int = 6,
    animated: Boolean = rememberMotionEnabled(),
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
    animated: Boolean = rememberMotionEnabled(),
) {
    val t = LocalDesignTokens.current
    // 序列色（--chart-seq）：跟随主题强调色（交接文档 §2.1）
    val line = color ?: t.chartSeq
    val anim = chartAnim(values, animated, t.durChartFast)
    val cache = rememberDrawCache()
    Spacer(
        modifier.drawWithCache {
            cache.onSize(size)
            val pts = normalize(values)
            val stroke = 2.dp.toPx()
            val step = if (pts.size > 1) size.width / (pts.size - 1) else 0f
            onDrawBehind {
                if (pts.size < 2) return@onDrawBehind
                val p = anim.value
                val shown = (pts.size * p).toInt().coerceAtLeast(2).coerceAtMost(pts.size)
                // 进度每帧变，折线前缀也变：只重写同一 Path 实例（reset + 前缀折线），
                // 不再每帧 new Path()；对象由 rememberDrawCache 跨帧持有。
                val path = cache.path(SPARK_PATH_KEY) {
                    for (i in 0 until shown) {
                        val x = step * i
                        val y = size.height * (1f - pts[i])
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
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
