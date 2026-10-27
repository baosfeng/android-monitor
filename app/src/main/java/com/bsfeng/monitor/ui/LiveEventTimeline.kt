package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时页的事件时间线 —— 按小时分组、组头吸顶、逐行入场、分页加载更多。
// 锚点（契约 s02）：s02-timeline / s02-g09..s02-g05 / s02-ev-page / s02-ev-notification / s02-loadwrap。

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.HourGroup
import com.bsfeng.monitor.ui.live.LiveFilter
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.live.formatClock
import com.bsfeng.monitor.ui.live.recordSourceLabel
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/** 时间列宽度（原型 .tl-item 时间列 46px）。 */
private val TL_TIME_COL_W = 46.dp

/**
 * 聚焦命中行的高亮时长 / 淡出 / 底色（s01-recent-1..3：从概览点进来要能看出是哪一条）。
 *
 * s01 契约只写「滚到可视区」没写高亮时长，这里沿用 s04 自动记账新行的同一口径
 * （FinanceMainDerived.kt:12-18：1.6s 保持 + 240ms 淡出 + 12% accent 底），
 * 避免同一 App 里出现第二种「新行点亮」节奏。
 */
private const val FOCUS_HIGHLIGHT_HOLD_MS = 1_600L
private const val FOCUS_HIGHLIGHT_FADE_MS = 240
private const val FOCUS_HIGHLIGHT_ALPHA = 0.12f

/** 等目标行出现在列表里的上限：超过就丢弃这次聚焦请求（数据 2s 内还没到，抢滚动只会让用户莫名其妙）。 */
private const val FOCUS_LOOKUP_TIMEOUT_MS = 2_000L

private const val TAG = "LiveEventTimeline"

/**
 * 一次性聚焦请求（[LiveEventTimeline] 的 focusRecordId）的推进阶段 —— 把「何时消费信号」
 * 从 Compose 协程里抽成可单测的纯状态（复走查 P1-02 的回归点，测试见 LiveFocusConsumeTimingTest）。
 *
 * 约束：消费信号会把 `pendingLiveFocusId` 置空 → `focusRecordId` 变 null → 本页
 * `LaunchedEffect(focusRecordId, groups)` 的 key 变化 → 正在跑的滚动协程被取消
 * （`CancellationException`），两步滚动的第二步永远轮不到。所以顺序钉死为「先滚动、后消费」。
 */
internal enum class LiveFocusStage {
    /** 目标行不在当前已加载分组里（首帧数据未落地）：不消费，等 groups 变化重试或超时兜底 */
    WAITING_DATA,

    /** 已定位、两步滚动未跑完（含滚动中）：此刻消费 = 取消自己，列表最多动一两帧 */
    SCROLLING,

    /** 两步滚动跑完（含抛错后放弃定位）：唯一允许消费的阶段 */
    SETTLED,
}

/** 是否允许此刻清空一次性聚焦请求：只有滚动阶段结束后才允许。 */
internal fun liveFocusConsumesSignal(stage: LiveFocusStage): Boolean = stage == LiveFocusStage.SETTLED

/**
 * 超时兜底到点时该报哪个阶段（兜底是**第二个**消费者，同样要过 [liveFocusConsumesSignal] 这道闸）。
 *
 * 到点仍没找到目标行、也没有滚动在跑 → 这条请求已经无处可滚，判 SETTLED，允许丢弃；
 * 目标已落地 / 滚动进行中 → 判 SCROLLING，不许消费，交给主 effect 在两步滚动之后自己收尾。
 * 反例（R3-P2-01）：兜底无条件 `onFocusConsumed()` 会改掉 `focusRecordId`，把主 effect 正在跑的
 * `animateScrollToItem` 连带取消 —— 数据晚到或两步滚动长于 2s 时必现。
 */
internal fun liveFocusTimeoutStage(scrolling: Boolean, targetPresent: Boolean): LiveFocusStage =
    if (scrolling || targetPresent) LiveFocusStage.SCROLLING else LiveFocusStage.SETTLED

/** 页面轨迹事件：action=PAGE，或 detail 带页面字段。 */
internal fun Record.isPageEvent(): Boolean =
    action.equals("PAGE", ignoreCase = true) ||
        detail?.containsKey("page_title") == true ||
        detail?.containsKey("page_fingerprint") == true

/** 通知事件：action 含 NOTIFICATION，或 detail 带通知渠道字段。 */
internal fun Record.isNotificationEvent(): Boolean =
    action.uppercase(Locale.US).contains("NOTIFICATION") ||
        detail?.containsKey("channel") == true ||
        detail?.containsKey("notification_channel") == true

