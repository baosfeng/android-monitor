package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/BudgetScreen.kt 的行组件与行选择；职责：KPI 文本行、单行预算进度、展示行的取行规则与展示常量。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.components.tweenOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 交易类型：支出 */
internal const val TYPE_EXPENSE = 0

/** 原型固定 6 条分类进度条 */
internal const val ROW_TARGET = 6

/** 展示用行：有预算的分类优先，不足 6 行时用其余支出分类补齐至 6 行。 */
internal fun budgetRowsFor(
    expenseCategories: List<Category>,
    budgetByCategory: Map<String, Budget>,
): List<Category> {
    val withBudget = expenseCategories.filter { budgetByCategory.containsKey(it.name) }
    val others = expenseCategories.filterNot { budgetByCategory.containsKey(it.name) }
    return if (withBudget.size >= ROW_TARGET) withBudget else (withBudget + others).take(ROW_TARGET)
}

/** 金额格式化（两位小数，与其余记账页面一致）。 */
internal fun formatAmount(value: Double): String =
    String.format(java.util.Locale.CHINA, "%.2f", value)

/** 预算 KPI 行（可点击的行会打开编辑弹窗）。 */
@Composable
internal fun BudgetKpiLine(
    label: String,
    value: String,
    color: Color,
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
internal fun BudgetRow(
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
            .staggerEnterAt(enterIndex)
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
