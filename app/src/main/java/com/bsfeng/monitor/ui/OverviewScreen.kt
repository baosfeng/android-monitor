package com.bsfeng.monitor.ui

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.FiveStateDemo
import com.bsfeng.monitor.ui.live.FiveStateDemoBar
import com.bsfeng.monitor.ui.live.HeadIconButton
import com.bsfeng.monitor.ui.live.HeatStrip
import com.bsfeng.monitor.ui.live.LiveEventRow
import com.bsfeng.monitor.ui.live.LiveKpiCard
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.ScreenHead
import com.bsfeng.monitor.ui.live.TypeIconDot
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.formatDayLabel
import com.bsfeng.monitor.ui.live.formatFullDate
import com.bsfeng.monitor.ui.live.formatHoursMinutes
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.live.recordTypeIcon
import com.bsfeng.monitor.ui.live.startOfDay
import com.bsfeng.monitor.ui.live.weekdayLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import java.util.Locale

/**
 * s01 今日概览（原型 .open-design/08-prototype.html → parts/s01-overview.html）。
 *
 * 三段面板：今日 / 近 7 天 / 近 30 天。
 * 今日段：2×2 KPI（AnimatedNumber + sparkline + 较昨日增减）→ 24 小时活跃面积图 + 24 格热力条
 *        → 类型环图 + 图例 → 最近事件 → 快捷入口。
 * 卡片入场统一走 rememberStaggerEnter(index)。
 */
@Composable
fun OverviewScreen(
    onOpenLive: () -> Unit,
    onOpenFinance: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = viewModel(),
) {
    // 生命周期感知收集：退到后台即取消订阅 → ViewModel 轮询同步挂起（见 OverviewViewModel.init）
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    var rangeIndex by remember { mutableIntStateOf(0) }
    val now = remember { System.currentTimeMillis() }

    val realState = when {
        state.isLoading -> FiveState.LOADING
        state.loadError != null -> FiveState.ERROR
        state.todayEventCount == 0 && state.weekEventCount == 0 -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "今日概览",
                meta = formatFullDate(now) + " · " + weekdayLabel(now),
                actionIcon = Icons.Outlined.Notifications,
                actionDesc = "今日提醒",
                onAction = { toast.show("今日已记录 " + state.todayEventCount + " 条事件") },
            )
            SegmentedControl(
                options = listOf("今日", "近 7 天", "近 30 天"),
                selectedIndex = rangeIndex,
                onSelect = { rangeIndex = it },
                modifier = Modifier.padding(horizontal = t.space4),
            )
            Spacer(Modifier.height(t.space3))
            StateContainer(
                state = FiveStateDemo.effective(realState),
                modifier = Modifier.weight(1f),
                emptyText = noEventEmptyText(state.accessibilityEnabled),
                errorText = state.loadError
                    ?: "本地数据库暂时不可用（SQLITE_BUSY）。稍后重试即可，已采集的事件不会丢失。",
                edgeText = "微信（com.tencent.mm）· 前台使用 128 分钟 32 秒 · 累计 1,024 次前台切换",
                onRetry = { viewModel.retry() },
            ) {
                // J6：整页 Column + verticalScroll → LazyColumn（一卡一 item），
                // 视觉（左右内边距 / 卡片间距 / 底部留白）与原实现一致。
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = t.space4,
                        end = t.space4,
                        bottom = t.space6,
                    ),
                    verticalArrangement = Arrangement.spacedBy(t.space3),
                ) {
                    when (rangeIndex) {
                        0 -> todayPanel(state = state, onOpenLive = onOpenLive, onOpenFinance = onOpenFinance)
                        1 -> weekPanel(state = state)
                        else -> monthPanel(state = state)
                    }
                }
            }
            FiveStateDemoBar()
        }
        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ───────────────────────────── 今日面板 ─────────────────────────────

