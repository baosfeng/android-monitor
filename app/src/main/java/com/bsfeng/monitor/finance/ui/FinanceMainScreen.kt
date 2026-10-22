package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bsfeng.monitor.finance.FinanceInsertSignal
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppFab
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.CategoryIcon
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
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
                Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(t.space2),
                            ) {
                                KpiLine(
                                    label = "本月支出",
                                    value = yen(animatedAmount(stats.totalExpense.toFloat()).toDouble(), 0),
                                    color = t.expenseText,
                                )
                                KpiLine(
                                    label = "本月收入",
                                    value = yen(animatedAmount(stats.totalIncome.toFloat()).toDouble(), 0),
                                    color = t.incomeText,
                                )
                                KpiLine(
                                    label = "本月结余",
                                    value = yen(animatedAmount(stats.balance.toFloat()).toDouble(), 0),
                                    color = t.fg,
                                )
                            }
                            Spacer(Modifier.width(t.space3))
                            FinanceRingGauge(
                                fraction = stats.budgetProgress,
                                big = ((stats.budgetProgress * 100).toInt()).toString() + "%",
                                small = "预算",
                                // 预算进度 = accent（蓝）：原型 s04/s06 circle.chart-gauge 实测
                                // stroke rgb(0,113,227) = --accent（设计规范 §2.4），与 BudgetScreen 同一口径
                                color = t.accent,
                            )
                        }
                    }
                    Spacer(Modifier.height(t.space3))
                    Text(
                        if (isCurrentMonth(currentMonth)) {
                            "今日 支出 " + yen(today.first, 0) +
                                " · 收入 " + yen(today.second, 0) +
                                " · 结余 " + yen(today.second - today.first, 0)
                        } else {
                            "共 " + transactions.size + " 笔 · 预算使用 " +
                                (stats.budgetProgress * 100).toInt() + "%"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = t.meta,
                    )
                }
            }

            item(key = "entries") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = t.space4, vertical = t.space2),
                    horizontalArrangement = Arrangement.spacedBy(t.space3),
                ) {
                    EntryCard(
                        title = "统计分析",
                        subtitle = "分类占比 · 近 7 天",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic(HapticTone.LIGHT)
                            onStatsClick()
                        },
                        icon = {
                            Icon(
                                Icons.Filled.BarChart,
                                contentDescription = null,
                                tint = t.fg2,
                                // 原型 .s04-entry-ic svg = 19px，取就近图标 token（IconSize.md = 20dp）
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                    EntryCard(
                        title = "预算设置",
                        subtitle = if (overallBudget != null && overallBudget.budgetAmount > 0) {
                            "已用 " + yen(overallBudget.spentAmount, 0) + " / " + yen(overallBudget.budgetAmount, 0)
                        } else {
                            "尚未设置本月预算"
                        },
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic(HapticTone.LIGHT)
                            onBudgetClick()
                        },
                        icon = {
                            Icon(
                                Icons.Filled.Savings,
                                contentDescription = null,
                                tint = t.fg2,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }
            }

            item(key = "listhead") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = t.space4, vertical = t.space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "交易明细",
                        style = MaterialTheme.typography.headlineSmall,
                        color = t.fg,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        // 原型 s04-listmeta（s04-finance-home.html:95 / :319）：当月 =「最近 3 天 · N 笔」，
                        // 其它月份 =「共 N 笔」（N = 当月总笔数）。
                        if (grouped.isEmpty()) {
                            "暂无记录"
                        } else if (isCurrentMonth(currentMonth)) {
                            "最近 3 天 · " + recentCount + " 笔"
                        } else {
                            "共 " + transactions.size + " 笔"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = t.meta,
                    )
                }
            }

            // J1：整月明细做成**行级** lazy items —— 日期头一个 item、每笔交易一个 item，
            // 只有可见行会组合；入场动画只给首屏可见的若干行。
            // 卡容器（原型 .group）不再由单个 item 承载，改由每行自己拼出：见 [DayGroupRow]。
            var rowIndex = 0
            when (state) {
                FiveState.POPULATED -> grouped.forEach { (date, txs) ->
                    item(key = "day-" + date) {
                        // 日期解析 + LocalDate.now() 只在挂载时算一次；
                        // 「今天/昨天」跨零点不会自动刷新 —— 本屏每次进入 Tab / 回前台都会重新 loadMonth 并重组，
                        // 可接受；若要求跨零点实时，应换成「每天变一次」的日期状态源。
                        val label = remember(date) { groupLabel(date) }
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = t.muted,
                            modifier = Modifier
                                .padding(horizontal = t.space4)
                                .padding(top = t.space2, start = t.space2),
                        )
                    }
                    txs.forEachIndexed { index, tx ->
                        // key 只用主键：finance_transactions.id 是 INTEGER PRIMARY KEY AUTOINCREMENT，
                        // toContentValues 不写 id（导入同样走自增），同一查询内 id 必然唯一。
                        // 带组内下标会让「插入/删除一笔」把该日后续行的 key 全改掉 → 整组丢状态、重播入场动画。
                        item(key = "tx-" + tx.id) {
                            DayGroupRow(
                                first = index == 0,
                                last = index == txs.lastIndex,
                            ) {
                                TransactionRow(
                                    transaction = tx,
                                    color = categoryColorOf(tx.categoryName),
                                    highlighted = tx.id == highlightId,
                                    enterIndex = if (rowIndex < STAGGER_MAX_STEPS) rowIndex++ else -1,
                                    onClick = {
                                        haptic(HapticTone.LIGHT)
                                        onEditClick(tx)
                                    },
                                )
                                if (index != txs.lastIndex) {
                                    HorizontalDivider(
                                        thickness = DividerDefaults.Thickness,
                                        color = t.borderSoft,
                                        modifier = Modifier.padding(start = t.space8),
                                    )
                                }
                            }
                        }
                    }
                }

                // 原型 s04-empty（s04:161-165）：标题 + 说明 + 「新增一笔」
                FiveState.EMPTY -> item(key = "empty") {
                    Column(Modifier.padding(horizontal = t.space4)) {
                        FinanceEmptyState(
                            title = "本月还没有交易",
                            desc = "点右下角「+」记下第一笔，或从熊猫记账导入历史账目",
                            icon = Icons.Filled.Download,
                            actionText = "新增一笔",
                            onAction = onAddClick,
                        )
                    }
                }

                else -> item(key = "state") {
                    StateContainer(
                        state = state,
                        modifier = Modifier.padding(horizontal = t.space4),
                        emptyText = "点右下角「+」记下第一笔，或从熊猫记账导入历史账目",
                        errorText = "本地数据库读取超时，没能取到交易记录",
                        edgeText = "极限数据：单月 10,240 笔 · 单笔最大 ¥1,234,567.89",
                        onRetry = { viewModel.loadCurrentMonth() },
                    ) { }
                }
            }

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

