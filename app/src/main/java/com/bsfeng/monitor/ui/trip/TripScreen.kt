package com.bsfeng.monitor.ui.trip

// s16 每日行程（push 屏，由概览「今日行程」卡进入）。
//
// 结构：页头（返回 + 标题 + 日期文案行 + 前一天/后一天）→ 今日结论 → 轨迹图（可导出 PNG 到相册）
// → 日视图时间轴（停留 / 移动 / 未记录）→ 口径说明；无定位时最后补一行「开启定位后可显示距离与地址」。
// 点停留段开底部抽屉看期间应用；无定位时整屏按设备活动推算，角标如实标注。
//
// 交互口径：锚点账本。页级锚点（testTag）：
// screen-s16 / s16-head / s16-prev / s16-next / s16-content / s16-empty / s16-empty-cta /
// s16-summary / s16-timeline / s16-rules / s16-goto-location。
// 时间轴逐段锚点（s16-timeline-list / 9 个 s16-seg-*）落在 TripTimeline.kt，摘要角标（s16-source）落在
// TripSummaryCard.kt，抽屉锚点（trip-detail-sheet/-2/-3）落在 TripStaySheet.kt。
//
// 「在地图中查看」入口（ui/maps/MapEntryCard.kt）挂在轨迹卡下方，只有当天有坐标时才渲染：
// 恰好一个候选（典型 = 只装了高德）直接打开；多个候选弹选择；一个都没有 toast 说明。
// 该入口**不新增锚点** —— 锚点集合要求逐字对账（scripts/check-anchors.mjs 的自增为硬错误），
// 新锚点须先在 锚点账本 登记，故交互由纯逻辑单测覆盖。

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.record.CoverageFailureLog
import com.bsfeng.monitor.record.CoverageReader
import com.bsfeng.monitor.record.CoverageSnapshot
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.SampleMode
import com.bsfeng.monitor.record.StoreCoverageReader
import com.bsfeng.monitor.ui.ScreenHead
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.formatFullDate
import com.bsfeng.monitor.ui.live.weekdayLabel
import com.bsfeng.monitor.ui.maps.MapEntryCard
import com.bsfeng.monitor.ui.maps.MapEntryPoint
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 空态说明：口径固定文案，不随数据变。 */
private const val EMPTY_TEXT = "停留与移动由设备活动推算；采集中断的时间标成「未记录」，不做推断。"

/** 空态标题。 */
private const val EMPTY_TITLE = "这一天还没有行程"

/** 空态 CTA：只在定位权限未开且已接线设置页时出现。 */
private const val EMPTY_CTA = "去开启定位权限"

/** 无定位时的提示行（锚点 s16-goto-location 固定文案）。 */
private const val LOCATION_HINT_TITLE = "开启定位后可显示距离与地址"
private const val LOCATION_HINT_TEXT = "现在按设备活动推算，地点名来自网络名与使用簇"

/**
 * 每日行程屏。
 *
 * 日期可切换：最近 30 天（含今天），今天不能再往后。历史日期的窗口是整日、今天截到此刻，
 * 由 [TripViewModel.selectDay] 统一按 [TripDays] 的边界夹住。
 *
 * 轨迹卡右上角「导出」把当天轨迹渲染成 PNG 写进系统相册（MediaStore，免存储权限）。
 *
 * @param onOpenLocationSettings 跳到设置页定位权限项；为 null 时不渲染空态 CTA 与定位提示行
 *   （未接线不伪造跳转）。当前装配方 MainOverlays.kt 还未传，见交接文档「需接线」。
 */
