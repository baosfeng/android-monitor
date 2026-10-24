package com.bsfeng.monitor.ui

// 从 ui/OverviewScreen.kt 拆出：概览「今日」面板 —— 2×2 KPI（含较昨日增减）→ 24 小时面积图 + 热力条 → 类型环图 → 最近事件 → 快捷入口。负责三段面板里的第一段。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.HeadIconButton
import com.bsfeng.monitor.ui.live.HeatStrip
import com.bsfeng.monitor.ui.live.LiveEventRow
import com.bsfeng.monitor.ui.live.LiveKpiCard
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.trip.TodayTripCard
import java.util.Locale

internal fun LazyListScope.todayPanel(
    state: OverviewState,
    onOpenLive: () -> Unit,
    onOpenFinance: () -> Unit,
    onOpenTrip: () -> Unit,
) {
    val eventDelta = state.todayEventCount - state.yesterdayEventCount
    val activeDelta = state.todayActiveMinutes - state.yesterdayActiveMinutes
    val skipDelta = state.todaySkipCount - state.yesterdaySkipCount

    item(key = "kpi-events") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "事件",
                unit = "条",
                delta = "较昨日 " + signed(eventDelta),
                deltaUp = eventDelta >= 0,
                spark = state.dailyEventCounts7,
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(0),
            ) { KpiValue(state.todayEventCount) }
            LiveKpiCard(
                label = "活跃",
                delta = "较昨日 " + signedMinutes(activeDelta),
                deltaUp = activeDelta >= 0,
                spark = state.dailyActiveMinutes7,
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(1),
            ) { KpiActive(state.todayActiveMinutes) }
        }
    }
    item(key = "kpi-skip") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "跳过广告",
                unit = "次",
                delta = "较昨日 " + signed(skipDelta),
                deltaUp = skipDelta >= 0,
                spark = state.dailySkipCounts7,
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(2),
            ) { KpiValue(state.todaySkipCount) }
            LiveKpiCard(
                label = "今日支出",
                delta = "收入 ¥" + money(state.todayIncome) + " · 结余 ¥" + money(state.todayIncome - state.todayExpense),
                modifier = Modifier
                    .weight(1f)
                    .staggerEnterAt(3),
            ) { KpiMoney(state.todayExpense) }
        }
    }

    item(key = "card-24h") {
        val t = LocalDesignTokens.current
        val peak = peakOf(state.hourlyIntensity)
        AppCard(modifier = Modifier.staggerEnterAt(4)) {
            CardHead("最近 24 小时活跃", "合计 " + state.todayActiveMinutes + " 分钟")
            Spacer(Modifier.height(t.space3))
            // 峰值标注保持默认开启：原型 area 引擎按 d.peak !== false 判定（08-prototype.html:5839），
            // s01-chart24h（2400-2403）未传关闭标记 → 原型此图带「峰值 N」。
            AreaChart(
                values = state.hourlyIntensity,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.chartH),
            )
            ChartAxisLabels(HOUR_TICKS)
            Spacer(Modifier.height(t.space3))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetaText("逐时活跃强度")
                MetaText(
                    "峰值 " + String.format(Locale.US, "%02d:00", peak.first) + " · " + peak.second.toInt() + " 条",
                )
            }
            Spacer(Modifier.height(t.space2))
            // s01 原缺 0 条兜底：0 条时 HeatBar 仍画 24 格满柱（13-baseline 报告「等高满柱」）；
            // 统一走 HeatStrip（有数据 24 格 / 无数据 2dp 空轨），与 s02 同源。
            HeatStrip(values = state.hourlyIntensity)
        }
    }

    // s01-trip：24h 活跃图之后、类型分布之前；整卡可点进行程页。
    // 空态/无定位态由卡片自己表达（不画假时间带），这里只负责位置与入场序号。
    item(key = "card-trip") {
        TodayTripCard(
            timeline = state.trip,
            dayStart = state.tripDayStart,
            dayEnd = state.lastUpdatedAt,
            onClick = onOpenTrip,
            modifier = Modifier.staggerEnterAt(5),
        )
    }

    item(key = "card-ring-today") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.staggerEnterAt(6)) {
            CardHead("事件类型分布", "共 " + state.todayEventCount + " 条")
            Spacer(Modifier.height(t.space3))
            if (state.typeSlicesToday.isEmpty()) {
                MetaText("暂无类型数据")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 分片色与右图例**同源**：同一份 sliceColors 同时喂给扇形与图例
                    // （原型 s01:2415-2423 两侧都是 s1..s7 同一个下标槽位），
                    // 显式传 colors 避免 PieChart 内部默认取色与图例各自演化后错位。
                    val sliceColors = state.typeSlicesToday.indices.map { t.categoryDot(it) }
                    // 占比用实心扇形图（原型 s01:2415 data-chart="pie"）。
                    // 圆形本体不叠中心文案 —— 原型 .s01-ringbox 内没有 s07 那样的 .s07-ringctr。
                    PieChart(
                        segments = state.typeSlicesToday.map { it.count.toFloat() },
                        modifier = Modifier.size(t.ringSize),
                        colors = sliceColors,
                        size = t.ringSize,
                    )
                    Spacer(Modifier.width(t.space4))
                    Legend(
                        // 原型图例 = 色点 + 名称 + 数值（s01:2417-2423），无百分比。
                        items = state.typeSlicesToday.mapIndexed { index, slice ->
                            LegendItem(slice.label, slice.count.toString(), sliceColors[index])
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    item(key = "card-recent") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.staggerEnterAt(7)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "最近事件",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    modifier = Modifier.weight(1f),
                )
                HeadIconButton(
                    icon = Icons.Outlined.NavigateNext,
                    contentDescription = "查看全部事件",
                    onClick = onOpenLive,
                )
            }
            Spacer(Modifier.height(t.space1))
            if (state.recentEvents.isEmpty()) {
                MetaText("今天还没有事件")
            } else {
                state.recentEvents.forEachIndexed { index, record ->
                    if (index > 0) HorizontalDivider(color = t.borderSoft)
                    // s01 行与 s02 时间线同源：时间列不带秒 → showSeconds = false（原型 .tl-time HH:mm）
                    LiveEventRow(record = record, showSeconds = false, onClick = onOpenLive)
                }
            }
        }
    }

    item(key = "btn-live") {
        val t = LocalDesignTokens.current
        Button(
            onClick = onOpenLive,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(t.radiusPill),
        ) {
            Text("去实时事件流", style = MaterialTheme.typography.labelLarge)
        }
    }
    item(key = "btn-finance") {
        val t = LocalDesignTokens.current
        OutlinedButton(
            onClick = onOpenFinance,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(t.radiusPill),
        ) {
            Text(
                text = "去记账 · 今日支出 ¥" + money(state.todayExpense),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ───────────────────────────── 近 7 天 ─────────────────────────────

private val HOUR_TICKS = listOf("00", "04", "08", "12", "16", "20", "23")
