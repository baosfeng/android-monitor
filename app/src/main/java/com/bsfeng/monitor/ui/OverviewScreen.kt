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

/** 单日展示上限：到达即进边界态（锚点 s01-edge「10,842 条 / 已达单日展示上限」）。 */
private const val EDGE_DAILY_LIMIT = 10_000

/** 最近事件最多三行（锚点 s01-recent-1/2/3）。 */
internal const val RECENT_ROWS = 3

/** 空态描述（锚点 s01-empty 文案口径逐字）：已授权 / 未授权两套。 */
private const val EMPTY_DESC_READY = "开启无障碍服务后，屏幕、应用、网络与充电事件会自动汇总到这里。"
private const val EMPTY_DESC_DENIED =
    "无障碍服务未授权（设置 → 无障碍服务 → 去开启）。开启后，屏幕、应用、网络与充电事件会自动汇总到这里。"

/** 加载超时兜底：骨架停 8 秒就转错误态，文案与真实读取失败共用同一句「可以重试」。 */
private const val LOAD_TIMEOUT_MS = 8_000L
private const val LOAD_TIMEOUT_DESC = "统计读取超时。点「重试」再试一次，已采集的事件不会丢失。"



internal val RANGE_OPTIONS = listOf("今日", "近 7 天", "近 30 天")

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
    /** s01-goto-totp：右上角验证器图标。null = 功能关闭，图标整个不渲染。 */
    onOpenTotp: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()
    val haptic = rememberHaptic()
    var rangeIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 页头日期与三段面板必须同一时刻：数据侧 startOfDay 由 VM 每轮刷新现算（REFRESH_MS = 5s），
    // 页头原先用无 key 的 remember { System.currentTimeMillis() }，冻结在进屏那一刻 ——
    // 常驻前台跨零点后数据已按新的一天重算、页头却还写着昨天。
    // 以 lastUpdatedAt 为 key：只在一次成功读表后重算，不随每帧重组跳动。
    val now = remember(state.lastUpdatedAt) {
        overviewHeaderNow(state.lastUpdatedAt, System.currentTimeMillis())
    }

    // 骨架超时兜底（与 s06 预算页同一口径）：事件表被占用/损坏时，加载会一直停在这里 ——
    // 没有文案、没有重试入口，用户只能杀进程。8 秒后转错误态，至少给得出一个可操作的出口。
    var loadTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        loadTimedOut = false
        if (state.isLoading) {
            delay(LOAD_TIMEOUT_MS)
            loadTimedOut = true
        }
    }

    // 五态按真实数据判定；演示条（devbar）不进产品。
    val uiState = when {
        state.isLoading && !loadTimedOut -> FiveState.LOADING
        state.isLoading -> FiveState.ERROR
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
                onOpenTotp = onOpenTotp,
            )
            RangeSegment(
                selectedIndex = rangeIndex,
                onSelect = {
                    if (it != rangeIndex) {
                        rangeIndex = it
                        // 锚点 s01-range：切换后内容滚回顶部
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
                        // 已授权时空态不出现 CTA（锚点 s01-empty-cta 边界）：回调给 null 即整颗不渲染
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
                        // 文案逐字；异常原文只在 ViewModel 日志里。
                        // 超时兜底（loadError 仍为 null）落的是同一句概括，用户看到的都是「可以重试」。
                        text = state.loadError ?: LOAD_TIMEOUT_DESC,
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
                            1 -> weekPanelS01(state = state, now = now)
                            else -> monthPanelS01(state = state, now = now)
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
