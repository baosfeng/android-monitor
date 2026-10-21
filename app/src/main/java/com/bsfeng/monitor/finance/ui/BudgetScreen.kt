package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.components.performConfirmHaptic
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.components.rememberStaggerEnter
import com.bsfeng.monitor.ui.components.tweenOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.foundation.pageBackdrop

/**
 * s06 预算设置。
 *
 * 总预算圆环 + 6 条分类进度条；修改任意一行后**总预算 = 分类预算之和**、圆环百分比与
 * 剩余额度都由真实数据重算（并同步写回总预算行，见 [FinanceViewModel.setCategoryBudget]）。
 */
@Composable
fun BudgetScreen(
    viewModel: FinanceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val view = LocalView.current
    val currentMonth by viewModel.currentMonth.collectAsState()
    val budgets by viewModel.budgets.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val stats by viewModel.monthlyStats.collectAsState()
    val loading by viewModel.loading.collectAsState()

    val control = rememberFinanceStateControl()
    var editingTarget by remember { mutableStateOf<BudgetEditorTarget?>(null) }

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
    val isOver = totalBudget > 0 && spentTotal > totalBudget

    val rows = remember(expenseCategories, budgetByCategory) {
        budgetRowsFor(expenseCategories, budgetByCategory)
    }

    // 空预算不再整页替换为空文案：空态卡自带「设置总预算」CTA（原型 s06-empty-set），
    // 下方的分类行也照常渲染并可直接编辑，保证未设预算的库也能完成一次设置。
    val realEmpty = !loading && budgets.isEmpty() && categorySum <= 0.0
    val derivedState = if (loading && budgets.isEmpty()) FiveState.LOADING else FiveState.POPULATED
    val state = control.value ?: derivedState
    val showEmptyCard = state == FiveState.EMPTY || (state == FiveState.POPULATED && realEmpty)
    // EMPTY 只作为演示态：内容仍按 POPULATED 渲染，空态卡画在列表里
    val containerState = if (state == FiveState.EMPTY) FiveState.POPULATED else state

    Box(Modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space2, vertical = t.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FinanceIconButton(onClick = onBack, contentDescription = "返回") {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = null,
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
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { control.toggleBar() },
                )
            }

            StateContainer(
                state = containerState,
                modifier = Modifier.weight(1f),
                emptyText = "先设置总预算，再为常用分类分配额度，超支时会收到提醒",
                errorText = "本地预算表损坏或读取超时，当前显示为上次缓存值",
                edgeText = "边界态：总预算超支 128% / 超长分类名 / 单行金额 ¥999,999",
                onRetry = { viewModel.loadCurrentMonth() },
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "month") {
                        FinanceMonthBar(
                            monthText = monthLabelOf(currentMonth),
                            onPrevious = { viewModel.previousMonth() },
                            onNext = { viewModel.nextMonth() },
                            modifier = Modifier.padding(vertical = t.space2),
                        )
                    }

                    if (showEmptyCard) {
                        item(key = "empty") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                                FinanceEmptyState(
                                    title = "本月还没有设置预算",
                                    desc = "先设置总预算，再为常用分类分配额度，超支时会收到提醒",
                                    icon = Icons.Filled.Savings,
                                    actionText = "设置总预算",
                                    onAction = {
                                        view.performConfirmHaptic()
                                        editingTarget = BudgetEditorTarget(
                                            categoryName = "",
                                            title = "总预算",
                                            currentAmount = overallBudget?.budgetAmount ?: 0.0,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    item(key = "kpis") {
                        Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                            GlassCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(t.space2),
                                    ) {
                                        BudgetKpiLine(
                                            label = "总预算",
                                            value = yen(animatedAmount(totalBudget.toFloat()).toDouble(), 0),
                                            color = t.fg,
                                            onClick = {
                                                view.performConfirmHaptic()
                                                editingTarget = BudgetEditorTarget(
                                                    categoryName = "",
                                                    title = "总预算",
                                                    currentAmount = overallBudget?.budgetAmount ?: 0.0,
                                                )
                                            },
                                        )
                                        BudgetKpiLine(
                                            label = "已支出",
                                            value = yen(animatedAmount(spentTotal.toFloat()).toDouble(), 0),
                                            color = t.expenseText,
                                        )
                                        BudgetKpiLine(
                                            label = "剩余",
                                            value = yen(animatedAmount(remaining.toFloat()).toDouble(), 0),
                                            color = if (remaining < 0) t.expenseText else t.fg,
                                        )
                                    }
                                    Spacer(Modifier.width(t.space3))
                                    FinanceRingGauge(
                                        fraction = fraction,
                                        big = ((fraction * 100).toInt()).toString() + "%",
                                        small = "已用",
                                        // B1：超支只改文本色，环保持强调色
                                        color = t.accent,
                                    )
                                }
                            }
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
                                "分类预算",
                                style = MaterialTheme.typography.headlineSmall,
                                color = t.fg,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "点任意一行可修改",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                            )
                        }
                    }

                    item(key = "rows") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            AppCard(padding = t.space1) {
                                rows.forEachIndexed { index, category ->
                                    val budget = budgetByCategory[category.name]
                                    BudgetRow(
                                        title = category.name,
                                        budgetAmount = budget?.budgetAmount ?: 0.0,
                                        spentAmount = budget?.spentAmount ?: 0.0,
                                        enterIndex = index,
                                        onClick = {
                                            view.performConfirmHaptic()
                                            editingTarget = BudgetEditorTarget(
                                                categoryName = category.name,
                                                title = category.name,
                                                currentAmount = budget?.budgetAmount ?: 0.0,
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(t.space3))
                            Text(
                                "分类预算合计等于总预算；超出后进度条转为深色并提示超支。",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                            )
                            Spacer(Modifier.height(t.space1))
                            Text(
                                // 原型 .s06-kpis 卡内只有 3 行 KPI + 环（无文本节点），说明统一挂到列表下方；
                                // App 的总预算行可点开编辑弹窗，故保留这句操作提示（原型无此句）。
                                "点「总预算」可修改总额，点任意分类行可分配额度。",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                            )
                        }
                    }

                    item(key = "bottom") { Spacer(Modifier.height(t.space12)) }
                }
            }
        }

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )
    }

    editingTarget?.let { target ->
        FinanceInputDialog(
            title = if (target.categoryName.isEmpty()) "设置总预算" else "修改预算 · " + target.title,
            initialValue = if (target.currentAmount > 0) formatAmount(target.currentAmount) else "",
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            errorMessage = "请输入不小于 0 的金额",
            isValid = { text -> text.isBlank() || (text.toDoubleOrNull()?.let { it >= 0 } == true) },
            onConfirm = { text ->
                val amount = text.toDoubleOrNull() ?: 0.0
                if (target.categoryName.isEmpty()) {
                    // 总预算：走 FinanceViewModel.setBudget（原先零调用的死函数），
                    // 不能用 setCategoryBudget——它会把总预算覆写为「分类预算之和」= 0
                    viewModel.setBudget("", amount)
                } else {
                    viewModel.setCategoryBudget(target.categoryName, amount)
                }
                view.performConfirmHaptic()
                editingTarget = null
            },
            onDismiss = { editingTarget = null },
        )
    }
}

