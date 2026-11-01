package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/BudgetScreen.kt；职责：预算设置屏幕装配（月份条、总预算/分类预算、五态、编辑弹窗与提示）。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Savings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.AppField
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.delay

/**
 * s06 预算设置（锚点账本，24 个锚点）。
 *
 * 总预算圆环 + 6 条分类进度条；修改任意一行后**总预算 = 分类预算之和**、圆环百分比与
 * 剩余额度都由真实数据重算（见 [FinanceViewModel.setCategoryBudget]）。
 * 顶栏固定、内容单列纵向滚动；五态演示条仅 DEBUG 包长按标题唤出。
 */
@Composable
fun BudgetScreen(
    viewModel: FinanceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    // 触觉统一走 foundation/Haptics.kt 的 rememberHaptic（三重闸门 + 强度档换算）
    val haptic = rememberHaptic()
    val currentMonth by viewModel.currentMonth.collectAsState()
    val budgets by viewModel.budgets.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val stats by viewModel.monthlyStats.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadFailed by viewModel.loadFailed.collectAsState()
    val monthNotice by viewModel.monthNotice.collectAsState()
    val toast = rememberToastHostState()

    val control = rememberFinanceStateControl()
    var editingTarget by remember { mutableStateOf<BudgetEditorTarget?>(null) }
    // 锚点 s06-dlg-save · 边界：写入失败时弹窗不关、输入保留，并在弹窗内提示「保存失败，请重试」
    var saveError by remember { mutableStateOf(false) }
    var refocusRow by remember { mutableStateOf<Int?>(null) }
    var refocusCta by remember { mutableStateOf(false) }
    var refocusTotal by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var loadTimedOut by remember { mutableStateOf(false) }
    val totalFocus = remember { FocusRequester() }
    val ctaFocus = remember { FocusRequester() }
    // 锚点 s06-empty「焦点落到标题」/ s06-error「焦点落到『重新加载』」的两个入口。
    // 空态标题是共享 EmptyState 的标题块（不可改共享件），焦点挂在整个标题块上：读屏先读标题再读描述。
    val emptyTitleFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val seenRows = remember { mutableSetOf<Int>() }

    val overallBudget = remember(budgets) { budgets.find { it.isOverall } }
    val categoryBudgets = remember(budgets) { budgets.filterNot { it.isOverall } }
    val budgetByCategory = remember(budgets) { budgets.associateBy { it.categoryName } }
    // 预算按一级分类设置：二级不单独占预算行
    val expenseCategories = remember(categories) {
        categories.filter { it.type == TYPE_EXPENSE && it.isTopLevel }
    }

    // 分类预算合计即总预算（原型口径）；一条分类预算都没有时回退到总预算行
    val categorySum = remember(categoryBudgets) { categoryBudgets.sumOf { it.budgetAmount } }
    val totalBudget = if (categorySum > 0) categorySum else (overallBudget?.budgetAmount ?: 0.0)
    val spentTotal = stats.totalExpense
    val remaining = totalBudget - spentTotal
    val fraction = if (totalBudget > 0) (spentTotal / totalBudget).toFloat() else 0f

    val rows = remember(expenseCategories, budgetByCategory) {
        budgetRowsFor(expenseCategories, budgetByCategory)
    }

    // 首屏加载中才铺骨架（有缓存时直接显示旧值，避免闪屏）；重试期间同样铺骨架
    val loadingLike = (loading && budgets.isEmpty()) || retrying
    // 空预算不再整页替换为空文案：空态卡自带「设置总预算」CTA（原型 s06-empty-set），
    // 下方的分类行也照常渲染并可直接编辑，保证未设预算的库也能完成一次设置。
    val realEmpty = !loadingLike && budgets.isEmpty() && categorySum <= 0.0
    val derivedState = when {
        // 锚点 s06-loading：超过 8s 仍无数据 → 错误态（先于 LOADING 判定，否则永远停在骨架）
        loadTimedOut -> FiveState.ERROR
        loadingLike -> FiveState.LOADING
        // 真实读取失败（与 s04 同一口径）：加载结束后仍失败 → 错误态，
        // 不再把「读不出来」渲染成「本月还没有设置预算」的空态。
        loadFailed -> FiveState.ERROR
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    // EMPTY 只作为演示态：内容仍按 POPULATED 渲染，空态卡画在列表里
    val showEmptyCard = state == FiveState.EMPTY || (state == FiveState.POPULATED && realEmpty)
    // 锚点 s06-error · 边界「有缓存时错误条贴在列表首位、下方数值可读不可改」：
    // 「有缓存」不能只看 budgets —— 没设预算但有支出的月份同样有可读数值（KPI / 分类行），
    // 所以按本屏会渲染的数据整体判：预算、本月支出、分类三者任一非空即算有缓存。
    val hasCache = budgets.isNotEmpty() || spentTotal > 0.0 || expenseCategories.isNotEmpty()
    /**
     * 打开预算编辑弹窗的唯一入口。
     *
     * 错误态下**不打开**（读不出来时写预算等于在坏数据上覆盖真实值，交互口径要求「可读不可改」），
     * 但给可解释的 toast，而不是让按钮静默失效（可解释原则：禁用必须有原因）。
     */
    fun openEditor(target: BudgetEditorTarget) {
        if (state == FiveState.ERROR) {
            toast.show("预算数据读取失败，重新加载后再修改")
            return
        }
        editingTarget = target
    }

    // 锚点 s06-loading：加载超过 8s 自动转错误态
    LaunchedEffect(loadingLike) {
        loadTimedOut = false
        if (loadingLike) {
            delay(8_000)
            loadTimedOut = true
        }
    }
    // 锚点 s06-retry：按**真实读取结果**分流 —— 失败保持错误态并 toast「仍未读取到预算数据」，
    // 成功才淡回列表；不再用「预算是否为空」猜成败（读库失败时预算同样为空，会被误判成成功）。
    LaunchedEffect(retrying, loading) {
        if (!retrying || loading) return@LaunchedEffect
        retrying = false
        if (viewModel.loadFailed.value || (budgets.isEmpty() && categorySum <= 0.0)) {
            toast.show("仍未读取到预算数据")
        } else {
            loadTimedOut = false
            control.select(null)
            // 锚点 s06-retry · 移除后：错误态消失后焦点回原「重新加载」位置（列表首项 = 总预算卡）
            refocusTotal = true
        }
    }
    // 每次打开 / 关闭弹窗都清掉上一次的写入失败提示（失败只在当前这一次保存里成立）
    LaunchedEffect(editingTarget) { saveError = false }

    // 锚点 s06-empty-set · 移除后：弹窗关闭后焦点回 CTA
    LaunchedEffect(refocusCta) {
        if (!refocusCta) return@LaunchedEffect
        // CTA 可能刚随空态卡重挂（重试成功 / 保存 0 后空态卡回来），延一帧等它进入焦点树
        withFrameNanos { }
        runCatching { ctaFocus.requestFocus() }
        refocusCta = false
    }
    // 锚点 s06-retry · 移除后：错误态消失后焦点回原「重新加载」位置（列表首项 = 总预算卡）
    LaunchedEffect(refocusTotal) {
        if (!refocusTotal) return@LaunchedEffect
        withFrameNanos { }
        runCatching { totalFocus.requestFocus() }
        refocusTotal = false
    }
    // 锚点 s06-empty：进入空态时焦点落到标题（只在该卡出现时请求，不抢输入焦点）
    LaunchedEffect(showEmptyCard) {
        if (!showEmptyCard) return@LaunchedEffect
        withFrameNanos { }
        runCatching { emptyTitleFocus.requestFocus() }
    }
    // 锚点 s06-error：进入错误态时焦点落到「重新加载」（错误态下 Tab 只停这一处）
    LaunchedEffect(state) {
        if (state != FiveState.ERROR) return@LaunchedEffect
        withFrameNanos { }
        runCatching { retryFocus.requestFocus() }
    }

    Box(modifier.fillMaxSize().pageBackdrop().testTag("screen-s06")) {
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
                    "预算设置",
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
                    .testTag("s06-content"),
            ) {
                // 锚点 s06-error · 边界：错误态两种形态互斥 ——
                // 无缓存 → 错态卡整块占据列表区（不显示任何数字）；
                // 有缓存 → 错误条贴列表首位，下方 KPI / 分类行照常渲染（可读不可改，闸门在 openEditor）。
                val errorCard = financeErrorAsCard(state, hasCache)
                when {
                    state == FiveState.LOADING -> item(key = "loading") {
                        // 骨架条 12/20px + shimmer，整屏仍可上下滚动，不出现任何真实数字
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .testTag("s06-loading")
                                .padding(horizontal = t.space4, vertical = t.space3),
                        ) {
                            StateSkeleton(rows = 6)
                        }
                    }

                    // 无缓存才整块替换：此时列表区没有任何可读数值，Tab 只停在「重新加载」
                    errorCard -> item(key = "error") {
                        ErrorState(
                            text = "本地预算表损坏或读取超时，当前显示为上次缓存值",
                            title = "预算数据读取失败",
                            actionLabel = "重新加载",
                            onRetry = {
                                retrying = true
                                viewModel.loadCurrentMonth()
                            },
                            modifier = Modifier.testTag("s06-error"),
                            // 锚点 s06-error：焦点直接落到「重新加载」（focusRequester 必须在 clickable 之前）
                            actionModifier = Modifier
                                .testTag("s06-retry")
                                .focusRequester(retryFocus),
                        )
                    }

                    else -> {
                        // 有缓存：错误条贴首位，下方数值仍在（一次读库抖动不清空用户已看到的数字）
                        if (state == FiveState.ERROR) item(key = "errorbar") {
                            FinanceInlineErrorBar(
                                title = "预算数据读取失败",
                                text = "本地预算表损坏或读取超时，当前显示为上次缓存值",
                                actionLabel = "重新加载",
                                onRetry = {
                                    retrying = true
                                    viewModel.loadCurrentMonth()
                                },
                                modifier = Modifier
                                    .testTag("s06-error")
                                    .padding(horizontal = t.space4, vertical = t.space2),
                                actionModifier = Modifier
                                    .testTag("s06-retry")
                                    .focusRequester(retryFocus),
                            )
                        }
                        if (showEmptyCard) item(key = "empty") {
                            EmptyState(
                                text = "先设置总预算，再为常用分类分配额度，超支时会收到提醒",
                                title = "本月还没有设置预算",
                                icon = Icons.Filled.Savings,
                                actionLabel = "设置总预算",
                                onAction = {
                                    openEditor(
                                        BudgetEditorTarget(
                                            categoryName = "",
                                            title = "总预算",
                                            currentAmount = overallBudget?.budgetAmount ?: 0.0,
                                        )
                                    )
                                },
                                // 锚点 s06-empty：空态标题获得初始焦点；标题块同时是键盘焦点停位，带 2dp 焦点环
                                modifier = Modifier
                                    .testTag("s06-empty")
                                    .focusRequester(emptyTitleFocus)
                                    .padding(horizontal = t.space4)
                                    .keyboardFocusRing(RoundedCornerShape(t.radiusLg))
                                    .focusable(),
                                actionModifier = Modifier
                                    .testTag("s06-empty-set")
                                    .focusRequester(ctaFocus),
                            )
                        }

                        item(key = "month") {
                            FinanceMonthBar(
                                monthText = monthLabelOf(currentMonth),
                                onPrevious = { viewModel.previousMonth() },
                                onNext = { viewModel.nextMonth() },
                                modifier = Modifier
                                    .padding(vertical = t.space2)
                                    .testTag("s06-monthbar")
                                    .onPreviewKeyEvent { event ->
                                        // 锚点 s06-monthbar：条内 ← / → 等同 prev / next，边界只出 toast
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                viewModel.previousMonth()
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                viewModel.nextMonth()
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                                previousModifier = Modifier.testTag("s06-prev"),
                                nextModifier = Modifier.testTag("s06-next"),
                            )
                        }

                        if (state == FiveState.EDGE) {
                            item(key = "edge") {
                                BudgetEdgeSection(
                                    Modifier
                                        .testTag("s06-edge")
                                        .padding(vertical = t.space2),
                                )
                            }
                            item(key = "edgerow") {
                                Column(Modifier.padding(horizontal = t.space4)) {
                                    AppCard(padding = t.space1) {
                                        BudgetEdgeRow(
                                            title = EDGE_ROW_NAME,
                                            budgetAmount = EDGE_ROW_BUDGET,
                                            spentAmount = EDGE_ROW_SPENT,
                                            onClick = {
                                                openEditor(
                                                    BudgetEditorTarget(
                                                        categoryName = EDGE_ROW_NAME,
                                                        title = EDGE_ROW_NAME,
                                                        currentAmount = EDGE_ROW_BUDGET,
                                                    )
                                                )
                                            },
                                            modifier = Modifier.testTag("s06-edge-brow"),
                                        )
                                    }
                                }
                            }
                        } else {
                            item(key = "kpis") {
                                BudgetKpiSection(
                                    totalBudget = totalBudget,
                                    spentTotal = spentTotal,
                                    remaining = remaining,
                                    fraction = fraction,
                                    onEditTotal = {
                                        openEditor(
                                            BudgetEditorTarget(
                                                categoryName = "",
                                                title = "总预算",
                                                currentAmount = overallBudget?.budgetAmount ?: 0.0,
                                            )
                                        )
                                    },
                                    modifier = Modifier.testTag("s06-card"),
                                    totalFocusRequester = totalFocus,
                                )
                            }

                            item(key = "list") {
                                BudgetCategoryRowsSection(
                                    rows = rows,
                                    budgetByCategory = budgetByCategory,
                                    onEditCategory = { category, budget ->
                                        openEditor(
                                            BudgetEditorTarget(
                                                categoryName = category.name,
                                                title = category.name,
                                                currentAmount = budget?.budgetAmount ?: 0.0,
                                            )
                                        )
                                    },
                                    modifier = Modifier.testTag("s06-list"),
                                    seenRows = seenRows,
                                    refocusIndex = refocusRow,
                                    onRefocused = { refocusRow = null },
                                )
                            }
                        }

                        item(key = "bottom") { Spacer(Modifier.height(t.space12)) }
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

        // 提示条：月份边界（VM 一次性消息）优先，其次本屏操作提示；两者都在 1.6s 后自动消失
        AppToast(
            message = monthNotice ?: toast.message,
            onDismiss = {
                if (monthNotice != null) viewModel.consumeMonthNotice() else toast.clear()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.space12),
        )
    }

    editingTarget?.let { target ->
        BudgetEditorDialog(
            title = if (target.categoryName.isEmpty()) "设置总预算" else "修改预算",
            subtitle = target.title.takeIf { target.categoryName.isNotEmpty() },
            initialValue = budgetInputText(target.currentAmount),
            onDismiss = {
                editingTarget = null
                if (target.categoryName.isEmpty()) refocusCta = true
                else refocusRow = rows.indexOfFirst { it.name == target.categoryName }.takeIf { it >= 0 }
            },
            saveError = saveError,
            onInputChange = { if (saveError) saveError = false },
            onSave = { text ->
                val amount = text.trim().toDoubleOrNull() ?: 0.0
                val targetName = target.categoryName
                // 回执决定关不关弹窗（s06-dlg-save）：成功才关 + toast + SUCCESS 触感；
                // 失败保持弹窗与输入不变。失败提示画在弹窗内 —— 本 App 弹窗走 Dialog 独立窗口，
                // 页面级的 AppToast 会被遮罩整个挡住（见 ToastHost.kt 顶部注释），用户看不到。
                val onResult: (Result<Unit>) -> Unit = { result ->
                    if (result.isSuccess) {
                        toast.show(
                            if (targetName.isEmpty()) "已更新总预算为 " + yen(amount, 0)
                            else "已更新 " + targetName + " 预算为 " + yen(amount, 0)
                        )
                        // 锚点 s06-dlg-save：保存完成给 SUCCESS 触感
                        haptic(HapticTone.SUCCESS)
                        editingTarget = null
                        if (targetName.isEmpty()) refocusCta = true
                        else refocusRow = rows.indexOfFirst { it.name == targetName }.takeIf { it >= 0 }
                    } else {
                        saveError = true
                    }
                }
                if (targetName.isEmpty()) {
                    // 总预算：走 setBudget，不能用 setCategoryBudget——它会把总预算覆写为「分类预算之和」
                    viewModel.setBudget("", amount, onResult)
                } else {
                    viewModel.setCategoryBudget(targetName, amount, onResult)
                }
            },
        )
    }
}

/**
 * 预算编辑弹窗（锚点 s06-dialog / s06-dlg-cancel / s06-dlg-save）。
 *
 * 自绘而不用共享 ConfirmDialog：本弹窗需要「副标题分类名 + 输入框自动聚焦并全选 +
 * 取消 / 保存两颗带锚点的按钮 + 写入失败提示」，共享弹窗不透出这几处钩子。
 * 视觉沿用 AppDialog + 同一套 token；遮罩点击 / 返回键由 AppDialog 统一关闭。
 */
@Composable
private fun BudgetEditorDialog(
    title: String,
    subtitle: String?,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /** 上一次保存写入失败（锚点 s06-dlg-save · 边界）：弹窗不关，在输入框下显示失败文案。 */
    saveError: Boolean = false,
    /** 用户重新输入：清掉上一次的失败提示，回到正常校验提示。 */
    onInputChange: () -> Unit = {},
) {
    val t = LocalDesignTokens.current
    var field by remember {
        mutableStateOf(TextFieldValue(initialValue, TextRange(0, initialValue.length)))
    }
    val focus = remember { FocusRequester() }
    val raw = field.text
    val amount = raw.trim().toDoubleOrNull()
    // 空 = 清零（口径：清空后保存视为该分类预算清零）；"." / 负数 / 超长 → 非法
    val valid = raw.isBlank() || (amount != null && amount >= 0.0)
    val shape = RoundedCornerShape(t.radiusMd)

    // 打开即聚焦并全选当前值（锚点 s06-card · click）
    LaunchedEffect(Unit) { focus.requestFocus() }

    AppDialog(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.testTag("s06-dialog"),
    ) {
        Column(
            Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 锚点 s06-dialog · 键盘：Esc 等同取消
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    // 锚点 s06-dlg-save · 键盘：Enter 合法即保存；不合法则焦点留在输入框
                    Key.Enter, Key.NumPadEnter -> {
                        if (valid) onSave(raw) else focus.requestFocus()
                        true
                    }
                    else -> false
                }
            },
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
            if (subtitle != null) {
                Spacer(Modifier.height(t.space1))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
            Spacer(Modifier.height(t.space3))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.tapMin)
                    .clip(shape)
                    .background(t.surface)
                    .border(1.dp, t.border, shape)
                    .padding(horizontal = t.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("¥", style = MaterialTheme.typography.bodyMedium, color = t.muted)
                Spacer(Modifier.height(t.space1))
                BasicTextField(
                    value = field,
                    onValueChange = { next ->
                        val filtered = filterBudgetAmountInput(next.text)
                        field = next.copy(text = filtered, selection = TextRange(filtered.length))
                        onInputChange()
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = t.fg),
                    cursorBrush = SolidColor(t.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (raw.isEmpty()) {
                                Text("0.00", style = MaterialTheme.typography.titleMedium, color = t.muted)
                            }
                            inner()
                        }
                    },
                )
            }
            if (!valid) {
                Spacer(Modifier.height(t.space1))
                Text(
                    "请输入不小于 0 的金额",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.dangerText,
                )
            } else if (saveError) {
                Spacer(Modifier.height(t.space1))
                Text(
                    "保存失败，请重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.dangerText,
                )
            }
            Spacer(Modifier.height(t.space4))
            Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                BudgetDialogAction(
                    label = "取消",
                    primary = false,
                    enabled = true,
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("s06-dlg-cancel"),
                )
                BudgetDialogAction(
                    label = "保存",
                    primary = true,
                    enabled = valid,
                    onClick = { if (valid) onSave(raw) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("s06-dlg-save"),
                )
            }
        }
    }
}

/** 弹窗动作按钮：按下 0.96（[pressFeedback]）+ 2dp 键盘焦点环 + 禁用态 45% 透明。 */
@Composable
private fun BudgetDialogAction(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = modifier
            .height(t.tapMin)
            .pressFeedback(interaction, enabled)
            .clip(shape)
            .background(if (primary) t.accent.copy(alpha = if (enabled) 1f else 0.45f) else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else t.border, shape)
            .keyboardFocusRing(shape)
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
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                !enabled -> t.muted
                primary -> t.accentOn
                else -> t.fg
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("dialog.action"),
        )
    }
}
