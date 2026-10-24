package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt；职责：统计分析屏幕装配（月份/KPI/图表卡与空态）。

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.RingChart
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.flow.MutableStateFlow
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * s07 统计分析。
 *
 * 收支卡 + 分类支出扇形图与图例 + 近 7 天分组柱 + 24 小时消费热力；图表全部走
 * [com.bsfeng.monitor.ui.components] 的 Canvas 基元（占比用 [RingChart]，分组柱由屏内组合）。
 */
@Composable
fun FinanceStatsScreen(
    currentMonth: String,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FinanceViewModel? = null,
    /** 空态 CTA「新增一笔」的目标（原型 s07-empty-add）；未传时空态只显示文案。 */
    onAddClick: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val vm = viewModel ?: rememberFinanceViewModel()
    val emptyFlow = remember { MutableStateFlow<List<Transaction>>(emptyList()) }
    val transactions by (vm?.monthlyTransactions ?: emptyFlow).collectAsState()
    val loading = vm?.loading?.collectAsState()?.value == true

    val control = rememberFinanceStateControl()
    val monthLabel = monthLabelOf(currentMonth)
    val sortedEntries = remember(categoryBreakdown) {
        categoryBreakdown.entries.sortedByDescending { it.value }.take(CATEGORY_LIMIT)
    }
    val week = remember(transactions, currentMonth) { last7DayTotals(transactions, currentMonth) }
    val hourly = remember(transactions) { hourlyCounts(transactions) }
    val peak = remember(hourly) { hourlyPeaks(hourly) }
    val weekExpense = remember(week) { week.sumOf { it.first.toDouble() } }
    val weekIncome = remember(week) { week.sumOf { it.second.toDouble() } }

    val derivedState = when {
        loading && transactions.isEmpty() && categoryBreakdown.isEmpty() -> FiveState.LOADING
        categoryBreakdown.isEmpty() && transactions.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    // EMPTY 只作为演示态：内容仍渲染，空态卡画在列表首位（原型 s07-empty 带「新增一笔」）
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
                    // 原型 section[data-screen="s07"] data-title="统计分析"（08-prototype.html:4044）
                    "统计分析",
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
                emptyText = "记一笔账或从熊猫记账导入历史数据后，这里会生成分类占比与趋势",
                errorText = "聚合查询超时，图表数据不完整",
                edgeText = "边界态：分类 120 个 / 单月 ¥12,345,678.90 / 缺失分类字段",
                onRetry = { vm?.loadCurrentMonth() },
            ) {
                // 底部避让：为底部 Tab 条留出高度（fab-size 56 + 32），
                // 滚到底时「近 7 天收支」的 x 轴日期与数值刻度不再被 Tab 条裁掉。
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = t.fabSize + t.space8),
                ) {
                    if (state == FiveState.EMPTY) {
                        item(key = "empty") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                                FinanceEmptyState(
                                    title = "本月还没有可统计的支出",
                                    desc = "记一笔账或从熊猫记账导入历史数据后，这里会生成分类占比与趋势",
                                    icon = Icons.Filled.BarChart,
                                    actionText = if (onAddClick != null) "新增一笔" else null,
                                    onAction = onAddClick,
                                )
                            }
                        }
                    }

                    item(key = "month") {
                        // 月份条自带 space4 水平边距（三屏统一 361dp 胶囊）：这里不能再包一层
                        // horizontal padding，否则统计页的胶囊比首页 / 预算页窄两个 16dp。
                        Column {
                            FinanceMonthBar(
                                monthText = monthLabel,
                                onPrevious = onPreviousMonth,
                                onNext = onNextMonth,
                                modifier = Modifier.padding(vertical = t.space2),
                            )
                            Text(
                                monthLabel + " · 共 " + sortedEntries.size + " 个分类",
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                                modifier = Modifier.padding(horizontal = t.space4),
                            )
                            Spacer(Modifier.height(t.space3))
                        }
                    }

                    item(key = "kpis") {
                        FinanceStatsKpiRow(stats)
                    }

                    item(key = "ring") {
                        FinanceStatsRingCard(
                            stats = stats,
                            sortedEntries = sortedEntries,
                        )
                    }

                    item(key = "week") {
                        FinanceStatsWeekCard(
                            week = week,
                            weekExpense = weekExpense,
                            weekIncome = weekIncome,
                        )
                    }

                    item(key = "heat") {
                        FinanceStatsHeatCard(
                            hourly = hourly,
                            peak = peak,
                        )
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
    }
}

