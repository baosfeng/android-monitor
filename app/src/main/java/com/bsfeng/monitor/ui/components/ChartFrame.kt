package com.bsfeng.monitor.ui.components

// 来源：记账「时间单位查询」需求（原型 s04 时间单位查询卡）；职责：带坐标轴刻度与单位标注的图表外壳 ——
// Y 轴刻度值 + X 轴刻度文字 + 右上单位 + 左上标题，内部按 kind 复用手绘柱状 / 面积 / 折线 / 热力基元。
// 只做外壳与刻度布局，不改变既有图表基元的视觉约束（柱状单色、面积峰值标注）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoBadge
import kotlin.math.roundToInt

/** 图表外壳支持的四类图形（与既有手绘基元一一对应）。 */
enum class ChartFrameKind { Bars, Area, Line, Heat }

/** Y 轴刻度行数（含 0 与上限）：rows 条 → rows + 1 个刻度值。 */
internal const val CHART_Y_TICKS = 4

/** X 轴最多直接标出的刻度数：再多的桶只抽稀标签，不缩字号。 */
internal const val CHART_X_TICKS_MAX = 7

/** Y 轴刻度列宽（给「12,345」这类四位数留位；写死不随字号变）。 */
internal val CHART_Y_LABEL_WIDTH = 34.dp

/**
 * Y 轴上限：把最大值抬到 1 / 2 / 5 × 10^n 的整数档，刻度才整齐（全 0 返回 1，避免除零）。
 */
internal fun chartYAxisMax(maxValue: Float): Float {
    if (!(maxValue > 0f)) return 1f
    var step = 1f
    while (maxValue > step * 5f) step *= 10f
    val nice = when {
        maxValue <= step -> step
        maxValue <= step * 2f -> step * 2f
        maxValue <= step * 5f -> step * 5f
        else -> step * 10f
    }
    return nice
}

/** Y 轴刻度文案：与数值直标同一口径（整数去小数尾巴，见 [chartValueLabel]）。 */
internal fun chartYTickLabels(max: Float, rows: Int = CHART_Y_TICKS): List<String> =
    (rows downTo 0).map { i -> chartValueLabel(max * i / rows) }

/**
 * X 轴刻度抽稀：桶数 ≤ [CHART_X_TICKS_MAX] 全标；再多时等距取 [CHART_X_TICKS_MAX] 个，
 * 末位保留（今天的点位必须可见）。返回值是「桶序号 to 文案」。
 */
internal fun chartXLabelsOf(labels: List<String>): List<Pair<Int, String>> {
    if (labels.isEmpty()) return emptyList()
    if (labels.size <= CHART_X_TICKS_MAX) return labels.mapIndexed { i, s -> i to s }
    val step = (labels.size - 1).toFloat() / (CHART_X_TICKS_MAX - 1)
    return (0 until CHART_X_TICKS_MAX).map { i ->
        val idx = (step * i).roundToInt().coerceIn(0, labels.size - 1)
        idx to labels[idx]
    }
}

/**
 * 带坐标轴的图表外壳（记账五档周期卡、以及任何需要「能看懂纵轴」的图表直接复用）。
 *
 * @param values 序列值（支出 / 收入 / 笔数，按调用方口径）
 * @param xLabels 每桶的 x 轴文案，长度与 values 对齐；超出 [CHART_X_TICKS_MAX] 自动抽稀
 * @param kind 图形类型：柱 / 面积 / 折线 / 热力
 * @param title 左上说明文案（空则不占行）
 * @param unit 右上单位标注，如「单位：元」（空则不占位）
 * @param yTicks Y 轴刻度行数（热力条恒为 0：色阶已编码强度，再标金额会误导）
 * @param showValueLabels 数值直标：柱状画柱顶、面积画峰值；折线与热力忽略
 * @param height 绘图区高度，默认 t.chartH
 */
