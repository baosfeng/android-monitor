package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/BudgetScreen.kt 的 KPI 卡与明细行块；职责：总预算/已支出/剩余 + 圆环、分类预算列表与操作提示。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 预算 KPI 卡 + 使用率圆环。 */
@Composable
internal fun BudgetKpiSection(
    totalBudget: Double,
    spentTotal: Double,
    remaining: Double,
    fraction: Float,
    onEditTotal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4, vertical = t.space2)) {
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
                        onClick = onEditTotal,
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

/** 分类预算明细块（每行可点开编辑）+ 两条说明。 */
@Composable
internal fun BudgetCategoryRowsSection(
    rows: List<Category>,
    budgetByCategory: Map<String, Budget>,
    onEditCategory: (Category, Budget?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        AppCard(padding = t.space1) {
            rows.forEachIndexed { index, category ->
                val budget = budgetByCategory[category.name]
                BudgetRow(
                    title = category.name,
                    budgetAmount = budget?.budgetAmount ?: 0.0,
                    spentAmount = budget?.spentAmount ?: 0.0,
                    enterIndex = index,
                    onClick = { onEditCategory(category, budget) },
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

/** 分类预算小标题行。 */
@Composable
internal fun BudgetListHead(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
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
