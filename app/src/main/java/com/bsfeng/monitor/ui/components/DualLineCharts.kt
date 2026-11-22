package com.bsfeng.monitor.ui.components

// 来源：原型 s07「年度趋势」的双线（.open-design/src/screens/s07-统计分析.html:76-100）；
// 职责：双序列折线外壳 —— 一个坐标系画「支出 / 收入」两条线，带 Y 轴刻度、X 轴刻度与右上单位，
// 网格线与轴刻度的几何全部复用 ChartFrame 的基元（[ChartYAxis] / [ChartXAxis] / [gridLines]）。
// 为什么不给 ChartFrame 加参数：ChartFrame 的 kind 分派只喂单序列，而双线要求「两条线共用同一个
// Y 轴上限」；往单序列外壳里塞第二组参数，会让既有调用点也背上与它们无关的概念。

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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.rememberDrawCache
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoBadge

/**
 * 双序列共用的 Y 轴上限：先取两条序列的全局最大值，再抬到 1 / 2 / 5 × 10^n 的整齐档。
 *
 * 必须共用而不是各画各的轴：两条线在同一坐标系里比较大小，轴一分开「支出比收入高」这种
 * 一眼可得的结论就失真了。纯函数（无 @Composable），记账侧单测直接引用。
 */
internal fun dualSeriesAxisMax(primary: List<Float>, secondary: List<Float>): Float =
    chartYAxisMax(maxOf(primary.maxOrNull() ?: 0f, secondary.maxOrNull() ?: 0f))

/** 双线在 [rememberDrawCache] 里的 Path key（两条线各持一个实例，跨帧复用）。 */
private const val DUAL_PRIMARY_PATH_KEY = "dual-primary"
private const val DUAL_SECONDARY_PATH_KEY = "dual-secondary"

/**
 * 双线图顶部留白（24dp，与 [AREA_PAD_TOP] 同值）：给 Y 轴首刻度的字顶让位，
 * 也让网格线与刻度共用一个「留白..底边」的区间（原型 s07:79-94 的 padT 是 36）。
 */
private val DUAL_LINE_PAD_TOP = AREA_PAD_TOP

/**
 * 双序列折线图外壳（s07 年度趋势：支出 + 收入，12 个月）。
 *
 * @param primary 主序列（支出），长度应与 [secondary] 一致
 * @param secondary 次序列（收入）
 * @param xLabels 每桶的 X 轴文案（1..12），超出 [CHART_X_TICKS_MAX] 自动抽稀
 * @param xTickIndices 调用方指定的刻度桶下标（如原型固定 1/3/5/7/9/12）；为空或全部越界时走通用抽稀
 * @param primaryColor 主序列线色；null 取语义色 expense-text
 * @param secondaryColor 次序列线色；null 取语义色 income-text
 * @param contentDescription 读屏名（图表本身是纯绘制，没有它读屏只会说「图片」）
 * @param yTicks Y 轴网格行数，默认 2 → 3 条网格线、3 个刻度（0 / ½上限 / 上限），与原型
 *   s07-统计分析.html:79-94 的 y=132/84/36 三条网格 + 0/1200/2400 三个刻度同口径；
 *   两条序列全为 0 时不画刻度列（假刻度会让空图看着「有量但很小」）
 */
@Composable
fun DualLineChartFrame(
    primary: List<Float>,
    secondary: List<Float>,
    modifier: Modifier = Modifier,
    xLabels: List<String> = emptyList(),
    xTickIndices: List<Int>? = null,
    primaryColor: Color? = null,
    secondaryColor: Color? = null,
    title: String = "",
    unit: String = "",
    yTicks: Int = CHART_Y_LABEL_ROWS,
    animated: Boolean = rememberMotionEnabled(),
    height: Dp? = null,
    contentDescription: String? = null,
) {
    val t = LocalDesignTokens.current
    val plotHeight = height ?: t.chartH
    val lineA = primaryColor ?: t.expenseText
    val lineB = secondaryColor ?: t.incomeText
    val hasValues = primary.any { it > 0f } || secondary.any { it > 0f }
    val yRows = if (hasValues) yTicks.coerceAtLeast(0) else 0
    val axisMax = dualSeriesAxisMax(primary, secondary)

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
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (yRows > 0) {
                // 与折线区的顶部留白同源：首刻度落在最高点那一行，不是画布顶（审计 s04 #27 同一口径）
                ChartYAxis(axisMax, yRows, plotHeight, DUAL_LINE_PAD_TOP, labelRows = yRows)
                Spacer(Modifier.width(t.space1))
            }
            Box(Modifier.weight(1f)) {
                DualLinePlot(
                    primary = primary,
                    secondary = secondary,
                    primaryColor = lineA,
                    secondaryColor = lineB,
                    axisMax = axisMax,
                    animated = animated,
                    height = plotHeight,
                    gridRows = yRows.coerceAtLeast(1),
                    a11yLabel = contentDescription,
                )
            }
        }
        if (xLabels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = t.space1)) {
                if (yRows > 0) Spacer(Modifier.width(CHART_Y_LABEL_WIDTH + t.space1))
                ChartXAxis(dualLineXTicks(xLabels, xTickIndices), xLabels.size, ChartFrameKind.Line)
            }
        }
    }
}

