package com.bsfeng.monitor.ui.trip

// s16 每日行程（push 屏，由概览「今日行程」卡进入）。
//
// 结构：页头（返回）→ 日期切换条（前一天 / 日期 / 后一天，最近 30 天到今天）→ 今日结论 →
// 轨迹图（可导出 PNG 到相册）→ 日视图时间轴（停留 / 移动 / 未记录）→ 口径说明。
// 点停留段开底部抽屉看期间应用与页面切换（指纹档）；无定位时整屏按设备活动推算，角标如实标注。

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.ScreenHead
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.performHaptic
import com.bsfeng.monitor.ui.live.FiveStateDemo
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.formatFullDate
import com.bsfeng.monitor.ui.live.weekdayLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.launch

/**
 * 每日行程屏。
 *
 * 日期可切换：最近 30 天（含今天），今天不能再往后。历史日期的窗口是整日、今天截到此刻，
 * 由 [TripViewModel.selectDay] 统一按 [TripDays] 的边界夹住。
 *
 * 轨迹卡右上角「导出」把当天轨迹渲染成 PNG 写进系统相册（MediaStore，免存储权限）。
 */
@Composable
fun TripScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val view = LocalView.current
    val toast = rememberToastHostState()
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    var selected by remember { mutableStateOf<Stay?>(null) }
    var exporting by remember { mutableStateOf(false) }

    // 首帧 state.dayStart 还是 0：先按今天渲染，等 VM 回填后再对齐
    val dayStart = state.dayStart.takeIf { it > 0L } ?: TripDays.today(now)
    val isToday = dayStart == TripDays.today(now)

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    // 换天时收起停留抽屉，避免抽屉里还是上一天的数据
    LaunchedEffect(state.dayStart) { selected = null }
    BackHandler(enabled = selected != null) { selected = null }

    val timeline = state.timeline
    val populated = timeline != null && (timeline.stays.isNotEmpty() || timeline.moves.isNotEmpty())
    val realState = when {
        state.error != null -> FiveState.ERROR
        state.isLoading && timeline == null -> FiveState.LOADING
        !populated -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }

    /** 导出当前这一天的轨迹图：渲染在 Default 线程，写相册在 IO 线程，结果用 Toast 报。 */
    fun exportTrace() {
        if (exporting) return
        val day = dayStart
        val currentTimeline = timeline ?: return
        val samples = state.trace
        exporting = true
        view.performHaptic(HapticTone.LIGHT)
        scope.launch {
            val result = TripExporter.export(
                context = context,
                dayStart = day,
                title = formatFullDate(day) + if (day == TripDays.today(System.currentTimeMillis())) " 今日轨迹" else " 当日轨迹",
                subtitle = exportSubtitle(samples, day),
                samples = samples,
                stays = currentTimeline.stays,
                style = TripExportStyle(
                    surface = t.surface.toArgb(),
                    warm = t.surfaceWarm.toArgb(),
                    fg = t.fg.toArgb(),
                    muted = t.muted.toArgb(),
                    accent = t.accent.toArgb(),
                    success = t.success.toArgb(),
                    danger = t.danger.toArgb(),
                ),
            )
            exporting = false
            result.fold(
                onSuccess = { toast.show("已保存到相册：" + TripExportNaming.ALBUM_NAME) },
                onFailure = { error ->
                    toast.show("导出失败：" + (error.message?.takeIf { it.isNotBlank() } ?: "写入相册失败"))
                },
            )
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pageBackdrop(),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHead(
                title = "每日行程",
                meta = "最近 30 天 · " + if (isToday) "今天" else "历史日期",
                onBack = onBack,
                consumeTopInset = true,
            )
            TripDayBar(
                dayStart = dayStart,
                now = now,
                onPrevious = {
                    view.performHaptic(HapticTone.LIGHT)
                    viewModel.selectDay(TripDays.previous(dayStart, now))
                },
                onNext = {
                    view.performHaptic(HapticTone.LIGHT)
                    viewModel.selectDay(TripDays.next(dayStart, now))
                },
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
                            TripMapCard(
                                timeline = current,
                                samples = state.trace,
                                isToday = isToday,
                                exporting = exporting,
                                onExport = { exportTrace() },
                            )
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

        AppToast(
            message = toast.message,
            onDismiss = { toast.clear() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.space12),
        )
    }
}

/** 日期切换条：前一天 / 日期 / 后一天；边界（最早一天、今天）按钮置灰。 */
@Composable
private fun TripDayBar(
    dayStart: Long,
    now: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val isToday = dayStart == TripDays.today(now)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayArrow(
            icon = Icons.Outlined.ChevronLeft,
            description = "前一天",
            enabled = TripDays.canPrevious(dayStart, now),
            onClick = onPrevious,
        )
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatFullDate(dayStart) + " · " + weekdayLabel(dayStart),
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaText(if (isToday) "今天 · 可回看最近 30 天" else "历史日期 · 右箭头回今天")
        }
        DayArrow(
            icon = Icons.Outlined.ChevronRight,
            description = "后一天",
            enabled = TripDays.canNext(dayStart, now),
            onClick = onNext,
        )
    }
}

@Composable
private fun DayArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Box(
        Modifier
            .size(t.space8)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(if (enabled) t.surfaceWarm else t.fgFade(4))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) t.fg else t.muted,
            modifier = Modifier.size(t.space5),
        )
    }
}
