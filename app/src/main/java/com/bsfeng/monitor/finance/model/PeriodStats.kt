package com.bsfeng.monitor.finance.model

// 来源：记账「时间单位查询」需求（原型 s04）；职责：周期统计的只读模型 —— 五档三值（支出/收入/结余）+ 笔数 + 分桶序列。
// 分桶序列与界面图表一一对应（day=24 小时、week=7 日、month=当月逐日、year=12 月、all=逐月），标签由 DAO 侧给出。

import java.util.Locale

/**
 * 周期统计的单个分桶（图表里的一根柱 / 一格热力）。
 *
 * @param label x 轴刻度文案（"0".."23" / 周一..周日 / "1".."31" / "1".."12" / yyyy-MM）
 * @param expense 该桶支出合计（数据库原值累加；退款负额已在内抵扣，与月度口径一致）
 * @param income 该桶收入合计
 * @param count 该桶**交易笔数**（热力图按笔数取色阶，与 [expense] 不同口径）
 */
data class PeriodBucket(
    val label: String,
    val expense: Double = 0.0,
    val income: Double = 0.0,
    val count: Int = 0,
)

/**
 * 某一时间单位（[com.bsfeng.monitor.finance.FinancePeriod]）的完整统计结果。
 *
 * 三值口径与月度 KPI 一致：支出取 type=0 的 SUM（含负额退款抵扣），收入取 type=1。
 * 金额一律来自 SQL SUM，不在内存里累加，避免「捞列表再 sum」被截断悄悄改小。
 *
 * @param fromDate 统计区间起点（含），yyyy-MM-dd；[com.bsfeng.monitor.finance.FinancePeriod.ALL] 为最早一笔的日期
 * @param toDate 统计区间终点（含），yyyy-MM-dd
 * @param buckets 分桶序列，长度与档位绑定（day=24、week=7、month=当月天数、year=12、all=月数）
 */
data class PeriodStats(
    val unitKey: String = "day",
    val unitLabel: String = "今日",
    val fromDate: String = "",
    val toDate: String = "",
    val totalExpense: Double = 0.0,
    val totalIncome: Double = 0.0,
    val transactionCount: Int = 0,
    val buckets: List<PeriodBucket> = emptyList(),
) {
    /** 结余（收入 - 支出） */
    val balance: Double get() = totalIncome - totalExpense

    /** x 轴刻度文案（直接喂图表外壳的 xLabels） */
    val labels: List<String> get() = buckets.map { it.label }

    /** 支出分桶（图表 values；柱状 / 面积 / 折线通用） */
    val expenseValues: List<Float> get() = buckets.map { it.expense.toFloat() }

    /** 收入分桶 */
    val incomeValues: List<Float> get() = buckets.map { it.income.toFloat() }

    /** 笔数分桶（day 档热力取色阶用） */
    val countValues: List<Float> get() = buckets.map { it.count.toFloat() }

    /** 分桶支出峰值（图表 Y 轴上限取它；全 0 时返回 0，交给外壳守卫） */
    val maxBucketExpense: Float get() = expenseValues.maxOrNull()?.takeIf { it > 0f } ?: 0f

    /** 本档是否有数据（KPI 卡空态判定；笔数或金额任一非零即算有） */
    val hasData: Boolean get() = transactionCount > 0 || totalExpense > 0.0 || totalIncome > 0.0

    /** 支出占收入比例（收入为 0 时返回 0，避免除零） */
    val expenseShareOfIncome: Float
        get() = if (totalIncome > 0.0) (totalExpense / totalIncome).toFloat() else 0f

    val displayExpense: String get() = money(totalExpense)
    val displayIncome: String get() = money(totalIncome)
    val displayBalance: String get() = money(balance)

    private fun money(value: Double): String = String.format(Locale.CHINA, "%,.2f", value)
}