/** 预算编辑目标（总预算的 categoryName 为空串） */
private data class BudgetEditorTarget(
    val categoryName: String,
    val title: String,
    val currentAmount: Double,
)

/** 展示用行：有预算的分类优先，不足 6 行时用其余支出分类补齐至 6 行。 */
private fun budgetRowsFor(
    expenseCategories: List<Category>,
    budgetByCategory: Map<String, Budget>,
): List<Category> {
    val withBudget = expenseCategories.filter { budgetByCategory.containsKey(it.name) }
    val others = expenseCategories.filterNot { budgetByCategory.containsKey(it.name) }
    return if (withBudget.size >= ROW_TARGET) withBudget else (withBudget + others).take(ROW_TARGET)
}

@Composable
private fun BudgetKpiLine(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                onClick?.let { action ->
                    Modifier.clip(RoundedCornerShape(t.radiusMd)).clickable { action() }
                } ?: Modifier,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** 单行预算：名称 + 预算额 + 进度条 + 已支出/剩余。 */
@Composable
private fun BudgetRow(
    title: String,
    budgetAmount: Double,
    spentAmount: Double,
    enterIndex: Int,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val hasBudget = budgetAmount > 0
    val progress = if (hasBudget) (spentAmount / budgetAmount).toFloat() else 0f
    val isOverBudget = hasBudget && spentAmount > budgetAmount
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = t.tweenOf(MotionToken.State),
        label = "budgetBar",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(rememberStaggerEnter(enterIndex))
            .clip(RoundedCornerShape(t.radiusMd))
            .clickable { onClick() }
            .padding(horizontal = t.space3, vertical = t.space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(t.space2))
            Text(
                if (hasBudget) yen(budgetAmount, 0) else "未设置",
                // 原型 .s06-amt = 17px/600 + 正文色（--fg 系）；「未设置」保留 muted 的弱化语义
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (hasBudget) t.fg else t.muted,
            )
        }
        Spacer(Modifier.height(t.space2))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(t.space2)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(t.borderSoft),
        ) {
            if (hasBudget) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(t.radiusPill))
                        // 原型 s06:27-28：常态 chart-3，超支 chart-1
                        .background(if (isOverBudget) t.chart1 else t.chart3),
                )
            }
        }
        Spacer(Modifier.height(t.space2))
        Text(
            if (!hasBudget) {
                "未设置预算"
            } else if (isOverBudget) {
                "已支出 " + yen(spentAmount, 0) + " · 超支 " + yen(spentAmount - budgetAmount, 0)
            } else {
                "已支出 " + yen(spentAmount, 0) + " · 剩余 " + yen(budgetAmount - spentAmount, 0)
            },
            // 原型 .s06-brow .row-sub = 14px（--text-sm）；12sp 的 labelSmall 比原型小一号
            style = MaterialTheme.typography.bodySmall,
            color = if (isOverBudget) t.expenseText else t.meta,
        )
    }
}

/** 金额格式化（两位小数，与其余记账页面一致）。 */
private fun formatAmount(value: Double): String =
    String.format(java.util.Locale.CHINA, "%.2f", value)

/** 交易类型：支出 */
private const val TYPE_EXPENSE = 0

/** 原型固定 6 条分类进度条 */
private const val ROW_TARGET = 6
