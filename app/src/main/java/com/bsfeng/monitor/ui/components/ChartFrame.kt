package com.bsfeng.monitor.ui.components

// 来源：记账「时间单位查询」需求（原型 s04 时间单位查询卡）；职责：带坐标轴刻度与单位标注的图表外壳 ——
// Y 轴刻度值 + X 轴刻度文字 + 右上单位 + 左上标题，内部按 kind 复用手绘柱状 / 面积 / 折线 / 热力基元。
// 只做外壳与刻度布局，不改变既有图表基元的视觉约束（柱状单色、面积峰值标注）。

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
import kotlin.math.floor
import kotlin.math.roundToInt

/** 图表外壳支持的四类图形（与既有手绘基元一一对应）。 */
enum class ChartFrameKind { Bars, Area, Line, Heat }

/** Y 轴网格行数（含 0 与上限）：rows 条 → rows + 1 条网格线。 */
internal const val CHART_Y_TICKS = 4

/**
 * 面积图 / 双线图的 Y 轴标签行数：网格 5 条却只标 0 / ½ / 上限三个值。
 *
 * 原型面积图把 [0, .25, .5, .75, 1] 全画成网格线，但只有 t = 0 / .5 / 1 写刻度文本
 * （99-app.js:653-659）；s07 双线图同样是 3 条网格 + 3 个刻度（s07-统计分析.html:79-94）。
 */
internal const val CHART_Y_LABEL_ROWS = 2

/** 柱状图的 Y 轴标签行数：只标 0 与上限两个值（原型 99-app.js:753-757）。 */
internal const val CHART_BAR_LABEL_ROWS = 1

/** X 轴最多直接标出的刻度数：再多的桶只抽稀标签，不缩字号。 */
internal const val CHART_X_TICKS_MAX = 7

/** Y 轴刻度列宽（给「12,345」这类四位数留位；写死不随字号变）。 */
internal val CHART_Y_LABEL_WIDTH = 34.dp

/**
 * Y 轴上限：把最大值抬到 1 / 2 / 5 × 10^n 的整数档，刻度才整齐（全 0 返回 1，避免除零）。
 *
 * 只有双线图（[dualSeriesAxisMax]）还用它：面积 / 柱 / 折线改成按实际峰值定上限后，
 * 抬档会把「25 元的柱子」压成一半高（真机实测），见 [ChartFrame] 里的 axisMax 注释。
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

/**
 * Y 轴刻度文案：一律取整（原型 99-app.js:657 的 `Math.round(t * mx)`）。
 *
 * 面积图 max = 293 时 ½ 刻度必须写 147，不是 146.5；柱顶 / 峰值直标仍走 [chartValueLabel]
 * 保留真实金额（轴上取整只影响「看得清」，不改直标的原值）。
 * rows = 0 的退化调用给单条上限文案，避免 0/0 得到 NaN 文本。
 */
internal fun chartYTickLabels(max: Float, rows: Int = CHART_Y_TICKS): List<String> {
    if (rows <= 0) return listOf(chartAxisRound(max).toString())
    return (rows downTo 0).map { i -> chartAxisRound(max * i / rows).toString() }
}

/**
 * Y 轴刻度取整：与原型 `Math.round` 同口径（99-app.js:657）。
 *
 * 不能用 kotlin.math.round：它是「.5 取偶」（146.5 → 146），而 JS 的 Math.round 是「.5 进位」
 * （146.5 → 147）。轴刻度只处理非负值，floor(x + 0.5) 就等价于 JS 的 Math.round。
 */
internal fun chartAxisRound(value: Float): Long = floor(value + 0.5f).toLong()

/**
 * X 轴刻度抽稀：桶数 ≤ [maxTicks] 全标；再多时等距取 [maxTicks] 个，末位保留（今天的点位必须可见）。
 * 返回值是「桶序号 to 文案」。
 *
 * @param maxTicks 最多标出的刻度数；默认 [CHART_X_TICKS_MAX]。标签短（如 4 位年份）时调用方可放宽，
 *   否则 9 个年份会被砍成 7 个且间隔忽 1 忽 2（2018/2019/2021/…），读起来像缺数据点。
 */
internal fun chartXLabelsOf(labels: List<String>, maxTicks: Int = CHART_X_TICKS_MAX): List<Pair<Int, String>> {
    if (labels.isEmpty()) return emptyList()
    val ticks = maxTicks.coerceAtLeast(2)
    if (labels.size <= ticks) return labels.mapIndexed { i, s -> i to s }
    val step = (labels.size - 1).toFloat() / (ticks - 1)
    return (0 until ticks).map { i ->
        val idx = (step * i).roundToInt().coerceIn(0, labels.size - 1)
        idx to labels[idx]
    }
}

