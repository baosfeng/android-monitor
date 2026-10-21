package com.bsfeng.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.ChipRow
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.FiveStateDemo
import com.bsfeng.monitor.ui.live.FiveStateDemoBar
import com.bsfeng.monitor.ui.live.HourGroupHead
import com.bsfeng.monitor.ui.live.LiveEventRow
import com.bsfeng.monitor.ui.live.LiveFilter
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.ScreenHead
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.live.detailOr
import com.bsfeng.monitor.ui.live.formatClock
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.formatDurationMs
import com.bsfeng.monitor.ui.live.groupByHourDesc
import com.bsfeng.monitor.ui.live.matching
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.live.recordSubtitle
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.heatHeight
import java.util.Locale
import kotlinx.coroutines.delay

/** 无数据热力轨的尺寸 / 文案（原型空态无热力条，这里保留一行极浅空轨以示「无数据」）。 */
private val EMPTY_TRACK_HEIGHT = 2.dp
private val EMPTY_TRACK_RADIUS = 2.dp
private const val EMPTY_HEAT_TEXT = "暂无数据"

/**
 * 进屏后的入场窗口（ms）：进屏 / 切筛选后这段时间内首次挂载的行才播入场动画。
 * `dur-page(340) + stagger(24) × STAGGER_MAX_STEPS(12) = 628ms` 是首屏整段的动画时长，
 * 窗口取 600ms 保证「进屏那一屏」能播完整套错峰，又不让后续滚动继续起动画。
 */
private const val ENTER_WINDOW_MS = 600L

/**
 * 入场窗口开关：进屏 / 切筛选后重新打开，窗口内首次挂载的组头与行才播入场动画。
 *
 * 有意**不用** Compose 状态：只有「之后挂载」的行需要读它，屏上已挂载的行不该因为
 * 窗口关闭而多重组一次（窗口开关与 UI 无关，只是组合期的瞬时判定）。
 */
private class EnterWindow {
    var open: Boolean = true
        private set

    fun reopen() {
        open = true
    }

    fun close() {
        open = false
    }
}

