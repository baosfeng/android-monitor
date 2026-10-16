package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.RingChart
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoBadge
import com.bsfeng.monitor.ui.theme.monitorMonoKpi
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/**
 * s07 统计分析。
 *
 * 收支卡 + 分类支出扇形图与图例 + 近 7 天分组柱 + 24 小时消费热力；图表全部走
 * [com.bsfeng.monitor.ui.components] 的 Canvas 基元（占比用 [RingChart]，分组柱由屏内组合）。
 */
@Composable
fun FinanceStatsScreen(
    currentMonth: String,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FinanceViewModel? = null,
    /** 空态 CTA「新增一笔」的目标（原型 s07-empty-add）；未传时空态只显示文案。 */
    onAddClick: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val vm = viewModel ?: rememberFinanceViewModel()
    val emptyFlow = remember { MutableStateFlow<List<Transaction>>(emptyList()) }
    val transactions by (vm?.monthlyTransactions ?: emptyFlow).collectAsState()
    val loading = vm?.loading?.collectAsState()?.value == true

    val control = rememberFinanceStateControl()
    val monthLabel = monthLabelOf(currentMonth)
    val sortedEntries = remember(categoryBreakdown) {
        categoryBreakdown.entries.sortedByDescending { it.value }.take(CATEGORY_LIMIT)
    }
    val week = remember(transactions, currentMonth) { last7DayTotals(transactions, currentMonth) }
    val hourly = remember(transactions) { hourlyCounts(transactions) }
    val peak = remember(hourly) { hourlyPeaks(hourly) }
    val weekExpense = remember(week) { week.sumOf { it.first.toDouble() } }
    val weekIncome = remember(week) { week.sumOf { it.second.toDouble() } }

    val derivedState = when {
        loading && transactions.isEmpty() && categoryBreakdown.isEmpty() -> FiveState.LOADING
        categoryBreakdown.isEmpty() && transactions.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    // EMPTY 只作为演示态：内容仍渲染，空态卡画在列表首位（原型 s07-empty 带「新增一笔」）
    val containerState = if (state == FiveState.EMPTY) FiveState.POPULATED else state

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.backdropA, t.backdropB)))) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space2, vertical = t.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FinanceIconButton(onClick = onBack, contentDescription = "返回") {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = null,
                        tint = t.accent,
                        modifier = Modifier.size(t.iconSize),
                    )
                }
                Text(
                    // 原型 section[data-screen="s07"] data-title="统计分析"（08-prototype.html:4044）
                    "统计分析",
                    style = MaterialTheme.typography.titleLarge,
                    color = t.fg,
                    modifier = Modifier
                        .padding(start = t.space2)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { control.toggleBar() },
                )
            }

            StateContainer(
                state = containerState,
                modifier = Modifier.weight(1f),
                emptyText = "记一笔账或从熊猫记账导入历史数据后，这里会生成分类占比与趋势",
                errorText = "聚合查询超时，图表数据不完整",
                edgeText = "边界态：分类 120 个 / 单月 ¥12,345,678.90 / 缺失分类字段",
                onRetry = { vm?.loadCurrentMonth() },
            ) {
                // 底部避让：为底部 Tab 条留出高度（fab-size 56 + 32），
                // 滚到底时「近 7 天收支」的 x 轴日期与数值刻度不再被 Tab 条裁掉。
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = t.fabSize + t.space8),
                ) {
                    if (state == FiveState.EMPTY) {
                        item(key = "empty") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                                FinanceEmptyState(
                                    title = "本月还没有可统计的支出",
                                    desc = "记一笔账或从熊猫记账导入历史数据后，这里会生成分类占比与趋势",
                                    icon = Icons.Filled.BarChart,
                                    actionText = if (onAddClick != null) "新增一笔" else null,
                                    onAction = onAddClick,
                                )
                            }
                        }
                    }

                    item(key = "month") {
                        // 月份条自带 space4 水平边距（三屏统一 361dp 胶囊）：这里不能再包一层
                        // horizontal padding，否则统计页的胶囊比首页 / 预算页窄两个 16dp。
                        Column {
                            FinanceMonthBar(
                                monthText = monthLabel,
                                onPrevious = onPreviousMonth,
                                onNext = onNextMonth,
                                modifier = Modifier.padding(vertical = t.space2),
                            )
                            Text(
                                monthLabel + " · 共 " + sortedEntries.size + " 个分类",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                                modifier = Modifier.padding(horizontal = t.space4),
                            )
                            Spacer(Modifier.height(t.space3))
                        }
                    }

                    item(key = "kpis") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = t.space4),
                            horizontalArrangement = Arrangement.spacedBy(t.space3),
                        ) {
                            KpiTile(
                                label = "支出",
                                value = yen(animatedAmount(stats.totalExpense.toFloat()).toDouble(), 0),
                                color = t.expenseText,
                                modifier = Modifier.weight(1f),
                            )
                            KpiTile(
                                label = "收入",
                                value = yen(animatedAmount(stats.totalIncome.toFloat()).toDouble(), 0),
                                color = t.incomeText,
                                modifier = Modifier.weight(1f),
                            )
                            KpiTile(
                                label = "结余",
                                value = yen(animatedAmount(stats.balance.toFloat()).toDouble(), 0),
                                color = if (stats.balance >= 0) t.fg else t.expenseText,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    item(key = "ring") {
                        Column(Modifier.padding(horizontal = t.space4, vertical = t.space3)) {
                            AppCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "分类支出",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = t.fg,
                                    )
                                    Text("占比", style = MaterialTheme.typography.labelSmall, color = t.meta)
                                }
                                Spacer(Modifier.height(t.space3))
                                // 分片色与下方图例**同源**：按分类名走 categoryColorOf
                                // （= categoryDot(categorySlotOf(name))），不用图表基元默认的「分片下标取色」，
                                // 否则同一分类在图例与扇形上会落到不同色槽（购物图例 chart-3、分片却 chart-1）。
                                val sliceColors = sortedEntries.map { categoryColorOf(it.key) }
                                // 占比用**环形**图：原型 s07 虽是 data-chart="pie" 标注，但运行时由
                                // ring() 渲染（08-prototype.html:3455 → :3408 size:132 thickness:16，
                                // 外层 .s07-ringwrap 150×150），环心才是 .s07-ringctr 的落点。
                                // 尺寸取原型实测 150×150 —— s04/s06 的环是 104，不能共用 FINANCE_RING_DIAMETER。
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    RingChart(
                                        segments = sortedEntries.map { it.value.toFloat() },
                                        colors = sliceColors,
                                        size = STATS_RING_DIAMETER,
                                        strokeWidth = STATS_RING_STROKE,
                                    )
                                    // 环心：¥总额（--text-lg 21px/700）+ 总支出（--text-sm 14px muted）
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(STATS_RING_CENTER_GAP),
                                    ) {
                                        Text(
                                            yen(stats.totalExpense, 0),
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = t.fg,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text("总支出", style = MaterialTheme.typography.bodySmall, color = t.muted)
                                    }
                                }
                                Spacer(Modifier.height(t.space3))
                                Legend(
                                    items = sortedEntries.mapIndexed { index, entry ->
                                        LegendItem(
                                            label = entry.key,
                                            // 原型 s07:4055-4060 的 .legend-num 只有金额（¥686），不带百分比后缀
                                            value = yen(entry.value, 0),
                                            // 与 s04 / s05 同一个分类色口径，且与上面的扇形分片共用同一份取色结果
                                            color = sliceColors[index],
                                        )
                                    },
                                )
                            }
                        }
                    }

                    item(key = "week") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            AppCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "近 7 天收支",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = t.fg,
                                    )
                                    Text("单位 ¥", style = MaterialTheme.typography.labelSmall, color = t.meta)
                                }
                                Spacer(Modifier.height(t.space3))
                                GroupedBarChart(
                                    values = week,
                                    modifier = Modifier.fillMaxWidth().height(t.chartH),
                                )
                                // X 轴日期与柱区左右边界对齐（跳掉左侧 Y 轴刻度列与右侧留白）
                                ChartAxisLabels(
                                    week.map { dayLabelOf(it.third) },
                                    modifier = Modifier.padding(
                                        start = WEEK_AXIS_PAD_LEFT,
                                        end = WEEK_AXIS_PAD_RIGHT,
                                    ),
                                )
                                Spacer(Modifier.height(t.space3))
                                Legend(
                                    items = listOf(
                                        LegendItem("支出", "7 天 " + yen(weekExpense, 0), t.danger),
                                        LegendItem("收入", "7 天 " + yen(weekIncome, 0), t.success),
                                    ),
                                )
                            }
                        }
                    }

                    item(key = "heat") {
                        Column(Modifier.padding(horizontal = t.space4, vertical = t.space3)) {
                            AppCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "24 小时消费强度",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = t.fg,
                                    )
                                    Text("笔 / 小时", style = MaterialTheme.typography.labelSmall, color = t.meta)
                                }
                                Spacer(Modifier.height(t.space3))
                                HeatBar(
                                    values = hourly,
                                    modifier = Modifier.fillMaxWidth().height(t.heatH),
                                )
                                ChartAxisLabels(listOf("00", "04", "08", "12", "16", "20"))
                                Spacer(Modifier.height(t.space3))
                                Text(
                                    peakText(peak),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.meta,
                                )
                            }
                        }
                    }

                }
            }
        }

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )
    }
}

