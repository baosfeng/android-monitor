package com.bsfeng.monitor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 散点图：每点一个圆；按整体最大值归一化 y，x 按下标等间距落点。
 * 用序列色（chartSeq），随主题强调色。
 */
@Composable
fun ScatterPlot(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    color: Color? = null,
    pointRadius: Int = 3,
    animated: Boolean = rememberMotionEnabled(),
) {
    val t = LocalDesignTokens.current
    val dotColor = color ?: t.chartSeq
    val anim = chartAnim(points, animated, t.durChartFast)
    Spacer(
        modifier.drawWithCache {
            val xs = points.map { it.first }
            val ys = points.map { it.second }
            val minX = xs.minOrNull() ?: 0f
            val maxX = xs.maxOrNull()?.takeIf { it > minX } ?: (minX + 1f)
            val minY = ys.minOrNull() ?: 0f
            val maxY = ys.maxOrNull()?.takeIf { it > minY } ?: (minY + 1f)
            onDrawBehind {
                gridLines(t.border, 4)
                if (points.isEmpty()) return@onDrawBehind
                val p = anim.value
                points.forEach { (x, y) ->
                    val xNorm = ((x - minX) / (maxX - minX)).coerceIn(0f, 1f)
                    val yNorm = ((y - minY) / (maxY - minY)).coerceIn(0f, 1f)
                    val cx = size.width * xNorm
                    val cy = size.height * (1f - yNorm) * p
                    drawCircle(dotColor, radius = pointRadius.dp.toPx(), center = Offset(cx, cy))
                }
            }
        },
    )
}
