package com.bsfeng.monitor.widget

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// 小组件文案的纯格式化：不碰 Android，边界全部由 WidgetFormatTest 在 JVM 里钉住。

/** 金额隐藏时的占位（隐私红线：桌面可见 = 唯一可见面）。 */
private const val HIDDEN_AMOUNT = "¥••••"

private const val NO_BUDGET = "未设预算"
private const val OVER_BUDGET = "已超支"
private const val BUDGET_SET = "已设预算"

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * 金额：`¥1,234.00`（负数 `-¥12.50`）；[hidden] 时一律 `¥••••`。
 *
 * 固定两位小数 + 千分位：桌面组件上的金额只做扫读，位数不固定会让同一位置的数字跳动。
 * 用 [Locale.US] 而不是系统语言：千分位必须是逗号，否则「1.234,00」这种欧陆写法与 ¥ 混排会误读。
 */
fun money(value: Double, hidden: Boolean): String {
    if (hidden) return HIDDEN_AMOUNT
    val sign = if (value < 0) "-" else ""
    return sign + "¥" + String.format(Locale.US, "%,.2f", abs(value))
}

/**
 * 1×1 档的金额：只留整数（含千分位），放不下两位小数。
 *
 * 为什么要单独一个函数：80dp 宽的卡里 `¥4,286.50` 会被截成 `¥4,28…`，
 * 截断点不可控、还会让人误读量级；这里主动丢掉小数，至少整数部分是完整的。
 * 隐藏金额仍走 [HIDDEN_AMOUNT]（隐私口径与 App 内一致）。
 */
fun tinyMoney(value: Double, hidden: Boolean): String {
    if (hidden) return HIDDEN_AMOUNT
    val sign = if (value < 0) "-" else ""
    return sign + "¥" + String.format(Locale.US, "%,d", abs(value).roundToInt())
}

/** 计数：`0` / `1,234`（千分位，与金额同一口径）。 */
fun count(n: Int): String = String.format(Locale.US, "%,d", n)

/**
 * 比例 0..1 → 整数百分比 0..100。
 *
 * 先乘后取整再夹逼：Provider 返回的 float 常带浮点误差（0.46f × 100 = 45.999996），
 * 直接截断会得到 45；超界的脏数据（1.5 / -0.2 / NaN）统一夹到 0..100，组件不显示「150%」。
 */
fun percent(fraction: Float): Int {
    if (fraction.isNaN()) return 0
    return (fraction * 100f).roundToInt().coerceIn(0, 100)
}

/**
 * 预算一句话文案。
 *
 * 优先级：未设预算 → 已超支 → 剩余金额 → 剩余百分比 → 只知「设过」。
 * **金额隐藏时调用方传 `remaining = null`**，这里自然退回百分比分支，
 * 避免为了一个布尔值再给纯函数加参数。
 */
fun budgetText(progress: Float?, remaining: Double?, hasBudget: Boolean): String {
    if (!hasBudget) return NO_BUDGET
    if (progress != null && progress >= 1f) return OVER_BUDGET
    if (remaining != null) return "剩 " + money(remaining, hidden = false)
    if (progress != null) return "剩 " + percent(1f - progress) + "%"
    return BUDGET_SET
}

/**
 * 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前。
 *
 * [thenMs] <= 0（从未发生过）返回「—」，让「没有数据」与「刚刚发生」在界面上可区分；
 * 时钟回拨（now < then）也按「刚刚」，组件不该因为用户改系统时间显示「-3 分钟前」。
 */
fun ageText(nowMs: Long, thenMs: Long): String {
    if (thenMs <= 0L) return "—"
    val elapsed = nowMs - thenMs
    return when {
        elapsed < MINUTE_MS -> "刚刚"
        elapsed < HOUR_MS -> (elapsed / MINUTE_MS).toString() + " 分钟前"
        elapsed < DAY_MS -> (elapsed / HOUR_MS).toString() + " 小时前"
        // 一天以上仍可能是「昨天 23:50」（= 1 天 10 分钟前），48 小时内统一说昨天，
        // 超过再报天数：额度快照按 1/3/6 小时刷新，几乎不会走到「N 天前」
        elapsed < 2 * DAY_MS -> "昨天"
        else -> (elapsed / DAY_MS).toString() + " 天前"
    }
}
