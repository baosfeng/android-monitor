package com.bsfeng.monitor.finance.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.FinanceColors
import com.bsfeng.monitor.ui.theme.IOSColors
import com.bsfeng.monitor.ui.theme.IOSDivider
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.InsetGroupedSection
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.Radius
import java.util.Locale

/**
 * 预算设置页面 — iOS 分组卡片风格。
 *
 * 支持设置「本月总预算」与「分类预算」；已支出金额由
 * [com.bsfeng.monitor.finance.FinanceManager.getMonthlyBudget] 按当月实际支出回填。
 */
@Composable
fun BudgetScreen(
    viewModel: FinanceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentMonth by viewModel.currentMonth.collectAsState()
    val budgets by viewModel.budgets.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val stats by viewModel.monthlyStats.collectAsState()
    val view = LocalView.current

    var editingTarget by remember { mutableStateOf<BudgetEditorTarget?>(null) }

    val overallBudget = remember(budgets) { budgets.find { it.isOverall } }
    val budgetByCategory = remember(budgets) { budgets.associateBy { it.categoryName } }
    val expenseCategories = remember(categories) { categories.filter { it.type == TYPE_EXPENSE } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        IOSNavigationBar(title = "预算设置", onBack = onBack)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "month") {
                Text(
                    viewModel.getMonthDisplayName(currentMonth),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MonitorSpacing.xxl,
                        vertical = MonitorSpacing.md,
                    ),
                )
            }

            item(key = "overall") {
                InsetGroupedSection(
                    header = "本月总预算",
                    footer = "总预算决定首页预算进度条；点击可修改",
                ) {
                    BudgetRow(
                        title = "总预算",
                        budgetAmount = overallBudget?.budgetAmount ?: 0.0,
                        spentAmount = stats.totalExpense,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            editingTarget = BudgetEditorTarget(
                                categoryName = "",
                                title = "总预算",
                                currentAmount = overallBudget?.budgetAmount ?: 0.0,
                            )
                        },
                    )
                }
            }

            item(key = "category_header") {
                Text(
                    "分类预算",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = MonitorSpacing.xxl,
                        top = MonitorSpacing.xxl,
                        bottom = MonitorSpacing.sm,
                    ),
                )
            }

            item(key = "category_list") {
                InsetGroupedCard {
                    expenseCategories.forEachIndexed { index, category ->
                        val budget = budgetByCategory[category.name]
                        BudgetRow(
                            title = category.name,
                            budgetAmount = budget?.budgetAmount ?: 0.0,
                            spentAmount = budget?.spentAmount ?: 0.0,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                editingTarget = BudgetEditorTarget(
                                    categoryName = category.name,
                                    title = category.name,
                                    currentAmount = budget?.budgetAmount ?: 0.0,
                                )
                            },
                        )
                        if (index != expenseCategories.lastIndex) IOSDivider()
                    }
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(MonitorSpacing.xxxl)) }
        }
    }

    editingTarget?.let { target ->
        FinanceInputDialog(
            title = "设置预算 · ${target.title}",
            initialValue = if (target.currentAmount > 0) formatAmount(target.currentAmount) else "",
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            errorMessage = "请输入不小于 0 的金额",
            isValid = { text -> text.isBlank() || (text.toDoubleOrNull()?.let { it >= 0 } == true) },
            onConfirm = { text ->
                viewModel.setBudget(target.categoryName, text.toDoubleOrNull() ?: 0.0)
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

/** 单行预算：预算额 + 已支出 + 进度条 */
@Composable
private fun BudgetRow(
    title: String,
    budgetAmount: Double,
    spentAmount: Double,
    onClick: () -> Unit,
) {
    val hasBudget = budgetAmount > 0
    val progress = if (hasBudget) (spentAmount / budgetAmount).toFloat().coerceIn(0f, 1f) else 0f
    val isOverBudget = hasBudget && spentAmount > budgetAmount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (hasBudget) "¥${formatAmount(budgetAmount)}" else "未设置",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (hasBudget) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasBudget) {
                    Text(
                        if (isOverBudget) "已用 ¥${formatAmount(spentAmount)} · 超支"
                        else "已用 ¥${formatAmount(spentAmount)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isOverBudget) FinanceColors.expense
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (hasBudget) {
            Spacer(Modifier.height(MonitorSpacing.xs))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(Radius.xxs)),
                color = if (isOverBudget) FinanceColors.expense else FinanceColors.budget,
                trackColor = IOSColors.lightGray,
            )
        }
    }
}

/** 金额格式化（两位小数 + 千分位不加分隔，与其余记账页面保持一致） */
private fun formatAmount(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

/** 交易类型：支出 */
private const val TYPE_EXPENSE = 0
