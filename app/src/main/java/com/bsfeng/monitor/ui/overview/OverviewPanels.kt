package com.bsfeng.monitor.ui

// s01 今日概览 —— 整页实现（41 个 testTag 逐字照抄锚点账本）。
// 本文件自包含：页头 / 时间范围分段 / 五态 / 今日 · 近 7 天 · 近 30 天 三段面板，全部锚点落在本文件内。
// 底座复用：PressFeedback(0.96) · RowPress(0.99) · FocusRing(仅键盘) · Haptics(tone) · Toast(1.6s)
//          FiveState/StateSkeleton · Stagger(24ms) · AnimatedText · Bar/Line/Pie Charts · Legend。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.HeatStrip
import com.bsfeng.monitor.ui.live.LiveEventRow
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.formatClock
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.live.TypeIconDot
import com.bsfeng.monitor.ui.live.formatFullDate
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.live.rollingHourAxisLabels
import com.bsfeng.monitor.ui.live.rollingHourStartAt
import com.bsfeng.monitor.ui.live.weekdayLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.onAccent
import com.bsfeng.monitor.ui.theme.sparkHeight
import kotlinx.coroutines.launch
import com.bsfeng.monitor.ui.trip.TripBand
import com.bsfeng.monitor.ui.trip.TripBandAxis
import com.bsfeng.monitor.ui.trip.TripBandLegend
import com.bsfeng.monitor.ui.trip.basisBadge
import com.bsfeng.monitor.ui.trip.tripCoverageNote
import com.bsfeng.monitor.ui.trip.tripHeadline
import java.util.Locale