/**
 * s02 实时事件流（原型 .open-design/08-prototype.html → parts/s02-live.html）。
 *
 * 结构：页头 → 类型 chip 筛选 → 24 格热力条 → 按小时分组的时间线（组头吸顶）→ 事件详情底部抽屉。
 * 五态由真实数据推导，debug 下可用「原型控制」覆盖。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    // 生命周期感知收集：退到后台即取消订阅 → ViewModel 轮询同步挂起（见 MainViewModel.init）
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()

    var filterIndex by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Record?>(null) }

    // 进屏后的入场窗口：进屏 / 切筛选时重新开窗，窗口内首次挂载的组头与行才播入场动画；
    // 窗口关掉之后滚动新挂载的一律落终态（否则每滚到一个新小时组，组头 12 行都会重播 340ms 入场，
    // 动画期间每行每帧重组）。
    val enterWindow = remember { EnterWindow() }
    LaunchedEffect(filterIndex) {
        enterWindow.reopen()
        delay(ENTER_WINDOW_MS)
        enterWindow.close()
    }

    val filter = LiveFilter.entries[filterIndex]
    val visibleEvents = remember(state.recentEvents, filterIndex) {
        state.recentEvents.matching(filter)
    }
    val groups = remember(visibleEvents) { groupByHourDesc(visibleEvents) }

    val clipboard = LocalClipboardManager.current

    val realState = when {
        state.isLoading && state.todayEventCount == 0 -> FiveState.LOADING
        state.loadError != null -> FiveState.ERROR
        state.todayEventCount == 0 -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val peak = remember(state.hourlyIntensity) { peakOf(state.hourlyIntensity) }
    // 热力条与峰值文案同源：0 条记录时既不出满高深色柱，也不写「峰值 00:00 · 0 条」
    val hasIntensity = remember(state.hourlyIntensity) { state.hourlyIntensity.any { it > 0f } }
    val peakText = if (hasIntensity) {
        "峰值 " + String.format(Locale.US, "%02d:00", peak.first) + " · " + peak.second.toInt() + " 条"
    } else {
        EMPTY_HEAT_TEXT
    }

    fun countOf(f: LiveFilter): Int =
        if (f == LiveFilter.ALL) state.todayEventCount
        else f.types.sumOf { state.typeCounts[it] ?: 0 }

    BackHandler(enabled = selected != null) { selected = null }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LiveScreenHead(
                viewModel = viewModel,
                count = state.todayEventCount,
                onSortClick = { toast.show("已按时间倒序排列") },
            )

            ChipRow(
                items = LiveFilter.entries.map { it.label + " " + countOf(it) },
                selectedIndex = filterIndex,
                onSelect = { filterIndex = it },
            )

            Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetaText("今日活跃强度")
                    MetaText(peakText)
                }
                Spacer(Modifier.height(t.space2))
                if (hasIntensity) {
                    HeatBar(
                        values = state.hourlyIntensity,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(t.heatHeight),
                    )
                } else {
                    // 无数据空轨：HeatBar 在 values 全 0 时 max 兜底为 1f → level=0 → chart-1
                    // 最深的满高柱（设备实证的 P0 假象），这里改为 2dp 浅色空轨（圆角与热力格一致）
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(EMPTY_TRACK_HEIGHT)
                            .background(t.borderSoft, RoundedCornerShape(EMPTY_TRACK_RADIUS)),
                    )
                }
            }
            Spacer(Modifier.height(t.space2))

            StateContainer(
                state = FiveStateDemo.effective(realState),
                modifier = Modifier.weight(1f),
                emptyText = noEventEmptyText(state.accessibilityEnabled),
                errorText = state.loadError ?: "采集服务无响应（AccessibilityService 未连接）。已落库的事件仍可查看。",
                edgeText = "微信（com.tencent.mm）· 前台使用 128 分钟 32 秒 · 累计 1,024 次前台切换",
                onRetry = { viewModel.retry() },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = t.space4,
                        end = t.space4,
                        bottom = t.space6,
                    ),
                ) {
                    groups.forEachIndexed { gi, group ->
                        stickyHeader(key = "head-" + group.hour) {
                            // 只在首次挂载时判定（失败即落终态）：滚动中新挂载的组头不重播入场
                            val headEnter = remember(group.hour) {
                                if (enterWindow.open && gi < STAGGER_MAX_STEPS) gi else -1
                            }
                            HourGroupHead(
                                label = group.label,
                                count = group.events.size,
                                showCount = filter == LiveFilter.ALL,
                                modifier = Modifier.then(rememberStaggerEnter(headEnter)),
                            )
                        }
                        itemsIndexed(
                            items = group.events,
                            key = { _, record -> record.id },
                        ) { ei, record ->
                            // J5：入场动画只给「进屏那一屏」的行；滚动新挂载的一律落终态。
                            // 用 remember 快照，窗口关闭时不会把正在播的行掐到终态。
                            val rowEnter = remember(record.id) {
                                if (enterWindow.open && ei < STAGGER_MAX_STEPS) ei + 1 else -1
                            }
                            StackRow(
                                first = false,
                                last = ei == group.events.lastIndex,
                                modifier = Modifier.then(rememberStaggerEnter(rowEnter)),
                            ) {
                                // 原型 s02 时间列是 HH:mm（.s02-t / .tl-time，s02-live.html:196），
                                // 46dp 时间列放得下 5 字符等宽；带秒（8 字符）会折行/裁切。
                                LiveEventRow(record = record, showSeconds = false, onClick = { selected = record })
                            }
                        }
                        if (gi != groups.lastIndex) {
                            item(key = "gap-" + group.hour) { Spacer(Modifier.height(t.space3)) }
                        }
                    }
                    if (filter == LiveFilter.ALL && (state.hasMore || state.isLoadingMore)) {
                        item(key = "load-more") {
                            Spacer(Modifier.height(t.space3))
                            OutlinedButton(
                                onClick = { viewModel.loadMore() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(t.radiusPill),
                            ) {
                                Text(
                                    text = if (state.isLoadingMore) "加载中…"
                                    else "加载更早的事件（还有 " + (state.todayEventCount - state.recentEvents.size) + " 条）",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            FiveStateDemoBar()
        }

        selected?.let { record ->
            // 遮罩由 BottomSheet 的窗口级全屏浮层承担（页内遮罩盖不住状态栏/手势条，且会叠成两层）
            Box(Modifier.fillMaxSize()) {
                BottomSheet(
                    visible = true,
                    onDismiss = { selected = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = record.label.ifBlank { PLACEHOLDER },
                            style = MaterialTheme.typography.titleSmall,
                            color = t.fg,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Badge(recordTypeLabel(record.type))
                    }
                    Spacer(Modifier.height(t.space3))
                    DetailRow("发生时间", formatClockSec(record.timestamp), first = true)
                    DetailRow("事件类型", recordTypeLabel(record.type) + " · " + record.action)
                    DetailRow("持续时长", formatDurationMs(record.duration))
                    DetailRow("包名", record.detailOr("package_name"))
                    DetailRow("来源", recordSubtitle(record))
                    Spacer(Modifier.height(t.space4))
                    Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                        OutlinedButton(
                            onClick = { selected = null },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(t.radiusPill),
                        ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(record.toString()))
                                toast.show("已复制事件 JSON")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(t.radiusPill),
                        ) { Text("复制 JSON", style = MaterialTheme.typography.labelLarge) }
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

/**
 * s02 页头（标题 + 「今日已记录 N 条 · 最近更新 HH:mm」+ 排序按钮）。
 *
 * 只订阅 [MainViewModel.lastUpdatedAt]：该值每轮轮询都变，收在这里后重组范围就是这一行，
 * 列表 state 因此可以在数据没变时完全不发（见 [MainViewModel.refreshData]）。
 */
@Composable
private fun LiveScreenHead(
    viewModel: MainViewModel,
    count: Int,
    onSortClick: () -> Unit,
) {
    val lastUpdatedAt by viewModel.lastUpdatedAt.collectAsStateWithLifecycle()
    ScreenHead(
        title = "实时事件流",
        meta = "今日已记录 " + count + " 条 · 最近更新 " +
            (if (lastUpdatedAt > 0L) formatClock(lastUpdatedAt) else PLACEHOLDER),
        actionIcon = Icons.Outlined.Sort,
        actionDesc = "按时间倒序排列",
        onAction = onSortClick,
    )
}
