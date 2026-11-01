package com.bsfeng.monitor.ui.components

// 从 ui/components/Charts.kt 拆出：走势类图表 —— LineChart（折线）、AreaChart（面积 + 峰值直标）、Sparkline（KPI 卡迷你走势）。负责「随时间波动」的曲线绘制。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.rememberDrawCache
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoBadge

/**
 * 图表基元（原 ui/components/Charts.kt 的文件级约定，随拆分保留）：
 * 全部自绘 Canvas，零第三方图表/动画库（交互口径 §6）；绘制时长走 --dur-chart / --dur-chart-fast，
 * 只动几何与 opacity。
 *
 * 热路径（J4）：动画值 `Animatable` 只在 **绘制期** 读（drawWithCache 的 build 块在
 * observeReads 作用域内执行，状态失效只触发重绘、不触发重组）；Path / dashPathEffect 这类
 * 每帧重建的对象放进缓存块，仅 values 或 size 变化时重建。 *
 * 色彩三角色（交接文档 §2.1）：序列色 [DesignTokens.chartSeq] 用于柱状 / 折线 / 面积 /
 * sparkline（跟随主题强调色，同一图表内同色）；分类色板 [DesignTokens.chartRamp] 用于扇形
 * 图分片（跨主题固定）。两者不得混用。
 */

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
