package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时页的事件时间线 —— 按小时分组、组头吸顶、逐行入场、分页加载更多。
// 负责「一屏事件按小时往下排」；详情抽屉见 EventDetailSheet.kt。

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.live.HourGroup
import com.bsfeng.monitor.ui.live.HourGroupHead
import com.bsfeng.monitor.ui.live.LiveEventRow
import com.bsfeng.monitor.ui.live.LiveFilter
import com.bsfeng.monitor.ui.live.StackRow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * s02 事件时间线列表（LazyColumn）：组头吸顶 + 按小时分组 + 分页加载更多。
 *
 * 入场动画只给「进屏那一屏」的行（[enterWindow]）：滚动新挂载的一律落终态，
 * 否则每滚到一个新小时组，组头与行都会重播整段入场。
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
) {
    val t = LocalDesignTokens.current
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
                    modifier = Modifier.staggerEnterAt(headEnter),
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
                    modifier = Modifier.staggerEnterAt(rowEnter),
                ) {
                    // 原型 s02 时间列是 HH:mm（.s02-t / .tl-time，s02-live.html:196），
                    // 46dp 时间列放得下 5 字符等宽；带秒（8 字符）会折行/裁切。
                    LiveEventRow(record = record, showSeconds = false, onClick = { onSelect(record) })
                }
            }
            if (gi != groups.lastIndex) {
                item(key = "gap-" + group.hour) { Spacer(Modifier.height(t.space3)) }
            }
        }
        if (filter == LiveFilter.ALL && (hasMore || isLoadingMore)) {
            item(key = "load-more") {
                Spacer(Modifier.height(t.space3))
                OutlinedButton(
                    onClick = onLoadMore,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(t.radiusPill),
                ) {
                    Text(
                        text = if (isLoadingMore) "加载中…"
                        else "加载更早的事件（还有 " + (todayEventCount - loadedCount) + " 条）",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