@Composable
private fun KpiLine(label: String, value: String, color: Color) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    // 原型 .s04-entry：min-height 104px、图标块 36×36（radius-md、底 fg 6%、色 fg-2）、
    // 标题 17sp/600、副标题 14sp（s04-finance-home.html:12-25）。
    AppCard(modifier = modifier.heightIn(min = 104.dp), onClick = onClick) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.fgFade(6)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(t.space2))
        Text(title, style = MaterialTheme.typography.titleSmall, color = t.fg, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(t.space1))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

/**
 * 按日分组卡（原型 `.group`：surface 底 + 1px border-soft + radius-lg，00-shell.html:255-258）的**单行**。
 *
 * J1 行级虚拟化后，卡容器不再由「一天一个 item」承载，改由每行自绘：首行圆上角、末行圆下角，
 * 其余行是直角，描边只画外缘（内侧边由行间的 HorizontalDivider 承担，等价原型
 * `.group-row:first-child{border-top:0}` + 行间 border-top，00-shell.html:262-268）。
 */
@Composable
private fun DayGroupRow(
    first: Boolean,
    last: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    val r = t.radiusLg
    val shape = remember(first, last, r) {
        RoundedCornerShape(
            topStart = if (first) r else 0.dp,
            topEnd = if (first) r else 0.dp,
            bottomStart = if (last) r else 0.dp,
            bottomEnd = if (last) r else 0.dp,
        )
    }
    Column(
        modifier = Modifier
            .padding(horizontal = t.space4)
            .fillMaxWidth()
            .background(t.surface, shape)
            // drawWithCache：outline / Stroke / 裁剪边界只在尺寸或 shape 变化时构建，
            // 滚动重绘时只重放 onDrawBehind（不再每帧 createOutline + new Stroke × 可见行数）
            .drawWithCache {
                // 只画外缘描边：把 outline 的上/下 1px 带裁掉（相邻行各自画竖线，接缝处仍是 1px）
                val w = 1.dp.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                val stroke = Stroke(w)
                val clipTop = if (first) 0f else w
                val clipBottom = if (last) size.height else size.height - w
                onDrawBehind {
                    clipRect(
                        left = 0f,
                        top = clipTop,
                        right = size.width,
                        bottom = clipBottom,
                    ) {
                        drawOutline(outline, color = t.borderSoft, style = stroke)
                    }
                }
            }
            .padding(t.space1),
        content = content,
    )
}