@Composable
fun TripScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLocationSettings: (() -> Unit)? = null,
    viewModel: TripViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val toast = rememberToastHostState()
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val now = remember { System.currentTimeMillis() }
    var selected by remember { mutableStateOf<Stay?>(null) }
    // 移除后五问之「焦点」：抽屉关闭后焦点回到打开它的停留段（AdScreen 的 lastRowRequester 模式）
    var lastRowRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var restoreRowFocus by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // 底图开关：默认关，落盘到 SharedPreferences（TripBasemap）；开着时轨迹卡与导出图都带底图
    var basemap by remember { mutableStateOf(TripBasemap.isEnabled(context)) }
    // 跨换日保留滚动位置（锚点 s16-prev「列表滚动位置保留、不强制回顶」；离开页面才丢弃）
    val listState = rememberLazyListState()

    // 首帧 state.dayStart 还是 0：先按今天渲染，等 VM 回填后再对齐
    val dayStart = state.dayStart.takeIf { it > 0L } ?: TripDays.today(now)
    val isToday = dayStart == TripDays.today(now)
    // 定位权限已开时，空态不再给「去开启定位权限」（锚点 s16-empty-cta 边界）
    val locationGranted = remember(context) {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val emptyAction = onOpenLocationSettings?.takeIf { !locationGranted }

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    // 换天时收起停留抽屉，避免抽屉里还是上一天的数据；换日不回段焦点（锚点 s16-prev：焦点回到箭头）
    LaunchedEffect(state.dayStart) {
        restoreRowFocus = false
        selected = null
    }
    // 移除后五问之「焦点」：抽屉关闭后焦点回到打开它的那一行（与 AdScreen 同一套写法）
    LaunchedEffect(selected) {
        if (selected == null && restoreRowFocus) {
            withFrameNanos { }
            runCatching { lastRowRequester?.requestFocus() }
            restoreRowFocus = false
        }
    }

    /** 关闭停留抽屉：置 restoreRowFocus，让焦点回到触发段（遮罩 / 返回键 / 卡内「关闭」三路同效）。 */
    fun closeSheet() {
        restoreRowFocus = true
        selected = null
    }

    BackHandler(enabled = selected != null) { closeSheet() }

    val timeline = state.timeline
    val populated = timeline != null && (timeline.stays.isNotEmpty() || timeline.moves.isNotEmpty())
    // 「在地图中查看」打开哪个点 / 用哪个名字：判定全在 MapEntryPoint（纯函数、有单测），
    // 这里只做一次记忆化，避免每次重组重算两份列表遍历
    val mapStays = timeline?.stays.orEmpty()
    val mapPoint = remember(state.trace, mapStays) { MapEntryPoint.pick(state.trace, mapStays) }
    val mapLabel = remember(state.trace, mapStays) { MapEntryPoint.label(state.trace, mapStays) }

    // 当日采集覆盖（锚点 trip-cov）：轨迹卡底部的细覆盖条 + 「今天断了 N 次」。
    // 与设置页「采集覆盖」卡用同一个数据源与同一套分段算法（CoverageTimeline），
    // 只是窗口换成「这一天」（历史日期整日，今天截到此刻）；进程内缓存按分钟键，不重复读库。
    val coverageReader = rememberCoverageReader()
    val hasSamples = state.trace.isNotEmpty()
    val coverage by produceState<CoverageSnapshot?>(
        initialValue = null,
        coverageReader,
        state.dayStart,
        state.dayEnd,
        hasSamples,
    ) {
        val reader = coverageReader
        val start = state.dayStart
        val end = state.dayEnd
        if (reader == null || start <= 0L || end <= start || !hasSamples) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) { reader.readWindow(start, end) }
    }

    /** 导出当前这一天的轨迹图：渲染在 Default 线程，写相册在 IO 线程，结果用 Toast 报。 */
    fun exportTrace() {
        if (exporting) return
        val day = dayStart
        val currentTimeline = timeline ?: return
        val samples = state.trace
        exporting = true
        haptic(HapticTone.LIGHT)
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
                basemap = basemap,
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

    // 整屏容器：不可聚焦、不进 Tab 序，点按空白无高亮无 toast（锚点 screen-s16）
    Box(
        modifier
            .fillMaxSize()
            .pageBackdrop()
            .testTag("screen-s16"),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 页头常驻不随内容滚出；页头文字不可点，只有前后箭头可点（锚点 s16-head / s16-prev / s16-next）
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("s16-head"),
            ) {
                ScreenHead(
                    title = "每日行程",
                    onBack = onBack,
                    consumeTopInset = true,
                )
                TripDayBar(
                    dayStart = dayStart,
                    now = now,
                    onPrevious = {
                        haptic(HapticTone.LIGHT)
                        viewModel.selectDay(TripDays.previous(dayStart, now))
                    },
                    onNext = {
                        haptic(HapticTone.LIGHT)
                        viewModel.selectDay(TripDays.next(dayStart, now))
                    },
                )
            }
            // 内容区：只有这一块跟手滚动，页头与日期条不动（锚点 s16-content）
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.error != null -> ErrorState(
                        text = state.error ?: DEFAULT_ERROR_TEXT,
                        modifier = Modifier.fillMaxWidth(),
                        onRetry = { viewModel.refresh() },
                    )
                    state.isLoading && timeline == null -> StateSkeleton(
                        modifier = Modifier.padding(t.space4),
                        rows = 6,
                    )
                    !populated -> EmptyState(
                        text = EMPTY_TEXT,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("s16-empty"),
                        title = EMPTY_TITLE,
                        icon = Icons.Outlined.LocationOn,
                        actionLabel = if (emptyAction != null) EMPTY_CTA else null,
                        onAction = emptyAction,
                        actionModifier = Modifier.testTag("s16-empty-cta"),
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("s16-content"),
                        contentPadding = PaddingValues(
                            start = t.space4,
                            end = t.space4,
                            bottom = t.space6,
                        ),
                        verticalArrangement = Arrangement.spacedBy(t.space3),
                    ) {
                        item(key = "trip-summary") {
                            TripSummaryCard(
                                timeline = timeline,
                                modifier = Modifier.testTag("s16-summary"),
                            )
                        }
                        item(key = "trip-map") {
                            // 同一批 records 算出的轨迹采样；坐标投影在卡的 drawScope 里按画布尺寸做
                            TripMapCard(
                                timeline = timeline,
                                samples = state.trace,
                                isToday = isToday,
                                exporting = exporting,
                                onExport = { exportTrace() },
                                basemapEnabled = basemap,
                                onToggleBasemap = {
                                    val next = !basemap
                                    basemap = next
                                    TripBasemap.setEnabled(context, next)
                                    haptic(HapticTone.LIGHT)
                                },
                                coverage = coverage?.window,
                                gaps = coverage?.gaps.orEmpty(),
                            )
                        }
                        // 「在地图中查看」：只有当天有坐标才渲染（没坐标就没有可看的位置）
                        if (mapPoint != null) {
                            item(key = "trip-map-entry") {
                                MapEntryCard(
                                    point = mapPoint,
                                    label = mapLabel,
                                    onMessage = { toast.show(it) },
                                )
                            }
                        }
                        item(key = "trip-timeline") {
                            TripTimelineCard(
                                timeline = timeline,
                                dayStart = state.dayStart,
                                onSelect = { stay, requester ->
                                    lastRowRequester = requester
                                    restoreRowFocus = false
                                    selected = stay
                                },
                                modifier = Modifier.testTag("s16-timeline"),
                            )
                        }
                        item(key = "trip-rules") {
                            TripRulesCard(
                                basis = timeline.basis,
                                modifier = Modifier.testTag("s16-rules"),
                            )
                        }
                        // 无定位时才提示去开定位；有坐标时整行不渲染（锚点 s16-goto-location 边界）
                        if (timeline.basis == Basis.DEVICE && onOpenLocationSettings != null) {
                            item(key = "trip-goto-location") {
                                TripLocationHint(onClick = onOpenLocationSettings)
                            }
                        }
                    }
                }
            }
        }

        selected?.let { stay ->
            TripStaySheet(
                stay = stay,
                dayStart = state.dayStart,
                placeOrdinal = timeline?.let { placeOrdinal(it, stay) } ?: 0,
                onDismiss = { closeSheet() },
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

/** 错态兜底文案（VM 已给可读文案，这里只防 null）。 */
private const val DEFAULT_ERROR_TEXT = "本地数据库暂时不可用（SQLITE_BUSY）。稍后重试即可，已采集的事件不会丢失。"

/**
 * 日期切换条：前一天 / 日期文案 / 后一天。
 *
 * 换日时日期文案 150ms 交叉淡入（锚点 s16-prev「内容 150ms 内整体替换，不播整页转场」）；
 * 内容本身的替换跟着数据走，不额外套转场，避免把滚动位置一起重置。
 */
@Composable
private fun TripDayBar(
    dayStart: Long,
    now: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayArrow(
            icon = Icons.Outlined.ChevronLeft,
            description = "前一天",
            modifier = Modifier.testTag("s16-prev"),
            enabled = TripDays.canPrevious(dayStart, now),
            onClick = onPrevious,
        )
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Crossfade(
                targetState = dayStart,
                animationSpec = tween(t.durOverlayOut),
                label = "tripDayText",
            ) { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatFullDate(day) + " · " + weekdayLabel(day),
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaText(dayCaption(day, now))
                }
            }
        }
        DayArrow(
            icon = Icons.Outlined.ChevronRight,
            description = "后一天",
            modifier = Modifier.testTag("s16-next"),
            enabled = TripDays.canNext(dayStart, now),
            onClick = onNext,
        )
    }
}