/**
 * 统计页 KPI 瓦片：原型 `.kpi` 实测 surface 底 + 1px --border-soft + radius-md(12px)、
 * padding 12px、标签与数值 gap 2px（00-shell:1284-1288）。
 * 卡片基元 AppCard 的圆角固定 radius-lg(18dp) 且不开放覆写，故此处按 `.kpi` 自绘。
 */
@Composable
private fun KpiTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space3),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(KPI_LABEL_GAP))
        Text(
            value,
            // 原型 .s07-kv（s07-stats.html:9）：--text-lg 等宽 700，逐位对齐
            style = monitorMonoKpi,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * 近 7 天分组柱：单序列 [com.bsfeng.monitor.ui.components.BarChart] 只画一根，
 * 这里在屏内把「支出 / 收入」两根一组逐日并排绘制（底部锚定 + 逐日错峰）。
 *
 * 几何对齐原型 bars()（00-shell:6450-6475）：左侧 padL = 30 留给 Y 轴刻度、右侧 padR = 12、
 * 网格线只画在绘图区内；柱宽按原型 rect width="15" / 组宽 ≈42.6（viewBox 单位）取 0.35，
 * 折算到实现里约 14.3dp（≈原型显示宽 327px 时的 14.4px）。
 */
@Composable
private fun GroupedBarChart(
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

/** 日期标签：2026-10-08 → 08。 */
private fun dayLabelOf(date: String): String = date.takeLast(2)

/**
 * 近 7 天（含当月为当前月时的今天 / 非当前月时的月末）的支出与收入。
 * 每项为 Triple(支出, 收入, yyyy-MM-dd)。
 */
private fun last7DayTotals(
    transactions: List<Transaction>,
    yearMonth: String,
): List<Triple<Float, Float, String>> {
    val today = LocalDate.now()
    val anchor = if (yearMonth == FinanceViewModel.currentMonthStr()) {
        today
    } else {
        val parts = yearMonth.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: today.year
        val month = parts.getOrNull(1)?.toIntOrNull() ?: today.monthValue
        LocalDate.of(year, month, 1).withDayOfMonth(
            LocalDate.of(year, month, 1).lengthOfMonth()
        )
    }
    val byDate = transactions.groupBy { it.date }
    return (6 downTo 0).map { offset ->
        val day = anchor.minusDays(offset.toLong())
        val key = day.toString()
        var expense = 0f
        var income = 0f
        byDate[key]?.forEach { tx ->
            val magnitude = if (tx.isRefundEntry) tx.amount else kotlin.math.abs(tx.amount)
            if (tx.isExpense) expense += magnitude.toFloat() else if (tx.isIncome) income += magnitude.toFloat()
        }
        Triple(expense, income, key)
    }
}

/** 24 小时笔数分布（取 dateTime 的小时位）。 */
private fun hourlyCounts(transactions: List<Transaction>): List<Float> {
    val buckets = MutableList(HOURS) { 0f }
    transactions.forEach { tx ->
        val hour = tx.dateTime.substringAfter(' ', "").take(2).toIntOrNull()
        if (hour != null && hour in 0 until HOURS) buckets[hour] += 1f
    }
    return buckets
}

/** 峰值小时（最多两个）。 */
private fun hourlyPeaks(hourly: List<Float>): List<Int> =
    hourly.withIndex()
        .filter { it.value > 0f }
        .sortedByDescending { it.value }
        .take(2)
        .map { it.index }

private fun peakText(peaks: List<Int>): String {
    if (peaks.isEmpty()) return "本月还没有交易记录，热力图为空。"
    val parts = peaks.map { hour ->
        val label = if (hour < 10) "0" + hour + ":00" else hour.toString() + ":00"
        label
    }
    return "峰值出现在 " + parts.joinToString(" 与 ") + "；柱高按当月最大值归一。"
}

/**
 * 统计页分类环直径：原型 `.s07-ringwrap` 实测 150×150（00-shell:2045）。
 * s04 / s06 的环是 104（FinanceUIKit 的 FINANCE_RING_DIAMETER），三者不可共用。
 */
private val STATS_RING_DIAMETER = 150.dp

/**
 * 统计页分类环的描边宽度。
 *
 * 原型 `#s07-ring` 的扇片是 `path.pie-slice`，路径 `M64 64 L64 1 A63 63 0 0 1 126.03 75.01 Z`
 * —— 起点落在圆心、内径 0，即**实心饼**，外径 147.7px（viewBox 128 渲染到 150px）。
 * 取 36dp（Lead 拍板）：环宽占半径 48%、孔径 150−72 = 78dp，中心文案约 53×44dp 的对角线
 * ≈75dp 仍在孔内，色块占比由 18dp 时的约 44% 提到约 75%，观感接近原型的厚环 + 中心白圆。
 */
private val STATS_RING_STROKE = 36.dp

/** 统计页 KPI 瓦片的标签与数值间距（原型 .kpi 的 gap:2px，00-shell:1285）。 */
private val KPI_LABEL_GAP = 2.dp

/** 环心「¥总额 / 总支出」的行间距（原型 .s07-ringctr 的 row-gap:2px）。 */
private val STATS_RING_CENTER_GAP = 2.dp

/** 近 7 天柱图左侧留白：容纳 Y 轴刻度文字（原型 bars 的 padL = 30，00-shell:6453）。 */
private val WEEK_AXIS_PAD_LEFT = 30.dp

/** 近 7 天柱图右侧留白（原型 bars 的 padR = 12）。 */
private val WEEK_AXIS_PAD_RIGHT = 12.dp

/** Y 轴刻度文字与绘图区的水平间距（原型 text x = padL - 6）。 */
private val WEEK_AXIS_TICK_GAP = 6.dp

/** 图表网格行数 */
private const val GRID_ROWS = 2

/** 24 小时 */
private const val HOURS = 24

/** 环形图与图例最多展示的分类数 */
private const val CATEGORY_LIMIT = 8
