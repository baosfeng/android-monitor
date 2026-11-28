package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlin.math.roundToInt

/**
 * 账号展开区的用量柱图（原型 s18 .chartwrap：Y 轴三档 + 96dp 画区 + 最多 30 根柱）。
 *
 * 数据同源：柱高 = 该桶请求数 / 区间峰值；峰值柱用 fg-2、其余用序列色（原型 .bar.is-peak）。
 * 0 值柱保留 4% 最小高度（原型 Math.max(4, ...)）：让「有这条桶」与「没有数据」可区分。
 */

/** 一根柱：高度比例（0.04..1）+ 是否峰值。 */
internal data class QuotaBar(val fraction: Float, val peak: Boolean)

/** 画区高度（原型 .yaxis / .plot 96px，纯布局语义）。 */
internal val QUOTA_CHART_HEIGHT = 96.dp

/** 最多画多少根（原型 m = 30 桶，超过取最近 30 桶）。 */
internal const val QUOTA_CHART_MAX_BARS = 30

/** 柱高序列：最多 30 根，峰值取第一个最大值，最小 4%。 */
internal fun quotaChartBars(values: List<Float>, maxBars: Int = QUOTA_CHART_MAX_BARS): List<QuotaBar> {
    if (values.isEmpty()) return emptyList()
    val window = if (values.size > maxBars) values.takeLast(maxBars) else values
    val peak = window.max()
    if (peak <= 0f) return window.map { QuotaBar(0.04f, false) }
    val peakIndex = window.indexOfFirst { it == peak }
    return window.mapIndexed { index, value ->
        QuotaBar(
            fraction = (value / peak).coerceIn(0f, 1f).coerceAtLeast(0.04f),
            peak = index == peakIndex,
        )
    }
}

/** Y 轴三档文案（原型 JS：max / round(max/2) / 0）。 */
internal fun quotaChartAxis(values: List<Float>): List<String> {
    if (values.isEmpty()) return listOf("0", "0", "0")
    val max = values.max()
    val half = (max / 2f).roundToInt()
    return listOf(formatAxis(max), formatAxis(half.toFloat()), "0")
}

private fun formatAxis(value: Float): String =
    if (value >= 1000f) (value / 1000f).roundToInt().toString() + "k" else value.roundToInt().toString()

/** 柱图本体：左 36dp 轴列 + 画区（虚线网格 0 / 50% / 100% + 底边）。 */
@Composable
internal fun QuotaUsageChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    chartTag: String? = null,
    /** 读屏播报（柱图是纯绘制，没有可读内容）：范围 + 根数 + 峰值。 */
    description: String = "",
) {
    val t = LocalDesignTokens.current
    val bars = quotaChartBars(values)
    val axis = quotaChartAxis(values)
    val accent = t.chartSeq
    val peakColor = t.fg2
    val track = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (description.isNotEmpty()) {
                    Modifier.semantics { stateDescription = description }
                } else {
                    Modifier
                },
            )
            .then(if (chartTag != null) Modifier.testTag(chartTag) else Modifier),
    ) {
        // 轴列：标签中心压在网格线上（顶 / 中 / 底），与画区的三条虚线共线
        Box(Modifier.width(36.dp).height(QUOTA_CHART_HEIGHT)) {
            Text(
                axis[0],
                style = quotaTextSmall,
                color = t.muted,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = (-6).dp),
            )
            Text(axis[1], style = quotaTextSmall, color = t.muted, modifier = Modifier.align(Alignment.CenterEnd))
            Text(
                axis[2],
                style = quotaTextSmall,
                color = t.muted,
                modifier = Modifier.align(Alignment.BottomEnd).offset(y = 6.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(QUOTA_CHART_HEIGHT)
                .drawWithCache {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                    val grid = t.borderSoft
                    val baseline = t.border
                    onDrawBehind {
                        listOf(0f, 0.5f, 1f).forEach { at ->
                            val y = (size.height * at).coerceIn(0.5f, size.height - 0.5f)
                            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
                        }
                        drawLine(baseline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                    }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                bars.forEach { bar ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(bar.fraction)
                            .background(if (bar.peak) peakColor else accent, track),
                    )
                }
            }
        }
    }
}
