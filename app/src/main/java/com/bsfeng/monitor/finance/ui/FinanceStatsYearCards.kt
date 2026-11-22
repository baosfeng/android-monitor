package com.bsfeng.monitor.finance.ui

// 来源：原型 s07 统计分析的三块内容 —— 时间范围卡（.open-design/src/screens/s07-统计分析.html:62-74）、
// 年度趋势双线（76-100）、年度 Top 分类（102-114）；职责：把这三张卡组装出来并统一走 AppCard / GlassCard 外壳。
// 数据一律由 ViewModel 侧的年度聚合与五档结果提供，本文件只做「怎么摆 / 怎么念」，不拼 SQL、不算金额口径
// （周均口径在 FinanceYearStats.yearWeeklyAverage，可单测）。

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.CategoryAmount
import com.bsfeng.monitor.finance.FinancePeriod
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.finance.yearBucketsOf
import com.bsfeng.monitor.finance.yearWeeklyAverage
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.DualLineChartFrame
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.time.LocalDate

/** 年度趋势整图高：原型 svg 显示高 150（327 × 156/340）。 */
private val YEAR_TREND_CHART_H = 150.dp

/** 年度趋势绘图区高：整图 150 − 单位行 18 − X 轴行 19。 */
private val YEAR_TREND_PLOT_H = YEAR_TREND_CHART_H - 37.dp

/** 图例行盒：原型 li 高 20.6（14px 正文行盒，无额外内边距）。 */
private val LEGEND_ROW_H_Y = 20.6.dp

/** 图例行距：原型 .legend gap = 8。 */
private val LEGEND_ROW_GAP_Y = 8.dp

/**
 * 五档段级锚点（s07-range-day / week / month / year / all）。
 *
 * 收成常量而不是写在调用点的 listOf 里：[SegmentedControl] 的逐段锚点要靠 optionTags 传，
 * 锚点对账要从实参回溯到字面量，写在实参里的集合字面量会断链（同 FINANCE_PERIOD_UNIT_TAGS 的口径）。
 */
/** 原型 s07 年度趋势的 X 轴刻度位置（12 个月 → 1 / 3 / 5 / 7 / 9 / 12）。 */
internal val YEAR_TREND_X_TICKS: List<Int> = listOf(0, 2, 4, 6, 8, 11)

internal val S07_RANGE_TAGS: List<String> = listOf(
    "s07-range-day",
    "s07-range-week",
    "s07-range-month",
    "s07-range-year",
    "s07-range-all",
)

/**
 * 时间范围卡（原型 62-74）：标题「时间范围」+ 右上口径 + 五档分段 + 三格支出口径 KPI。
 *
 * 三格的口径（原型 68-72）：
 * - **总花费**跟随选中档（今日 / 本周 / 本月 / 本年 / 全部）；
 * - **周均 / 年累计**固定取「浏览年」，不随档位变 —— 这一格回答的是「今年花了多少、按今年节奏一周多少」。
 *
 * 取数未完成时给占位符「—」而不是 ¥0：把「还没算出来」说成「花了 0 元」是错的信息。
 *
 * @param selectedExpense 选中档的支出合计（null = 该档还没取到）
 * @param yearExpense 浏览年的累计支出（null = 年度聚合还没取到）
 * @param averageAnchor 周均口径的锚点日（今年 = 今天 / 往年 = 年末，见 FinanceYearStats.yearAverageAnchor）
 */
