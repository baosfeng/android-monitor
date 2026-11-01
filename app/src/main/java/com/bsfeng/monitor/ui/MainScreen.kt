package com.bsfeng.monitor.ui

// s02 实时事件流：页头 + 类型 chip + 热力条 + 时间线 + 五态 + 详情抽屉。
// 锚点（锚点账本）：screen-s02 / s02-content / s02-head /
// s02-sort / s02-chips / s02-loading / s02-empty / s02-empty-cta / s02-error / s02-error-retry /
// s02-edge / s02-filter-tip / s02-heatwrap。

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.FiveStateDemo
import com.bsfeng.monitor.ui.live.FiveStateDemoBar
import com.bsfeng.monitor.ui.live.LiveFilter
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.formatClock
import com.bsfeng.monitor.ui.live.groupByHourDesc
import com.bsfeng.monitor.ui.live.matching
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.delay

// ── 文案口径（口径末尾「文案口径」逐字） ──
private const val TOAST_SORTED = "已按时间倒序排列"
private const val TOAST_WAITING = "监控中 · 等待事件"
private const val TOAST_RECONNECT = "正在重新连接采集服务…"
private const val EMPTY_TITLE = "还没有事件"
private const val EMPTY_ACTION = "刷新试试"
private const val EMPTY_DESC_READY = "无障碍服务已就绪。屏幕、应用、网络与充电一旦变化，就会按小时汇总到这里。"
private const val EMPTY_DESC_DENIED = "无障碍服务未授权（设置 → 无障碍服务 → 去开启）。开启后，屏幕、应用、网络与充电事件会自动汇总到这里。"
private const val ERROR_TITLE = "事件流读取失败"
private const val ERROR_DESC = "采集服务无响应（AccessibilityService 未连接）。已落库的事件仍可查看。"
private const val ERROR_ACTION = "重新连接"
private const val EDGE_BADGE = "11,342 条"
/** 边界态超长行样例：仅调试演示可达（batch1.md §5 T2），产品页不会渲染。 */
private const val EDGE_SAMPLE =
    "微信（com.tencent.mm）· 前台使用 128 分钟 32 秒 · 这是一条刻意超长的应用名用来验证单行省略"
private const val EDGE_TEXT = "今日窗口已满"
private const val FILTER_TIP = "已按类型筛选 · 组头的「条数」仅在全部视图下显示"

