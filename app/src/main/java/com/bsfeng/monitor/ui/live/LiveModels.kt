package com.bsfeng.monitor.ui.live

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * s02 实时事件流 / s01 概览 共用的纯数据辅助：筛选、按小时分组、逐时强度、时间格式化。
 * 全部是纯函数（无 IO、无 Compose 依赖），便于单测。
 * 数据源与 token 均来自 W0 冻结接口，本文件不含任何色值 / 字号 / 间距字面量。
 */

/** 类型筛选：与原型 s02 的 6 个 chip 一一对应。 */
enum class LiveFilter(val label: String, val types: Set<RecordType>) {
    ALL("全部", emptySet()),
    SCREEN("屏幕", setOf(RecordType.SCREEN)),
    APP_USAGE("应用", setOf(RecordType.APP_USAGE)),
    CHARGING("充电", setOf(RecordType.CHARGING, RecordType.BATTERY)),
    WIFI("网络", setOf(RecordType.WIFI, RecordType.PHONE_STATE, RecordType.AIRPLANE)),
    AUDIO("音频", setOf(RecordType.AUDIO));

    fun matches(record: Record): Boolean = this == ALL || types.contains(record.type)
}

/** 按筛选条件过滤（保留原顺序）。 */
fun List<Record>.matching(f: LiveFilter): List<Record> = this.filter { f.matches(it) }

/** 小时分组：组按小时倒序，组内按时间倒序。 */
data class HourGroup(val hour: Int, val label: String, val events: List<Record>)

fun hourOfTimestamp(timestamp: Long): Int = zonedOf(timestamp).hour

fun hourLabel(hour: Int): String = String.format(Locale.US, "%02d:00", hour)

fun groupByHourDesc(events: List<Record>): List<HourGroup> =
    events.groupBy { hourOfTimestamp(it.timestamp) }
        .entries
        .sortedByDescending { it.key }
        .map { entry ->
            HourGroup(
                hour = entry.key,
                label = hourLabel(entry.key),
                events = entry.value.sortedByDescending { it.timestamp },
            )
        }

/** 24 格逐时强度（下标 = 0..23 时），按事件条数。 */
fun hourlyIntensityOf(events: List<Record>): List<Float> {
    val buckets = FloatArray(HOURS_PER_DAY)
    events.forEach { buckets[hourOfTimestamp(it.timestamp)] += 1f }
    return buckets.toList()
}

/** 峰值：返回 (小时, 峰值计数)；全 0 时返回 (0, 0f)。 */
fun peakOf(values: List<Float>): Pair<Int, Float> {
    var idx = 0
    var max = 0f
    values.forEachIndexed { i, v ->
        if (v > max) {
            max = v
            idx = i
        }
    }
    return idx to max
}

/** 当天 00:00 的毫秒时间戳（沿用既有口径：now - now % 86400000）。 */
fun startOfDay(now: Long): Long = now - (now % DAY_MS)

const val DAY_MS: Long = 86_400_000L
const val HOURS_PER_DAY: Int = 24

// J8：DateTimeFormatter 不可变且线程安全（SimpleDateFormat 非线程安全，且随默认时区在类加载时固化）。
private val ZONE: ZoneId = ZoneId.systemDefault()
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val clockSecFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
private val dayLabelFormat = DateTimeFormatter.ofPattern("MM-dd", Locale.getDefault())
private val fullDateFormat = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.getDefault())

private fun zonedOf(timestamp: Long): ZonedDateTime = Instant.ofEpochMilli(timestamp).atZone(ZONE)

fun formatClock(timestamp: Long): String = clockFormat.format(zonedOf(timestamp).toLocalTime())
fun formatClockSec(timestamp: Long): String = clockSecFormat.format(zonedOf(timestamp).toLocalTime())
fun formatDayLabel(timestamp: Long): String = dayLabelFormat.format(zonedOf(timestamp).toLocalDate())
fun formatFullDate(timestamp: Long): String = fullDateFormat.format(zonedOf(timestamp).toLocalDate())

private val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

fun weekdayLabel(timestamp: Long): String =
    // java.time 的 DayOfWeek：周一=1 … 周日=7 → %7 映射到 WEEKDAYS（0=星期日）
    WEEKDAYS[zonedOf(timestamp).dayOfWeek.value % 7]

/** 毫秒 → "12 分 04 秒"；不足 1 分钟只显示秒。null → "—"。 */
fun formatDurationMs(ms: Long?): String {
    if (ms == null) return PLACEHOLDER
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) {
        String.format(Locale.US, "%d 分 %02d 秒", min, sec)
    } else {
        String.format(Locale.US, "%d 秒", sec)
    }
}

/** 毫秒 → "6h 12m"（近 7 天 / 近 30 天活跃 KPI 用）。 */
fun formatHoursMinutes(totalMinutes: Long): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return h.toString() + "h " + String.format(Locale.US, "%02d", m) + "m"
}

/** 缺失字段统一占位（与原型「缺失字段以 — 占位」一致）。 */
const val PLACEHOLDER: String = "—"

/** 取 detail 字段，缺失或空白一律回落到占位符。 */
fun Record.detailOr(tag: String): String {
    val v = detail?.get(tag)
    return if (v.isNullOrBlank()) PLACEHOLDER else v
}
