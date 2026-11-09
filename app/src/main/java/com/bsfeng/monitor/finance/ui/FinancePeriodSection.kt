package com.bsfeng.monitor.finance.ui

// 来源：原型 s04 统计汇总 pane 的时间单位查询卡（.open-design/src/screens/s04-记账.html:177-224）；
// 职责：五档 unitbar + 每档「两格 KPI + 带坐标轴图表」+ 多周期总花费卡（241-256）。
// 纯派生（档位顺序 / 图形类型 / X 轴刻度 / 直标策略 / 图注文案）见 FinancePeriodDerived.kt。
//
// 数据一律来自 FinanceManager.getPeriodStatsAll（SQL SUM + 分桶），界面不拼 SQL、也不捞列表在内存里 sum；
// 图表外壳统一走 ui/components 的 ChartFrame（Y 轴刻度 + X 轴刻度文字 + 右上单位 + 数值直标），
// 这里不再各写一套坐标轴。

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.finance.FinancePeriod
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.ui.components.ChartFrame
import com.bsfeng.monitor.ui.components.ChartFrameKind
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 五档时间单位切换条（原型 s04-unitbar）。
 *
 * 复用公共 [SegmentedControl]：键盘 ← → / Home / End 切换、选中 pill、切换 LIGHT 触觉都已内建，
 * 这里只负责「档位 ↔ 下标」映射。重复点当前档由组件内部拦掉，不会多震一次。
 */
