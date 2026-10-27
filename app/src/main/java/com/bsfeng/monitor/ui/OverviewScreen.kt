package com.bsfeng.monitor.ui

// s01 今日概览 —— 整页实现（锚点见 docs/交互设计/s01-今日概览-交互契约.md，41 个 testTag 逐字照抄）。
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

/** 单日展示上限：到达即进边界态（契约 s01-edge「10,842 条 / 已达单日展示上限」）。 */
private const val EDGE_DAILY_LIMIT = 10_000

/** 最近事件最多三行（契约 s01-recent-1/2/3）。 */
private const val RECENT_ROWS = 3

/** 空态描述（契约 s01-empty 文案口径逐字）：已授权 / 未授权两套。 */
private const val EMPTY_DESC_READY = "开启无障碍服务后，屏幕、应用、网络与充电事件会自动汇总到这里。"
private const val EMPTY_DESC_DENIED =
    "无障碍服务未授权（设置 → 无障碍服务 → 去开启）。开启后，屏幕、应用、网络与充电事件会自动汇总到这里。"

private val HOUR_TICKS = listOf("00", "04", "08", "12", "16", "20", "23")

private val RANGE_OPTIONS = listOf("今日", "近 7 天", "近 30 天")

@Composable
fun OverviewScreen(
    onOpenLive: () -> Unit,
    onOpenFinance: () -> Unit,
    onOpenTrip: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = viewModel(),
    /** s01-empty-cta：跳设置页无障碍分组。未接线时按钮不出现（不伪造跳转）。 */
    onOpenSettings: (() -> Unit)? = null,
    /** s01-recent-*：切实时 Tab 并把该条滚到可视区。未接线时退化为 onOpenLive。 */
    onOpenLiveEvent: ((Record) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    val haptic = rememberHaptic()
    var rangeIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }

    // 五态按真实数据判定；演示条（devbar）不进产品。
    val uiState = when {
        state.isLoading -> FiveState.LOADING
        state.loadError != null -> FiveState.ERROR
        state.todayEventCount >= EDGE_DAILY_LIMIT -> FiveState.EDGE
        state.todayEventCount == 0 && state.weekEventCount == 0 -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }

    Box(modifier.fillMaxSize().testTag("screen-s01")) {
        Column(Modifier.fillMaxSize()) {
            OverviewHead(
                dateText = formatFullDate(now) + " · " + weekdayLabel(now),
                onBell = { toast.show("今日已记录 " + state.todayEventCount + " 条事件") },
            )
            RangeSegment(
                selectedIndex = rangeIndex,
                onSelect = {
                    if (it != rangeIndex) {
                        rangeIndex = it
                        // 契约 s01-range：切换后内容滚回顶部
                        scope.launch { listState.scrollToItem(0) }
                    }
                },
                onHaptic = { haptic(HapticTone.LIGHT) },
                modifier = Modifier.padding(horizontal = t.space4),
            )
            Spacer(Modifier.height(t.space3))
            Box(Modifier.weight(1f)) {
                when (uiState) {
                    // 五态一律走 ui/components 的共享件（EmptyState / ErrorState / EdgeState）：
                    // 块级锚点进 modifier、CTA 锚点进 actionModifier，页面不再自持第二份规格。
                    FiveState.LOADING -> LoadingBlock()
                    FiveState.EMPTY -> {
                        // 已授权时空态不出现 CTA（契约 s01-empty-cta 边界）：回调给 null 即整颗不渲染
                        val cta = onOpenSettings.takeUnless { state.accessibilityEnabled }
                        EmptyState(
                            text = if (state.accessibilityEnabled) EMPTY_DESC_READY else EMPTY_DESC_DENIED,
                            title = "今天还没有记录",
                            icon = Icons.Outlined.Notifications,
                            actionLabel = if (cta != null) "去设置开启监控" else null,
                            onAction = cta,
                            actionModifier = Modifier.testTag("s01-empty-cta"),
                            modifier = Modifier.fillMaxSize().testTag("s01-empty"),
                        )
                    }
                    FiveState.ERROR -> ErrorState(
                        // 契约文案逐字；异常原文只在 ViewModel 日志里
                        text = state.loadError ?: LOAD_ERROR_DESC,
                        title = "统计加载失败",
                        // 触感由共享 GhostButton 内部发 LIGHT，这里不再补一次（同一操作不重复震）
                        onRetry = {
                            toast.show("正在重新统计…")
                            viewModel.retry()
                        },
                        actionModifier = Modifier.testTag("s01-error-retry"),
                        modifier = Modifier.fillMaxSize().testTag("s01-error"),
                    )
                    FiveState.EDGE -> EdgeState(
                        text = "缺失字段统一写「—」",
                        modifier = Modifier.fillMaxSize().testTag("s01-edge"),
                        content = {
                            EdgeBody(count = state.todayEventCount, longest = state.longestEvent)
                        },
                    )
                    FiveState.POPULATED -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("s01-content"),
                        contentPadding = PaddingValues(
                            start = t.space4,
                            end = t.space4,
                            bottom = t.space6,
                        ),
                        verticalArrangement = Arrangement.spacedBy(t.space3),
                    ) {
                        when (rangeIndex) {
                            0 -> todayPanelS01(
                                state = state,
                                onOpenLive = onOpenLive,
                                onOpenFinance = onOpenFinance,
                                onOpenTrip = onOpenTrip,
                                onOpenLiveEvent = { record ->
                                    if (onOpenLiveEvent != null) onOpenLiveEvent.invoke(record) else onOpenLive()
                                },
                                onHaptic = haptic,
                            )
                            1 -> weekPanelS01(state = state)
                            else -> monthPanelS01(state = state)
                        }
                    }
                }
            }
        }
        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ───────────────────────────── 页头 / 分段 ─────────────────────────────

@Composable
private fun OverviewHead(dateText: String, onBell: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s01-head")
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText(dateText)
            Spacer(Modifier.height(t.space2))
            Text("今日概览", style = MaterialTheme.typography.headlineMedium, color = t.fg)
        }
        Spacer(Modifier.width(t.space3))
        OverviewIconButton(
            icon = Icons.Outlined.Notifications,
            description = "今日提醒",
            modifier = Modifier.testTag("s01-bell"),
            onClick = onBell,
        )
    }
}

