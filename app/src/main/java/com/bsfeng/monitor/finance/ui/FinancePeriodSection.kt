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
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoValue

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
            modifier = Modifier.fillMaxWidth().periodKpiTag(selected),
            horizontalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            // 档位两格走 .s04-amt 口径：标签 14px（.kpi-label，20-base:275）、数值 17px 等宽
            // （--text-base，10-tokens:105；审计 #18）。s07 三格不传，仍是 21sp 的 .s07-kv。
            KpiTile(
                label = periodKpiLabel(selected, "支出"),
                value = if (stats == null) PLACEHOLDER_AMOUNT else yen(stats.totalExpense, 0),
                color = t.expenseText,
                modifier = Modifier.weight(1f),
                labelStyle = MaterialTheme.typography.bodySmall,
                valueStyle = monitorMonoValue,
            )
            KpiTile(
                label = periodKpiLabel(selected, "收入"),
                value = if (stats == null) PLACEHOLDER_AMOUNT else yen(stats.totalIncome, 0),
                color = t.incomeText,
                modifier = Modifier.weight(1f),
                labelStyle = MaterialTheme.typography.bodySmall,
                valueStyle = monitorMonoValue,
            )
        }
        Spacer(Modifier.height(t.space3))
        Box(Modifier.fillMaxWidth().periodPaneTag(selected)) {
        ChartFrame(
            values = periodChartValues(selected, stats),
            xLabels = periodXLabels(selected, stats),
            kind = periodChartKind(selected),
            title = periodChartTitle(selected),
            unit = periodChartUnit(selected),
            // 直标口径按原型 data-chart-vlabels：热力不标、其余只标峰值（柱图只标最大那根）
            valueLabels = periodValueLabels(selected),
            // X 轴抽稀按原型：年档跳奇数（1/3/5/7/9/11）、月档每 5 根（1/6/11/16/21/26/30）
            xTickStep = periodXTickStep(selected),
            // 全部档是 4 位年份（2018..2026），标签短 → 放宽刻度数，避免忽 1 忽 2 的抽稀
            xLabelMax = periodXLabelMax(selected),
            modifier = Modifier.periodChartTag(selected),
        )
        }
        // day 档的图注口径说明（含合计与峰值）+ 月 / 年 / 全部档的峰值补注：只在读不全时补，
        // 周档 7 根柱顶已直标峰值，再来一行图注是重复信息（原型 s04:201 也没有这行）。
        if (periodShowsValueNote(selected)) {
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
                    labelStyle = MaterialTheme.typography.bodySmall,
                    valueStyle = monitorMonoValue,
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
                    labelStyle = MaterialTheme.typography.bodySmall,
                    valueStyle = monitorMonoValue,
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
 * 统计汇总 pane 的段落顺序（原型 s04:177-289 的 DOM 顺序）。
 *
 * 顺序是原型口径的一部分：查询卡 → 本月 KPI（含预算环 + 今日摘要）→ 多周期总花费 → 双入口 → 三图卡。
 * 收成常量后 [statsPane] 按它逐段派发，顺序只有一处定义，也才能被单测钉住。
 */
internal val FINANCE_STATS_PANE_SECTIONS: List<String> =
    listOf("periodcard", "statskpis", "multiperiod", "entries", "charts")

/**
 * 统计 pane 里受五态门控的三块：KPI 三值卡（s04:227）、多周期总花费（s04:242）、双入口（s04:259）
 * 原型都带 data-state-hide="loading empty error edge" —— 只在「有数据」态渲染。
 *
 * 查询卡（s04:177）与三图卡（s04:274-288）原型没有 state-hide，任何态都照常出现：
 * 空态下它们给的是「本档全是 0」的真实信息，收起来反而像整块功能坏了。
 */
internal val FINANCE_STATS_PANE_STATE_GATED: Set<String> = setOf("statskpis", "multiperiod", "entries")

/** 该段在给定五态下是否渲染（纯函数，可 JVM 单测）。 */
internal fun financeStatsSectionVisible(section: String, state: FiveState): Boolean =
    section !in FINANCE_STATS_PANE_STATE_GATED || state == FiveState.POPULATED

/**
 * 统计汇总 pane 的列表项（LazyListScope 扩展），按 [FINANCE_STATS_PANE_SECTIONS] 的顺序逐段派发。
 *
 * 抽成扩展而不是继续堆在 [FinanceMainScreen] 里：明细 pane 与统计 pane 共用同一个 LazyColumn，
 * 「统计 pane 有哪些项、以什么顺序出现」集中在一处，改动时不会漏掉项下标。
 *
 * 五态联动（原型 s04:227/242/259 的 data-state-hide）：[state] 不是 POPULATED 时
 * statskpis / multiperiod / entries 三块整段不产生 item，顺序常量与其余段落保持原样。
 */
internal fun LazyListScope.statsPane(
    viewModel: FinanceViewModel,
    allPeriodStats: List<PeriodStats>,
    activePeriodStats: PeriodStats?,
    lastDaysStats: PeriodStats?,
    selectedPeriod: FinancePeriod,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    overallBudget: Budget?,
    onPeriodSelect: (FinancePeriod) -> Unit,
    onStatsClick: () -> Unit,
    onBudgetClick: () -> Unit,
    /** 当前五态：KPI / 多周期 / 双入口三块只在 [FiveState.POPULATED] 渲染（原型 data-state-hide）。 */
    state: FiveState,
    periodBarEnabled: Boolean = true,
    budgetEnabled: Boolean = true,
    kpiToday: Pair<Double, Double> = 0.0 to 0.0,
    currentMonth: String = "",
    transactionCount: Int = 0,
) {
    FINANCE_STATS_PANE_SECTIONS.forEach { section ->
        // 五态门控先于 when 分派：被隐藏的段连 item key 都不产生，列表下标与顺序仍然稳定
        if (!financeStatsSectionVisible(section, state)) return@forEach
        when (section) {
            "periodcard" -> item(key = "periodcard") {
                // 统计汇总 pane 的容器锚点（s04-pane-stats）
                FinancePeriodQueryCard(
                    stats = activePeriodStats,
                    selected = selectedPeriod,
                    onSelect = onPeriodSelect,
                    modifier = Modifier.testTag("s04-pane-stats"),
                    periodBarEnabled = periodBarEnabled,
                )
            }

            // KPI 三值卡在原型里紧跟查询卡（s04:227-240），多周期总花费排在它之后（241-256）。
            "statskpis" -> item(key = "statskpis") {
                FinanceMainKpiSection(
                    stats = stats,
                    today = kpiToday,
                    currentMonth = currentMonth,
                    transactionCount = transactionCount,
                    budgetEnabled = budgetEnabled,
                )
            }

            // 多周期总花费卡是 unitbar 的同一件事（一次把五档支出全列出来）：开关关掉后连这张卡一起收起，
            // 否则会出现「档位切不了、但五个档的金额照常列着」的半开半关状态。
            "multiperiod" -> if (periodBarEnabled) {
                item(key = "multiperiod") {
                    FinanceMultiPeriodCard(allStats = allPeriodStats)
                }
            }

            "entries" -> item(key = "entries") {
                FinanceMainEntrySection(
                    overallBudget = overallBudget,
                    onStatsClick = onStatsClick,
                    onBudgetClick = onBudgetClick,
                )
            }

            "charts" -> item(key = "charts") {
                FinanceStatsChartsSection(
                    // 近 7 天卡用滚动 7 天（今天往前 6 天），不是五档里的「本周」（审计 s04 #32/#33）
                    lastDaysStats = lastDaysStats,
                    yearStats = viewModel.periodStatsOf(allPeriodStats, FinancePeriod.YEAR),
                    stats = stats,
                    categoryBreakdown = categoryBreakdown,
                )
            }
        }
    }
}
