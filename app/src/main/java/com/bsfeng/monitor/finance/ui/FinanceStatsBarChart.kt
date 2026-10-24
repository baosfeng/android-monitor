package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt 的 GroupedBarChart 与轴常量；职责：近 7 天「支出/收入」双柱图（自绘 Canvas）。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoBadge
import kotlin.math.roundToInt

/** Y 轴刻度文字与绘图区的水平间距（原型 text x = padL - 6）。 */
internal val WEEK_AXIS_TICK_GAP: Dp = 6.dp

/** 图表网格行数 */
internal const val GRID_ROWS = 2

/**
 * 近 7 天分组柱：单序列 [com.bsfeng.monitor.ui.components.BarChart] 只画一根，
 * 这里在屏内把「支出 / 收入」两根一组逐日并排绘制（底部锚定 + 逐日错峰）。
 *
 * 几何对齐原型 bars()（00-shell:6450-6475）：左侧 padL = 30 留给 Y 轴刻度、右侧 padR = 12、
 * 网格线只画在绘图区内；柱宽按原型 rect width="15" / 组宽 ≈42.6（viewBox 单位）取 0.35，
 * 折算到实现里约 14.3dp（≈原型显示宽 327px 时的 14.4px）。
 */
@Composable
internal fun GroupedBarChart(
    values: List<Triple<Float, Float, String>>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val animated = rememberMotionEnabled()
    val progress = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(values, animated) {
        if (animated) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(t.durChartFast, easing = t.easeOut))
        } else {
            progress.snapTo(1f)
        }
    }
    // 语义对优先于分类色板（设计规范 §7.4）：原型 rect.chart-bar.seq-expense = --danger、
    // seq-income = --success；图例必须与柱同色，两处一起改。
    val colors = listOf(t.danger, t.success)
    // 轴文字口径同图表基元：--chart-axis-fs 11px 等宽 + --muted（00-shell:1462）
    val tickStyle = monitorMonoBadge.copy(color = t.muted)
    val measurer = rememberTextMeasurer()
    val maxValue = remember(values) {
        values.maxOfOrNull { maxOf(it.first, it.second) }?.takeIf { it > 0f } ?: 1f
    }
    // 刻度文本只与数据有关 → 组合期量一次；绘制期（含动画逐帧重绘）只算落点，不重复测量
    val tickLayouts = remember(values, tickStyle) {
        (0..GRID_ROWS).map { i ->
            measurer.measure(((GRID_ROWS - i) * maxValue / GRID_ROWS).roundToInt().toString(), tickStyle)
        }
    }
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val dash = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
        val rows = GRID_ROWS
        val padLeft = WEEK_AXIS_PAD_LEFT.toPx()
        val padRight = WEEK_AXIS_PAD_RIGHT.toPx()
        val plotWidth = (size.width - padLeft - padRight).coerceAtLeast(1f)
        for (i in 0..rows) {
            val y = size.height * i / rows
            drawLine(
                t.border,
                Offset(padLeft, y),
                Offset(size.width - padRight, y),
                strokeWidth = 1f,
                pathEffect = dash,
            )
            // Y 轴刻度：自上而下 = 最大值 / 半值 / 0（原型 svg 的 0 / 600 / 1200）
            val layout = tickLayouts[i]
            drawText(
                layout,
                topLeft = Offset(
                    (padLeft - WEEK_AXIS_TICK_GAP.toPx() - layout.size.width).coerceAtLeast(0f),
                    (y - layout.size.height / 2f)
                        .coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f)),
                ),
            )
        }
        val groupWidth = plotWidth / values.size
        val barWidth = groupWidth * 0.35f
        val barGap = groupWidth * 0.06f
        values.forEachIndexed { index, day ->
            val per = ((progress.value * values.size) - index).coerceIn(0f, 1f)
            val startX = padLeft + groupWidth * index + groupWidth / 2f - barWidth - barGap / 2f
            listOf(day.first to colors[0], day.second to colors[1]).forEachIndexed { slot, pair ->
                val height = (size.height - 2f) * (pair.first / maxValue) * per
                if (height > 0f) {
                    drawRoundRect(
                        color = pair.second,
                        topLeft = Offset(startX + slot * (barWidth + barGap), size.height - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                }
            }
        }
    }
}