internal fun LazyListScope.todayPanelS01(
    state: OverviewState,
    onOpenLive: () -> Unit,
    onOpenFinance: () -> Unit,
    onOpenTrip: () -> Unit,
    onOpenLiveEvent: (Record) -> Unit,
    onHaptic: (HapticTone) -> Unit,
) {
    val eventDelta = state.todayEventCount - state.yesterdayEventCount
    val activeDelta = state.todayActiveMinutes - state.yesterdayActiveMinutes
    val skipDelta = state.todaySkipCount - state.yesterdaySkipCount

    // s01-kpi：2×2 容器不可聚焦，唯一可点元素是四张子卡
    item(key = "kpi-row-1") {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("s01-kpi"),
            verticalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space3),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space3)) {
                OverviewKpiCard(
                    label = "事件",
                    unit = "条",
                    delta = "较昨日 " + signedDelta(eventDelta),
                    deltaTone = toneOf(eventDelta),
                    spark = state.dailyEventCounts7,
                    modifier = Modifier.testTag("s01-kpi-events").weight(1f).staggerEnterAt(0),
                    onHaptic = onHaptic,
                ) { replay ->
                    RollingInt(state.todayEventCount, replay, t0Fg = LocalDesignTokens.current.fg)
                }
                OverviewKpiCard(
                    label = "活跃",
                    delta = "较昨日 " + signedMinutes(activeDelta),
                    deltaTone = toneOf(activeDelta),
                    spark = state.dailyActiveMinutes7,
                    modifier = Modifier.testTag("s01-kpi-active").weight(1f).staggerEnterAt(1),
                    onHaptic = onHaptic,
                ) { replay ->
                    RollingActive(state.todayActiveMinutes, replay)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LocalDesignTokens.current.space3)) {
                OverviewKpiCard(
                    label = "跳过广告",
                    unit = "次",
                    delta = "较昨日 " + signedDelta(skipDelta),
                    deltaTone = toneOf(skipDelta),
                    spark = state.dailySkipCounts7,
                    modifier = Modifier.testTag("s01-kpi-skip").weight(1f).staggerEnterAt(2),
                    onHaptic = onHaptic,
                ) { replay ->
                    RollingInt(state.todaySkipCount, replay, t0Fg = LocalDesignTokens.current.fg)
                }
                OverviewKpiCard(
                    label = "今日支出",
                    delta = "收入 ¥" + money(state.todayIncome) + " · 结余 ¥" + money(state.todayIncome - state.todayExpense),
                    deltaTone = toneOf(state.todayIncome - state.todayExpense),
                    modifier = Modifier.testTag("s01-kpi-spend").weight(1f).staggerEnterAt(3),
                    onHaptic = onHaptic,
                ) { replay ->
                    RollingMoney(state.todayExpense, replay)
                }
            }
        }
    }

    item(key = "card-24h") {
        val t = LocalDesignTokens.current
        // 窗口是「此刻往回 24 小时」的滚动窗口（跨自然日），刻度和峰值都按真实时刻标；
        // 曾经按自然日 0..23 时画、刻度写死 00/04/08/12/16/20/23，凌晨打开时标题与图对不上。
        val intensity = state.last24hIntensity
        val peak = peakOf(intensity)
        val axis = remember(state.lastUpdatedAt) { rollingHourAxisLabels(state.lastUpdatedAt) }
        AppCard(modifier = Modifier.testTag("s01-chart24h").staggerEnterAt(4)) {
            CardHead("最近 24 小时活跃", "合计 " + state.last24hActiveMinutes + " 分钟")
            Spacer(Modifier.height(t.space3))
            AreaChart(
                values = intensity,
                modifier = Modifier.fillMaxWidth().height(t.chartH),
            )
            ChartAxisLabels(axis)
            Spacer(Modifier.height(t.space3))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetaText("逐时活跃强度")
                // 0 条时峰值文案写「暂无数据」
                MetaText(
                    if (intensity.any { it > 0f }) {
                        "峰值 " + formatClock(rollingHourStartAt(state.lastUpdatedAt, peak.first)) +
                            " · " + peak.second.toInt() + " 条"
                    } else {
                        "暂无数据"
                    },
                )
            }
            Spacer(Modifier.height(t.space2))
            HeatStrip(values = intensity)
        }
    }

    // s01-trip / s01-trip-source / trip-band：整卡可点进行程页，角标与时间带不拦截点击。
    item(key = "card-trip") {
        Box(
            Modifier
                .testTag("s01-trip")
                .staggerEnterAt(5),
        ) {
            TripCardS01(
                timeline = state.trip,
                dayStart = state.tripDayStart,
                dayEnd = state.lastUpdatedAt,
                onClick = onOpenTrip,
            )
        }
    }

    item(key = "card-ring-today") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.testTag("s01-ring").staggerEnterAt(6)) {
            CardHead("事件类型分布", "共 " + state.todayEventCount + " 条")
            Spacer(Modifier.height(t.space3))
            RingBlock(
                slices = state.typeSlicesToday,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    item(key = "card-recent") {
        val t = LocalDesignTokens.current
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("s01-recent")
                .clip(RoundedCornerShape(t.radiusLg))
                .background(t.surface)
                .border(1.dp, t.borderSoft, RoundedCornerShape(t.radiusLg))
                .padding(t.space4)
                .staggerEnterAt(7),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近事件",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    modifier = Modifier.weight(1f),
                )
                OverviewIconButton(
                    icon = Icons.Outlined.NavigateNext,
                    description = "查看全部事件",
                    modifier = Modifier.testTag("s01-recent-all"),
                    onClick = { onHaptic(HapticTone.LIGHT); onOpenLive() },
                )
            }
            Spacer(Modifier.height(t.space1))
            if (state.recentEvents.isEmpty()) {
                MetaText("今天还没有事件")
            } else {
                state.recentEvents.take(RECENT_ROWS).forEachIndexed { index, record ->
                    LiveEventRow(
                        record = record,
                        modifier = when (index) {
                            0 -> Modifier.testTag("s01-recent-1")
                            1 -> Modifier.testTag("s01-recent-2")
                            else -> Modifier.testTag("s01-recent-3")
                        },
                        showSeconds = false,
                        onClick = { onHaptic(HapticTone.LIGHT); onOpenLiveEvent(record) },
                    )
                }
            }
        }
    }

    // s01-finance：记账图表卡（原型 s01-finance 区块）。卡片只在「首页记账卡」开关开着时出现：
    // state.financeCard 为 null（开关关 / 本轮未取数）时整块不打节点，语义树里连容器都没有。
    state.financeCard?.let { card ->
        item(key = "card-finance") {
            OverviewFinanceCard(
                state = card,
                onOpenFinance = onOpenFinance,
                onHaptic = onHaptic,
                modifier = Modifier.staggerEnterAt(8),
            )
        }
    }

    item(key = "quick") {
        val t = LocalDesignTokens.current
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("s01-quick"),
            verticalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            PrimaryTextButton(
                text = "去实时事件流",
                modifier = Modifier.testTag("s01-quick-live"),
                tone = HapticTone.MEDIUM,
                onClick = onOpenLive,
            )
            GhostTextButton(
                text = "去记账 · 今日支出 ¥" + money(state.todayExpense),
                modifier = Modifier.testTag("s01-quick-finance"),
                onClick = { onHaptic(HapticTone.LIGHT); onOpenFinance() },
            )
        }
    }
}

