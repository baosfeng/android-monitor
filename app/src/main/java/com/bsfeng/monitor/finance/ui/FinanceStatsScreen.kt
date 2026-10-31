package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt；职责：统计分析屏幕装配（月份条、KPI/环/柱/热力四卡、五态与提示）。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoKpi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * s07 统计分析（契约 docs/交互设计/s07-统计分析-交互契约.md，16 个锚点）。
 *
 * 收支卡 + 分类支出环与图例 + 近 7 天分组柱 + 24 小时消费热力；顶栏固定、内容单列纵向滚动，
 * 底部留白 [DesignTokens.fabSize] + 8dp，滚到底时日期轴不被底部 Tab 条遮住。
 */
@Composable
fun FinanceStatsScreen(
    currentMonth: String,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FinanceViewModel? = null,
    /** 空态 CTA「新增一笔」的目标（原型 s07-empty-add）；未传时空态只显示文案。 */
    onAddClick: (() -> Unit)? = null,
    /**
     * 从「新增一笔」表单返回本页时自增的信号（契约 s07-empty-add · 移除后「焦点回本按钮」）。
     *
     * 表单页会把本页整屏卸载，返回时是**重新组合**的一屏：滚动位置与焦点都不会自动回来，
     * 所以由导航方（MainContent）在返回时把信号 +1，本页据此把焦点放回空态卡上的按钮。
     * 0 = 不是从表单返回，不做任何焦点动作。
     */
    returnFocusTick: Int = 0,
) {
    val t = LocalDesignTokens.current
    val vm = viewModel ?: rememberFinanceViewModel()
    val emptyFlow = remember { MutableStateFlow<List<Transaction>>(emptyList()) }
    val transactions by (vm?.monthlyTransactions ?: emptyFlow).collectAsState()
    val loading = vm?.loading?.collectAsState()?.value == true
    // 真实聚合查询失败（同 s04 口径）：此前 s07 的错态只能由 8s 超时或演示条触发
    val loadFailed = vm?.loadFailed?.collectAsState()?.value == true
    val monthNotice by (vm?.monthNotice ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val toast = rememberToastHostState()

    val control = rememberFinanceStateControl()
    var retrying by remember { mutableStateOf(false) }
    var loadTimedOut by remember { mutableStateOf(false) }
    // 契约 s07-error「焦点落到『重新计算』」/ s07-empty「焦点落到标题」/ s07-retry「成功后回到列表首项」
    val retryFocus = remember { FocusRequester() }
    val emptyTitleFocus = remember { FocusRequester() }
    // 空态 CTA「新增一笔」的落点（s07-empty-add · 移除后焦点回本按钮）
    val addFocus = remember { FocusRequester() }
    val monthPrevFocus = remember { FocusRequester() }
    var refocusListHead by remember { mutableStateOf(false) }

    val monthLabel = monthLabelOf(currentMonth)
    val sortedEntries = remember(categoryBreakdown) {
        categoryBreakdown.entries.sortedByDescending { it.value }.take(CATEGORY_LIMIT)
    }
    val week = remember(transactions, currentMonth) { last7DayTotals(transactions, currentMonth) }
    val hourly = remember(transactions) { hourlyCounts(transactions) }
    val peak = remember(hourly) { hourlyPeaks(hourly) }
    val weekExpense = remember(week) { week.sumOf { it.first.toDouble() } }
    val weekIncome = remember(week) { week.sumOf { it.second.toDouble() } }

    val loadingLike = (loading && transactions.isEmpty() && categoryBreakdown.isEmpty()) || retrying
    val realEmpty = !loadingLike && categoryBreakdown.isEmpty() && transactions.isEmpty()
    val derivedState = when {
        // 契约 s07-loading：超过 8s 仍无数据 → 错误态（先于 LOADING 判定）
        loadTimedOut -> FiveState.ERROR
        loadingLike -> FiveState.LOADING
        // 真实聚合失败 → 错误态（不再落到空态卡，把「算不出来」说成「没有可统计的支出」）
        loadFailed -> FiveState.ERROR
        realEmpty -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    val showEmptyCard = state == FiveState.EMPTY || (state == FiveState.POPULATED && realEmpty)
    // 契约 s07-error · 边界「完全无数据时错误卡独占列表区；部分失败时错误卡贴列表首位并保留可用图表」：
    // 有缓存 = 上一轮成功读到过东西（分类占比 / 当月交易 / KPI 任一非空）。
    val hasCache = categoryBreakdown.isNotEmpty() || transactions.isNotEmpty() ||
        stats.totalExpense > 0.0 || stats.totalIncome > 0.0

    // 契约 s07-loading：加载超过 8s 自动转错误态
    LaunchedEffect(loadingLike) {
        loadTimedOut = false
        if (loadingLike) {
            delay(8_000)
            loadTimedOut = true
        }
    }
    // 契约 s07-retry：重试结束仍在错误态 → toast「仍未取到完整数据」；否则淡回图表
    LaunchedEffect(retrying, loading) {
        if (!retrying || loading) return@LaunchedEffect
        retrying = false
        // 按真实结果分流：聚合失败时数据同样为空，光看「有没有数据」会把它误判成重试成功
        if (vm?.loadFailed?.value == true ||
            (categoryBreakdown.isEmpty() && transactions.isEmpty())
        ) {
            toast.show("仍未取到完整数据")
        } else {
            loadTimedOut = false
            control.select(null)
            // 契约 s07-retry · 移除后：焦点回原「重新计算」位置（列表首项 = 月份条「上个月」）
            refocusListHead = true
        }
    }
    // 契约 s07-empty：进入空态时焦点落到标题（从表单返回的那一次除外 —— 落点是 CTA 按钮，
    // 见下面的 returnFocusTick；否则两个请求会抢同一帧，谁最后跑谁生效）
    LaunchedEffect(showEmptyCard) {
        if (!showEmptyCard || returnFocusTick > 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { emptyTitleFocus.requestFocus() }
    }
    // 契约 s07-empty-add · 移除后：表单取消返回后焦点回「新增一笔」按钮
    LaunchedEffect(returnFocusTick) {
        if (returnFocusTick <= 0 || !showEmptyCard) return@LaunchedEffect
        withFrameNanos { }
        runCatching { addFocus.requestFocus() }
    }
    // 契约 s07-error：进入错误态时焦点落到「重新计算」（错误态下 Tab 只停这一处）
    LaunchedEffect(state) {
        if (state != FiveState.ERROR) return@LaunchedEffect
        withFrameNanos { }
        runCatching { retryFocus.requestFocus() }
    }
    // 契约 s07-retry · 移除后：焦点回列表首项（月份条「上个月」，节点随错误卡卸载后重挂，延一帧）
    LaunchedEffect(refocusListHead) {
        if (!refocusListHead) return@LaunchedEffect
        withFrameNanos { }
        runCatching { monthPrevFocus.requestFocus() }
        refocusListHead = false
    }

    Box(modifier.fillMaxSize().pageBackdrop().testTag("screen-s07")) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space2, vertical = t.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FinanceIconButton(onClick = onBack, contentDescription = "返回") { label ->
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = label,
                        tint = t.accent,
                        modifier = Modifier.size(t.iconSize),
                    )
                }
                Text(
                    "统计分析",
                    style = MaterialTheme.typography.titleLarge,
                    color = t.fg,
                    modifier = Modifier
                        .padding(start = t.space2)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { control.toggleBar() },
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("s07-content"),
                // 底部避让：为底部 Tab 条留出高度（fab-size 56 + 8dp），
                // 滚到底时「近 7 天收支」的 x 轴日期与数值刻度不再被 Tab 条裁掉。
                contentPadding = PaddingValues(bottom = t.fabSize + t.space2),
            ) {
                // 契约 s07-error · 边界：错误态两种形态互斥 ——
                // 完全无数据 → 错误卡独占列表区；部分失败（有缓存）→ 错误条贴首位，下方图表照常可用。
                val errorCard = financeErrorAsCard(state, hasCache)
                when {
                    state == FiveState.LOADING -> item(key = "loading") {
                        StatsSkeleton(Modifier.testTag("s07-loading").padding(t.space4))
                    }

                    errorCard -> item(key = "error") {
                        // 无缓存：列表区没有任何可读图表，Tab 只停在「重新计算」
                        ErrorState(
                            text = "聚合查询超时，图表数据不完整",
                            title = "统计计算失败",
                            actionLabel = "重新计算",
                            onRetry = {
                                retrying = true
                                vm?.loadCurrentMonth()
                            },
                            modifier = Modifier.testTag("s07-error"),
                            // 契约 s07-error：焦点直接落到「重新计算」（focusRequester 在 clickable 之前）
                            actionModifier = Modifier
                                .testTag("s07-retry")
                                .focusRequester(retryFocus),
                        )
                    }

                    else -> {
                        // 有缓存：错误条贴首位，下方已算出的图表仍然可读（一次抖动不清空用户已看到的数据）
                        if (state == FiveState.ERROR) item(key = "errorbar") {
                            FinanceInlineErrorBar(
                                title = "统计计算失败",
                                text = "聚合查询超时，图表数据不完整",
                                actionLabel = "重新计算",
                                onRetry = {
                                    retrying = true
                                    vm?.loadCurrentMonth()
                                },
                                modifier = Modifier
                                    .testTag("s07-error")
                                    .padding(horizontal = t.space4, vertical = t.space2),
                                actionModifier = Modifier
                                    .testTag("s07-retry")
                                    .focusRequester(retryFocus),
                            )
                        }
                        if (showEmptyCard) item(key = "empty") {
                            EmptyState(
                                text = "记一笔账或从熊猫记账导入历史数据后，这里会生成分类占比与趋势",
                                title = "本月还没有可统计的支出",
                                icon = Icons.Filled.BarChart,
                                actionLabel = if (onAddClick != null) "新增一笔" else null,
                                onAction = onAddClick,
                                // 契约 s07-empty：空态标题获得初始焦点；标题块兼作键盘焦点停位（2dp 环）
                                modifier = Modifier
                                    .testTag("s07-empty")
                                    .focusRequester(emptyTitleFocus)
                                    .padding(horizontal = t.space4)
                                    .keyboardFocusRing(RoundedCornerShape(t.radiusLg))
                                    .focusable(),
                                // 契约 s07-empty-add · 移除后：表单取消返回后焦点回本按钮
                                actionModifier = Modifier
                                    .testTag("s07-empty-add")
                                    .focusRequester(addFocus),
                            )
                        }

                        item(key = "month") {
                            Column {
                                FinanceMonthBar(
                                    monthText = monthLabel,
                                    onPrevious = onPreviousMonth,
                                    onNext = onNextMonth,
                                    modifier = Modifier
                                        .padding(vertical = t.space2)
                                        .testTag("s07-monthbar")
                                        .onPreviewKeyEvent { event ->
                                            // 契约 s07-monthbar：条内 ← / → 等同 prev / next
                                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (event.key) {
                                                Key.DirectionLeft -> {
                                                    onPreviousMonth()
                                                    true
                                                }
                                                Key.DirectionRight -> {
                                                    onNextMonth()
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                    // 重试成功后焦点回列表首项用的落点（契约 s07-retry · 移除后）
                                    previousModifier = Modifier
                                        .testTag("s07-prev")
                                        .focusRequester(monthPrevFocus),
                                    nextModifier = Modifier.testTag("s07-next"),
                                )
                                // 副标题随月份同步刷新（契约 s07-prev）
                                Text(
                                    monthLabel + " · 共 " + sortedEntries.size + " 个分类",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.meta,
                                    modifier = Modifier.padding(horizontal = t.space4),
                                )
                                Spacer(Modifier.height(t.space3))
                            }
                        }

                        if (state == FiveState.EDGE) {
                            item(key = "edge") {
                                FinanceStatsEdgeSection(
                                    Modifier
                                        .testTag("s07-edge")
                                        .padding(horizontal = t.space4, vertical = t.space2),
                                )
                            }
                        }

                        item(key = "kpis") {
                            Spacer(Modifier.height(t.space2))
                            FinanceStatsKpiRow(stats)
                        }

                        item(key = "ring") {
                            FinanceStatsRingCard(stats = stats, sortedEntries = sortedEntries)
                        }

                        item(key = "week") {
                            FinanceStatsWeekCard(
                                week = week,
                                weekExpense = weekExpense,
                                weekIncome = weekIncome,
                            )
                        }

                        item(key = "heat") {
                            FinanceStatsHeatCard(hourly = hourly, peak = peak)
                        }

                        item(key = "bottom") { Spacer(Modifier.height(t.space4)) }
                    }
                }
            }
        }

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )

        AppToast(
            message = monthNotice ?: toast.message,
            onDismiss = {
                if (monthNotice != null) vm?.consumeMonthNotice() else toast.clear()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.space12),
        )
    }
}

/** 加载骨架：三格 KPI + 150dp 圆环 + 两张卡（契约 s07-loading）。 */
@Composable
private fun StatsSkeleton(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            repeat(3) { Box(Modifier.weight(1f)) { StateSkeleton(rows = 2) } }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(STATS_RING_DIAMETER)) { StateSkeleton(rows = 3) }
        }
        AppCard { StateSkeleton(rows = 3) }
        AppCard { StateSkeleton(rows = 3) }
    }
}

