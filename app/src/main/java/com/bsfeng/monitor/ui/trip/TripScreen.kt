package com.bsfeng.monitor.ui.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.DaySegment
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.ScreenHead
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.FiveStateDemo
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.formatFullDate
import com.bsfeng.monitor.ui.live.weekdayLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * s16 每日行程（push 屏，由概览「今日行程」卡进入）。
 *
 * 结构：页头（返回 + 日期）→ 今日结论 → 日视图时间轴（停留 / 移动 / 未记录）→ 口径说明。
 * 点停留段开底部抽屉看期间应用与页面轨迹（应用全量、页面只给 App + 切换次数，指纹档）。
 * 无定位时整屏按设备活动推算：角标与口径说明都如实标注，不编造坐标、不编造距离。
 */
@Composable
fun TripScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val now = remember { System.currentTimeMillis() }
    var selected by remember { mutableStateOf<Stay?>(null) }

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    BackHandler(enabled = selected != null) { selected = null }

    val timeline = state.timeline
    val populated = timeline != null && (timeline.stays.isNotEmpty() || timeline.moves.isNotEmpty())
    val realState = when {
        state.error != null -> FiveState.ERROR
        state.isLoading && timeline == null -> FiveState.LOADING
        !populated -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }

    Box(
        modifier
            .fillMaxSize()
            .pageBackdrop(),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "每日行程",
                meta = formatFullDate(now) + " · " + weekdayLabel(now),
                onBack = onBack,
                consumeTopInset = true,
            )
            StateContainer(
                state = FiveStateDemo.effective(realState),
                modifier = Modifier.weight(1f),
                emptyTitle = "这一天还没有行程",
                emptyText = "停留与移动由设备活动推算；采集中断的时间标成「未记录」，不做推断。",
                errorText = state.error ?: "本地数据库暂时不可用（SQLITE_BUSY）。稍后重试即可，已采集的事件不会丢失。",
                onRetry = { viewModel.refresh() },
            ) {
                val current = timeline
                if (current != null) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = t.space4,
                            end = t.space4,
                            bottom = t.space6,
                        ),
                        verticalArrangement = Arrangement.spacedBy(t.space3),
                    ) {
                        item(key = "trip-summary") {
                            TripSummaryCard(timeline = current)
                        }
                        item(key = "trip-timeline") {
                            TripTimelineCard(
                                timeline = current,
                                dayStart = state.dayStart,
                                onSelect = { selected = it },
                            )
                        }
                        item(key = "trip-rules") {
                            TripRulesCard(basis = current.basis)
                        }
                    }
                }
            }
        }

        selected?.let { stay ->
            TripStaySheet(
                stay = stay,
                dayStart = state.dayStart,
                onDismiss = { selected = null },
            )
        }
    }
}

// ───────────────────────────── 今日结论 ─────────────────────────────

@Composable
private fun TripSummaryCard(timeline: DayTimeline, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetaText("今日结论", Modifier.weight(1f))
            Badge(basisBadge(timeline.basis))
        }
        Spacer(Modifier.height(t.space2))
        Text(
            text = tripHeadline(timeline),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = t.fg,
        )
        Spacer(Modifier.height(t.space1))
        MetaText(tripSubSummary(timeline))
        Spacer(Modifier.height(t.space3))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            tripMetrics(timeline).forEach { (label, value) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(t.radiusMd))
                        .background(t.fgFade(4))
                        .padding(horizontal = t.space3, vertical = t.space2),
                ) {
                    // 标签与数值都固定单行：数值走 formatCompactSpan（15h15m），24h 全长也放得下
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = t.muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(t.space1))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = t.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (timeline.basis == Basis.DEVICE) {
            Spacer(Modifier.height(t.space3))
            MetaText("按设备活动推算：没有定位点，地点名来自网络名与使用簇，坐标与真实距离不显示。")
        }
    }
}

// ───────────────────────────── 日视图时间轴 ─────────────────────────────

@Composable
private fun TripTimelineCard(
    timeline: DayTimeline,
    dayStart: Long,
    onSelect: (Stay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        // 处数 / 段数已由结论卡次行给出，这里只给操作提示，避免同一组计数在屏内出现两遍
        CardHead("时间轴", "停留段可点开详情")
        Spacer(Modifier.height(t.space2))
        timeline.segments.forEachIndexed { index, segment ->
            if (index > 0) HorizontalDivider(color = t.borderSoft)
            when (segment) {
                is Stay -> SegmentRow(segment, dayStart, onSelect = { onSelect(segment) })
                else -> SegmentRow(segment, dayStart, onSelect = null)
            }
        }
    }
}

