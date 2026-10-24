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
import com.bsfeng.monitor.ui.components.staggerEnterAt
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
import com.bsfeng.monitor.ui.trip.TodayTripCard
import java.util.Locale

/**
 * s01 今日概览（原型 .open-design/08-prototype.html → parts/s01-overview.html）。
 *
 * 三段面板：今日 / 近 7 天 / 近 30 天。
 * 今日段：2×2 KPI（AnimatedNumber + sparkline + 较昨日增减）→ 24 小时活跃面积图 + 24 格热力条
 *        → 类型环图 + 图例 → 最近事件 → 快捷入口。
 * 卡片入场统一走 Modifier.staggerEnterAt(index)。
 */
@Composable
fun OverviewScreen(
    onOpenLive: () -> Unit,
    onOpenFinance: () -> Unit,
    onOpenTrip: () -> Unit,
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
                        0 -> todayPanel(
                            state = state,
                            onOpenLive = onOpenLive,
                            onOpenFinance = onOpenFinance,
                            onOpenTrip = onOpenTrip,
                        )
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