@Composable
internal fun FinanceStatsRangeCard(
    selected: FinancePeriod,
    onSelect: (FinancePeriod) -> Unit,
    selectedExpense: Double?,
    yearExpense: Double?,
    averageAnchor: LocalDate,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val totalText = selectedExpense?.let { yen(it, 0) } ?: PLACEHOLDER_AMOUNT
    val yearText = yearExpense?.let { yen(it, 0) } ?: PLACEHOLDER_AMOUNT
    val weekAvgText = yearExpense?.let { yen(yearWeeklyAverage(it, averageAnchor), 0) } ?: PLACEHOLDER_AMOUNT
    val shape = RoundedCornerShape(t.radiusMd)
    val spoken = "总花费 " + totalText + "，周均 " + weekAvgText + "，年累计 " + yearText

    // 只补上边距（不加下边距）：下一张卡自己补上边距，卡间距恒为 --space-3（原型 mt-3 的 12px 节奏），
    // 不会因为两张卡各补一半而变成 24dp。
    // 锚点挂在原型 .pad.mt-3 上：宽 393（含左右 16 内边距）、盒顶 = 卡片顶（12dp 的外边距在盒外）。
    // 所以 padding(top) 必须在 testTag 之前、horizontal padding 在之后。
    Column(modifier.padding(top = t.space3).fillMaxWidth().testTag("s07-range").padding(horizontal = t.space4)) {
        GlassCard {
            StatsCardTitleRow("时间范围", "支持日 / 周 / 月 / 年 / 全部")
            Spacer(Modifier.height(t.space3))
            // 五档切换：键盘 ← → / Home / End 与切换触觉由公共 SegmentedControl 内建
            SegmentedControl(
                options = FINANCE_PERIOD_LABELS,
                selectedIndex = financePeriodIndexOf(selected),
                onSelect = { index -> onSelect(financePeriodAt(index)) },
                optionTags = S07_RANGE_TAGS,
            )
            Spacer(Modifier.height(t.space3))
            // 三格 KPI 作一个焦点停位、整体朗读（与 s07-kpicard 同一交互口径：三格不拆停位）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable()
                    .keyboardFocusRing(shape)
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
                horizontalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                KpiTile(
                    label = "总花费",
                    value = totalText,
                    color = t.expenseText,
                    modifier = Modifier.weight(1f).testTag("s07-kpis-total"),
                )
                KpiTile(
                    label = "周均",
                    value = weekAvgText,
                    color = t.expenseText,
                    modifier = Modifier.weight(1f).testTag("s07-kpis-weekavg"),
                )
                KpiTile(
                    label = "年累计",
                    value = yearText,
                    color = t.expenseText,
                    modifier = Modifier.weight(1f).testTag("s07-kpis-yeartotal"),
                )
            }
            Spacer(Modifier.height(t.space2))
            // 周均口径写明：这一格是页面上唯一「需要解释」的除法，不写读者没法自己复算。
            // 不说「今年」是因为月份条能翻到往年 —— 往年取的是该年的已过周数（整年 52/53 周）。
            Text(
                "周均 = 年累计 ÷ 该年已过去周数（含本周），支出口径",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
    }
}

/**
 * 年度趋势双线卡（原型 76-100）：同一坐标系画「支出 / 收入」12 个月，图例两行给年累计。
 *
 * 与 s04 的年度面积图分开：这里是**两条线的对比**（收入够不够覆盖支出），单序列面积表达不了。
 * 数据缺（还没取到 / 该年没有账）时补 12 个 0 桶 + 一行说明，整块不消失 —— 图表消失比空图更像故障。
 */
@Composable
internal fun FinanceStatsYearTrendCard(
    yearStats: PeriodStats?,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val buckets = yearBucketsOf(yearStats)
    val expense = buckets.map { it.expense.toFloat() }
    val income = buckets.map { it.income.toFloat() }
    val hasData = yearStats?.hasData == true
    // 还没取到时图例给占位符「—」而不是 ¥0：把「还没算出来」说成「收入 0 元」是错的信息
    val expenseTotal = yearStats?.totalExpense
    val incomeTotal = yearStats?.totalIncome

    Column(modifier.padding(top = t.space3).fillMaxWidth().testTag("s07-yeartrend").padding(horizontal = t.space4)) {
        AppCard(padding = t.space4 + 1.dp) {
            StatsCardTitleRow("年度趋势", "12 个月 · 支出 / 收入")
            Spacer(Modifier.height(t.space2))
            DualLineChartFrame(
                primary = expense,
                secondary = income,
                xLabels = buckets.map { it.label },
                // 原型 s07 手摆 1/3/5/7/9/12：通用抽稀会给 7 与 8 相邻（真机实测）
                xTickIndices = if (buckets.size == 12) YEAR_TREND_X_TICKS else null,
                primaryColor = t.expenseText,
                secondaryColor = t.incomeText,
                unit = "单位：元",
                // 原型 svg 显示高 150（viewBox 340×156 × 327/340）**含**轴标签；
                // 实现的轴标签是 canvas 外的两行（单位行 18 + X 轴行 19），绘图区要扣掉它们，
                // 整卡才与原型 267.2 同高。
                height = YEAR_TREND_PLOT_H,
                contentDescription = "年度收支趋势：" + buckets.size + " 个月，支出 " +
                    (expenseTotal?.let { yen(it, 0) } ?: PLACEHOLDER_AMOUNT) +
                    "，收入 " + (incomeTotal?.let { yen(it, 0) } ?: PLACEHOLDER_AMOUNT),
            )
            // 原型 .legend{margin:0} 覆盖 mt-3：图例紧贴图表（实测 legend 顶 = chart 底）
            if (!hasData) {
                Text(
                    "这一年还没有可统计的收支",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.meta,
                )
                Spacer(Modifier.height(t.space1))
            }
            // 图例（原型 95-98）：色点 + 系列名 + 年累计
            Column(
                Modifier.fillMaxWidth().testTag("s07-yeartrend-legend"),
                verticalArrangement = Arrangement.spacedBy(LEGEND_ROW_GAP_Y),
            ) {
                YearTrendLegendRow(color = t.expenseText, label = "支出", total = expenseTotal)
                YearTrendLegendRow(color = t.incomeText, label = "收入", total = incomeTotal)
            }
        }
    }
}

/** 一行图例：色点 + 系列名 + 「年累计 ¥…」；[total] 为 null（还没取到）时给占位符。 */
@Composable
private fun YearTrendLegendRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    total: Double?,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().height(LEGEND_ROW_H_Y),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(color)
        Spacer(Modifier.width(t.space2))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
        )
        Text(
            "年累计 " + (total?.let { yen(it, 0) } ?: PLACEHOLDER_AMOUNT),
            style = MaterialTheme.typography.bodySmall,
            color = t.meta,
        )
    }
}

