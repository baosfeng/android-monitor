package com.bsfeng.monitor.ui

// 从 ui/OverviewScreen.kt 拆出：概览的数值格式化 —— 带符号增减（整数/分钟）、月份刻度、金额取整、日标签。负责「数字怎么写成一行字」。

import androidx.compose.foundation.layout.size
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.formatDayLabel
import com.bsfeng.monitor.ui.live.startOfDay
import java.util.Locale

internal fun signed(delta: Int): String = if (delta >= 0) "+" + delta else delta.toString()

/** 分钟级增减：原型写作「较昨日 +42m」（parts/s01-overview.html:173）。 */
internal fun signedMinutes(delta: Long): String =
    (if (delta >= 0) "+" else "-") + kotlin.math.abs(delta) + "m"

/** 近 30 天横轴 7 个刻度（首 / 每 5 天 / 末，原型 parts/s01-overview.html:360）。 */
internal fun monthTicks(labels: List<String>): List<String> =
    if (labels.isEmpty()) emptyList()
    else (0..6).map { labels[(it * (labels.size - 1) / 6).coerceIn(0, labels.size - 1)] }

internal fun money(value: Double): String = String.format(Locale.US, "%,.0f", value)

internal fun dayLabels(days: Int): List<String> {
    val start = startOfDay(System.currentTimeMillis()) - (days - 1) * DAY_MS
    return (0 until days).map { formatDayLabel(start + it * DAY_MS) }
}
