package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt；职责：s04 记账首页的屏幕装配（列表结构 / 状态机 / FAB / 刷新时机）。
// 卡片见 FinanceMainSections，明细行与五态块见 FinanceMainRows，派生纯函数见 FinanceMainDerived。
//
// 锚点：锚点 30 行 → 30 个 testTag，整屏根锚点 screen-s04 也落在这里。

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bsfeng.monitor.finance.FinanceInsertSignal
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppFab
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberDemoVisible
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * s04 记账首页。
 *
 * 月份弹簧切换 + 3 个滚动数字 + 预算圆环 + 双入口卡 + 按日分组的交易明细 + FAB。
 *
 * ⚠️ FAB 禁止外包 inset 高度容器（见 docs/踩坑/记账FAB被底部导航栏遮住导致无法新增交易.md）：
 * 容器高度只有 inset 那么高，56dp 的 FAB 从容器顶部向下溢出会被底部导航栏整块盖住。
 * 正确做法是把 align(BottomEnd) 直接加在 FAB 自己的 Modifier 上（见文件内 AppFab 调用点）。
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
    /**
     * 删除成功返回本屏后的焦点复位请求（锚点 s05-del-confirm · 移除后）。
     *
     * 由删除入口（MainTabContent）给出：删的是哪一笔、焦点该落到哪一笔。本屏消费一次后
     * 通过 [onRowFocusConsumed] 清空，避免任何后续重组重复抢焦点。
     */
    focusRowAfterDelete: RowFocusAfterDelete? = null,
    onRowFocusConsumed: () -> Unit = {},
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
    val loadFailed by viewModel.loadFailed.collectAsState()
    val monthNotice by viewModel.monthNotice.collectAsState()
    val actionNotice by viewModel.actionNotice.collectAsState()

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

    // 保存 / 删除成功的一次性反馈（s05-save / s05-del-confirm）：文案与触感由 ViewModel 给出
    // （「已保存 · 支出 餐饮 ¥38.00」SUCCESS /「已删除这笔交易」REJECT），1.6s 停留由 AppToast 统一，
    // 本屏只负责消费一次 —— 事件被清空后，切 Tab / 回前台重拉都不会重复弹。
    LaunchedEffect(actionNotice) {
        val notice = actionNotice ?: return@LaunchedEffect
        haptic(if (notice.deleted) HapticTone.REJECT else HapticTone.SUCCESS)
        toast.show(notice.message)
        viewModel.consumeActionNotice()
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
    val demoVisible = rememberDemoVisible()
    // 移除后五问（焦点）：错态重试成功后焦点回明细区标题；五态条收起后焦点回页头标题。
    //
    // ⚠️ 页头落点（headFocus）只在演示件可见时才挂到节点上（见下方 head item 的 if (demoVisible)）：
    // 正式包 / 未解锁 debug 包上该节点不存在，对它 requestFocus() 会抛
    // IllegalStateException（FocusRequester is not initialized）—— 真实用户长按两次标题即可触发。
    // 所以「要不要请求」先判落点是否挂载（[shouldRestoreHeadFocus]），再兜一层 runCatching。
    val listHeadFocus = remember { FocusRequester() }
    val headFocus = remember { FocusRequester() }
    // 明细行的焦点落点（删除后回位用；只挂到目标那一行，见 financeMainItems 的 focusRowId）
    val rowFocus = remember { FocusRequester() }
    var barWasShown by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(control.barShown) {
        val restoreHeadFocus = shouldRestoreHeadFocus(barWasShown, control.barShown, demoVisible)
        barWasShown = control.barShown
        if (restoreHeadFocus) runCatching { headFocus.requestFocus() }
    }
    LaunchedEffect(retryTick) {
        if (retryTick == 0) return@LaunchedEffect
        // 移除后五问（滚动 / 焦点）：列表回顶、焦点回明细区标题；计数与 KPI 由数据重算
        listState.scrollToItem(0)
        listHeadFocus.requestFocus()
    }
    val monthDisplay = monthLabelOf(currentMonth)
    val grouped = remember(transactions) { viewModel.getTransactionsGroupedByDate(transactions) }
    val overallBudget = remember(budgets) { budgets.find { it.isOverall } }
    val today = remember(transactions, currentMonth) { todayTotals(transactions, currentMonth) }
    // O(n) 计数 + LocalDate.now()：只随列表数据 / 月份变化重算（放在屏级，列表头滚出再滚回不重算）；
    // 跨零点不自刷，取舍同 groupLabel。
    val recentCount = remember(transactions, currentMonth) {
        recentDayCount(transactions, LIST_META_DAYS)
    }

    // 四态推导（s04-loading / empty / error / populated）：
    // 1) 加载中且还没有数据 → 骨架；换月时有旧内容则保留到新数据到达，不闪骨架（s04-loading 边界）。
    // 2) 加载结束仍读取失败 → 错态。这条是真实故障的唯一入口：读库异常由 FinanceViewModel 落到
    //    loadFailed，此前没有它，数据库坏了只会显示「本月还没有交易」，重试还无条件报成功。
    // 3) 读到空列表 → 空态；其余 → 有数据态。
    val derivedState = when {
        loading -> if (transactions.isEmpty()) FiveState.LOADING else FiveState.POPULATED
        loadFailed -> FiveState.ERROR
        transactions.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState

    // 锚点 s05-del-confirm · 移除后：删除并返回列表后，焦点落在被删行的下一行（末行则上一行）。
    // 三步走，全程不抛：
    // 1) 等重载落地（被删的那笔从明细消失）——否则位次差一行，焦点落到再下一行；
    // 2) 按明细项下标把目标行滚进组合窗口（懒加载列表里没组合的项请求不到焦点）；
    // 3) 逐帧请求，最多 3 帧；仍失败就兜底回明细区标题（不崩、不静默卡住）。
    LaunchedEffect(focusRowAfterDelete, state, grouped) {
        val pending = focusRowAfterDelete ?: return@LaunchedEffect
        // 删除后重载失败（loadFailed → ERROR）：被删那笔仍留在内存列表里，「等它消失」永不成立，
        // 不兜底就 pending 永不消费、焦点不落地（不崩但也没有回位）。错态下没有相邻行可落，
        // 兜底到明细区标题（listhead 在错态照常渲染），并当次消费掉，避免后续每次重组重复请求。
        if (state == FiveState.ERROR) {
            runCatching { listHeadFocus.requestFocus() }
            onRowFocusConsumed()
            return@LaunchedEffect
        }
        if (grouped.values.any { txs -> txs.any { it.id == pending.deletedId } }) return@LaunchedEffect
        val index = pending.targetId?.let { financeMainItemIndexOf(grouped, it) }
        if (state == FiveState.POPULATED && index != null) {
            runCatching { listState.scrollToItem(index) }
            var focused = false
            repeat(3) {
                if (focused) return@repeat
                withFrameNanos { }
                focused = runCatching { rowFocus.requestFocus() }.isSuccess
            }
            if (!focused) runCatching { listHeadFocus.requestFocus() }
        } else {
            // 列表已空（删掉最后一笔）或不在有数据态：没有相邻行可落，焦点回明细区标题
            runCatching { listHeadFocus.requestFocus() }
        }
        onRowFocusConsumed()
    }

    // 沉浸式底衬由 MainActivity 最外层 appBackdrop() 负责（同一套 backdrop-a/b token 渐变），
    // 本屏不再叠一层全屏渐变：滚动时少一次全屏填充与两次 Brush/listOf 分配。
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen-s04"),
    ) {
        // 底部避让：列表末端留出 FAB 盒（fab-size 56）+ FAB 外边距（16）+ 一行余量（24）= 96dp，
        // 滚到列表底部时最后一行金额不再压在 FAB 下（原先是列表尾部的假 item，等效但不可见）。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("s04-content"),
            contentPadding = PaddingValues(bottom = t.fabSize + t.space4 + t.space6),
        ) {
            item(key = "head") {
                // 页头：长按（锚点 s04-head）由共享屏头承载；此处只负责锚点与「收起后焦点回标题」。
                Box(
                    modifier = Modifier
                        .testTag("s04-head")
                        .then(
                            if (demoVisible) {
                                Modifier
                                    .focusRequester(headFocus)
                                    .focusable()
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    FinanceScreenHead(
                        title = "记账",
                        subtitle = monthDisplay + " · 共 " + transactions.size + " 笔",
                        // 月份标签统一走 monthLabelOf（与原型一致保留空格）
                        // 锚点 s04-head · 边界「正式包长按无任何反应」：演示件不可见时不接手势
                        // （传 null 而不是空实现：不接管指针输入才真的没有反馈，也不改任何状态）
                        onLongPress = if (demoVisible) ({ control.toggleBar() }) else null,
                        longPressTimeoutMillis = 600L,
                        // 锚点 s04-import：44dp 圆形图标按钮，按下 0.96 + LIGHT（共享壳件无 interaction 入口，
                        // 故导入按钮由本页部件提供，锚点落在按钮自身）
                        trailing = { S04ImportButton(onClick = onImportClick) },
                    )
                }
            }

            item(key = "month") {
                S04MonthBar(
                    monthText = monthDisplay,
                    onPrevious = { viewModel.previousMonth() },
                    onNext = { viewModel.nextMonth() },
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
                    modifier = Modifier
                        .focusRequester(listHeadFocus)
                        .focusable()
                        .keyboardFocusRing(RoundedCornerShape(t.radiusMd)),
                )
            }

            financeMainItems(
                state = state,
                grouped = grouped,
                highlightId = highlightId,
                tokens = t,
                focusRowId = focusRowAfterDelete?.targetId,
                focusRequester = rowFocus,
                onRowClick = {
                    haptic(HapticTone.LIGHT)
                    onEditClick(it)
                },
                onAddClick = onAddClick,
                onRetry = {
                    // 锚点 s04-retry：立即重查，**按真实结果**决定走向 ——
                    // 成功才清演示态 + toast「已重新加载」+ 焦点/滚动回明细区标题；
                    // 再次失败时 loadFailed 保持置位（错态卡原地保留），不弹第二个 toast、不伪装成功。
                    viewModel.loadCurrentMonth { result ->
                        if (result.isSuccess) {
                            control.select(null)
                            toast.show("已重新加载")
                            retryTick++
                        }
                    }
                },
            )
        }

        // FAB：直接 align(BottomEnd)，禁止外包 inset 高度容器（踩坑文档见类注释）。
        // 按压缩放（0.96）与 MEDIUM 触觉由 AppFab 内部统一提供（PressFeedback + Haptics），
        // 调用点不要再叠 graphicsLayer 缩放：外层 0.88 × 组件内 0.96 实际压到 0.845。
        AppFab(
            onClick = onAddClick,
            contentDescription = "新增交易",
            modifier = Modifier
                .testTag("s04-fab")
                .align(Alignment.BottomEnd)
                // 锚点 s04-fab：距右、距下各 16dp；固定右下、不随滚动移动
                .padding(t.space4),
        )

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )

        // 单条 toast 通道：月份越界提示优先，其次操作结果提示（锚点 1.6s 停留由 AppToast 统一）
        AppToast(
            message = monthNotice ?: toast.message,
            onDismiss = {
                if (monthNotice != null) viewModel.consumeMonthNotice() else toast.clear()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 抬到 FAB 之上并留出 FAB 盒高度：成功提示不遮挡右下角新增按钮
                .padding(bottom = t.fabSize + t.space8),
        )
    }
}

/**
 * 五态条从「展开」变「收起」时，是否该把焦点请求给页头标题（纯函数，可 JVM 单测）。
 *
 * 三个条件缺一不可：
 * 1. [barWasShown] —— 上一帧是展开的（否则这次变化不是「收起」）；
 * 2. [barShown] 现在已是收起；
 * 3. [headFocusAttached] —— 页头落点**真的挂在节点上**（只在演示件可见时挂）。
 *
 * 第 3 条是防崩的关键：`FocusRequester.requestFocus()` 打在没挂载的请求器上会抛
 * `IllegalStateException: FocusRequester is not initialized`，而正式包上「长按标题两次」就能
 * 走到这里（长按入口原先没有闸门）。返回 false 时调用方连请求都不发，焦点原地不动。
 */
internal fun shouldRestoreHeadFocus(
    barWasShown: Boolean,
    barShown: Boolean,
    headFocusAttached: Boolean,
): Boolean = barWasShown && !barShown && headFocusAttached