/** 44dp 图标按钮：0.96 按下 + LIGHT 触感 + 仅键盘可见焦点环（契约 s01-bell）。 */
@Composable
private fun OverviewIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .then(modifier)
            .size(t.tapMin)
            .clip(CircleShape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, CircleShape)
            .pressFeedback(interaction)
            .keyboardFocusRing(CircleShape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = t.fg2, modifier = Modifier.size(t.iconSize / 2))
    }
}

/**
 * 时间范围分段：三选一互斥、切换 120ms（--dur-state）、LIGHT 触感、44dp 段高 + 外框 4dp。
 * 键盘：左右方向键即时切换，Tab 只落在当前选中项（契约 s01-range 的 click / 键盘 / focus 三行）。
 */
@Composable
private fun RangeSegment(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    val focusRequesters = remember { List(RANGE_OPTIONS.size) { FocusRequester() } }
    // 键盘切换后的焦点请求不能在按键栈里直接发：此刻新段的 canFocus 还是 false
    // （下面 focusProperties 的 canFocus 要等重组才按新的 selected 重算），请求会当场失败；
    // 重组后旧段 canFocus=false、请求也落了空 → 焦点被清空，第一次切换后方向键再也到不了分段。
    // 口径照 MainScreen.kt:357-364 的 s02 chips：先记「下次该聚焦谁」，等重组把 canFocus 算完，
    // 再在 keyed effect 里请求（LaunchedEffect 在组件树应用之后才启动，那时 canFocus 已是新值）。
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusIndex) {
        pendingFocusIndex?.let { index ->
            runCatching { focusRequesters[index].requestFocus() }
            pendingFocusIndex = null
        }
    }
    val segmentPad = t.space1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("s01-range")
            .height(t.tapMin + segmentPad * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .padding(segmentPad)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val next = when (event.key) {
                    Key.DirectionLeft -> selectedIndex - 1
                    Key.DirectionRight -> selectedIndex + 1
                    else -> return@onPreviewKeyEvent false
                }
                if (next in RANGE_OPTIONS.indices) {
                    // 先切状态、再把焦点请求交给重组后的 effect（不在按键栈里零帧 requestFocus）。
                    onSelect(next)
                    pendingFocusIndex = next
                }
                true
            },
        horizontalArrangement = Arrangement.spacedBy(segmentPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RANGE_OPTIONS.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) t.surface else Color.Transparent,
                animationSpec = androidx.compose.animation.core.tween<Color>(t.durState),
                label = "segBg",
            )
            val fg by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) t.fg else t.muted,
                animationSpec = androidx.compose.animation.core.tween<Color>(t.durState),
                label = "segFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(t.tapMin)
                    .focusRequester(focusRequesters[index])
                    .focusProperties { canFocus = selected }
                    .keyboardFocusRing(shape)
                    .clip(shape)
                    .background(bg)
                    .clickable { onHaptic(); onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg,
                )
            }
        }
    }
}