/**
 * 数值直标口径（原型 data-chart-vlabels，99-app.js:745-747 / 867-868 的三态）：
 * [All] = 每根柱都标、[Peak] = 只标最大值那根、[None] = 不标。
 *
 * 面积图的峰值标注不受它控制：原型 area 的 peak 恒开（99-app.js:674 的 `d.peak !== false`），
 * 所以面积档即使传 [Peak] 也只会在峰值点上方出现一次「峰值 N」。
 */
enum class ChartValueLabels { All, Peak, None }

/**
 * 第 [labelIndex] 个 Y 轴刻度标签压在第几条网格线上（标签只能落在网格线上，不能另起一套均分）。
 *
 * 为什么要这张映射表：网格 rows + 1 条、标签 labelRows + 1 个，两者数量可以不同
 * （面积图 5 条网格只标 3 个值，99-app.js:653-659）。标签按 `labelIndex * rows / labelRows`
 * 落到网格线序号上，再用同一个 [chartRowFraction] 换算 y —— 刻度与网格线因此永远共线（审计 s04 #27）。
 */
internal fun chartLabelRowIndex(labelIndex: Int, rows: Int, labelRows: Int): Int {
    if (rows <= 0 || labelRows <= 0) return 0
    return (labelIndex.toFloat() * rows / labelRows).roundToInt().coerceIn(0, rows)
}

/**
 * X 轴按步长抽稀（原型两套规则，靠 [keepLast] 区分）：
 * - 面积：跳过步长以外的索引、不强制末位 —— 12 桶 step=2 得 1/3/5/7/9/11（99-app.js:661-670）；
 * - 柱：首根 + 每 step 根 + 末根 —— 30 根 step=5 得 1/6/11/16/21/26/30（99-app.js:750-751 注释口径；
 *   同文件 769 行的实现从末位倒推，两者相差一根，这里按注释 + 任务验收的数字）。
 */
internal fun chartXTicksByStep(labels: List<String>, step: Int, keepLast: Boolean): List<Pair<Int, String>> {
    if (labels.isEmpty()) return emptyList()
    val stride = step.coerceAtLeast(1)
    val last = labels.lastIndex
    return labels.indices
        .filter { i -> i == 0 || i % stride == 0 || (keepLast && i == last) }
        .map { it to labels[it] }
}

/**
 * 热力条的 X 轴刻度：标签给的是**格边界**文案（0/6/12/18/24），第 i 个落在
 * `i * count / (labels.size - 1)` 号边界上 —— 原型按总格数均分成四段、位置 i2·(cw+gap)
 * （99-app.js:822-833，24 格 → 0/6/12/18/24、12 格 → 0/3/6/9/12）。
 * 首末刻度由 [ChartXAxis] 夹到两端（原型首 start 左端、末 end 右端）。
 */
internal fun heatXTicks(labels: List<String>, count: Int): List<Pair<Int, String>> {
    if (labels.isEmpty() || count <= 0) return emptyList()
    val segments = (labels.size - 1).coerceAtLeast(1)
    return labels.mapIndexed { i, label -> (i * count / segments) to label }
}

/**
 * X 轴刻度的分派：热力按格边界、调用方给了步长就走原型两套规则、否则沿用等距抽稀。
 */
internal fun chartXTicksFor(
    kind: ChartFrameKind,
    labels: List<String>,
    count: Int,
    step: Int?,
    maxTicks: Int,
): List<Pair<Int, String>> = when {
    kind == ChartFrameKind.Heat -> heatXTicks(labels, count)
    step != null && step > 1 -> chartXTicksByStep(labels, step, keepLast = kind == ChartFrameKind.Bars)
    else -> chartXLabelsOf(labels, maxTicks)
}

