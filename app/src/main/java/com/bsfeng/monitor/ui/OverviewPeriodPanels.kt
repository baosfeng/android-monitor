package com.bsfeng.monitor.ui

// 从 ui/OverviewScreen.kt 拆出：概览「近 7 天 / 近 30 天」面板 —— weekPanel / monthPanel 及其柱状图、环比文案。负责三段面板里的后两段。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.LiveKpiCard
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.TypeIconDot
import com.bsfeng.monitor.ui.live.formatHoursMinutes
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import java.util.Locale

internal fun LazyListScope.weekPanel(state: OverviewState) {
    item(key = "kpi-week-events") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "事件",
                unit = "条",
                delta = "日均 " + (state.weekEventCount / 7) + " 条",
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(0),
            ) { KpiValue(state.weekEventCount) }
            LiveKpiCard(
                label = "活跃",
                delta = "日均 " + formatHoursMinutes(state.weekActiveMinutes / 7),
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(1),
            ) { KpiActive(state.weekActiveMinutes) }
        }
    }
    item(key = "kpi-week-skip") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "跳过广告",
                unit = "次",
                delta = "日均 " + String.format(Locale.US, "%.1f", state.weekSkipCount / 7.0) + " 次",
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(2),
            ) { KpiValue(state.weekSkipCount) }
            LiveKpiCard(
                label = "支出",
                delta = "收入 ¥" + money(state.weekIncome) + " · 结余 ¥" + money(state.weekIncome - state.weekExpense),
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(3),
            ) { KpiMoney(state.weekExpense) }
        }
    }

    item(key = "card-7d-bars") {
        val t = LocalDesignTokens.current
        val labels = remember { dayLabels(7) }
        AppCard(modifier = Modifier.staggerEnterAt(4)) {
            CardHead("近 7 天每日事件量", labels.first() + " – " + labels.last())
            Spacer(Modifier.height(t.space3))
            BarChart(
                values = state.dailyEventCounts7,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.chartH),
            )
            ChartAxisLabels(labels)
        }
    }

    item(key = "card-top7d") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.staggerEnterAt(5)) {
            CardHead("本周跳过最多", "共 " + state.weekSkipCount + " 次")
            Spacer(Modifier.height(t.space1))
            if (state.topSkippedApps.isEmpty()) {
                MetaText("本周还没有跳过记录")
            } else {
                state.topSkippedApps.forEachIndexed { index, slice ->
                    if (index > 0) HorizontalDivider(color = t.borderSoft)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = t.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeIconDot(RecordType.APP_USAGE)
                        Spacer(Modifier.width(t.space3))
                        Text(
                            text = slice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.fg,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = slice.count.toString() + " 次",
                            style = MaterialTheme.typography.labelMedium,
                            color = t.muted,
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────────── 近 30 天 ─────────────────────────────

internal fun LazyListScope.monthPanel(state: OverviewState) {
    item(key = "kpi-month-events") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "事件",
                unit = "条",
                delta = "日均 " + (state.monthEventCount / 30) + " 条",
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(0),
            ) { KpiValue(state.monthEventCount) }
            LiveKpiCard(
                label = "活跃",
                delta = "日均 " + formatHoursMinutes(state.monthActiveMinutes / 30),
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(1),
            ) { KpiValue((state.monthActiveMinutes / 60).toInt()) }
        }
    }
    item(key = "kpi-month-skip") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "跳过广告",
                unit = "次",
                delta = "日均 " + String.format(Locale.US, "%.1f", state.monthSkipCount / 30.0) + " 次",
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(2),
            ) { KpiValue(state.monthSkipCount) }
            LiveKpiCard(
                label = "支出",
                delta = "收入 ¥" + money(state.monthIncome) + " · 结余 ¥" + money(state.monthIncome - state.monthExpense),
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(3),
            ) { KpiMoney(state.monthExpense) }
        }
    }

    item(key = "card-30d-trend") {
        val t = LocalDesignTokens.current
        val labels = remember { dayLabels(30) }
        AppCard(modifier = Modifier.staggerEnterAt(4)) {
            CardHead("近 30 天活跃趋势", "单位 分钟")
            Spacer(Modifier.height(t.space3))
            // 与 24h 同口径：原型 s01-chart30d（2550-2554）同样是 area 且未传 peak 关闭标记
            // → 引擎默认标注「峰值 372」（08-prototype.html:5839）。
            AreaChart(
                values = state.dailyActiveMinutes30,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.chartH),
            )
            ChartAxisLabels(monthTicks(labels))
        }
    }

    item(key = "card-30d-ring") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.staggerEnterAt(5)) {
            CardHead("近 30 天类型分布", "共 " + state.monthEventCount + " 条")
            Spacer(Modifier.height(t.space3))
            if (state.typeSlices30.isEmpty()) {
                MetaText("暂无类型数据")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 同上：近 30 天类型分布也是占比 → 实心扇形图（原型 s01:2562 data-chart="pie"），
                    // 分片色与图例共用同一份 sliceColors（原型两侧同为 s1..s7 下标槽位）。
                    val sliceColors = state.typeSlices30.indices.map { t.categoryDot(it) }
                    PieChart(
                        segments = state.typeSlices30.map { it.count.toFloat() },
                        modifier = Modifier.size(t.ringSize),
                        colors = sliceColors,
                        size = t.ringSize,
                    )
                    Spacer(Modifier.width(t.space4))
                    Legend(
                        // 原型图例 = 色点 + 名称 + 数值（s01:2564-2570），无百分比。
                        items = state.typeSlices30.mapIndexed { index, slice ->
                            LegendItem(slice.label, slice.count.toString(), sliceColors[index])
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ───────────────────────────── 数值 / 工具 ─────────────────────────────
