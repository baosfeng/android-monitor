package com.bsfeng.monitor.finance

// 来源：原型 s07「本月日均」卡（.open-design/src/screens/s07-统计分析.html:62-70）；
// 职责：日均口径的**纯逻辑** —— 已过天数与「累计 ÷ 已过天数」。不碰数据库、不含 @Composable，可直接 JVM 单测。

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * 浏览月的已过天数（分母）：本月 = 今天几号（含今天）/ 往年整月 = 当月天数 / 未来月 = 0。
 *
 * 与周均同一条口径（[elapsedWeeksOf]，分子都是同一张卡上的「累计」），三处差别：
 * - 已过去的月份整月都在里面，分母取 [YearMonth.lengthOfMonth]（2 月 28/29、闰年自动对）；
 * - 未来月份还没开始，返回 0 —— 让调用方显示占位「—」，而不是把「还没到」算成无穷大或 0 元日均；
 * - 本月含今天：今天花的钱也算「已过的这一天」，与周均「含本周」一致。
 *
 * 解析不出来（脏月份字符串）按 0 处理：日均卡宁可不显示数字，也不猜一个月出来。
 */
internal fun monthElapsedDays(yearMonth: String, today: LocalDate): Int {
    val target = runCatching { YearMonth.parse(yearMonth) }.getOrNull() ?: return 0
    val current = YearMonth.of(today.year, today.monthValue)
    return when {
        target.isBefore(current) -> target.lengthOfMonth()
        target == current -> today.dayOfMonth
        else -> 0
    }
}

/**
 * 本月日均支出 = 浏览月累计支出 / 已过天数，四舍五入到元。
 *
 * 取整到元与同屏的「本月累计 / 总花费 / 周均 / 年累计」一致（都是 0 位小数），
 * 一屏不出现两种精度；日均是给人看节奏的量级，分位在这个尺度上没有信息量。
 * 分母 ≤ 0（未来月）或分子 ≤ 0 时返回 0.0，由界面决定显示占位还是「¥0」。
 */
internal fun monthDailyAverage(monthExpense: Double, elapsedDays: Int): Double {
    if (!(monthExpense > 0.0) || elapsedDays <= 0) return 0.0
    return (monthExpense / elapsedDays).roundToLong().toDouble()
}
