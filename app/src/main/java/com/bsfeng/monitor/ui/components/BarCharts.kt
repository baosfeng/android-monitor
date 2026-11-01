package com.bsfeng.monitor.ui.components

// 从 ui/components/Charts.kt 拆出：条形类图表 —— BarChart（柱状 + 柱顶数值）、HeatBar（24h 热力色阶）。负责「按柱 / 按格比较大小」的绘制。

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.heatRamp
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