/**
 * 带坐标轴的图表外壳（记账五档周期卡、以及任何需要「能看懂纵轴」的图表直接复用）。
 *
 * @param values 序列值（支出 / 收入 / 笔数，按调用方口径）
 * @param xLabels 每桶的 x 轴文案，长度与 values 对齐；超出 [xLabelMax] 自动抽稀
 * @param kind 图形类型：柱 / 面积 / 折线 / 热力
 * @param title 左上说明文案（空则不占行）
 * @param unit 右上单位标注，如「单位：元」（空则不占位）
 * @param yTicks Y 轴**网格**行数（热力条恒为 0：色阶已编码强度，再标金额会误导）
 * @param yLabelRows Y 轴**标签**行数；null 按 kind 取原型口径（面积 2、柱 1、其余 = [yTicks]）
 * @param valueLabels 数值直标口径（原型 data-chart-vlabels），默认 [ChartValueLabels.All]；
 *   全 0 数据一律按 [ChartValueLabels.None] 处理
 * @param height 绘图区高度；null 时按 kind 取默认值（热力 = t.heatH 的 44dp 细带，其余 = t.chartH）
 * @param xTickStep X 轴按步长抽稀（原型面积 / 柱两套规则，见 [chartXTicksByStep]）；null 走 [xLabelMax]
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
    yLabelRows: Int? = null,
    valueLabels: ChartValueLabels = ChartValueLabels.All,
    animated: Boolean = rememberMotionEnabled(),
    height: Dp? = null,
    /** X 轴最多标出的刻度数：短标签（4 位年份）可放宽，默认 [CHART_X_TICKS_MAX]。 */
    xLabelMax: Int = CHART_X_TICKS_MAX,
    xTickStep: Int? = null,
) {
    val t = LocalDesignTokens.current
    // 热力是「一条细带」（原型 s04:193 cellH 44、s07 热力卡同为 heatH）：按 chartH 铺 148dp
    // 会把 24 格拉成 3.4 倍高的竖条，与原型完全不是一张图（审计 s04 #20）。
    val plotHeight = height ?: if (kind == ChartFrameKind.Heat) t.heatH else t.chartH
    // 空数据（全 0 / 空列表）不画 Y 轴刻度：画 0..1 的假刻度会让空卡看起来「有量但很小」
    val hasValues = values.any { it > 0f }
    // 网格行数与标签行数分开：原型面积图网格 5 条却只标 0/½/上限 3 个值（99-app.js:653-659），
    // 柱图 2 条网格 2 个值（99-app.js:753-757）。标签只压网格线，不另起一套均分。
    val yRows = when {
        kind == ChartFrameKind.Heat || !hasValues -> 0
        kind == ChartFrameKind.Bars -> 1
        else -> yTicks.coerceAtLeast(0)
    }
    val labelRows = (yLabelRows ?: when (kind) {
        ChartFrameKind.Bars -> CHART_BAR_LABEL_ROWS
        ChartFrameKind.Area -> CHART_Y_LABEL_ROWS
        else -> yRows
    }).coerceIn(0, yRows)
    // 上限一律取**实际峰值**：原型面积图 mx = maxOf(v)、Y(val) 让峰值顶到最上一条网格线
    // （99-app.js:641,646），柱图同理（99-app.js:741,759）。抬到 1/2/5 档会让「25 元的柱子」
    // 只占一半高、刻度还标出 12.5 这种读不出来的数（真机实测）。
    val rawMax = values.maxOrNull() ?: 0f
    val axisMax = if (rawMax > 0f) rawMax else 1f
    // 全 0 数据不直标：空图上的「0」看着像绘图故障（真机实测）
    val labelMode = if (hasValues) valueLabels else ChartValueLabels.None

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
        // 绘图区顶部留白（柱 22dp / 面积 24dp 给柱顶数值与峰值标注）：网格线、柱高、曲线与
        // Y 轴刻度必须同用这一个区间，否则除 0 线外每个刻度都比网格线低 5~22dp（审计 s04 #27）。
        val plotTopInset = when (kind) {
            ChartFrameKind.Bars -> BAR_PAD_TOP
            ChartFrameKind.Area -> AREA_PAD_TOP
            else -> 0.dp
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (yRows > 0) {
                ChartYAxis(axisMax, yRows, plotHeight, plotTopInset, labelRows = labelRows)
                Spacer(Modifier.width(t.space1))
            }
            Box(Modifier.weight(1f)) {
                ChartPlot(
                    values, kind, labelMode, animated, plotHeight, axisMax,
                    gridRows = yRows.coerceAtLeast(1),
                )
            }
        }
        if (xLabels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = t.space1)) {
                if (yRows > 0) Spacer(Modifier.width(CHART_Y_LABEL_WIDTH + t.space1))
                ChartXAxis(chartXTicksFor(kind, xLabels, values.size, xTickStep, xLabelMax), values.size, kind)
            }
        }
    }
}

/**
 * Y 轴刻度列：值右对齐，每个刻度文字**垂直居中压在对应网格线上**。
 *
 * 为什么不用 Column + SpaceBetween：SpaceBetween 只能把首末文字的边沿贴到容器上下边，
 * 文字自身还有半个行高（≈7dp）的偏移，顶部再叠上 padT 就是 5~22dp 的错位（审计 s04 #27）。
 * 这里按 [chartRowFraction] 与网格线算同一条 y，再减去半个文字高度居中。
 *
 * 顶格不越过标题行（y 夹到 0）；底部刻度允许贴边溢出 —— x 轴刻度行左端是刻度列留白，
 * 那里没有文字，不会遮挡。
 *
 * @param rows 网格行数（rows + 1 条网格线）
 * @param labelRows 标签行数（labelRows + 1 个刻度值）；标签按 [chartLabelRowIndex] 压到对应网格线上。
 *   面积图 rows=4 / labelRows=2 就是原型「5 条网格只写 0/½/上限」（99-app.js:653-659）。
 */