/**
 * 绘图区：网格 + 两条折线（共用 [axisMax]）。
 *
 * 绘制约束与其他图表基元一致：Path 由 [rememberDrawCache] 按 key 复用，绘制期只重写坐标；
 * 入场进度 [chartAnim] 只在绘制作用域里读，动画期间不重组。
 */
@Composable
private fun DualLinePlot(
    primary: List<Float>,
    secondary: List<Float>,
    primaryColor: Color,
    secondaryColor: Color,
    axisMax: Float,
    animated: Boolean,
    height: Dp,
    /** 网格行数：与 Y 轴刻度同源（2 → 3 条，原型 s07:79-94）。 */
    gridRows: Int,
    a11yLabel: String?,
) {
    val t = LocalDesignTokens.current
    val border = t.border
    val anim = chartAnim(primary to secondary, animated, t.durChart)
    val cache = rememberDrawCache()
    val stroke = 2.dp
    // 读屏名单独入参：函数参数也叫 contentDescription 时，semantics 里的同名字段会互相遮蔽
    val a11y = if (a11yLabel == null) {
        Modifier
    } else {
        Modifier.semantics { contentDescription = a11yLabel }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .then(a11y)
            .drawWithCache {
                cache.onSize(size)
                val ceiling = axisMax.takeIf { it > 0f } ?: 1f
                val strokePx = stroke.toPx()
                // 绘图区 = padT..height（与网格线、Y 轴刻度同一区间）
                val padT = DUAL_LINE_PAD_TOP.toPx()
                val plot = (size.height - padT).coerceAtLeast(1f)
                fun pointsOf(values: List<Float>): List<Offset> {
                    if (values.size < 2) return emptyList()
                    val step = size.width / (values.size - 1)
                    return values.mapIndexed { i, v ->
                        Offset(step * i, padT + plot * (1f - (v / ceiling).coerceIn(0f, 1f)))
                    }
                }
                val primaryPoints = pointsOf(primary)
                val secondaryPoints = pointsOf(secondary)
                // 全 0 的那条不画：一条贴着基线的 0 线会被读成「有数据，只是都接近 0」
                val showPrimary = primary.any { it > 0f }
                val showSecondary = secondary.any { it > 0f }
                onDrawBehind {
                    gridLines(border, gridRows, padT)
                    val progress = anim.value
                    fun drawSeries(key: String, points: List<Offset>, color: Color, present: Boolean) {
                        if (!present || points.size < 2) return
                        // 逐步揭示（与 sparkline 同一写法）：每帧重写同一 Path 实例，不每帧 new
                        val shown = (points.size * progress).toInt()
                            .coerceAtLeast(2)
                            .coerceAtMost(points.size)
                        val path = cache.path(key) {
                            for (i in 0 until shown) {
                                if (i == 0) moveTo(points[i].x, points[i].y) else lineTo(points[i].x, points[i].y)
                            }
                        }
                        drawPath(path, color, style = Stroke(width = strokePx, cap = StrokeCap.Round))
                    }
                    drawSeries(DUAL_PRIMARY_PATH_KEY, primaryPoints, primaryColor, showPrimary)
                    drawSeries(DUAL_SECONDARY_PATH_KEY, secondaryPoints, secondaryColor, showSecondary)
                }
            },
    )
}

/**
 * 双线图 X 轴刻度：调用方给了 [indices] 就用它（只保留落在范围内的），否则走通用抽稀。
 *
 * 为什么允许自定义：原型 s07 年度趋势手摆 1/3/5/7/9/12 六个刻度；通用抽稀在 12 桶时
 * 给的是 1,3,5,7,8,10,12 —— 7 与 8 只隔一桶，两段文字贴在一起（真机实测）。
 */
internal fun dualLineXTicks(labels: List<String>, indices: List<Int>?): List<Pair<Int, String>> {
    val custom = indices.orEmpty()
        .filter { it in labels.indices }
        .distinct()
        .sorted()
        .map { it to labels[it] }
    return custom.ifEmpty { chartXLabelsOf(labels) }
}
