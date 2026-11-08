package com.bsfeng.monitor.ui

// 从 ui/OverviewScreen.kt 拆出：概览的数值格式化 —— 带符号增减（整数/分钟）、月份刻度、金额取整、日标签。负责「数字怎么写成一行字」。

import androidx.compose.foundation.layout.size
import com.bsfeng.monitor.ui.live.dayStarts
import com.bsfeng.monitor.ui.live.formatDayLabel
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

/**
 * 概览页头用的「现在时刻」：优先跟随数据快照的时刻，未加载成功时退回真实时钟。
 *
 * 页头日期与三段面板的 startOfDay 必须同源。面板每轮刷新（REFRESH_MS = 5s）现算 startOfDay，
 * 页头若各自冻一次街钟，跨零点后两者会错位（写着昨天、数据已按今天重算）。
 * 传 0（首帧、还没读到数据）时才回落到墙钟。
 */
internal fun overviewHeaderNow(lastUpdatedAt: Long, wallClock: Long = System.currentTimeMillis()): Long =
    lastUpdatedAt.takeIf { it > 0L } ?: wallClock

/**
 * 近 N 天横轴的日标签（首 = 最早那天）。
 *
 * [now] 必须与数据快照同源（传 state.lastUpdatedAt）：VM 的日桶按 startOfDay(now) 现算，
 * 横轴若自己取街钟并 remember 住，跨零点后柱值和标签会各停各的那一天。
 */
internal fun dayLabels(days: Int, now: Long = System.currentTimeMillis()): List<String> {
    if (days <= 0) return emptyList()
    // 按日历取每天的本地 00:00：DST 日只有 23 / 25 小时，定长 DAY_MS 回推会让轴首偏一天
    return dayStarts(days, now).map { formatDayLabel(it) }
}
