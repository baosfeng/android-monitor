package com.bsfeng.monitor.finance.ui

// 来源：原型 s04 统计汇总 pane 的时间单位查询卡；职责：五档周期卡的**纯派生逻辑**（档位顺序与锚点、
// 图形类型、X 轴刻度、数值直标策略、图注文案）。不含任何 @Composable，可直接 JVM 单测。

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.finance.FinancePeriod
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.ui.components.CHART_X_TICKS_MAX
import com.bsfeng.monitor.ui.components.ChartFrameKind

/** unitbar 的固定顺序（原型 s04-unitbar：今日 / 本周 / 本月 / 本年 / 全部），与 [FinancePeriod] 声明顺序一致。 */
internal val FINANCE_PERIOD_ORDER: List<FinancePeriod> = listOf(
    FinancePeriod.DAY,
    FinancePeriod.WEEK,
    FinancePeriod.MONTH,
    FinancePeriod.YEAR,
    FinancePeriod.ALL,
)

/** unitbar 文案（五档）。 */
internal val FINANCE_PERIOD_LABELS: List<String> = FINANCE_PERIOD_ORDER.map { it.label }

/**
 * unitbar 的段级锚点（五档逐字：今日 / 本周 / 本月 / 本年 / 全部）。
 *
 * 收成常量而不是写进调用点的集合字面量：锚点对账要从 testTag 调用点顺着实参回溯到字面量，
 * 直接写在 optionTags 里会断链，5 个锚点会全被判成「代码里没有」。
 */
internal val FINANCE_PERIOD_UNIT_TAGS: List<String> = listOf(
    "s04-unit-day",
    "s04-unit-week",
    "s04-unit-month",
    "s04-unit-year",
    "s04-unit-all",
)

/** 档位 → unitbar 下标（未知档落 0，避免越界）。 */
internal fun financePeriodIndexOf(period: FinancePeriod): Int =
    FINANCE_PERIOD_ORDER.indexOf(period).coerceAtLeast(0)

/** unitbar 下标 → 档位（越界落 DAY）。 */
internal fun financePeriodAt(index: Int): FinancePeriod =
    FINANCE_PERIOD_ORDER.getOrElse(index) { FinancePeriod.DAY }

/**
 * 档位 → 图形类型（纯函数，可 JVM 单测）。
 *
 * day 用热力（24 格按**该小时支出金额**取色阶，原型 s04:194「格越深当日该小时支出越多」）；
 * week / month 用柱（逐日比较大小）；year / all 用面积（看走势、不看单点）。
 */
internal fun periodChartKind(period: FinancePeriod): ChartFrameKind = when (period) {
    FinancePeriod.DAY -> ChartFrameKind.Heat
    FinancePeriod.WEEK, FinancePeriod.MONTH -> ChartFrameKind.Bars
    FinancePeriod.YEAR, FinancePeriod.ALL -> ChartFrameKind.Area
}

/** 档位 → 图表左上说明（对齐原型 data-chart-label）。 */
internal fun periodChartTitle(period: FinancePeriod): String = when (period) {
    FinancePeriod.DAY -> "今日 24 小时消费金额"
    FinancePeriod.WEEK -> "本周每日支出"
    FinancePeriod.MONTH -> "本月每日支出"
    FinancePeriod.YEAR -> "年度支出走势"
    FinancePeriod.ALL -> "历年支出走势"
}

/** 档位 → 右上单位标注：五档统一按金额编码（day 的色阶也编码金额，见 [periodChartValues]）。 */
internal fun periodChartUnit(period: FinancePeriod): String = "单位：元"

/**
 * 档位 → 图表序列：五档一律取支出金额。
 *
 * day 档曾按**笔数**取色阶，与原型「格越深该小时支出越多」（s04-记账.html:194）不符：
 * 两笔 ¥2 与一笔 ¥300 在笔数口径下前者的格子更深，金额口径下才是后者更深。
 * 色阶归一由 HeatBar 按传入序列的最大值做（6 级不变），换成金额后自动按金额归一。
 *
 * 空结果统一返回 24 个 0（而不是空 list）：空 list 会让绘图区整块消失、坐标轴只剩刻度值，
 * 用户看到的是「图表坏了」而不是「今天还没有消费」。
 */
internal fun periodChartValues(period: FinancePeriod, stats: PeriodStats?): List<Float> {
    val values = stats?.expenseValues
    return values?.takeIf { it.isNotEmpty() } ?: List(PERIOD_CHART_MIN_BUCKETS) { 0f }
}

/** 空态时占位桶数（热力条 24 格、柱最少 7 根，取 24 让两种形状都不至于空掉）。 */
internal const val PERIOD_CHART_MIN_BUCKETS: Int = 24

/** day 档热力条的 X 轴刻度：0/6/12/18/24 五点（24 格均分成四段，刻度落在每段起点）。 */
internal val PERIOD_HEAT_X_LABELS: List<String> = listOf("0", "6", "12", "18", "24")

/**
 * all 档 X 轴最多标出的刻度数：4 位年份的标签宽约 26dp，340dp 宽的绘图区并排 9 个仍有
 * 9dp 间隙（原型 s04:222 给的正是 2018..2026 这 9 个）。沿用外壳默认的 7 个会把年份砍成
 * 「2018/2019/2021/…」这种忽 1 忽 2 的间隔，看起来像少了两年数据。
 */
internal const val PERIOD_ALL_X_LABEL_MAX: Int = 9

/**
 * 档位 → X 轴最多标出的刻度数（短标签放宽，见 [PERIOD_ALL_X_LABEL_MAX]）。
 */