@Composable
internal fun FinancePeriodUnitBar(
    selected: FinancePeriod,
    onSelect: (FinancePeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedControl(
        options = FINANCE_PERIOD_LABELS,
        selectedIndex = financePeriodIndexOf(selected),
        onSelect = { index -> onSelect(financePeriodAt(index)) },
        modifier = modifier.testTag("s04-unitbar"),
        optionTags = FINANCE_PERIOD_UNIT_TAGS,
    )
}

/**
 * 时间单位查询卡：档位切换条 + 两格 KPI + 一张带坐标轴的图表。
 *
 * KPI 与图表同源同一份 [stats]，所以「切档 → 两个数字与图形一起变」，
 * 不会出现数字换了、图表还是上一档的内容。
 *
 * @param stats 该档统计；null = 还在取数（KPI 显示占位符，图表保留空网格与刻度）
 * @param periodBarEnabled 「多周期查询入口」开关（[com.bsfeng.monitor.ui.foundation.FinanceUiToggle.PERIOD_BAR]）：
 *   关掉时只是收起五档切换条，KPI 与图表仍按当前档（默认本月）照常显示 —— 整卡不消失，
 *   否则用户连当月统计都看不到了。
 */
@Composable
internal fun FinancePeriodQueryCard(
    stats: PeriodStats?,
    selected: FinancePeriod,
    onSelect: (FinancePeriod) -> Unit,
    modifier: Modifier = Modifier,
    periodBarEnabled: Boolean = true,
) {
    val t = LocalDesignTokens.current
    GlassCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("时间单位查询", style = MaterialTheme.typography.headlineSmall, color = t.fg)
            // 开关关掉时右侧「日 / 周 / 月 / 年 / 全部」也一起收起：留下的清单会让人以为还能切
            if (periodBarEnabled) {
                Text("日 / 周 / 月 / 年 / 全部", style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
        }
        Spacer(Modifier.height(t.space3))
        if (periodBarEnabled) {
            FinancePeriodUnitBar(selected = selected, onSelect = onSelect)
        } else {
            // 不整卡消失：只把切换条收成一行说明，下面 KPI 与图表继续显示当前档（默认本月）
            Text(
                "多周期查询已关闭 · 去设置开启",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
        Spacer(Modifier.height(t.space3))
        Row(
            modifier = Modifier.fillMaxWidth().then(periodKpiTag(selected)),
            horizontalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            KpiTile(
                label = periodKpiLabel(selected, "支出"),
                value = if (stats == null) PLACEHOLDER_AMOUNT else yen(stats.totalExpense, 0),
                color = t.expenseText,
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                label = periodKpiLabel(selected, "收入"),
                value = if (stats == null) PLACEHOLDER_AMOUNT else yen(stats.totalIncome, 0),
                color = t.incomeText,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(t.space3))
        Box(Modifier.fillMaxWidth().then(periodPaneTag(selected))) {
        ChartFrame(
            values = periodChartValues(selected, stats),
            xLabels = periodXLabels(selected, stats),
            kind = periodChartKind(selected),
            title = periodChartTitle(selected),
            unit = periodChartUnit(selected),
            // 柱太多（月度 30 桶）时柱顶数字会叠在一起：降级为只标峰值，并把峰值写进图注。
            showValueLabels = periodShowsAllValues(selected, stats),
            modifier = periodChartTag(selected),
        )
        }
        // day 档的图注在「不逐柱直标」分支里统一给足（口径说明 + 合计 + 峰值），
        // 不再另起一行「1 格 = 1 小时」：两句话说的是同一件事，分两行反而要多读一遍。
        if (!periodShowsAllValues(selected, stats)) {
            Spacer(Modifier.height(t.space1))
            Text(
                periodValueNote(selected, stats),
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
        // 空态说明：没有这行时全 0 的图与「图表坏了」长得一样
        if (stats != null && !stats.hasData) {
            Spacer(Modifier.height(t.space2))
            Text(
                selected.label + "还没有交易记录",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
    }
}

/** 取数未完成时的占位金额（不用 ¥0.00：那会被读成「花了 0 元」）。 */
internal const val PLACEHOLDER_AMOUNT: String = "—"

/**
 * 多周期总花费卡（原型 s04 241-256）：今日 / 本周 / 本周三格 + 本年 / 全部两格，支出口径。
 *
 * 五个金额来自一次 getPeriodStatsAll 的同一份结果，跨档之间天然同口径；
 * 某档缺失时显示「—」而不是 ¥0，避免把「没算出来」说成「没花钱」。
 */
@Composable
internal fun FinanceMultiPeriodCard(
    allStats: List<PeriodStats>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    GlassCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("多周期总花费", style = MaterialTheme.typography.headlineSmall, color = t.fg)
            Text("支出口径", style = MaterialTheme.typography.labelSmall, color = t.meta)
        }
        Spacer(Modifier.height(t.space3))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            FINANCE_PERIOD_ORDER.subList(0, 3).forEach { period ->
                KpiTile(
                    label = period.label,
                    value = periodExpenseText(allStats, period),
                    color = t.expenseText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(t.space2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            FINANCE_PERIOD_ORDER.subList(3, FINANCE_PERIOD_ORDER.size).forEach { period ->
                KpiTile(
                    label = period.label,
                    value = periodExpenseText(allStats, period),
                    color = t.expenseText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(t.space3))
        // 审计 P2-6：原型卡底有两段说明（支持哪几档 + 三值口径），实现只剩右上「支出口径」四字。
        // 压成一行（不换行、不省略）：说明是读一次就够的背景信息，占两行会把五格金额挤到屏幕外。
        Text(
            "按日 / 周 / 月 / 年 / 全部统计，收入与结余同口径",
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
            maxLines = 1,
        )
    }
}

/** 某档的支出文案：该档还没算出来时给占位符（不写 ¥0，免得读成「本档没花钱」）。 */
internal fun periodExpenseText(allStats: List<PeriodStats>, period: FinancePeriod): String {
    val stats = allStats.firstOrNull { it.unitKey == period.key } ?: return PLACEHOLDER_AMOUNT
    return yen(stats.totalExpense, 0)
}
/**
 * 统计汇总 pane 的列表项（LazyListScope 扩展）：查询卡、多周期总花费、本月 KPI、双入口、三图卡。
 *
 * 抽成扩展而不是继续堆在 [FinanceMainScreen] 里：明细 pane 与统计 pane 共用同一个 LazyColumn，
 * 「统计 pane 有哪些项」集中在一处，改动时不会漏掉项下标（删除后焦点复位依赖头部项数）。
 */
internal fun LazyListScope.statsPane(
    viewModel: FinanceViewModel,
    allPeriodStats: List<PeriodStats>,
    activePeriodStats: PeriodStats?,
    selectedPeriod: FinancePeriod,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    overallBudget: Budget?,
    onPeriodSelect: (FinancePeriod) -> Unit,
    onStatsClick: () -> Unit,
    onBudgetClick: () -> Unit,
    periodBarEnabled: Boolean = true,
    budgetEnabled: Boolean = true,
    kpiToday: Pair<Double, Double> = 0.0 to 0.0,
    currentMonth: String = "",
    transactionCount: Int = 0,
) {
    item(key = "periodcard") {
        // 统计汇总 pane 的容器锚点（s04-pane-stats）
        FinancePeriodQueryCard(
            stats = activePeriodStats,
            selected = selectedPeriod,
            onSelect = onPeriodSelect,
            modifier = Modifier.testTag("s04-pane-stats"),
            periodBarEnabled = periodBarEnabled,
        )
    }
    // 多周期总花费卡是 unitbar 的同一件事（一次把五档支出全列出来）：开关关掉后连这张卡一起收起，
    // 否则会出现「档位切不了、但五个档的金额照常列着」的半开半关状态。
    if (periodBarEnabled) {
        item(key = "multiperiod") {
            FinanceMultiPeriodCard(allStats = allPeriodStats)
        }
    }
    // 审计 P2-3：原型 s04-kpis（本月支出 / 收入 / 结余 + 预算环，s04:227-240）落在 stats pane 区间内
    // （177–289），实现原本只在交易明细 pane 渲染 —— 切到「统计汇总」就看不到本月三值。
    // 这里补一份，位置与原型一致：多周期总花费之后、双入口之前。
    //
    // ⚠️ anchorTagged = false：FinanceMainKpiSection 内部自带 s04-kpis / s04-ring，锚点账本已冻结，
    // 同名 testTag 在两个 pane 同时渲染会重复；KPI 卡的元素都不可点（纯文本展示），
    // 按无障碍基线「纯文本展示不写 testTag」的口径，这一份不挂锚点也能被读屏读全。
    item(key = "statskpis") {
        FinanceMainKpiSection(
            stats = stats,
            today = kpiToday,
            currentMonth = currentMonth,
            transactionCount = transactionCount,
            budgetEnabled = budgetEnabled,
            anchorTagged = false,
        )
    }
    item(key = "entries") {
        FinanceMainEntrySection(
            overallBudget = overallBudget,
            onStatsClick = onStatsClick,
            onBudgetClick = onBudgetClick,
        )
    }
    item(key = "charts") {
        FinanceStatsChartsSection(
            weekStats = viewModel.periodStatsOf(allPeriodStats, FinancePeriod.WEEK),
            yearStats = viewModel.periodStatsOf(allPeriodStats, FinancePeriod.YEAR),
            stats = stats,
            categoryBreakdown = categoryBreakdown,
        )
    }
}
