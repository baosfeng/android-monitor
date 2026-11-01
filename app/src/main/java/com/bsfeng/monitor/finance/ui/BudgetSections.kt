package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/BudgetScreen.kt 的 KPI 卡与明细行块；职责：总预算/已支出/剩余 + 圆环、分类预算列表、边界数据区与操作提示。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 预算 KPI 卡 + 使用率圆环（锚点 s06-card / s06-ring）。
 *
 * 只有「总预算」这一行可点（s06-card · click / 键盘）；「已支出」「剩余」不可点；
 * 圆环不吞点击、不可聚焦，读屏整体朗读「总预算使用率 N 百分比」。
 */
@Composable
internal fun BudgetKpiSection(
    totalBudget: Double,
    spentTotal: Double,
    remaining: Double,
    fraction: Float,
    onEditTotal: () -> Unit,
    modifier: Modifier = Modifier,
    totalFocusRequester: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    val pct = fraction.coerceIn(0f, 9.99f).times(100).toInt()
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
                        modifier = if (totalFocusRequester != null) {
                            Modifier.focusRequester(totalFocusRequester)
                        } else {
                            Modifier
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
                    big = pct.toString() + "%",
                    small = "已用",
                    // B1：超支只改文本色，环保持强调色（锚点 s06-ring · 边界）
                    color = t.accent,
                    modifier = Modifier
                        .testTag("s06-ring")
                        .semantics { contentDescription = "总预算使用率 " + pct + " 百分比" },
                )
            }
        }
    }
}

/**
 * 分类预算明细块（锚点 s06-list：标题「分类预算」+ 6 行 + 两条说明）。
 *
 * 每行可点开编辑；行入场 300ms 淡入上移、行间错峰 24ms，且**滚回时不重播**（[seenRows] 记账）。
 * [refocusIndex] 用于弹窗关闭后把焦点送回触发它的那一行（移除后五问 · 焦点）。
 */
@Composable
internal fun BudgetCategoryRowsSection(
    rows: List<Category>,
    budgetByCategory: Map<String, Budget>,
    onEditCategory: (Category, Budget?) -> Unit,
    modifier: Modifier = Modifier,
    seenRows: MutableSet<Int> = remember { mutableSetOf() },
    refocusIndex: Int? = null,
    onRefocused: () -> Unit = {},
) {
    val t = LocalDesignTokens.current
    // 焦点落点按**实际行数**分配，不按 ROW_TARGET 定长：有预算的分类 ≥ ROW_TARGET 时
    // budgetRowsFor 不截断（支出一级分类默认 18 个），行数可以是 7、8…
    // 此前 List(ROW_TARGET) + requesters[index] 让第 7 行在**组合期**直接抛
    // IndexOutOfBoundsException 崩页。行数变了就按新行数重建。
    val requesters = remember(rows.size) { List(focusSlotCountFor(rows.size)) { FocusRequester() } }
    LaunchedEffect(refocusIndex) {
        val index = refocusIndex ?: return@LaunchedEffect
        requesters.getOrNull(index)?.requestFocus()
        onRefocused()
    }
    Column(modifier.padding(horizontal = t.space4)) {
        BudgetListHead()
        AppCard(padding = t.space1) {
            rows.forEachIndexed { index, category ->
                val budget = budgetByCategory[category.name]
                val enterIndex = if (index in seenRows) -1 else index
                SideEffect { seenRows.add(index) }
                BudgetRow(
                    title = category.name,
                    budgetAmount = budget?.budgetAmount ?: 0.0,
                    spentAmount = budget?.spentAmount ?: 0.0,
                    enterIndex = enterIndex,
                    onClick = { onEditCategory(category, budget) },
                    modifier = budgetRowAnchor(index),
                    // 双保险：即使将来有人把 requesters 换成定长列表，越界也只是「不回位」而不是崩页
                    focusRequester = requesterSlotFor(index, requesters.size)?.let { requesters[it] },
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
            .padding(vertical = t.space2),
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

/** 分类行锚点（锚点 s06-brow-0…5，逐字对应原型 data-od-id）。 */
private fun budgetRowAnchor(index: Int): Modifier = when (index) {
    0 -> Modifier.testTag("s06-brow-0")
    1 -> Modifier.testTag("s06-brow-1")
    2 -> Modifier.testTag("s06-brow-2")
    3 -> Modifier.testTag("s06-brow-3")
    4 -> Modifier.testTag("s06-brow-4")
    5 -> Modifier.testTag("s06-brow-5")
    else -> Modifier.testTag("s06-brow-" + index)
}

/** 边界态占位数值（锚点 s06-edge 写死；不读库，避免把真实账目写进代码）。 */
internal const val EDGE_TOTAL_BUDGET = 999_999.0
internal const val EDGE_SPENT = 1_280_000.0
internal const val EDGE_ROW_BUDGET = 200.0
internal const val EDGE_ROW_SPENT = 256.0
internal const val EDGE_ROW_NAME = "人情往来 · 红白喜事与节日红包"

/**
 * 边界数据区（锚点 s06-edge）：超支 128% 的总预算卡 + 超长分类名分组标题。
 * 金额单行右对齐、超支副行转红、环满格仍为强调色（正常卡的环不参与边界区）。
 */
@Composable
internal fun BudgetEdgeSection(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(horizontal = t.space4)) {
        GlassCard {
            BudgetKpiLine(
                label = "总预算（超支 128%）",
                value = yen(EDGE_TOTAL_BUDGET, 0),
                color = t.expenseText,
            )
            Spacer(Modifier.height(t.space2))
            // 超支进度条满格转深色（原型 s06:36 .s06-brow.is-over .s06-bar i）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.space2)
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(t.chart1),
            )
            Spacer(Modifier.height(t.space2))
            Text(
                "已支出 " + yen(EDGE_SPENT, 0) + " · 超支 " + yen(EDGE_SPENT - EDGE_TOTAL_BUDGET, 0),
                style = MaterialTheme.typography.bodySmall,
                color = t.expenseText,
            )
        }
        Spacer(Modifier.height(t.space3))
        Text("超长分类名", style = MaterialTheme.typography.labelSmall, color = t.meta)
    }
}