@Composable
private fun SegmentRow(
    segment: DaySegment,
    dayStart: Long,
    onSelect: (() -> Unit)?,
) {
    val t = LocalDesignTokens.current
    val tint = when (segment) {
        is Stay -> t.chart2
        is Move -> t.chart1
        is Gap -> t.fg2
    }
    val icon: ImageVector = when (segment) {
        is Stay -> Icons.Outlined.LocationOn
        is Move -> Icons.Outlined.SwapHoriz
        is Gap -> Icons.Outlined.History
    }
    val trackText = (segment as? Stay)?.let { pageTrackText(it) }.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onSelect != null) {
                    Modifier.clickable { onSelect() }
                } else {
                    Modifier
                },
            )
            .heightIn(min = t.rowMinH)
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.Top,
    ) {
        // 时间列：起止各一行，等宽数字（原型 .s16-seg-time）
        Column(Modifier.width(t.space12)) {
            Text(
                text = formatSpanClock(segment.start, dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
            Text(
                text = formatSpanClock(segment.end, dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(t.space2))
        Box(
            Modifier
                .size(t.space8)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(tint.copy(alpha = tint.alpha * 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(t.space5))
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = segmentTitle(segment),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (segment is Gap) t.muted else t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(t.space1))
            Text(
                text = segmentSubtitle(segment),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trackText.isNotEmpty()) {
                Spacer(Modifier.height(t.space1))
                Text(
                    text = trackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSelect != null) {
            Icon(
                Icons.Outlined.NavigateNext,
                contentDescription = "查看停留详情",
                tint = t.muted,
                modifier = Modifier.size(t.space5),
            )
        }
    }
}

// ───────────────────────────── 口径说明 ─────────────────────────────

@Composable
private fun TripRulesCard(basis: Basis, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        CardHead("口径说明", basisBadge(basis))
        Spacer(Modifier.height(t.space2))
        RuleRow("停留", "连续 10 分钟以上停在同一地点")
        RuleRow("移动", "两点间净位移超过 300 米，且定位覆盖不少于 60%")
        RuleRow("未记录", "采集有中断，期间内容不做推断")
        if (basis == Basis.DEVICE) {
            RuleRow("地点名", "来自当前网络名与 App 使用簇；开启定位后可显示距离与地址")
        }
    }
}

@Composable
private fun RuleRow(title: String, desc: String) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = t.space2)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = t.fg)
        Spacer(Modifier.height(t.space1))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

// ───────────────────────────── 停留详情抽屉 ─────────────────────────────

@Composable
private fun TripStaySheet(
    stay: Stay,
    dayStart: Long,
    onDismiss: () -> Unit,
) {
    val t = LocalDesignTokens.current
    BottomSheet(visible = true, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = placeDisplayName(stay),
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Badge("停留 " + formatSpanText(stay.durationMs))
        }
        Spacer(Modifier.height(t.space1))
        MetaText(
            formatSpanClock(stay.start, dayStart) + " – " + formatSpanClock(stay.end, dayStart) +
                " · " + placeSourceNote(stay.place),
        )

        Spacer(Modifier.height(t.space4))
        Text("期间应用", style = MaterialTheme.typography.labelLarge, color = t.muted)
        Spacer(Modifier.height(t.space2))
        if (stay.topApps.isEmpty()) {
            MetaText("这段时间没有应用使用记录")
        } else {
            // 模型侧给的是全部应用（按时长降序）；这里列前 8 条 + 「其他 N 个应用」，
            // 避免抽屉被长列表撑出屏，同时不会把剩下的 App 直接吞掉。
            visibleApps(stay.topApps).forEach { slice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = t.tapMin),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = slice.app,
                        style = MaterialTheme.typography.bodyMedium,
                        color = t.fg,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatSpanText(slice.ms),
                        style = MaterialTheme.typography.labelMedium,
                        color = t.muted,
                    )
                }
            }
            val rest = otherAppsNote(stay.topApps)
            if (rest.isNotEmpty()) {
                Spacer(Modifier.height(t.space1))
                MetaText(rest)
            }
        }

        if (stay.pages.isNotEmpty()) {
            Spacer(Modifier.height(t.space4))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "页面轨迹",
                    style = MaterialTheme.typography.labelLarge,
                    color = t.muted,
                    modifier = Modifier.weight(1f),
                )
                Badge("切换 " + stay.pages.sumOf { it.switches } + " 次")
            }
            Spacer(Modifier.height(t.space2))
            stay.pages.forEach { tally ->
                Text(
                    text = pageTrackLine(tally),
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.fg2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = t.tapMin)
                        .padding(vertical = t.space2),
                )
            }
            MetaText("只记录 App 与切换次数，不记录页面名称与内容。")
        }

        Spacer(Modifier.height(t.space4))
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(t.radiusPill),
        ) {
            Text("关闭", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 地点来源：有坐标说定位，没坐标如实说是推算。 */
private fun placeSourceNote(place: Place): String = when (place.source) {
    Basis.GPS -> "地点来自定位"
    Basis.DEVICE -> "地点来自网络名与使用簇"
}
