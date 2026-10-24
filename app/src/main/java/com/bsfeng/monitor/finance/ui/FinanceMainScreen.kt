package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt；职责：s04 记账首页的屏幕装配（列表结构 / 状态机 / FAB / 刷新时机）。
// 卡片见 FinanceMainCards，明细行与外框见 FinanceMainRows，分段组件见 FinanceMainSections，
// 派生纯函数见 FinanceMainDerived，明细 lazy items 见 FinanceMainList。

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bsfeng.monitor.finance.FinanceInsertSignal
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppFab
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.time.LocalDate

/**
 * s04 记账首页。
 *
 * 月份弹簧切换 + 3 个滚动数字 + 预算圆环 + 双入口卡 + 按日分组的交易明细 + FAB。
 *
 * ⚠️ FAB 禁止外包 inset 高度容器（见 docs/踩坑/记账FAB被底部导航栏遮住导致无法新增交易.md）。
 */
@Composable
fun FinanceMainScreen(
    viewModel: FinanceViewModel,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
    onEditClick: (Transaction) -> Unit = {},
    onStatsClick: () -> Unit = {},
    onBudgetClick: () -> Unit = {},
    /** 每次进入本屏（Tab 切回来 / 路由返回）自增，用于把列表滚回顶部。 */
    entryKey: Int = 0,
) {
    val t = LocalDesignTokens.current
    // 触觉统一走 foundation/Haptics.kt 的 rememberHaptic（三重闸门 + 强度档换算）
    val haptic = rememberHaptic()
    val toast = rememberToastHostState()
    val currentMonth by viewModel.currentMonth.collectAsState()
    val transactions by viewModel.monthlyTransactions.collectAsState()
    val stats by viewModel.monthlyStats.collectAsState()
    val budgets by viewModel.budgets.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val monthNotice by viewModel.monthNotice.collectAsState()

    // 自动记账落库后的一次性「新行高亮」：信号只消费一次（clear 后不再亮），
    // 所以切 Tab / 回前台重新加载列表都不会重复高亮 —— 它表示「刚刚多了这笔」。
    val latestInsertId by FinanceInsertSignal.latestId.collectAsState()
    var highlightId by remember { mutableStateOf(0L) }
    LaunchedEffect(latestInsertId) {
        val id = latestInsertId ?: return@LaunchedEffect
        FinanceInsertSignal.clear()
        highlightId = id
        // 高亮保持 1.6s 后 240ms 淡出：状态多留一个淡出时长，行内的动画层才能把淡出走完再卸载
        kotlinx.coroutines.delay(HIGHLIGHT_DURATION_MS + HIGHLIGHT_FADE_MS)
        if (highlightId == id) highlightId = 0L
    }

    // 显式持有滚动状态：每次进入本屏（entryKey 变化）把头部复位，
    // 避免「记账」标题被上一次的滚动位置顶出屏幕。
    // 键必须是 entryKey：切 Tab / 子页返回时 entryKey 会自增，用 Unit 则只在首次组合时跑一次。
    val listState = rememberLazyListState()
    // 每次进入本屏（切 Tab / 从子页返回）重新拉当月数据：
    // 自动记账在无障碍服务里落库，切回来必须能看到新记录，不能等用户手动操作触发刷新。
    LaunchedEffect(entryKey) {
        listState.scrollToItem(0)
        viewModel.loadCurrentMonth()
    }

    // App 从后台回前台时 Activity 重新 resume，但本 composable 一直在组合里（entryKey 不变），
    // 所以再挂一个 ON_RESUME：期间自动记账落库的记录同样立刻可见。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadCurrentMonth()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val control = rememberFinanceStateControl()
    val monthDisplay = monthLabelOf(currentMonth)
    val grouped = remember(transactions) { viewModel.getTransactionsGroupedByDate(transactions) }
    val overallBudget = remember(budgets) { budgets.find { it.isOverall } }
    val today = remember(transactions, currentMonth) { todayTotals(transactions, currentMonth) }
    // O(n) 计数 + LocalDate.now()：只随列表数据 / 月份变化重算（放在屏级，列表头滚出再滚回不重算）；
    // 跨零点不自刷，取舍同 groupLabel。
    val recentCount = remember(transactions, currentMonth) {
        recentDayCount(transactions, LIST_META_DAYS)
    }

    val derivedState = when {
        loading && transactions.isEmpty() -> FiveState.LOADING
        transactions.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState

    // 沉浸式底衬由 MainActivity 最外层 appBackdrop() 负责（同一套 backdrop-a/b token 渐变），
    // 本屏不再叠一层全屏渐变：滚动时少一次全屏填充与两次 Brush/listOf 分配。
    Box(modifier = modifier.fillMaxSize()) {
        // 底部避让：列表末端留出 FAB 盒（fab-size 56）+ FAB 外边距（16）+ 一行余量（24），
        // 滚到列表底部时最后一行金额不再压在 FAB 下（原先是列表尾部的假 item，等效但不可见）。
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = t.fabSize + t.space4 + t.space6),
        ) {
            item(key = "head") {
                FinanceScreenHead(
                    title = "记账",
                    subtitle = monthDisplay + " · 共 " + transactions.size + " 笔",
                    // 月份标签统一走 monthLabelOf（与原型一致保留空格）
                    onLongPress = { control.toggleBar() },
                    trailing = {
                        FinanceIconButton(onClick = onImportClick, contentDescription = "数据导入") {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = null,
                                tint = t.accent,
                                modifier = Modifier.size(t.iconSize),
                            )
                        }
                    },
                )
            }

            item(key = "month") {
                FinanceMonthBar(
                    monthText = monthDisplay,
                    onPrevious = { viewModel.previousMonth() },
                    onNext = { viewModel.nextMonth() },
                    modifier = Modifier.padding(vertical = t.space2),
                )
            }

            item(key = "kpis") {
                FinanceMainKpiSection(
                    stats = stats,
                    today = today,
                    currentMonth = currentMonth,
                    transactionCount = transactions.size,
                )
            }

            item(key = "entries") {
                FinanceMainEntrySection(
                    overallBudget = overallBudget,
                    onStatsClick = onStatsClick,
                    onBudgetClick = onBudgetClick,
                )
            }

            item(key = "listhead") {
                FinanceMainListHead(
                    currentMonth = currentMonth,
                    groupedIsEmpty = grouped.isEmpty(),
                    recentCount = recentCount,
                    transactionCount = transactions.size,
                )
            }

            financeMainTransactionItems(
                state = state,
                grouped = grouped,
                highlightId = highlightId,
                tokens = t,
                onRowClick = {
                    haptic(HapticTone.LIGHT)
                    onEditClick(it)
                },
                onAddClick = onAddClick,
                onRetry = { viewModel.loadCurrentMonth() },
            )

        }



        // FAB：直接 align(BottomEnd)，禁止外包 inset 高度容器。
        // 按压缩放与触觉由 AppFab 内部统一提供（foundation/PressFeedback.kt + foundation/Haptics.kt），
        // 调用点不要再叠 graphicsLayer 缩放：外层 0.88 × 组件内 0.96 实际压到 0.845，
        // 视觉比设计值重，且绕过 rememberMotionTier() 让精简档仍然缩放。
        AppFab(
            onClick = onAddClick,
            contentDescription = "添加",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(t.space4),
        )

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )

        // 月份切换越界提示（接 availableMonths 的边界）
        AppToast(
            message = monthNotice,
            onDismiss = { viewModel.consumeMonthNotice() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = t.space12),
        )
    }
}

