package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的三段列表项；职责：KPI 卡、双入口卡、明细标题行。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * KPI 卡 + 今日/本月一行摘要。
 *
 * 金额走 [animatedAmount] 做滚动数字；预算进度用 accent（与 BudgetScreen 同一口径）。
 */
@Composable
internal fun FinanceMainKpiSection(
    stats: FinanceStats,
    today: Pair<Double, Double>,
    currentMonth: String,
    transactionCount: Int,
) {
    val t = LocalDesignTokens.current
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
                "共 " + transactionCount + " 笔 · 预算使用 " +
                    (stats.budgetProgress * 100).toInt() + "%"
            },
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}

/** 双入口卡：统计分析 / 预算设置（点击带轻触觉）。 */
@Composable
internal fun FinanceMainEntrySection(
    overallBudget: Budget?,
    onStatsClick: () -> Unit,
    onBudgetClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
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

/** 明细标题行：左「交易明细」+ 右「最近 N 天 · X 笔 / 共 X 笔」。 */
@Composable
internal fun FinanceMainListHead(
    currentMonth: String,
    groupedIsEmpty: Boolean,
    recentCount: Int,
    transactionCount: Int,
) {
    val t = LocalDesignTokens.current
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
            if (groupedIsEmpty) {
                "暂无记录"
            } else if (isCurrentMonth(currentMonth)) {
                "最近 3 天 · " + recentCount + " 笔"
            } else {
                "共 " + transactionCount + " 笔"
            },
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}