// ───────────────────────────── 近 7 天 / 近 30 天 ─────────────────────────────

internal fun LazyListScope.weekPanelS01(state: OverviewState, now: Long) {
    item(key = "k7-row-1") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            StaticKpiCard(
                label = "事件",
                unit = "条",
                value = thousands(state.weekEventCount),
                delta = eventDeltaText("较上周", state.weekEventCount, state.prevWeekEventCount),
                deltaTone = deltaToneOf(state.weekEventCount, state.prevWeekEventCount, percent = true),
                modifier = Modifier.testTag("s01-k7-events").weight(1f).staggerEnterAt(0),
            )
            StaticKpiCard(
                label = "活跃",
                value = hoursMinutes(state.weekActiveMinutes),
                delta = "日均 " + dailyHoursMinutes(state.weekActiveMinutes, 7),
                deltaTone = DeltaTone.MUTED,
                modifier = Modifier.testTag("s01-k7-active").weight(1f).staggerEnterAt(1),
            )
        }
        Spacer(Modifier.height(t.space3))
    }
    item(key = "k7-row-2") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            StaticKpiCard(
                label = "跳过广告",
                unit = "次",
                value = thousands(state.weekSkipCount),
                delta = countDeltaText("较上周", state.weekSkipCount, state.prevWeekSkipCount),
                deltaTone = deltaToneOf(state.weekSkipCount, state.prevWeekSkipCount, percent = false),
                modifier = Modifier.testTag("s01-k7-skip").weight(1f).staggerEnterAt(2),
            )
            StaticKpiCard(
                label = "支出",
                value = "¥" + money(state.weekExpense),
                delta = "收入 ¥" + money(state.weekIncome) + " · 结余 ¥" + money(state.weekIncome - state.weekExpense),
                deltaTone = toneOf(state.weekIncome - state.weekExpense),
                modifier = Modifier.testTag("s01-k7-spend").weight(1f).staggerEnterAt(3),
            )
        }
    }
    item(key = "card-7d-bars") {
        val t = LocalDesignTokens.current
        // 与 VM 的日桶同源（同 [now]）：跨零点后柱值与横轴一起翻篇
        val labels = remember(now) { dayLabels(7, now) }
        AppCard(modifier = Modifier.testTag("s01-chart7d").staggerEnterAt(4)) {
            CardHead("近 7 天每日事件量", labels.first() + " – " + labels.last())
            Spacer(Modifier.height(t.space3))
            BarChart(
                values = state.dailyEventCounts7,
                modifier = Modifier.fillMaxWidth().height(t.chartH),
            )
            ChartAxisLabels(labels)
        }
    }
    item(key = "card-top7d") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.testTag("s01-top7d").staggerEnterAt(5)) {
            CardHead("本周跳过最多", "共 " + state.weekSkipCount + " 次")
            Spacer(Modifier.height(t.space3))
            if (state.topSkippedApps.isEmpty()) {
                MetaText("本周还没有跳过记录")
            } else {
                state.topSkippedApps.take(3).forEach { slice ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = t.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeIconDot(com.bsfeng.monitor.domain.model.RecordType.APP_USAGE)
                        Spacer(Modifier.width(t.space3))
                        Text(
                            slice.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            slice.count.toString() + " 次",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = t.muted,
                        )
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.monthPanelS01(state: OverviewState, now: Long) {
    item(key = "k30-row-1") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            StaticKpiCard(
                label = "事件",
                unit = "条",
                value = thousands(state.monthEventCount),
                delta = eventDeltaText("较上月", state.monthEventCount, state.prevMonthEventCount),
                deltaTone = deltaToneOf(state.monthEventCount, state.prevMonthEventCount, percent = true),
                modifier = Modifier.testTag("s01-k30-events").weight(1f).staggerEnterAt(0),
            )
            StaticKpiCard(
                label = "活跃",
                value = (state.monthActiveMinutes / 60).toString() + "h",
                delta = "日均 " + dailyHoursMinutes(state.monthActiveMinutes, 30),
                deltaTone = DeltaTone.MUTED,
                modifier = Modifier.testTag("s01-k30-active").weight(1f).staggerEnterAt(1),
            )
        }
        Spacer(Modifier.height(t.space3))
    }
    item(key = "k30-row-2") {
        val t = LocalDesignTokens.current
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            StaticKpiCard(
                label = "跳过广告",
                unit = "次",
                value = thousands(state.monthSkipCount),
                delta = countDeltaText("较上月", state.monthSkipCount, state.prevMonthSkipCount),
                deltaTone = deltaToneOf(state.monthSkipCount, state.prevMonthSkipCount, percent = false),
                modifier = Modifier.testTag("s01-k30-skip").weight(1f).staggerEnterAt(2),
            )
            StaticKpiCard(
                label = "支出",
                value = "¥" + money(state.monthExpense),
                delta = "收入 ¥" + money(state.monthIncome) + " · 结余 ¥" + money(state.monthIncome - state.monthExpense),
                deltaTone = toneOf(state.monthIncome - state.monthExpense),
                modifier = Modifier.testTag("s01-k30-spend").weight(1f).staggerEnterAt(3),
            )
        }
    }
    item(key = "card-30d-trend") {
        val t = LocalDesignTokens.current
        val labels = remember(now) { dayLabels(30, now) }
        AppCard(modifier = Modifier.testTag("s01-chart30d").staggerEnterAt(4)) {
            CardHead("近 30 天活跃趋势", "单位 分钟")
            Spacer(Modifier.height(t.space3))
            AreaChart(
                values = state.dailyActiveMinutes30,
                modifier = Modifier.fillMaxWidth().height(t.chartH),
            )
            ChartAxisLabels(monthTicks(labels))
        }
    }
    item(key = "card-30d-ring") {
        val t = LocalDesignTokens.current
        AppCard(modifier = Modifier.testTag("s01-ring30d").staggerEnterAt(5)) {
            CardHead("近 30 天类型分布", "共 " + state.monthEventCount + " 条")
            Spacer(Modifier.height(t.space3))
            RingBlock(
                slices = state.typeSlices30,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ───────────────────────────── 局部件 ─────────────────────────────

internal enum class DeltaTone { MUTED, SUCCESS, DANGER }

internal fun toneOf(delta: Long): DeltaTone = when {
    delta > 0 -> DeltaTone.SUCCESS
    delta < 0 -> DeltaTone.DANGER
    else -> DeltaTone.MUTED
}

internal fun toneOf(delta: Int): DeltaTone = toneOf(delta.toLong())

internal fun toneOf(delta: Double): DeltaTone = toneOf(delta.toLong())

