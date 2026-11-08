package com.bsfeng.monitor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp

/**
 * 分组柱状图：每组若干柱（series），颜色按分类色板 chartRamp。
 * 适用于同一维度下多系列对比（如不同 provider 或不同访问类型）。
 */
@Composable
fun GroupedBarChart(
    groups: List<List<Float>>,
    modifier: Modifier = Modifier,
    animated: Boolean = rememberMotionEnabled(),
) {
    val t = LocalDesignTokens.current
    val anim = chartAnim(groups, animated, t.durChartFast)
    Spacer(
        modifier.drawWithCache {
            val all = groups.flatMap { it }
            val max = all.maxOrNull()?.takeIf { it > 0f } ?: 1f
            val groupGap = size.width * 0.08f
            val barGap = 2.dp.toPx()
            val groupWidth = (size.width - groupGap * (groups.size + 1)) / groups.size.coerceAtLeast(1)
            val seriesCount = groups.maxOfOrNull { it.size } ?: 0
            val barWidth = if (seriesCount == 0) 0f else (groupWidth - barGap * (seriesCount + 1)) / seriesCount
            onDrawBehind {
                gridLines(t.border, 4)
                if (groups.isEmpty()) return@onDrawBehind
                val p = anim.value
                groups.forEachIndexed { gi, series ->
                    val gx = groupGap * (gi + 1) + groupWidth * gi
                    series.forEachIndexed { si, value ->
                        val color = t.chartRamp.getOrElse(si) { t.chartSeq }
                        val h = size.height * ((value / max).coerceIn(0f, 1f)) * p
                        val x = gx + barGap * (si + 1) + barWidth * si
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, size.height - h),
                            size = Size(barWidth.coerceAtLeast(0f), h),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        )
                    }
                }
            }
        },
    )
}