@Composable
fun ChartFrame(
    values: List<Float>,
    modifier: Modifier = Modifier,
    xLabels: List<String> = emptyList(),
    kind: ChartFrameKind = ChartFrameKind.Bars,
    title: String = "",
    unit: String = "",
    yTicks: Int = CHART_Y_TICKS,
    showValueLabels: Boolean = true,
    animated: Boolean = rememberMotionEnabled(),
    height: Dp? = null,
) {
    val t = LocalDesignTokens.current
    val plotHeight = height ?: t.chartH
    // 空数据（全 0 / 空列表）不画 Y 轴刻度：画 0..1 的假刻度会让空卡看起来「有量但很小」
    val hasValues = values.any { it > 0f }
    val yRows = if (kind == ChartFrameKind.Heat || !hasValues) 0 else yTicks.coerceAtLeast(0)
    val axisMax = chartYAxisMax(values.maxOrNull() ?: 0f)

    Column(modifier.fillMaxWidth()) {
        if (title.isNotEmpty() || unit.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = t.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (title.isNotEmpty()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelSmall,
                            color = t.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (unit.isNotEmpty()) {
                    Text(unit, style = monitorMonoBadge.copy(color = t.meta))
                }
            }
        }
        // Y 轴刻度要落在「柱顶最高处」而不是视图顶部：柱状 / 面积基元各有自己的顶部留白
        // （BAR_PAD_TOP 22dp / AREA_PAD_TOP 24dp），刻度文字若从 0 起排，最大值会显示在柱顶上方一大截。
        val plotTopInset = when (kind) {
            ChartFrameKind.Bars -> BAR_PAD_TOP
            ChartFrameKind.Area -> AREA_PAD_TOP
            else -> 0.dp
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (yRows > 0) {
                ChartYAxis(axisMax, yRows, plotHeight, plotTopInset)
                Spacer(Modifier.width(t.space1))
            }
            Box(Modifier.weight(1f)) {
                ChartPlot(values, kind, showValueLabels, animated, plotHeight, axisMax)
            }
        }
        if (xLabels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = t.space1)) {
                if (yRows > 0) Spacer(Modifier.width(CHART_Y_LABEL_WIDTH + t.space1))
                ChartXAxis(chartXLabelsOf(xLabels), xLabels.size, kind)
            }
        }
    }
}

/** Y 轴刻度列：值右对齐，与各基元自绘的四条网格线重叠。 */
@Composable
internal fun ChartYAxis(max: Float, rows: Int, height: Dp, topInset: Dp = 0.dp) {
    val t = LocalDesignTokens.current
    Column(
        modifier = Modifier.width(CHART_Y_LABEL_WIDTH).height(height).padding(top = topInset),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        chartYTickLabels(max, rows).forEach { label ->
            Text(
                label,
                style = monitorMonoBadge.copy(color = t.muted),
                maxLines = 1,
            )
        }
    }
}

/**
 * X 轴刻度行：标签按「桶序号占位」真实定位（用 Layout 而不是 weight/Row，
 * 这样 24 小时桶抽稀到 7 个刻度时，刻度仍落在对应柱子上方）。
 *
 * 位置口径与各基元对齐：柱状 / 热力按「格中心」((i + 0.5) / n)，面积 / 折线按「点」((i / (n - 1))。
 *
 * @param picks 桶序号 to 文案（[chartXLabelsOf] 的结果）
 * @param count 桶总数（决定每个刻度在绘图区内的百分比位置）
 * @param kind 决定刻度落在格中心还是数据点上（与 [ChartPlot] 的分派一致）
 */
@Composable
internal fun ChartXAxis(picks: List<Pair<Int, String>>, count: Int, kind: ChartFrameKind) {
    if (picks.isEmpty()) return
    val t = LocalDesignTokens.current
    val bandCentered = kind == ChartFrameKind.Bars || kind == ChartFrameKind.Heat
    fun fractionOf(index: Int): Float = if (bandCentered) {
        if (count <= 0) 0.5f else (index + 0.5f) / count
    } else {
        if (count <= 1) 0.5f else index.toFloat() / (count - 1)
    }
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            picks.forEach { (_, label) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = t.muted,
                    maxLines = 1,
                )
            }
        },
    ) { measurables, constraints ->
        val measured = measurables.map { it.measure(Constraints()) }
        val width = constraints.maxWidth
        val rowHeight = measured.maxOfOrNull { it.height } ?: 0
        layout(width, rowHeight) {
            measured.forEachIndexed { i, placeable ->
                val x = (width * fractionOf(picks[i].first) - placeable.width / 2f).roundToInt()
                    .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.place(x, 0)
            }
        }
    }
}

/** 绘图区：按 kind 分派到既有基元，保持「柱状单色 / 面积峰值标注」口径。 */
@Composable
internal fun ChartPlot(
    values: List<Float>,
    kind: ChartFrameKind,
    showValues: Boolean,
    animated: Boolean,
    height: Dp,
    axisMax: Float,
) {
    // 柱高 / 曲线高度与 Y 轴刻度同源（axisMax）：否则柱顶标 468、刻度却把 468 画在 93% 处
    when (kind) {
        ChartFrameKind.Bars -> BarChart(
            values,
            Modifier.fillMaxWidth().height(height),
            animated = animated,
            showValues = showValues,
            maxValue = axisMax,
        )
        ChartFrameKind.Area -> AreaChart(
            values,
            Modifier.fillMaxWidth().height(height),
            animated = animated,
            markPeak = showValues,
            maxValue = axisMax,
        )
        ChartFrameKind.Line -> LineChart(
            values,
            Modifier.fillMaxWidth().height(height),
            animated = animated,
            maxValue = axisMax,
        )
        ChartFrameKind.Heat -> HeatBar(values, Modifier.fillMaxWidth().height(height), animated = animated)
    }
}