/** 边界数据区（契约 s07-edge）：超长分类名 + 7 位以上金额 + 分类总数角标 + 缺失分类占位。 */
@Composable
private fun FinanceStatsEdgeSection(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier) {
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    EDGE_CATEGORY_NAME,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(t.space2))
                // 分类总数角标（契约：120 个分类时角标显示总数）
                Text(
                    formatCount(EDGE_CATEGORY_COUNT),
                    style = MaterialTheme.typography.labelSmall,
                    color = t.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(t.space2))
            // 超 7 位金额：等宽单行不换行（不缩字号）
            Text(
                yen(EDGE_AMOUNT, 2),
                style = monitorMonoKpi,
                color = t.fg,
                maxLines = 1,
            )
            Spacer(Modifier.height(t.space2))
            Text(
                "未分类（缺失分类字段） " + yen(0.0, 2),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.height(t.space2))
        Text(
            "边界态：分类 " + formatCount(EDGE_CATEGORY_COUNT) + " 个 / 环与图例只取金额最高的 8 个",
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}

/** 边界态占位数据（契约 s07-edge 写死；不读库，避免真实账目进代码）。 */
internal const val EDGE_CATEGORY_NAME = "人情往来 · 红白喜事与节日红包"
internal const val EDGE_CATEGORY_COUNT = 128_430
internal const val EDGE_AMOUNT = 12_345_678.90