private fun LazyListScope.todayPanel(
    state: OverviewState,
    onOpenLive: () -> Unit,
    onOpenFinance: () -> Unit,
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
                    .then(rememberStaggerEnter(0)),
            ) { KpiValue(state.todayEventCount) }
            LiveKpiCard(
                label = "活跃",
                delta = "较昨日 " + signedMinutes(activeDelta),
                deltaUp = activeDelta >= 0,
                spark = state.dailyActiveMinutes7,
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(1)),
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
                    .then(rememberStaggerEnter(2)),
            ) { KpiValue(state.todaySkipCount) }
            LiveKpiCard(
                label = "今日支出",
                delta = "收入 ¥" + money(state.todayIncome) + " · 结余 ¥" + money(state.todayIncome - state.todayExpense),
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(3)),
            ) { KpiMoney(state.todayExpense) }
        }
    }

    item(key = "card-24h") {
        val t = LocalDesignTokens.current
        val peak = peakOf(state.hourlyIntensity)
        AppCard(modifier = Modifier.then(rememberStaggerEnter(4))) {
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

    item(key = "card-ring-today") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.then(rememberStaggerEnter(5))) {
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
        AppCard(modifier = Modifier.then(rememberStaggerEnter(6))) {
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

private fun LazyListScope.weekPanel(state: OverviewState) {
    item(key = "kpi-week-events") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "事件",
                unit = "条",
                delta = "日均 " + (state.weekEventCount / 7) + " 条",
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(0)),
            ) { KpiValue(state.weekEventCount) }
            LiveKpiCard(
                label = "活跃",
                delta = "日均 " + formatHoursMinutes(state.weekActiveMinutes / 7),
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(1)),
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
                    .then(rememberStaggerEnter(2)),
            ) { KpiValue(state.weekSkipCount) }
            LiveKpiCard(
                label = "支出",
                delta = "收入 ¥" + money(state.weekIncome) + " · 结余 ¥" + money(state.weekIncome - state.weekExpense),
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(3)),
            ) { KpiMoney(state.weekExpense) }
        }
    }

    item(key = "card-7d-bars") {
        val t = LocalDesignTokens.current
        val labels = remember { dayLabels(7) }
        AppCard(modifier = Modifier.then(rememberStaggerEnter(4))) {
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
        AppCard(modifier = Modifier.then(rememberStaggerEnter(5))) {
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

private fun LazyListScope.monthPanel(state: OverviewState) {
    item(key = "kpi-month-events") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            LiveKpiCard(
                label = "事件",
                unit = "条",
                delta = "日均 " + (state.monthEventCount / 30) + " 条",
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(0)),
            ) { KpiValue(state.monthEventCount) }
            LiveKpiCard(
                label = "活跃",
                delta = "日均 " + formatHoursMinutes(state.monthActiveMinutes / 30),
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(1)),
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
                    .then(rememberStaggerEnter(2)),
            ) { KpiValue(state.monthSkipCount) }
            LiveKpiCard(
                label = "支出",
                delta = "收入 ¥" + money(state.monthIncome) + " · 结余 ¥" + money(state.monthIncome - state.monthExpense),
                modifier = Modifier
                    .weight(1f)
                    .then(rememberStaggerEnter(3)),
            ) { KpiMoney(state.monthExpense) }
        }
    }

    item(key = "card-30d-trend") {
        val t = LocalDesignTokens.current
        val labels = remember { dayLabels(30) }
        AppCard(modifier = Modifier.then(rememberStaggerEnter(4))) {
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
        AppCard(modifier = Modifier.then(rememberStaggerEnter(5))) {
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

private val HOUR_TICKS = listOf("00", "04", "08", "12", "16", "20", "23")

/**
 * KPI 数值：原型 .kpi-value 为 28px/700 + font-mono + tabular-nums（parts/00-shell.html:322）。
 * W1 的等宽数字样式尚未落到 ui/theme/Type.kt，这里先显式指定 FontFamily.Monospace，
 * 集成时统一替换为 Type.kt 的等宽样式。
 */
@Composable
private fun KpiValue(value: Int) {
    val t = LocalDesignTokens.current
    AnimatedNumber(
        value = value,
        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
        color = t.fg,
    )
}

@Composable
private fun KpiMoney(value: Double) {
    val t = LocalDesignTokens.current
    AnimatedNumber(
        value = value.toFloat(),
        decimals = 0,
        prefix = "¥",
        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
        color = t.fg,
    )
}

@Composable
private fun KpiActive(minutes: Long) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedNumber(
            value = (minutes / 60).toInt(),
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
            color = t.fg,
        )
        Text("h", style = MaterialTheme.typography.labelMedium, color = t.muted)
        Spacer(Modifier.width(t.space1))
        AnimatedNumber(
            value = (minutes % 60).toInt(),
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
            color = t.fg,
        )
        Text("m", style = MaterialTheme.typography.labelMedium, color = t.muted)
    }
}

private fun signed(delta: Int): String = if (delta >= 0) "+" + delta else delta.toString()

/** 分钟级增减：原型写作「较昨日 +42m」（parts/s01-overview.html:173）。 */
private fun signedMinutes(delta: Long): String =
    (if (delta >= 0) "+" else "-") + kotlin.math.abs(delta) + "m"

/** 近 30 天横轴 7 个刻度（首 / 每 5 天 / 末，原型 parts/s01-overview.html:360）。 */
private fun monthTicks(labels: List<String>): List<String> =
    if (labels.isEmpty()) emptyList()
    else (0..6).map { labels[(it * (labels.size - 1) / 6).coerceIn(0, labels.size - 1)] }

private fun money(value: Double): String = String.format(Locale.US, "%,.0f", value)

private fun dayLabels(days: Int): List<String> {
    val start = startOfDay(System.currentTimeMillis()) - (days - 1) * DAY_MS
    return (0 until days).map { formatDayLabel(start + it * DAY_MS) }
}