// ───────────────────────────── 五态块 ─────────────────────────────

@Composable
private fun LoadingBlock() {
    val t = LocalDesignTokens.current
    // 骨架不接收点击、不可聚焦；超 10s 仍停在骨架（不自动转错态）。
    Box(
        Modifier
            .fillMaxSize()
            .testTag("s01-loading")
            .padding(horizontal = t.space4),
    ) {
        StateSkeleton(rows = 6)
    }
}

/**
 * 边界态内容（契约 s01-edge）：万级徽标 + **真实**最长标题（单行省略）+ 真实字段行（缺失写「—」）。
 *
 * 事件量过万是真实用户可达态，不是调试演示：这里不写任何具体应用名 / 事件内容，
 * 只展示库里真实存在的那一条最长的记录，字段缺失就写「—」，不补 0 也不猜。
 */
@Composable
private fun EdgeBody(count: Int, longest: Record?) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(thousands(count) + " 条")
        Spacer(Modifier.width(t.space2))
        Text("已达单日展示上限", style = MaterialTheme.typography.labelMedium, color = t.muted)
    }
    Spacer(Modifier.height(t.space2))
    Text(
        text = longest?.label?.takeIf { it.isNotBlank() } ?: PLACEHOLDER,
        style = MaterialTheme.typography.bodyMedium,
        color = t.fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(t.space1))
    Text(
        text = edgeMetaOf(longest),
        style = MaterialTheme.typography.labelSmall,
        color = t.meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 边界态字段行：时间 · 类型 · 时长；任一字段缺失写「—」（契约 s01-edge「字段缺失显示「—」」）。 */
private fun edgeMetaOf(record: Record?): String {
    if (record == null) return PLACEHOLDER
    val duration = record.duration
        ?.let { (it / 60_000L).toString() + " 分钟" }
        ?: PLACEHOLDER
    return formatClock(record.timestamp) + " · " + recordTypeLabel(record.type) + " · " + duration
}

// ───────────────────────────── 今日面板 ─────────────────────────────

private fun LazyListScope.todayPanelS01(
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
        val peak = peakOf(state.hourlyIntensity)
        AppCard(modifier = Modifier.testTag("s01-chart24h").staggerEnterAt(4)) {
            CardHead("最近 24 小时活跃", "合计 " + state.todayActiveMinutes + " 分钟")
            Spacer(Modifier.height(t.space3))
            AreaChart(
                values = state.hourlyIntensity,
                modifier = Modifier.fillMaxWidth().height(t.chartH),
            )
            ChartAxisLabels(HOUR_TICKS)
            Spacer(Modifier.height(t.space3))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetaText("逐时活跃强度")
                // 0 条时峰值文案写「暂无数据」
                MetaText(
                    if (state.hourlyIntensity.any { it > 0f }) {
                        "峰值 " + String.format(Locale.US, "%02d:00", peak.first) + " · " + peak.second.toInt() + " 条"
                    } else {
                        "暂无数据"
                    },
                )
            }
            Spacer(Modifier.height(t.space2))
            HeatStrip(values = state.hourlyIntensity)
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

private fun LazyListScope.weekPanelS01(state: OverviewState) {
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
        val labels = remember { dayLabels(7) }
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

private fun LazyListScope.monthPanelS01(state: OverviewState) {
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
        val labels = remember { dayLabels(30) }
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

private enum class DeltaTone { MUTED, SUCCESS, DANGER }

private fun toneOf(delta: Long): DeltaTone = when {
    delta > 0 -> DeltaTone.SUCCESS
    delta < 0 -> DeltaTone.DANGER
    else -> DeltaTone.MUTED
}

private fun toneOf(delta: Int): DeltaTone = toneOf(delta.toLong())

private fun toneOf(delta: Double): DeltaTone = toneOf(delta.toLong())

@Composable
private fun DeltaText(text: String, tone: DeltaTone, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = when (tone) {
            DeltaTone.SUCCESS -> t.successText
            DeltaTone.DANGER -> t.dangerText
            DeltaTone.MUTED -> t.muted
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 可点 KPI 卡：0.96 按下 + LIGHT 触感 + 键盘焦点环；点击只重播数字滚动（契约 s01-kpi-* click）。 */
@Composable
private fun OverviewKpiCard(
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    deltaTone: DeltaTone = DeltaTone.MUTED,
    spark: List<Float>? = null,
    onHaptic: (HapticTone) -> Unit,
    value: @Composable (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    var replay by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            // 长按不注册 onLongClick：无菜单 / 无放大 / 无复制浮层，按下态保持到抬起
            .clickable(interactionSource = interaction, indication = null) {
                onHaptic(HapticTone.LIGHT)
                replay++
            }
            .padding(t.space3),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space2))
        Row(verticalAlignment = Alignment.Bottom) {
            value(replay)
            if (unit != null) {
                Spacer(Modifier.width(t.space1))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
        Spacer(Modifier.height(t.space1))
        Row(verticalAlignment = Alignment.Bottom) {
            if (delta != null) {
                DeltaText(delta, deltaTone, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (spark != null && spark.size >= 2) {
                Spacer(Modifier.width(t.space2))
                com.bsfeng.monitor.ui.components.Sparkline(
                    values = spark,
                    modifier = Modifier.width(t.space12 + t.space6).height(t.sparkHeight),
                )
            }
        }
    }
}

/** 近 7 / 30 天 KPI：静态数值，不重播滚动、不跳转（契约 s01-k7-* / s01-k30-*）。 */
@Composable
private fun StaticKpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    deltaTone: DeltaTone = DeltaTone.MUTED,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space3),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space2))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                color = t.fg,
            )
            if (unit != null) {
                Spacer(Modifier.width(t.space1))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
        Spacer(Modifier.height(t.space1))
        if (delta != null) DeltaText(delta, deltaTone)
    }
}

/** 整数滚动 + 千分位；replay 变化即从 0 重播（600ms 取组件内 t.durChart）。 */
@Composable
private fun RollingInt(value: Int, replay: Int, t0Fg: Color) {
    val style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace)
    key(replay) {
        val shown = com.bsfeng.monitor.ui.components.animatedCount(value)
        Text(thousands(shown), style = style, color = t0Fg)
    }
}

/** h 与 m 两段同步从 0 滚入（契约 s01-kpi-active）。 */
@Composable
private fun RollingActive(minutes: Long, replay: Int) {
    val t = LocalDesignTokens.current
    val style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace)
    Row(verticalAlignment = Alignment.Bottom) {
        key(replay) {
            Text(
                com.bsfeng.monitor.ui.components.animatedCount((minutes / 60).toInt()).toString(),
                style = style,
                color = t.fg,
            )
        }
        Text("h", style = MaterialTheme.typography.labelMedium, color = t.muted)
        Spacer(Modifier.width(t.space1))
        key(replay) {
            Text(
                com.bsfeng.monitor.ui.components.animatedCount((minutes % 60).toInt()).toString(),
                style = style,
                color = t.fg,
            )
        }
        Text("m", style = MaterialTheme.typography.labelMedium, color = t.muted)
    }
}

/** 带 ¥ 前缀的金额滚动（千分位，契约 s01-kpi-spend / 「¥0」）。 */
@Composable
private fun RollingMoney(value: Double, replay: Int) {
    val t = LocalDesignTokens.current
    val style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace)
    key(replay) {
        val shown = com.bsfeng.monitor.ui.components.animatedAmount(value.toFloat())
        Text("¥" + money(shown.toDouble()), style = style, color = t.fg)
    }
}

