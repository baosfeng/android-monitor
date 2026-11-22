package com.bsfeng.monitor.finance

// 来源：原型 s07 的「时间范围卡 / 年度趋势 / 年度 Top 分类」（.open-design/src/screens/s07-统计分析.html:62-114）；
// 职责：年度聚合的**纯逻辑** —— 一级分类 Top-N 排序、周均口径与锚点、12 桶补齐。
// 不碰数据库、不含 @Composable，可直接 JVM 单测。

import com.bsfeng.monitor.finance.model.PeriodBucket
import com.bsfeng.monitor.finance.model.PeriodStats
import java.time.LocalDate
import kotlin.math.roundToLong

/** 一级分类累计的一行（Top 分类卡 = 色点 + 分类名 + 金额）。 */
internal data class CategoryAmount(val name: String, val amount: Double)

/** Top 分类条数：原型 s07 的「年度 Top 分类」列 5 行（102-114）。 */
internal const val YEAR_TOP_CATEGORY_LIMIT: Int = 5

/**
 * 取金额最高的 [limit] 个一级分类。
 *
 * 先滤掉 ≤ 0 的项：退款抵扣后净额为 0 / 负的分类不该占 Top 名额（否则一张「Top 5」里会混进 $0）。
 * 同额按名称升序：SQLite 的 GROUP BY 与 Kotlin Map 的同额顺序都不稳定，不定序会让色点在两次
 * 刷新之间跳位 —— 用户会以为「分类变了」。
 */
internal fun topCategoriesOf(
    rows: Map<String, Double>,
    limit: Int = YEAR_TOP_CATEGORY_LIMIT,
): List<CategoryAmount> {
    if (limit <= 0) return emptyList()
    return rows.entries
        .filter { it.value > 0.0 }
        .sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key },
        )
        .take(limit)
        .map { CategoryAmount(it.key, it.value) }
}

/**
 * 已过去周数（含当前这一周，至少 1）：第 1 周 = 1 月 1 日 .. 1 月 7 日。
 *
 * 用「自然周的整周切分」而不是 ISO 周号：ISO 第 1 周可能落在去年 12 月，年初会算出 0 周甚至
 * 负数，周均直接除爆；按年内第几天切则 1 月 1 日恒为第 1 周。
 */
internal fun elapsedWeeksOf(today: LocalDate): Int = (today.dayOfYear - 1) / 7 + 1

/**
 * 周均口径的锚点日：今年取今天，往年取该年最后一天，未来年取该年首日。
 *
 * 为什么不能一律用「今天」：s07 的月份条可以翻到往年，用今年的已过周数去除往年的钱，
 * 1 月份翻到去年时会得到「去年一年支出 ÷ 1 周」这种荒诞值。
 */
internal fun yearAverageAnchor(year: Int, today: LocalDate): LocalDate = when {
    year < today.year -> LocalDate.of(year, 12, 31)
    year == today.year -> today
    else -> LocalDate.of(year, 1, 1)
}

/**
 * 周均支出 = 年度累计支出 / 已过去周数，四舍五入到元。
 *
 * 口径选择理由（原型 6070 只写了「周均 ¥617」，没写怎么算）：
 * - 不用「年累计 ÷ 52」或「÷ (12/4)」：那等于把「今年还没过完」当成「已经过完一整年」，
 *   上半年算出来的周均会被压到真实值的一半以下，看不出最近的花钱速度；
 * - 用「已过去周数」时分子就是同一张卡上的「年累计」，两格同源，读者能自己复算；
 * - 取整到元与同卡「总花费 / 年累计」一致（都是 0 位小数），三格不出现两种精度。
 */
internal fun yearWeeklyAverage(yearExpense: Double, anchor: LocalDate): Double {
    if (!(yearExpense > 0.0)) return 0.0
    val weeks = elapsedWeeksOf(anchor).coerceAtLeast(1)
    return (yearExpense / weeks).roundToLong().toDouble()
}

/**
 * 年度 12 桶：年度统计还没取到 / 桶数不对时补 12 个 0 桶。
 *
 * 补桶而不是返回空列表：年度趋势卡与图例按「12 个月」工作，空列表会让整块图与坐标轴消失，
 * 用户看到的是「图表坏了」而不是「这一年还没有账」。
 */
internal fun yearBucketsOf(stats: PeriodStats?): List<PeriodBucket> {
    val buckets = stats?.buckets.orEmpty()
    if (buckets.size == PERIOD_YEAR_MONTHS) return buckets
    return (1..PERIOD_YEAR_MONTHS).map { PeriodBucket(label = it.toString()) }
}