internal fun periodXLabelMax(period: FinancePeriod): Int =
    if (period == FinancePeriod.ALL) PERIOD_ALL_X_LABEL_MAX else CHART_X_TICKS_MAX

/**
 * 档位的 X 轴刻度。
 *
 * 热力用固定五档而不是 24 格标签：抽稀到 7 个会把 6/12/18 打散成 0/4/8/…，
 * 「几点在花钱」就一眼读不出来了。
 *
 * all 档直接给年份（2018 / 2019 …）：4 位数字的刻度宽约 26dp，调用点按 [periodXLabelMax]
 * 放宽到 9 个就能全标（[PERIOD_ALL_X_LABEL_MAX]），不需要压短成 25-04 或二次稀疏。
 */
internal fun periodXLabels(period: FinancePeriod, stats: PeriodStats?): List<String> = when (period) {
    FinancePeriod.DAY -> PERIOD_HEAT_X_LABELS
    else -> stats?.labels.orEmpty()
}

/**
 * 该档 KPI 卡的锚点（五档各一个稳定标识，切换档位时同一位置换名）。
 *
 * 写成 when + 字面量而不是 "s04-unitkpi-" + key 拼接：锚点对账只认字面量或可追踪的常量，
 * 运行时拼接会让 5 个锚点在代码侧「查不到」。
 */
internal fun periodKpiTag(period: FinancePeriod): Modifier = when (period) {
    FinancePeriod.DAY -> Modifier.testTag("s04-unitkpi-day")
    FinancePeriod.WEEK -> Modifier.testTag("s04-unitkpi-week")
    FinancePeriod.MONTH -> Modifier.testTag("s04-unitkpi-month")
    FinancePeriod.YEAR -> Modifier.testTag("s04-unitkpi-year")
    FinancePeriod.ALL -> Modifier.testTag("s04-unitkpi-all")
}

/** 该档面板的锚点（五档各一个字面量）。 */
internal fun periodPaneTag(period: FinancePeriod): Modifier = when (period) {
    FinancePeriod.DAY -> Modifier.testTag("s04-pane-day")
    FinancePeriod.WEEK -> Modifier.testTag("s04-pane-week")
    FinancePeriod.MONTH -> Modifier.testTag("s04-pane-month")
    FinancePeriod.YEAR -> Modifier.testTag("s04-pane-year")
    FinancePeriod.ALL -> Modifier.testTag("s04-pane-all")
}

/** 该档图表的锚点（同上：五档各一个字面量）。 */
internal fun periodChartTag(period: FinancePeriod): Modifier = when (period) {
    FinancePeriod.DAY -> Modifier.testTag("s04-chart-day")
    FinancePeriod.WEEK -> Modifier.testTag("s04-chart-week")
    FinancePeriod.MONTH -> Modifier.testTag("s04-chart-month")
    FinancePeriod.YEAR -> Modifier.testTag("s04-chart-year")
    FinancePeriod.ALL -> Modifier.testTag("s04-chart-all")
}

/** 周期 KPI 文案：今日支出 / 本周支出 …（档位标签前缀 + 支出 / 收入）。 */
internal fun periodKpiLabel(period: FinancePeriod, kind: String): String = period.label + kind

/** 柱数超过这个值就不再逐柱直标（30 根柱的柱顶数字会互相压住，读不出也看不清）。 */
internal const val PERIOD_VALUES_MAX_BARS: Int = 10

/** 是否逐柱直标：柱少直标（week 7 根），柱多降级只在图注标峰值。 */
internal fun periodShowsAllValues(period: FinancePeriod, stats: PeriodStats?): Boolean {
    if (period == FinancePeriod.DAY) return false
    val values = stats?.expenseValues?.takeIf { it.isNotEmpty() } ?: return false
    return values.size <= PERIOD_VALUES_MAX_BARS
}

/**
 * 不逐柱直标时的图注：把峰值写出来，柱太密也能读到关键信息。
 *
 * 单位按档位取：day 的桶标签是「小时」（19 时），其余是「日 / 月」，
 * 写成统一的「日」会把 19 点读成 19 号。
 */
internal fun periodValueNote(period: FinancePeriod, stats: PeriodStats?): String {
    // day 档口径是金额（色阶 = 该小时支出金额）：图注必须写合计与峰值金额。
    // 空数据（全 0 桶 / 还没取到）也照样给完整说明，不留一句「柱高按本档最大值归一」的含糊话。
    if (period == FinancePeriod.DAY) {
        val base = "横轴为小时（0 / 6 / 12 / 18 / 24），1 格 = 1 小时；" +
            "格越深该小时支出越多，合计 " + yen(stats?.totalExpense ?: 0.0, 0)
        val peak = stats?.buckets?.maxByOrNull { it.expense }
        return if (peak != null && peak.expense > 0.0) {
            base + " · 峰值 " + peak.label + " 时 " + yen(peak.expense, 0)
        } else {
            base
        }
    }
    val buckets = stats?.buckets
    if (buckets == null || buckets.isEmpty()) return "柱高按本档最大值归一"
    val peak = buckets.maxByOrNull { it.expense } ?: return "柱高按本档最大值归一"
    val unit = when (period) {
        FinancePeriod.YEAR -> " 月"
        // all 档的分桶是年份（原型 s04:222 的横轴 2018..2026），写成「月」会把年份读成月份
        FinancePeriod.ALL -> " 年"
        else -> " 日"
    }
    return "峰值 " + peak.label + unit + " " + yen(peak.expense, 0) + " · 柱高按本档最大值归一"
}