@Composable
private fun TransactionRow(
    transaction: Transaction,
    color: Color,
    /** 自动记账刚落库：整行淡 accent 底点亮，1.6s 后淡出（其余行恒为透明） */
    highlighted: Boolean = false,
    /** >= 0 走错落入场；-1 表示离屏行不做入场动画（每行一个 Animatable 会拖帧） */
    enterIndex: Int,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val amountIsPositive = !transaction.isExpense || transaction.isRefundEntry
    val isTransfer = transaction.isTransfer
    // 来源徽标：自动记账 / 带 sourceId 的导入行才判 true，手写记录恒 false（见 TransactionSourceBadge）
    val isAutoBooked = TransactionSourceBadge.isAutoBooked(transaction.source, transaction.sourceId)
    val enterModifier = if (enterIndex >= 0) Modifier.staggerEnterAt(enterIndex) else Modifier
    val rowShape = remember(t.radiusMd) { RoundedCornerShape(t.radiusMd) }
    // 沿用原本的「背景 + clip」口径；只有高亮行才组合动画对象（非高亮行走 Color.Transparent 的零对象路径）
    val highlightColor = if (highlighted) autoBookHighlightColor() else Color.Transparent
    // 行内金额的 String.format 只随金额 / 收支方向变化重算，滚动挂载不重复格式化
    val amountText = remember(transaction.amount, isTransfer, amountIsPositive) {
        if (isTransfer) "¥" + transaction.displayAmount
        else (if (amountIsPositive) "+" else "-") + "¥" + transaction.displayAmount
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(enterModifier)
            .clip(rowShape)
            .background(highlightColor)
            .clickable { onClick() }
            .padding(horizontal = t.space3, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 列表行展示分类线描图标（与分类选择格同一套配色），不再只用色点
        CategoryIcon(name = transaction.categoryName, color = color, size = 34.dp)
        Spacer(Modifier.width(t.space3))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 转账没有分类：标题显示「转出账户 → 转入账户」，否则这一行会是空的
                    if (isTransfer) RecordForm.transferTitle(transaction) else transaction.categoryName,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isAutoBooked) {
                    // 原型 s04：标题右侧、金额左边的小号「自动」徽标（浅底圆角、token 着色）
                    Spacer(Modifier.width(t.space1))
                    Badge(TransactionSourceBadge.AUTO_LABEL, tone = BadgeTone.Accent)
                }
            }
            // 副标题：优先用户手写的「说明」，为空时回退自动采集的商户 · 付款方式
            // （再退回商品说明），见 RecordForm.subtitle
            val sub = RecordForm.subtitle(transaction)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(t.space2))
        Text(
            // 转账既不是花掉也不是赚到：不带正负号、用中性色（账户余额仍按双向真实增减）
            text = amountText,
            // 原型 .list-row .row-tail = 14px/600（同行 .row-title 17px/500）：金额不得抢过标题
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                isTransfer -> t.muted
                amountIsPositive -> t.incomeText
                else -> t.expenseText
            },
        )
    }
}

/**
 * 自动记账新行的高亮底色。
 *
 * 只有 [highlighted] 为 true 的行才会组合到这里：非高亮行不建 Animatable / 动画状态对象，
 * 滚动挂载 8–12 行时省下 8–12 套对象。挂载即满亮（新行插入时原先就是直接满高亮），
 * 1.6s 后 240ms 淡出；外层计时同为「1.6s + 淡出」，淡出跑完才卸载这一层。
 */
@Composable
private fun autoBookHighlightColor(): Color {
    val t = LocalDesignTokens.current
    val alpha = remember { Animatable(HIGHLIGHT_ALPHA) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(HIGHLIGHT_DURATION_MS)
        alpha.animateTo(0f, tween(HIGHLIGHT_FADE_MS))
    }
    return t.accent.copy(alpha = alpha.value)
}

/** 分组标题：10 月 14 日 · 今天 / 昨天 / 前天。 */
internal fun groupLabel(date: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val today = LocalDate.now()
    val suffix = when (parsed) {
        today -> " · 今天"
        today.minusDays(1) -> " · 昨天"
        today.minusDays(2) -> " · 前天"
        else -> ""
    }
    return parsed.monthValue.toString() + " 月 " + parsed.dayOfMonth + " 日" + suffix
}

/** 交易明细右侧「最近 N 天」的天数（原型 s04-listmeta：最近 3 天 · 8 笔）。 */
private const val LIST_META_DAYS = 3

/** 自动记账新行高亮时长（原型 s04「高亮 1.6s」） */
private const val HIGHLIGHT_DURATION_MS = 1600L

/** 高亮淡出时长（与原 animateColorAsState 的 tween(240) 一致） */
private const val HIGHLIGHT_FADE_MS = 240

/** 高亮底色不透明度（原 t.accent.copy(alpha = 0.12f)） */
private const val HIGHLIGHT_ALPHA = 0.12f

/** 最近 [days] 天（含今天）的笔数：日期为 ISO yyyy-MM-dd，可直接做字典序比较。 */
internal fun recentDayCount(transactions: List<Transaction>, days: Int): Int {
    if (days <= 0) return 0
    val from = LocalDate.now().minusDays((days - 1).toLong()).toString()
    return transactions.count { it.date >= from }
}

internal fun isCurrentMonth(yearMonth: String): Boolean =
    yearMonth == FinanceViewModel.currentMonthStr()

/** 今日（当月为当前月时）的支出 / 收入。 */
internal fun todayTotals(transactions: List<Transaction>, yearMonth: String): Pair<Double, Double> {
    if (!isCurrentMonth(yearMonth)) return 0.0 to 0.0
    val today = LocalDate.now().toString()
    var expense = 0.0
    var income = 0.0
    transactions.filter { it.date == today }.forEach { tx ->
        val magnitude = if (tx.isRefundEntry) tx.amount else kotlin.math.abs(tx.amount)
        if (tx.isExpense) expense += magnitude else if (tx.isIncome) income += magnitude
    }
    return expense to income
}
