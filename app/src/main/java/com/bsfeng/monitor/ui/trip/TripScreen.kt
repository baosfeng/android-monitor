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
                        item(key = "trip-map") {
                            // 同一批 records 算出的轨迹采样；坐标投影在卡的 drawScope 里按画布尺寸做
                            TripMapCard(timeline = current, samples = state.trace)
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