/**
 * s02 实时事件流（原型 .open-design/src/screens/s02-实时事件流.html）。
 *
 * 结构：页头 → 类型 chip 筛选 → 24 格热力条 → 筛选提示 → 按小时分组的时间线（组头吸顶）→ 详情抽屉。
 * 五态由真实数据推导，debug 下可用「原型控制」覆盖。
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastUpdatedAt by viewModel.lastUpdatedAt.collectAsStateWithLifecycle()
    val reconnectTick by viewModel.reconnectTick.collectAsStateWithLifecycle()
    // s01-recent-1..3：概览页传来的「滚到这一条」请求（一次性，时间线消费后清空）
    val pendingFocusId by viewModel.pendingLiveFocusId.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val toast = rememberToastHostState()

    var filterIndex by remember { mutableIntStateOf(0) }
    // s01-recent-1..3：聚焦请求可能指向被当前类型筛选藏掉的事件 —— 先退回「全部」，
    // 否则时间线里根本没有这一行，滚动定位无从谈起（筛选本身是用户上一轮的临时选择）。
    LaunchedEffect(pendingFocusId) {
        if (pendingFocusId != null && filterIndex != 0) filterIndex = 0
    }
    var selected by remember { mutableStateOf<Record?>(null) }
    // 错态重连：整块转骨架，直到本轮刷新落地（lastUpdatedAt 前进）再回真实态
    var reconnecting by remember { mutableStateOf(false) }
    var reconnectBaseline by remember { mutableLongStateOf(0L) }
    var reconnectTickBaseline by remember { mutableLongStateOf(0L) }

    // 抽屉关闭后焦点回触发它的那一行（行可能已滚出屏 → 失败不崩）
    val rowRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var pendingRowFocus by remember { mutableStateOf<String?>(null) }

    // 进屏 / 切筛选时重新开窗：窗口内首次挂载的组头与行才播入场动画
    val enterWindow = remember { EnterWindow() }
    LaunchedEffect(filterIndex) {
        enterWindow.reopen()
        delay(ENTER_WINDOW_MS)
        enterWindow.close()
    }
    LaunchedEffect(pendingRowFocus) {
        val id = pendingRowFocus ?: return@LaunchedEffect
        runCatching { rowRequesters[id]?.requestFocus() }
        pendingRowFocus = null
    }
    // 骨架退出的两个信号：① 本轮刷新成功推进了 lastUpdatedAt；② 本轮重连尝试结束
    // （成功或失败都会推进 reconnectTick）。只看 ① 时「再失败」会永久卡在骨架、回不到错态。
    LaunchedEffect(lastUpdatedAt, reconnectTick) {
        if (reconnecting && (lastUpdatedAt > reconnectBaseline || reconnectTick > reconnectTickBaseline)) {
            reconnecting = false
        }
    }

    val filter = LiveFilter.entries[filterIndex]
    val visibleEvents = remember(state.recentEvents, filterIndex) {
        state.recentEvents.matching(filter)
    }
    val groups = remember(visibleEvents) { groupByHourDesc(visibleEvents) }

    val realState = when {
        reconnecting -> FiveState.LOADING
        state.isLoading && state.todayEventCount == 0 -> FiveState.LOADING
        state.loadError != null -> FiveState.ERROR
        state.todayEventCount == 0 -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val shownState = FiveStateDemo.effective(realState)

    fun countOf(f: LiveFilter): Int =
        if (f == LiveFilter.ALL) state.todayEventCount
        else f.types.sumOf { state.typeCounts[it] ?: 0 }

    fun closeSheet() {
        val id = selected?.id
        selected = null
        if (id != null) pendingRowFocus = id
    }

    BackHandler(enabled = selected != null) { closeSheet() }

    Box(modifier.fillMaxSize().testTag("screen-s02")) {
        Column(Modifier.fillMaxSize().testTag("s02-content")) {
            LivePageHead(
                count = state.todayEventCount,
                lastUpdatedAt = lastUpdatedAt,
                onSortClick = { toast.show(TOAST_SORTED) },
            )

            LiveFilterChips(
                items = LiveFilter.entries.map { it.label + " " + countOf(it) },
                selectedIndex = filterIndex,
                onSelect = { filterIndex = it },
            )

            LiveIntensityPanel(
                hourlyIntensity = state.hourlyIntensity,
                modifier = Modifier.testTag("s02-heatwrap"),
            )

            if (filter != LiveFilter.ALL) {
                LiveFilterTip()
            }

            Spacer(Modifier.height(t.space2))

            Box(Modifier.weight(1f)) {
                when (shownState) {
                    // 空 / 错 / 边界一律走 ui/components 的共享件：块级锚点走 modifier，
                    // CTA 锚点走 actionModifier（页面不再自持第二份规格）。
                    FiveState.LOADING -> LiveLoadingBlock()
                    FiveState.EMPTY -> EmptyState(
                        text = if (state.accessibilityEnabled) EMPTY_DESC_READY else EMPTY_DESC_DENIED,
                        title = EMPTY_TITLE,
                        icon = Icons.Outlined.Widgets,
                        actionLabel = EMPTY_ACTION,
                        onAction = {
                            toast.show(TOAST_WAITING)
                            viewModel.retry()
                        },
                        actionModifier = Modifier.testTag("s02-empty-cta"),
                        modifier = Modifier.testTag("s02-empty"),
                    )
                    FiveState.ERROR -> ErrorState(
                        text = ERROR_DESC,
                        title = ERROR_TITLE,
                        actionLabel = ERROR_ACTION,
                        onRetry = {
                            reconnectBaseline = lastUpdatedAt
                            reconnectTickBaseline = reconnectTick
                            reconnecting = true
                            toast.show(TOAST_RECONNECT)
                            viewModel.retry()
                        },
                        actionModifier = Modifier.testTag("s02-error-retry"),
                        modifier = Modifier.testTag("s02-error"),
                    )
                    FiveState.EDGE -> EdgeState(
                        // 该态真实数据不可达（只有调试演示条能强制），内容沿用原型样例，
                        // 见 batch1.md §5 T2：产品页不会渲染到这里。
                        text = EDGE_TEXT + " · 缺失字段：" + PLACEHOLDER,
                        modifier = Modifier.testTag("s02-edge"),
                        content = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = EDGE_SAMPLE,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = t.fg2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(t.space3))
                                Badge(EDGE_BADGE, tone = BadgeTone.Accent)
                            }
                        },
                    )
                    FiveState.POPULATED -> LiveEventTimeline(
                        groups = groups,
                        filter = filter,
                        enterWindow = enterWindow,
                        hasMore = state.hasMore,
                        isLoadingMore = state.isLoadingMore,
                        todayEventCount = state.todayEventCount,
                        loadedCount = state.recentEvents.size,
                        onSelect = { selected = it },
                        onLoadMore = { viewModel.loadMore() },
                        focusRequesterFor = { id -> rowRequesters.getOrPut(id) { FocusRequester() } },
                        focusRecordId = pendingFocusId,
                        onFocusConsumed = { viewModel.consumeLiveFocus() },
                    )
                }
            }

            FiveStateDemoBar()
        }

        selected?.let { record ->
            EventDetailSheet(
                record = record,
                toast = toast,
                onDismiss = { closeSheet() },
            )
        }

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 页头：「今日已记录 N 条 · 最近更新 HH:mm」+ 大标题 + 排序按钮（44dp，0.96，LIGHT）。 */
@Composable
private fun LivePageHead(count: Int, lastUpdatedAt: Long, onSortClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s02-head")
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText(
                "今日已记录 " + count + " 条 · 最近更新 " +
                    (if (lastUpdatedAt > 0L) formatClock(lastUpdatedAt) else PLACEHOLDER),
            )
            Spacer(Modifier.height(t.space2))
            Text("实时事件流", style = MaterialTheme.typography.headlineMedium, color = t.fg)
        }
        Spacer(Modifier.width(t.space3))
        Box(
            modifier = Modifier
                .size(t.tapMin)
                .pressFeedback(interaction)
                .clip(CircleShape)
                .background(t.surface)
                .border(1.dp, t.borderSoft, CircleShape)
                .keyboardFocusRing(CircleShape)
                .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                    haptic(HapticTone.LIGHT)
                    onSortClick()
                }
                .testTag("s02-sort"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Sort,
                contentDescription = "按时间倒序排列",
                tint = t.fg2,
                modifier = Modifier.size(t.iconSize / 2),
            )
        }
    }
}