/** 事件行锚点：页面轨迹 / 通知事件各一个，其余按类型回落。 */
private fun Modifier.eventAnchor(record: Record): Modifier = when {
    record.isPageEvent() -> testTag("s02-ev-page")
    record.isNotificationEvent() -> testTag("s02-ev-notification")
    else -> testTag("s02-ev-" + record.type.toString().lowercase(Locale.US))
}

/** 小时组头锚点：原型固定 09..05 五个组，其余小时按同一命名回落。 */
private fun Modifier.hourAnchor(hour: Int): Modifier = when (hour) {
    9 -> testTag("s02-g09")
    8 -> testTag("s02-g08")
    7 -> testTag("s02-g07")
    6 -> testTag("s02-g06")
    5 -> testTag("s02-g05")
    else -> testTag(String.format(Locale.US, "s02-g%02d", hour))
}

/**
 * 在时间线里定位一条事件：返回（所在小时组头的 LazyColumn 下标, 行本身的下标）。
 *
 * 下标必须与下面 DSL 的 item 顺序逐项对齐：组头 → 组内行 → 组间 8dp 空隙（最后一个组没有空隙）。
 * 找不到（该条已被清理 / 不在当前已加载页）返回 (-1, -1)，调用方据此放弃滚动，不抛异常。
 */
internal fun lazyIndexesOfRecord(groups: List<HourGroup>, recordId: String): Pair<Int, Int> {
    var index = 0
    for ((gi, group) in groups.withIndex()) {
        val headerIndex = index
        index++ // 组头
        val offsetInGroup = group.events.indexOfFirst { it.id == recordId }
        if (offsetInGroup >= 0) return headerIndex to (index + offsetInGroup)
        index += group.events.size
        if (gi != groups.lastIndex) index++ // 组间空隙 item
    }
    return -1 to -1
}

/** 行副标题：契约固定「页面轨迹 · 无障碍服务」「通知事件 · 正文不记录」，其余按类型推导。 */
internal fun rowSubtitleOf(record: Record): String = when {
    record.isPageEvent() -> "页面轨迹 · 无障碍服务"
    record.isNotificationEvent() -> "通知事件 · 正文不记录"
    else -> recordTypeLabel(record.type) + "事件 · " + recordSourceLabel(record.type)
}

