package com.bsfeng.monitor.finance.ui

// 来源：原型 s04 统计汇总 pane 的三张图表卡（.open-design/src/screens/s04-记账.html:273-288）：
// 近 7 天收支柱、年度趋势面积、分类占比环。职责：把三张卡组装出来并统一走 ChartFrame 外壳，
// 让每张图都有 Y 轴刻度、X 轴刻度文字与数值直标（用户口径：图表不能「光秃秃」）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ChartFrame
import com.bsfeng.monitor.ui.components.ChartFrameKind
import com.bsfeng.monitor.ui.components.RingChart
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 分类环直径（与 s07 统计分析页同一口径，避免同一种环在两屏不一样大）。 */
internal val FINANCE_STATS_RING_SIZE = STATS_RING_DIAMETER

/** 分类环描边宽度（同 s07）。 */
internal val FINANCE_STATS_RING_STROKE = STATS_RING_STROKE

/** 环内最多展示的分类数（与 s07 的 CATEGORY_LIMIT 同口径）。 */
internal const val FINANCE_RING_CATEGORY_LIMIT = CATEGORY_LIMIT

/**
 * 统计汇总 pane 的三张图表卡（近 7 天 / 年度趋势 / 分类占比）。
 *
 * 近 7 天与年度趋势直接吃五档结果里的 WEEK / YEAR（与上方 unitbar 同一份数据，口径不会互相打架）；
 * 分类占比沿用当月分类结果（环图编码的是「分类身份」，不是时间序列）。
 *
 * @param weekStats 本周统计（null = 还没取到：卡片整张不出现，而不是画一张空图）
 * @param yearStats 本年统计
 * @param categoryBreakdown 当月分类支出（按月口径，与分类环的图例同源）
 */
@Composable
internal fun FinanceStatsChartsSection(
    weekStats: PeriodStats?,
    yearStats: PeriodStats?,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    modifier: Modifier = Modifier,
) {
    // 环图的分片与图例同源（categoryColorOf），排序在此处一次算定，不随重组重排
    val t = LocalDesignTokens.current
    Column(modifier.padding(vertical = t.space2)) {
        if (weekStats != null) {
            FinanceChartCard(
                title = "近 7 天收支",
                // 审计 P2-6：原型三图卡 meta 带口径 + 合计金额，缺了它这张卡只有标题没有量。
                meta = chartMetaOf("本周", weekStats.buckets.sumOf { it.expense }),
                modifier = Modifier.testTag("s04-card-week7"),
            ) {
                ChartFrame(
                    values = weekStats.expenseValues,
                    xLabels = weekStats.labels,
                    kind = ChartFrameKind.Bars,
                    title = "本周每日支出",
                    unit = "单位：元",
                    modifier = Modifier.testTag("s04-chart-week7"),
                )
            }
        }
        if (yearStats != null) {
            Spacer(Modifier.height(t.space3))
            FinanceChartCard(
                title = "年度趋势",
                meta = chartMetaOf("12 个月", yearStats.buckets.sumOf { it.expense }),
                modifier = Modifier.testTag("s04-card-year12"),
            ) {
                ChartFrame(
                    values = yearStats.expenseValues,
                    xLabels = yearStats.labels,
                    kind = ChartFrameKind.Area,
                    title = "年度支出走势",
                    unit = "单位：元",
                    modifier = Modifier.testTag("s04-chart-year12"),
                )
            }
        }
        Spacer(Modifier.height(t.space3))
        FinanceRingSummaryCard(
            stats = stats,
            categoryBreakdown = categoryBreakdown,
            modifier = Modifier.testTag("s04-card-pie"),
        )
    }
}

/** 单张图表卡外壳：标题 + 右侧口径标注 + 内容（内容自身已带坐标轴，这里不再画第二套）。 */
@Composable
private fun FinanceChartCard(
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier.padding(horizontal = t.space4).fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = t.fg)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = t.meta)
        }
        Spacer(Modifier.height(t.space3))
        content()
    }
}

/**
 * 分类占比卡：环图 + 环心总额 + 三行图例（原型 284-286 只画环，图例补足「哪个色块是哪个分类」）。
 *
 * 分片色与图例同源（[categoryColorOf]），不用图表基元默认的下标取色，
 * 否则同一分类在本页与统计分析页会变成两种颜色。
 */
@Composable
private fun FinanceRingSummaryCard(
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val entries = remember(categoryBreakdown) {
        categoryBreakdown.entries.sortedByDescending { it.value }.take(FINANCE_RING_CATEGORY_LIMIT)
    }
    AppCard(modifier = modifier.padding(horizontal = t.space4).fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分类占比", style = MaterialTheme.typography.headlineSmall, color = t.fg)
            Text(
                chartMetaOf("本月", stats.totalExpense),
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
        Spacer(Modifier.height(t.space3))
        if (entries.isEmpty()) {
            Text(
                "本月还没有可统计的分类支出",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
            return@AppCard
        }
        val colors = entries.map { categoryColorOf(it.key) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RingChart(
                segments = entries.map { it.value.toFloat() },
                colors = colors,
                size = FINANCE_STATS_RING_SIZE,
                strokeWidth = FINANCE_STATS_RING_STROKE,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        entries.take(FINANCE_RING_LEGEND_ROWS).forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = t.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(colors[index])
                Spacer(Modifier.height(t.space1))
                Text(
                    entry.key,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = t.space2),
                )
                Text(yen(entry.value, 0), style = MaterialTheme.typography.bodySmall, color = t.meta)
            }
        }
    }
}

/** 图例最多行数：再多的分类交给「统计分析」页的完整图例，本卡只做概览。 */
internal const val FINANCE_RING_LEGEND_ROWS: Int = 4

/**
 * 图表卡右上口径文案：「本周 · 合计 ¥832」。
 *
 * 审计 P2-6：原型三图卡的 meta 带合计金额，实现只写了时间跨度，读者只能看到图形、读不到量。
 * 金额与图本身同源（同一条 [com.bsfeng.monitor.finance.model.PeriodStats] 的分桶 / 当月支出），
 * 所以「图上看到的柱总和」与这行文字永远一致，不会出现两套口径。
 *
 * 纯函数（无 @Composable），可直接 JVM 单测。
 */
internal fun chartMetaOf(span: String, total: Double): String = span + " · 合计 " + yen(total, 0)