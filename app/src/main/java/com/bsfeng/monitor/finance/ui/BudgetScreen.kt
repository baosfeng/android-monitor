package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/BudgetScreen.kt；职责：预算设置屏幕装配（总预算/分类预算状态、空态与编辑弹窗）。

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    // 触觉统一走 foundation/Haptics.kt 的 rememberHaptic（三重闸门 + 强度档换算）
    val haptic = rememberHaptic()
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
                                        haptic(HapticTone.LIGHT)
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
                        BudgetKpiSection(
                            totalBudget = totalBudget,
                            spentTotal = spentTotal,
                            remaining = remaining,
                            fraction = fraction,
                            onEditTotal = {
                                haptic(HapticTone.LIGHT)
                                editingTarget = BudgetEditorTarget(
                                    categoryName = "",
                                    title = "总预算",
                                    currentAmount = overallBudget?.budgetAmount ?: 0.0,
                                )
                            },
                        )
                    }

                    item(key = "listhead") {
                        BudgetListHead()
                    }

                    item(key = "rows") {
                        BudgetCategoryRowsSection(
                            rows = rows,
                            budgetByCategory = budgetByCategory,
                            onEditCategory = { category, budget ->
                                haptic(HapticTone.LIGHT)
                                editingTarget = BudgetEditorTarget(
                                    categoryName = category.name,
                                    title = category.name,
                                    currentAmount = budget?.budgetAmount ?: 0.0,
                                )
                            },
                        )
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
                haptic(HapticTone.SUCCESS)
                editingTarget = null
            },
            onDismiss = { editingTarget = null },
        )
    }
}