@Composable
internal fun ChartYAxis(max: Float, rows: Int, height: Dp, topInset: Dp = 0.dp, labelRows: Int = rows) {
    val t = LocalDesignTokens.current
    val style = monitorMonoBadge.copy(color = t.muted)
    val labels = chartYTickLabels(max, labelRows.coerceAtLeast(0))
    Layout(
        modifier = Modifier.width(CHART_Y_LABEL_WIDTH).height(height),
        content = {
            labels.forEach { label -> Text(label, style = style, maxLines = 1) }
        },
    ) { measurables, constraints ->
        val measured = measurables.map { it.measure(Constraints()) }
        val columnWidth = constraints.maxWidth
        val columnHeight = constraints.maxHeight
        layout(columnWidth, columnHeight) {
            val topRatio = if (columnHeight > 0) {
                (topInset.toPx() / columnHeight).coerceIn(0f, 1f)
            } else {
                0f
            }
            measured.forEachIndexed { index, placeable ->
                val gridRow = chartLabelRowIndex(index, rows, labelRows)
                val centerY = columnHeight * chartRowFraction(gridRow, rows, topRatio)
                val y = (centerY - placeable.height / 2f).coerceAtLeast(0f).roundToInt()
                placeable.place(columnWidth - placeable.width, y)
            }
        }
    }
}

/**
 * X 轴刻度行：标签按「桶序号占位」真实定位（用 Layout 而不是 weight/Row，
 * 这样 24 小时桶抽稀到 7 个刻度时，刻度仍落在对应柱子上方）。
 *
 * 位置口径与各基元对齐：柱状按「格中心」((i + 0.5) / n)、面积 / 折线按「点」(i / (n - 1))、
 * 热力按「格边界」(i / n)：原型 24 格的刻度是 0/6/12/18/24 五个边界，按格中心摆会整体错半格
 * （99-app.js:822-833 的 i2·(cw+gap)）。首末刻度靠下面的 clamp 贴到两端（原型首 start、末 end）。
 *
 * @param picks 桶序号（热力是边界序号）to 文案
 * @param count 桶总数（决定每个刻度在绘图区内的百分比位置）
 * @param kind 决定刻度落在格中心、数据点还是格边界（与 [ChartPlot] 的分派一致）
 */
@Composable
internal fun ChartXAxis(picks: List<Pair<Int, String>>, count: Int, kind: ChartFrameKind) {
    if (picks.isEmpty()) return
    val t = LocalDesignTokens.current
    fun fractionOf(index: Int): Float = when {
        kind == ChartFrameKind.Heat -> if (count <= 0) 0f else index.toFloat() / count
        kind == ChartFrameKind.Bars -> if (count <= 0) 0.5f else (index + 0.5f) / count
        count <= 1 -> 0.5f
        else -> index.toFloat() / (count - 1)
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
    valueLabels: ChartValueLabels,
    animated: Boolean,
    height: Dp,
    axisMax: Float,
    /** 网格行数：与 Y 轴网格行数同源（柱 1 → 0 与 max 两条线，面积 4 → 5 条，原型口径）。 */
    gridRows: Int = CHART_Y_TICKS,
) {
    // 柱高 / 曲线高度与 Y 轴刻度同源（axisMax）：否则柱顶标 468、刻度却把 468 画在 93% 处
    when (kind) {
        ChartFrameKind.Bars -> BarChart(
            values,
            Modifier.fillMaxWidth().height(height),
            animated = animated,
            showValues = valueLabels != ChartValueLabels.None,
            peakOnly = valueLabels == ChartValueLabels.Peak,
            maxValue = axisMax,
            gridRows = gridRows,
        )
        ChartFrameKind.Area -> AreaChart(
            values,
            Modifier.fillMaxWidth().height(height),
            animated = animated,
            // 原型 area 的 peak 恒开（99-app.js:674 的 d.peak !== false）：口径只管柱图，
            // 但全 0 / 显式 None 时仍不标 —— 空图上的「峰值 0」会被读成绘图故障（真机实测）
            markPeak = valueLabels != ChartValueLabels.None,
            maxValue = axisMax,
            gridRows = gridRows,
        )
        ChartFrameKind.Line -> LineChart(
            values,
            Modifier.fillMaxWidth().height(height),
            animated = animated,
            maxValue = axisMax,
            gridRows = gridRows,
        )
        ChartFrameKind.Heat -> HeatBar(values, Modifier.fillMaxWidth().height(height), animated = animated)
    }
}
