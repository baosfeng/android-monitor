package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt 的四个图表卡；职责：分类环+图例、近 7 天收支柱、24 小时热力、KPI 行。

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.RingChart
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 三格 KPI 行（支出 / 收入 / 结余）。 */
@Composable
internal fun FinanceStatsKpiRow(stats: FinanceStats, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
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

/** 分类支出环形图 + 图例卡（分片色与图例同源，都走 categoryColorOf）。 */
@Composable
internal fun FinanceStatsRingCard(
    stats: FinanceStats,
    sortedEntries: List<Map.Entry<String, Double>>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4, vertical = t.space3)) {
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
            // 尺寸取原型实测 150×150 —— s04/s06 的环是 104，不能共用同一常量。
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

/** 近 7 天收支双柱卡。 */
@Composable
internal fun FinanceStatsWeekCard(
    week: List<Triple<Float, Float, String>>,
    weekExpense: Double,
    weekIncome: Double,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
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

/** 24 小时消费强度热力卡。 */
@Composable
internal fun FinanceStatsHeatCard(
    hourly: List<Float>,
    peak: List<Int>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4, vertical = t.space3)) {
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