/**
 * s02 事件时间线列表（LazyColumn）：组头吸顶 + 按小时分组 + 分页加载更多。
 *
 * 入场动画只给「进屏那一屏」的行（[enterWindow]）：滚动新挂载的一律落终态。
 * [focusRequesterFor] 由页面持有：抽屉关闭后把焦点送回触发它的那一行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveEventTimeline(
    groups: List<HourGroup>,
    filter: LiveFilter,
    enterWindow: EnterWindow,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    todayEventCount: Int,
    loadedCount: Int,
    onSelect: (Record) -> Unit,
    onLoadMore: () -> Unit,
    focusRequesterFor: (String) -> FocusRequester,
    /**
     * s01-recent-1..3：从概览页带过来的「滚到这一条」请求（一次性）。
     * null = 没有请求；与 [onFocusConsumed] 成对使用。
     */
    focusRecordId: String? = null,
    /** 已定位（或确认无法定位）后回调，调用方据此清空一次性请求，避免重复抢滚动。 */
    onFocusConsumed: () -> Unit = {},
) {
    val t = LocalDesignTokens.current
    val listState = rememberLazyListState()
    // 高亮只在「本次聚焦」这一行上点亮 1.6s + 240ms 淡出，到点自动卸掉（不常驻 remember 状态）
    var focusedRowId by remember { mutableStateOf<String?>(null) }
    // 自动卸高亮独立成 effect：消费聚焦信号会让下面那个 effect 的 key 变化并重启它，
    // 复位若写在那里就会被连带取消，该行永远停在高亮态（复走查 P2-14）。
    LaunchedEffect(focusedRowId) {
        val activeId = focusedRowId ?: return@LaunchedEffect
        delay(FOCUS_HIGHLIGHT_HOLD_MS + FOCUS_HIGHLIGHT_FADE_MS)
        if (focusedRowId == activeId) focusedRowId = null
    }
    LaunchedEffect(focusRecordId, groups) {
        // 局部快照：本次 effect 全程只认这个 id —— 消费信号后回读 focusRecordId 只会读到 null。
        val id = focusRecordId ?: return@LaunchedEffect
        val (headerIndex, rowIndex) = lazyIndexesOfRecord(groups, id)
        // 找不到先不消费：首帧数据可能还没落地（切 Tab 同时触发加载 / 筛选刚被退回「全部」），
        // 等 groups 变化后本 effect 重跑再试；超时兜底见下面那个 effect。
        var stage = LiveFocusStage.WAITING_DATA
        if (rowIndex >= 0) {
            stage = LiveFocusStage.SCROLLING
            focusedRowId = id
            // 契约 s01-recent-*：滚到「该条本身」，不是滚到组头 —— 目标行常不是组内第一行，
            // 只滚组头会把它留在视口外，或被吸顶组头顶住。
            // 两步：① 先把组头滚到顶部（保证它被组合出来、量得到真实高度）；② 再按组头高度
            // 给行留出偏移，让行落在吸顶组头下方。组头是吸顶的，滚到行时它仍占住顶部这段高度。
            val hour = groups.firstOrNull { group -> group.events.any { it.id == id } }?.hour
            try {
                listState.animateScrollToItem(headerIndex)
                val headPx = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == "head-" + hour }?.size ?: 0
                // 量不到组头高度（极端首帧）就停在组头位置，不比原来更差
                if (headPx > 0) listState.animateScrollToItem(rowIndex, -headPx)
            } catch (c: CancellationException) {
                // 协程取消是正常控制流：必须往外抛，否则上层永远不知道自己被取消。
                throw c
            } catch (e: Throwable) {
                // 只有真异常才吞：记日志并放弃本次定位，不再无限重试。
                Log.w(TAG, "聚焦滚动失败：id=" + id, e)
            }
            stage = LiveFocusStage.SETTLED
        }
        // 两步滚动之后才消费：先消费会改掉本 effect 的 key，把这次滚动自己取消掉（复走查 P1-02）。
        if (liveFocusConsumesSignal(stage)) onFocusConsumed()
    }
    // 找不到目标的兜底：最多等 [FOCUS_LOOKUP_TIMEOUT_MS]，到点仍没有就消费掉，
    // 避免一条失效请求长期挂在 ViewModel 里，之后每次进实时页都抢一次滚动。
    // 兜底是第二个消费者，必须和主 effect 过同一道 stage 闸（R3-P2-01）：目标刚落地 / 滚动进行中时
    // 消费会改掉 focusRecordId，把上面那个 effect 正在跑的两步滚动取消掉。阶段按「此刻的」事实判：
    // 目标在不在用最新 groups 重算，滚动状态取列表自己的 isScrollInProgress。
    val latestGroups by rememberUpdatedState(groups)
    LaunchedEffect(focusRecordId) {
        val id = focusRecordId ?: return@LaunchedEffect
        delay(FOCUS_LOOKUP_TIMEOUT_MS)
        if (focusRecordId != id) return@LaunchedEffect
        val stage = liveFocusTimeoutStage(
            scrolling = listState.isScrollInProgress,
            targetPresent = lazyIndexesOfRecord(latestGroups, id).second >= 0,
        )
        if (liveFocusConsumesSignal(stage)) onFocusConsumed()
    }
    val headRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    // 加载更多完成后焦点落到「新出现的第一个组头」；用加载前的最高小时做分界。
    var loadAnchorHour by remember { mutableStateOf<Int?>(null) }
    var sawLoading by remember { mutableStateOf(false) }
    LaunchedEffect(isLoadingMore, groups.size) {
        if (isLoadingMore) {
            sawLoading = true
        } else if (sawLoading) {
            sawLoading = false
            val anchor = loadAnchorHour
            loadAnchorHour = null
            if (anchor != null) {
                groups.firstOrNull { it.hour < anchor }?.let { target ->
                    runCatching { headRequesters.getOrPut(target.hour) { FocusRequester() }.requestFocus() }
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("s02-timeline"),
        contentPadding = PaddingValues(start = t.space4, end = t.space4, bottom = t.space6),
    ) {
        // 筛选无命中（或数据里确实一行都没进当前分组）：只出一行说明，不出空态大图
        if (groups.isEmpty()) {
            item(key = "no-match") { LiveNoMatchRow() }
        }
        groups.forEachIndexed { gi, group ->
            stickyHeader(key = "head-" + group.hour) {
                val headEnter = remember(group.hour) {
                    if (enterWindow.open && gi < STAGGER_MAX_STEPS) gi else -1
                }
                LiveHourGroupHead(
                    label = group.label,
                    count = group.events.size,
                    showCount = filter == LiveFilter.ALL,
                    modifier = Modifier
                        .staggerEnterAt(headEnter)
                        .hourAnchor(group.hour)
                        .focusRequester(headRequesters.getOrPut(group.hour) { FocusRequester() })
                        .focusable()
                        .keyboardFocusRing(RoundedCornerShape(t.radiusLg)),
                )
            }
            itemsIndexed(items = group.events, key = { _, record -> record.id }) { ei, record ->
                val rowEnter = remember(record.id) {
                    if (enterWindow.open && ei < STAGGER_MAX_STEPS) ei + 1 else -1
                }
                StackRow(
                    first = false,
                    last = ei == group.events.lastIndex,
                    modifier = Modifier.staggerEnterAt(rowEnter),
                ) {
                    LiveTimelineRow(
                        record = record,
                        highlighted = record.id == focusedRowId,
                        modifier = Modifier
                            .eventAnchor(record)
                            .focusRequester(focusRequesterFor(record.id))
                            .testTagRowPress { onSelect(record) },
                    )
                }
            }
            if (gi != groups.lastIndex) {
                item(key = "gap-" + group.hour) { Spacer(Modifier.height(t.space3)) }
            }
        }
        if (filter == LiveFilter.ALL && (hasMore || isLoadingMore)) {
            item(key = "load-more") {
                Spacer(Modifier.height(t.space3))
                LiveLoadMore(
                    isLoading = isLoadingMore,
                    remaining = (todayEventCount - loadedCount).coerceAtLeast(0),
                    onClick = {
                        loadAnchorHour = groups.firstOrNull()?.hour
                        onLoadMore()
                    },
                )
            }
        }
    }
}

/**
 * 筛选无命中时的一行说明（契约 s02-chips 边界「列表区显示空行文案且不出空态大图」）。
 *
 * 文案与 s03 同口径（s03-chips 边界逐字为「该筛选方式下暂无记录」），不另编一句。
 */
@Composable
private fun LiveNoMatchRow() {
    val t = LocalDesignTokens.current
    Text(
        text = "该筛选方式下暂无记录",
        style = MaterialTheme.typography.bodySmall,
        color = t.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space6),
    )
}

/** 小时组头（吸顶）：不可点、不折叠；筛选态隐藏条数徽标。 */
@Composable
private fun LiveHourGroupHead(
    label: String,
    count: Int,
    showCount: Boolean,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(topStart = t.radiusLg, topEnd = t.radiusLg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .padding(horizontal = t.space4, vertical = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
        )
        if (showCount) Badge(count.toString() + " 条事件")
    }
}

/** 事件行本体：行高 ≥ 52dp、标题单行省略、副行不换行；[highlighted] 时整行点亮一次。 */
@Composable
private fun LiveTimelineRow(
    record: Record,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.focusHighlight() else Modifier)
            .heightIn(min = t.rowMinH)
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClock(record.timestamp),
            style = monitorMonoMeta,
            color = t.muted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(TL_TIME_COL_W),
        )
        Spacer(Modifier.width(t.space1))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.label.ifBlank { PLACEHOLDER },
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rowSubtitleOf(record),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space2))
        Icon(
            Icons.Outlined.NavigateNext,
            contentDescription = null,
            tint = t.meta,
            modifier = Modifier.size(t.space5),
        )
    }
}