/** 环图 + 图例（扇形与图例色同源、图例不可点）；无数据时整块写「暂无类型数据」。 */
@Composable
private fun RingBlock(slices: List<TypeSlice>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    if (slices.isEmpty()) {
        MetaText("暂无类型数据", modifier)
        return
    }
    val colors = slices.indices.map { t.categoryDot(it) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PieChart(
            segments = slices.map { it.count.toFloat() },
            modifier = Modifier.size(t.ringSize),
            colors = colors,
            size = t.ringSize,
        )
        Spacer(Modifier.width(t.space4))
        Legend(
            items = slices.mapIndexed { index, slice ->
                LegendItem(slice.label, slice.count.toString(), colors[index])
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 主按钮：44dp、满宽、--accent 底、0.96 按下 + 指定触感（契约 s01-quick-live / s01-empty-cta）。 */
@Composable
private fun PrimaryTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: HapticTone = HapticTone.LIGHT,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(t.accent)
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(tone)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = t.onAccent)
    }
}

/** 幽灵按钮：按下底色变浅一档（1.2 倍不透明度） + LIGHT 触感（契约 s01-quick-finance / s01-error-retry）。 */
@Composable
private fun GhostTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(if (pressed) t.fgFade(10) else t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = t.fg)
    }
}


/**
 * 今日行程卡（锚点 s01-trip / s01-trip-source / trip-band）。
 * 角标与时间带不单独拦截点击，统一穿透到整卡 → 进每日行程页（契约三行同结果）。
 */
