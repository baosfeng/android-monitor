package com.bsfeng.monitor.finance.ui

// 来源：原型 s04 统计汇总 pane 的时间单位查询卡；职责：五档周期卡的**纯派生逻辑**（档位顺序与锚点、
// 图形类型、X 轴刻度、数值直标策略、图注文案）。不含任何 @Composable，可直接 JVM 单测。

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.finance.FinancePeriod
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.ui.components.CHART_X_TICKS_MAX
import com.bsfeng.monitor.ui.components.ChartFrameKind
import com.bsfeng.monitor.ui.components.chartXLabelsOf

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
 * all 档 X 轴最多直接标出的刻度数：首末必选 + 中间挑间隔最宽的一组。
 *
 * 口径来自审计 P2-7：12 个月的抽稀位置是 0/2/4/6/7/9/11，其中 6 与 7 只隔一桶
 * （约 23dp），「26-06」「26-07」两段文字直接边界相接。外壳的抽稀上限
 * （[CHART_X_TICKS_MAX] = 7）在调用侧再降一档即可，无需改外壳。
 *
 * 降一档到 5 仍然贴边：候选池被外壳锁死在 7 个等距位置上，池内挑 5 个时最小间隔只能
 * 2 池位 = 120px，减去 85px 标签宽只剩 35px 空隙（实测 25-12 与 26-02 之间），
 * 视觉上仍是「两段挤在一起」。降到 4 个后最小间隔 3 池位 = 180px、空隙 95px，
 * 大于半字宽（42px），才是真正分开。
 */
internal const val PERIOD_ALL_X_LABEL_MAX: Int = 4

/**
 * 档位的 X 轴刻度。
 *
 * 热力用固定五档而不是 24 格标签：抽稀到 7 个会把 6/12/18 打散成 0/4/8/…，
 * 「几点在花钱」就一眼读不出来了。
 */
internal fun periodXLabels(period: FinancePeriod, stats: PeriodStats?): List<String> = when (period) {
    FinancePeriod.DAY -> PERIOD_HEAT_X_LABELS
    // all 档的桶标签是 yyyy-MM（5 个字的刻度在 12 个月宽度里会互相压住）：压短 + 降档稀疏
    FinancePeriod.ALL -> sparseYearMonthLabels(stats?.labels.orEmpty())
    else -> stats?.labels.orEmpty()
}

/**
 * all 档的月份刻度压缩：2025-04 → 25-04。
 *
 * 7 个 yyyy-MM 刻度在 360dp 宽里必然重叠（实测「2025-0」互相覆盖）；
 * 保留「年-月」信息即可，完整年份由 KPI 区间（fromDate..toDate）负责。
 */
internal fun shortYearMonthLabel(label: String): String {
    val parts = label.split("-")
    if (parts.size != 2 || parts[0].length != 4) return label
    return parts[0].takeLast(2) + "-" + parts[1]
}

/**
 * all 档标签稀疏化：返回**与入参等长**的列表，未标位置填空串。
 *
 * 为什么填空串而不是缩短列表：ChartFrame 的 X 轴刻度用 xLabels.size 当桶总数来定位
 * （[com.bsfeng.monitor.ui.components.ChartXAxis]），列表变短会让刻度与图形整体错位；
 * 置空只影响文字可见性，桶序号与刻度位置都不动。
 *
 * 选取口径：**候选池就是外壳自己会取的位置**（[chartXLabelsOf] 的 7 点等距抽稀），
 * 再从池里挑出 [PERIOD_ALL_X_LABEL_MAX] 个「相邻间隔最宽且最均匀」的位置（首末固定）。
 *
 * 为什么不能自己另算一套等分位置：ChartFrame 对 > 7 桶的序列一律只标它自己那 7 个点，
 * 池外的位置即使有文字也画不出来 —— 实测按 5 点等分（0/3/6/8/11）只剩 3 个标签可见。
 *
 * 为什么还要挑：12 个月的池是 0/2/4/6/7/9/11，其中 6 与 7 只隔 1 桶；而末位标签会被
 * ChartXAxis 的右边界 clamp 向内推（实测吃掉约 1 桶），所以「9 与 11」这种 2 桶间隔
 * 实际只剩 35px，「26-09」「26-11」贴到一起。挑出 4 个时池内最宽的一组是 0/4/7/11，
 * 最小间隔 3 池位（约 180px）> 标签宽 85px，空隙 95px > 半字宽（42px）。
 *
 * 桶数 ≤ [CHART_X_TICKS_MAX] 时外壳本来就全标且单桶宽度足够，原样返回。
 */
internal fun sparseYearMonthLabels(labels: List<String>): List<String> {
    if (labels.isEmpty()) return emptyList()
    val short = labels.map { shortYearMonthLabel(it) }
    if (short.size <= CHART_X_TICKS_MAX) return short
    val pool = chartXLabelsOf(short).map { it.first }
    val kept = widestEvenPicks(pool, PERIOD_ALL_X_LABEL_MAX)
    return short.mapIndexed { i, s -> if (i in kept) s else "" }
}

/** 末位标签被右边界 clamp 向内推，实测吃掉约 1 桶宽度：末位与前者比较时按此扣减。 */
internal const val SPARSE_LAST_GAP_PENALTY: Int = 1

/**
 * 从 [pool] 里挑 [take] 个位置（首末必选）：先让「最小相邻间隔」最大，同分取间隔更均匀的一组。
 *
 * 只在池内挑是硬约束（池外位置外壳不画），纯函数便于单测。
 */
internal fun widestEvenPicks(pool: List<Int>, take: Int): List<Int> {
    if (take >= pool.size) return pool
    if (take <= 2) return listOf(pool.first(), pool.last())
    var best: List<Int> = emptyList()
    var bestMinGap = -1
    var bestSpread = Int.MAX_VALUE
    // 池最多 7 个、首末固定 → 中间 C(5,3)=10 种组合，直接枚举
    val candidates = middleCombinations(pool, take)
    for (combo in candidates) {
        val picks = listOf(pool.first()) + combo + listOf(pool.last())
        val gaps = picks.zipWithNext { a, b -> b - a }
        // 末位那一跳先扣 clamp 损失再参与比较
        val score = gaps.last() - SPARSE_LAST_GAP_PENALTY
        val minGap = minOf(gaps.dropLast(1).minOrNull() ?: score, score)
        val spread = gaps.sumOf { it * it }
        if (minGap > bestMinGap || (minGap == bestMinGap && spread < bestSpread)) {
            best = picks
            bestMinGap = minGap
            bestSpread = spread
        }
    }
    return best.ifEmpty { pool }
}

/** 池内挑 [take] - 2 个（不含首末）的全部组合。 */
private fun middleCombinations(pool: List<Int>, take: Int): List<List<Int>> {
    val middle = pool.subList(1, pool.size - 1)
    val need = take - 2
    val out = mutableListOf<List<Int>>()
    fun walk(start: Int, acc: MutableList<Int>) {
        if (acc.size == need) {
            out.add(acc.toList())
            return
        }
        for (i in start until middle.size) {
            acc.add(middle[i])
            walk(i + 1, acc)
            acc.removeAt(acc.size - 1)
        }
    }
    walk(0, mutableListOf())
    return out
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
        FinancePeriod.YEAR, FinancePeriod.ALL -> " 月"
        else -> " 日"
    }
    return "峰值 " + peak.label + unit + " " + yen(peak.expense, 0) + " · 柱高按本档最大值归一"
}