/**
 * 年度 Top 分类卡（原型 102-114）：一级分类按年累计金额降序，最多 5 行，色点走 [categoryColorOf]。
 *
 * 空数据仍显示卡片与一行说明：整块消失会让用户分不清「这一年没怎么花钱」与「统计坏了」。
 */
@Composable
internal fun FinanceStatsTopCategoriesCard(
    categories: List<CategoryAmount>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(modifier.padding(top = t.space3).fillMaxWidth().testTag("s07-topcats").padding(horizontal = t.space4)) {
        AppCard(padding = t.space4 + 1.dp) {
            StatsCardTitleRow("年度 Top 分类", "累计")
            if (categories.isEmpty()) {
                Text(
                    "本年度还没有可统计的支出分类",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            } else {
                val spoken = "年度 Top 分类，共 " + categories.size + " 个：" +
                    categories.joinToString("，") { it.name + " " + yen(it.amount, 0) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { contentDescription = spoken },
                    verticalArrangement = Arrangement.spacedBy(LEGEND_ROW_GAP_Y),
                ) {
                    categories.forEach { item ->
                        TopCategoryRow(item)
                    }
                }
            }
            Spacer(Modifier.height(t.space3))
            // 原型 s07:112 的卡底注：分类行在下钻版本里可点，当前只做展示（原型示意）。
            // 空数据也照样给：说明的是「这一块能做什么」，与有没有分类无关。
            Text(
                "点击分类可下钻到交易明细（原型示意）。",
                // 原型卡底注是 .meta（14px），不是 12sp
                style = MaterialTheme.typography.bodySmall,
                color = t.meta,
            )
        }
    }
}

/** 一行 Top 分类：色点（分类身份色）+ 分类名（超长省略）+ 金额。 */
@Composable
private fun TopCategoryRow(item: CategoryAmount) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().height(LEGEND_ROW_H_Y),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 色点与分类环 / 交易行同源（categoryColorOf），同一分类在三处同色
        LegendDot(categoryColorOf(item.name))
        Spacer(Modifier.width(t.space2))
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            color = t.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(t.space2))
        Text(yen(item.amount, 0), style = MaterialTheme.typography.bodySmall, color = t.meta)
    }
}