@Composable
private fun TripCardS01(
    timeline: DayTimeline?,
    dayStart: Long,
    dayEnd: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val hasTrack = timeline != null && (timeline.stays.isNotEmpty() || timeline.moves.isNotEmpty())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .rowPressFeedback(interaction)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(t.space4),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "今日行程",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                modifier = Modifier.weight(1f),
            )
            // 无数据时角标换成「暂无数据」，不宣称推算基准
            Box(Modifier.testTag("s01-trip-source")) {
                Badge(if (timeline == null) "暂无数据" else basisBadge(timeline.basis))
            }
        }
        Spacer(Modifier.height(t.space3))
        if (!hasTrack) {
            Text(
                text = if (timeline == null) "今天还没有行程" else tripHeadline(timeline),
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
            )
            Spacer(Modifier.height(t.space1))
            MetaText("停留与移动由设备活动推算；采集中断的时间标成「未记录」，不做推断。")
        } else {
            Column(Modifier.testTag("trip-band")) {
                TripBand(segments = timeline.segments, dayStart = dayStart, dayEnd = dayEnd)
                Spacer(Modifier.height(t.space1))
                TripBandAxis()
                Spacer(Modifier.height(t.space2))
                TripBandLegend(timeline)
                Spacer(Modifier.height(t.space3))
                Text(
                    text = tripHeadline(timeline),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = t.fg,
                )
                Spacer(Modifier.height(t.space1))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetaText(tripCoverageNote(timeline))
                    MetaText("查看时间轴 ›")
                }
            }
        }
    }
}

// ───────────────────────────── 格式化（契约文案逐字） ─────────────────────────────

private fun thousands(value: Int): String = String.format(Locale.US, "%,d", value)

/** 契约「较昨日 +18 / 较昨日 0 / 较昨日 -4」：0 不带符号。 */
private fun signedDelta(delta: Int): String = when {
    delta > 0 -> "+" + delta
    delta < 0 -> delta.toString()
    else -> "0"
}

/**
 * 事件类环比副行（契约 s01-k7-events「较上周 +6.2%」/ s01-k30-events「较上月 +9.8%」）。
 *
 * 基数来自数据层真实 COUNT（上一段同长度窗口）；基数为 0 时比例没有定义 → 写「—」，
 * 不用 100% 之类编出来的数字充数。
 */
private fun eventDeltaText(prefix: String, current: Int, previous: Int): String {
    if (previous <= 0) return "$prefix —"
    val pct = (current - previous) * 100.0 / previous
    // 涨写「+6.2%」、跌写「-9.8%」、持平写「0.0%」（0 不带符号，与 s01-kpi 的「较昨日 0」同口径）
    val shown = if (pct > 0) String.format(Locale.US, "+%.1f%%", pct)
    else String.format(Locale.US, "%.1f%%", pct)
    return "$prefix $shown"
}

/** 跳过类环比副行（契约 s01-k7-skip「较上周 +31」/ s01-k30-skip「较上月 +122」）：给计数差。 */
private fun countDeltaText(prefix: String, current: Int, previous: Int): String =
    "$prefix " + signedDelta(current - previous)

/** 环比色调：涨走 --success、跌走 --danger；事件类基数为 0 时无可比基数 → MUTED。 */
private fun deltaToneOf(current: Int, previous: Int, percent: Boolean): DeltaTone =
    if (percent && previous <= 0) DeltaTone.MUTED else toneOf(current - previous)

/** 「42h 05m」：时分两段等宽（契约 s01-k7-active）。 */
private fun hoursMinutes(minutes: Long): String =
    (minutes / 60).toString() + "h " + String.format(Locale.US, "%02d", minutes % 60) + "m"

/** 「日均 6h01m」（契约 s01-k7-active / s01-k30-active）。 */
private fun dailyHoursMinutes(totalMinutes: Long, days: Int): String {
    val perDay = if (days <= 0) 0L else totalMinutes / days
    return (perDay / 60).toString() + "h" + String.format(Locale.US, "%02d", perDay % 60) + "m"
}