/** 类型筛选 chip 组：六选一互斥；键盘左右方向键即时切换；Tab 只停当前选中项。 */
@Composable
private fun LiveFilterChips(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusIndex) {
        pendingFocusIndex?.let { index ->
            runCatching { requesters[index].requestFocus() }
            pendingFocusIndex = null
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("s02-chips")
            .padding(horizontal = t.space4),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items.forEachIndexed { index, label ->
            LiveFilterChip(
                label = label,
                selected = index == selectedIndex,
                canFocus = index == selectedIndex,
                focusRequester = requesters[index],
                onClick = {
                    haptic(HapticTone.LIGHT)
                    onSelect(index)
                },
                onMove = { step ->
                    val next = (index + step + items.size) % items.size
                    onSelect(next)
                    pendingFocusIndex = next
                },
            )
        }
    }
}

/** 单个筛选 chip：选中底 fg 7% / 描边 fg 24% / 字重 600，切换 --dur-state。 */
@Composable
private fun LiveFilterChip(
    label: String,
    selected: Boolean,
    canFocus: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusPill)
    val bg by animateColorAsState(
        targetValue = if (selected) t.fgFade(7) else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(t.durState),
        label = "s02ChipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) t.fg else t.fg2,
        animationSpec = tween(t.durState),
        label = "s02ChipFg",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) t.fgFade(24) else t.border,
        animationSpec = tween(t.durState),
        label = "s02ChipStroke",
    )
    Box(
        modifier = Modifier
            .height(t.tapMin)
            .focusRequester(focusRequester)
            .focusProperties { this.canFocus = canFocus }
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .testTag("chip.filter")
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onMove(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        onMove(1)
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = t.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 筛选提示行：仅类型 ≠ 全部时出现；纯说明文案，不可点、不可关闭。 */
@Composable
private fun LiveFilterTip(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(
        text = FILTER_TIP,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space1)
            .testTag("s02-filter-tip"),
        style = MaterialTheme.typography.labelSmall,
        color = t.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 加载骨架：3 行、1400ms shimmer，不接收点击、不可聚焦。 */
@Composable
private fun LiveLoadingBlock() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = LocalDesignTokens.current.space4)
            .testTag("s02-loading"),
    ) {
        StateSkeleton()
    }
}

/** 通用 CTA：44dp、按下 0.96、LIGHT 触感、键盘焦点环。 */
@Composable
internal fun LiveActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = modifier
            .heightIn(min = t.tapMin)
            .pressFeedback(interaction)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = t.fg,
            maxLines = 1,
            modifier = Modifier.testTag("button.action"),
        )
    }
}