/** 日期条第二行：今天 / 历史日期。 */
private fun dayCaption(dayStart: Long, now: Long): String =
    if (dayStart == TripDays.today(now)) "今天 · 可回看最近 30 天" else "历史日期 · 右箭头回今天"

/**
 * 日期箭头：点击区 44dp，按下 0.96 + LIGHT（锚点 s16-prev / s16-next）。
 *
 * 边界：已是最早一天 / 今天 → 底色 fgFade(4)、图标 muted、不可点、不弹提示（原因由灰态表达）。
 */
@Composable
private fun DayArrow(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(t.tapMin)
            .pressFeedback(interaction, enabled)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(if (enabled) t.surfaceWarm else t.fgFade(4))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) t.fg else t.muted,
            modifier = Modifier.size(t.space5).testTag("button.icon"),
        )
    }
}

/**
 * 「开启定位后可显示距离与地址」提示行（锚点 s16-goto-location）。
 *
 * click：行底色 120ms 加深 + LIGHT，右侧 › 不动画 → 跳设置页定位权限项；
 * 未接线（[onClick] 无来源）时整行不渲染，不伪造跳转。
 */
@Composable
private fun TripLocationHint(onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) t.fgFade(7) else t.surfaceWarm,
        animationSpec = tween(t.durState),
        label = "gotoLocationBg",
    )
    val shape = RoundedCornerShape(t.radiusLg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .heightIn(min = t.rowMinH)
            .padding(horizontal = t.space4, vertical = t.space3)
            .testTag("s16-goto-location"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.LocationOn,
            contentDescription = null,
            tint = t.accent,
            modifier = Modifier.size(t.space5),
        )
        Column(Modifier.weight(1f).padding(start = t.space3)) {
            Text(
                text = LOCATION_HINT_TITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaText(LOCATION_HINT_TEXT)
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = t.muted,
            modifier = Modifier.size(t.space5),
        )
    }
}

/**
 * 当日覆盖的数据源：DI 容器里的记录库 + 全局失败日志。
 *
 * 与设置页的 StoreCoverageReader 是同一个类、同一个失败日志单例，所以
 * 「断档原因」在两处口径一致；缓存也共用（按窗口与分钟键），切页不重复读库。
 */
@Composable
private fun rememberCoverageReader(): CoverageReader? {
    val context = LocalContext.current
    return remember(context) {
        val store = runCatching { MonitorApp.instance.container.recordStore }.getOrNull() ?: return@remember null
        StoreCoverageReader(
            store = store,
            failures = CoverageFailureLog.shared,
            modeOf = { runCatching { LocationCollector.get(context).mode }.getOrDefault(SampleMode.STILL) },
            statusOf = { runCatching { LocationCollector.get(context).health() }.getOrNull() },
        )
    }
}