/**
 * 聚焦命中行的点亮底色：accent 12% 满亮挂载，保持 1.6s 后 240ms 淡出。
 *
 * 只有 [LiveTimelineRow] 的 highlighted 分支会组合到这里，非高亮行不建 Animatable。
 */
@Composable
private fun Modifier.focusHighlight(): Modifier {
    val t = LocalDesignTokens.current
    val alpha = remember { Animatable(FOCUS_HIGHLIGHT_ALPHA) }
    LaunchedEffect(Unit) {
        delay(FOCUS_HIGHLIGHT_HOLD_MS)
        alpha.animateTo(0f, tween(FOCUS_HIGHLIGHT_FADE_MS))
    }
    return background(t.accent.copy(alpha = alpha.value))
}

/**
 * 行点击：0.99 + alpha .9 按下（--dur-press）+ LIGHT 触感；触感只在这里发一次。
 *
 * 键盘 / 遥控器聚焦时叠 2dp 焦点环（契约 s02 头部通用口径；触屏点按不出环）。
 */
@Composable
private fun Modifier.testTagRowPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val shape = RoundedCornerShape(LocalDesignTokens.current.radiusLg)
    return this
        .rowPressFeedback(interaction)
        .keyboardFocusRing(shape)
        .clickable(interactionSource = interaction, indication = LocalIndication.current) {
            haptic(HapticTone.LIGHT)
            onClick()
        }
}

/** 「加载更早的事件（还有 N 条）」：44dp、按下 0.96、LIGHT；加载中锁连点。 */
@Composable
private fun LiveLoadMore(isLoading: Boolean, remaining: Int, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = t.tapMin)
            .pressFeedback(interaction)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .keyboardFocusRing(shape)
            .clickable(
                enabled = !isLoading,
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space4)
            .testTag("s02-loadwrap"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isLoading) "加载中…" else "加载更早的事件（还有 " + remaining + " 条）",
            style = MaterialTheme.typography.labelLarge,
            color = t.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
