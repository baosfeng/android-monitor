package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.DaySegment
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.GapReason
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.domain.trip.TRIP_DAY_MS
import com.bsfeng.monitor.domain.trip.UsageSlice
import com.bsfeng.monitor.ui.live.formatClock
import java.util.Locale

/**
 * 行程页 / 概览行程卡共用的**纯文案**函数（无 Compose、无 IO、可单测）。
 *
 * 口径与原型 s16 对齐：时长「X 小时 Y 分」、移动「约 1.2 公里」、未记录「不做推断」。
 * 页面轨迹已下线：不再输出任何页面切换次数或页面名称。
 */

/** 时间轴两端：窗口终点显示 24:00，而不是次日的 00:00。 */
fun formatSpanClock(timestamp: Long, dayStart: Long): String =
    if (timestamp >= dayStart + TRIP_DAY_MS) "24:00" else formatClock(timestamp)

/** 时长：`3 小时 20 分` / `45 分` / `40 秒`。 */
fun formatSpanText(ms: Long): String {
    val totalMin = ms / 60_000L
    return when {
        totalMin >= 60L -> {
            val hours = totalMin / 60L
            val minutes = totalMin % 60L
            if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分"
        }
        totalMin >= 1L -> "$totalMin 分"
        ms > 0L -> "${(ms / 1000L).coerceAtLeast(1L)} 秒"
        else -> "0 分"
    }
}

/**
 * 指标格用的紧凑时长：`15h15m` / `6h` / `45m` / `<1m`。
 *
 * 为什么不用 [formatSpanText]：指标格是三等分宽度（411dp 下每格约 110dp），
 * 「15 小时 15 分」在 `maxLines = 1` 下会被省略号截成「15 小时 15…」，
 * 数字被吞掉等于没显示；紧凑格式在 24h 全长（`24h`）下也放得下。
 */
fun formatCompactSpan(ms: Long): String {
    val totalMin = ms / 60_000L
    return when {
        totalMin >= 60L -> {
            val hours = totalMin / 60L
            val minutes = totalMin % 60L
            if (minutes == 0L) "${hours}h" else "${hours}h${minutes}m"
        }
        totalMin >= 1L -> "${totalMin}m"
        ms > 0L -> "<1m"
        else -> "0m"
    }
}

/** 基准角标文案：有定位 → 「已定位」；无定位 → 「按设备活动推算」。 */
fun basisBadge(basis: Basis): String = when (basis) {
    Basis.GPS -> "已定位"
    Basis.DEVICE -> "按设备活动推算"
}

/**
 * 结论一句话——**概览卡与行程页共用这一处**，任何一处不得自己再拼。
 *
 * 按停留处数分支，避免「只有一处停留时停留时长 = 最长停留时长」的自重复：
 * - ≥2 处：`在外 3 小时 · 在家 9 小时`（第二个时长 = 主地点合计 = 指标格第一格，同一口径）；
 *   主地点没有可读名字时写 `在外 3 小时 · 主要停留 9 小时`
 * - =1 处：`停留 21 小时 8 分`（最长停留恒等于它，不再追加）
 * - =0 处：`今天没有可识别的地点`（不写「今天还没有行程」——有移动/未记录时行程是有的）
 */
fun tripHeadline(timeline: DayTimeline): String {
    val summary = timeline.summary
    if (summary.stayCount <= 0) return NO_PLACE_HEADLINE
    if (summary.stayCount == 1) return "停留 " + formatSpanText(summary.stayMs)
    val parts = ArrayList<String>(2)
    if (summary.awayMs > 0L) parts.add("在外 " + formatSpanText(summary.awayMs))
    if (summary.topPlaceMs > 0L) {
        // 与指标格同口径（都用 topPlaceMs），全卡只留一个「主地点时长」概念：
        // 「最长单段」不再进摘要（要看得去停留抽屉，那里按段显示时长）
        parts.add(topPlacePhrase(timeline))
    }
    return if (parts.isEmpty()) "停留 " + formatSpanText(summary.stayMs) else parts.joinToString(" · ")
}

/**
 * 结论句里的主地点短语（含时长）：**有可读名字就用名字**——`在家 9 小时` / `在家庭网络 2 小时`；
 * 没有名字（聚合层只能给出通用占位，或只是使用簇退化标记）时退回 `主要停留 9 小时`，不硬编地名。
 */
private fun topPlacePhrase(timeline: DayTimeline): String {
    val summary = timeline.summary
    val label = summary.topPlaceLabel
    val named = label.isNotBlank() && label != GENERIC_PLACE_LABEL && label != USAGE_CLUSTER_PLACE
    val head = if (named) "在" + label else TOP_PLACE_TOKEN
    return head + " " + formatSpanText(summary.topPlaceMs)
}

/**
 * 时间轴行标题。移动没有两端地名时只说「移动」，不编造地点名。
 *
 * 标题用**短名**（[placeShortName]）：一行只有一行的宽度，「上海市闵行区华漕镇牛 New 寿喜烧」
 * 这种全名会把「A → B」整段挤掉，只剩行政区划前缀。全名仍在抽屉标题、地图标签、导出里可查。
 */
fun segmentTitle(segment: DaySegment): String = when (segment) {
    is Stay -> placeShortName(placeDisplayName(segment.place))
    is Move -> {
        val from = segment.from?.let { placeShortName(placeDisplayName(it)) }.orEmpty()
        val to = segment.to?.let { placeShortName(placeDisplayName(it)) }.orEmpty()
        if (from.isNotBlank() && to.isNotBlank()) "$from → $to" else "移动"
    }
    is Gap -> "未记录"
}

/**
 * 地点显示名（唯一出口）。
 *
 * 无定位时聚合层把「由 App 使用簇合成的地点」标记成 [USAGE_CLUSTER_PLACE]，
 * 那是算法内部标记，不是给用户看的词——展示侧统一换成「设备活跃」；
 * 有定位 / 有网络名时原样显示，不加工。
 */
fun placeDisplayName(place: Place): String =
    if (place.source == Basis.DEVICE && place.label == USAGE_CLUSTER_PLACE) DEVICE_ACTIVE_LABEL else place.label

fun placeDisplayName(stay: Stay): String = placeDisplayName(stay.place)

/**
 * 地名短名（时间轴标题、轨迹卡停留标签共用这一处，不要在别处再写一份）。
 *
 * 规则：依次去掉「省 / 市 / 区 / 县 / 镇」后缀及其前缀（最多三轮，且剩余至少 2 字才去），
 * 然后截到 12 字；去不干净或全空时回退原名。
 *
 * 为什么只认这几个单字后缀：'州' 会误伤「杭州」「苏州」这类地名本身；多字后缀（'街道'）
 * 需要另做多字匹配，而 12 字截断通常已够一行放下，先不加。剩余至少 2 字的门槛也挡住
 * 「镇江」「镇海」这类被当成后缀误伤的正式地名。
 */
fun placeShortName(raw: String): String {
    var text = raw.trim()
    var round = 0
    while (round < 3) {
        // 按行政层级（省 → 市 → 区 → 县 → 镇）找第一个可去掉的后缀，而不是取字符串里最靠前的后缀字符：
        // 「镇江市」里 '镇' 出现在 '市' 之前，按位置切会切出「江市」。
        // 下标从 1 起：'镇'/'区'/'市' 打头的正式地名（镇江、镇海）不能被当成后缀切掉。
        var cut = -1
        for (suffix in ADMIN_SUFFIXES) {
            val at = text.indexOf(suffix)
            if (at in 1..6 && text.length - at - 1 >= 2) {
                cut = at
                break
            }
        }
        if (cut < 0) break
        text = text.substring(cut + 1).trim()
        round++
    }
    // 回退原名时也 trim：全空白的地名在时间轴上就是空标题，不能画出一串空格
    return text.take(12).ifBlank { raw.trim() }
}

/** 「省 / 市 / 区 / 县 / 镇」五个单字后缀（顺序无关，命中最靠前的一个）。 */
private val ADMIN_SUFFIXES = charArrayOf('省', '市', '区', '县', '镇')

/**
 * 时间轴行副标题。
 *
 * 未记录分两级（[GapReason]）：区间内没有定位采样 = 「采集中断」（本来就不知道发生了什么）；
 * 有采样但判不出 = 「定位不足」（定位漂移 / 断档 / 覆盖不够）。两者都不推断期间内容，只换成因措辞。
 */
fun segmentSubtitle(segment: DaySegment): String = when (segment) {
    is Stay -> "停留 " + formatSpanText(segment.durationMs)
    is Move -> {
        val base = "移动 " + formatSpanText(segment.durationMs)
        if (segment.distanceMeters >= 100.0) {
            base + " · 约 " + String.format(Locale.US, "%.1f", segment.distanceMeters / 1000.0) + " 公里"
        } else {
            base
        }
    }
    is Gap -> when (segment.reason) {
        GapReason.INTERRUPTED -> "采集中断 " + formatSpanText(segment.durationMs) + " · 不做推断"
        GapReason.UNRESOLVED -> "定位不足 " + formatSpanText(segment.durationMs) + " · 不做推断"
    }
}

/** 停留抽屉里「期间应用」最多列几条，其余折成一行汇总（长列表会把抽屉撑出屏）。 */
const val APPS_IN_SHEET: Int = 8

/** 抽屉里实际列出的应用条数（模型侧已是全部，这里只做展示截取）。 */
fun visibleApps(apps: List<UsageSlice>): List<UsageSlice> = apps.take(APPS_IN_SHEET)

/** 超出 [APPS_IN_SHEET] 的部分汇总成一行；没超出返回空串（UI 据此不渲染该行）。 */
fun otherAppsNote(apps: List<UsageSlice>): String {
    val rest = apps.drop(APPS_IN_SHEET)
    if (rest.isEmpty()) return ""
    return "其他 " + rest.size + " 个应用 · 合计 " + formatSpanText(rest.sumOf { it.ms })
}

/**
 * 结论卡的指标格。
 *
 * - 有定位：`主要停留 / 在外 / 未记录` 三格（互斥，合计 = 当天窗口）；
 * - 无定位（[Basis.DEVICE]）：**去掉「在外」**——没有位置就无从判断「在外」，
 *   显示「在外 0 分」会被读成「今天一直在原地」。只留 `停留 / 未记录` 两格。
 */
fun tripMetrics(timeline: DayTimeline): List<Pair<String, String>> {
    val summary = timeline.summary
    val gap = "未记录" to formatCompactSpan(summary.gapMs)
    return if (timeline.basis == Basis.DEVICE) {
        listOf("停留" to formatCompactSpan(summary.stayMs), gap)
    } else {
        // 有定位：三格必须互斥且合计 = 当天窗口 → 主要停留 + 在外 + 未记录。
        // 「停留」格取 topPlaceMs（同一地点多段之和），不再是全部停留：
        // 否则「停留(全部) + 在外(移动 + 非主地点停留)」会把非主地点的停留算两遍。
        listOf(
            "主要停留" to formatCompactSpan(summary.topPlaceMs),
            "在外" to formatCompactSpan(summary.awayMs),
            gap,
        )
    }
}

/** 结论卡的次行：**只给计数**（`1 处停留 · 0 段移动`），最长停留已由标题承担，不再重复。 */
fun tripSubSummary(timeline: DayTimeline): String =
    timeline.stays.size.toString() + " 处停留 · " + timeline.moves.size + " 段移动"

/** 概览卡底部注释：只给覆盖度（停留处数已由同一张卡的图例给出，不重复）。 */
fun tripCoverageNote(timeline: DayTimeline): String = "已记录 " + timeline.coveragePct + "%"

/** 无任何可识别地点时的结论句（有移动 / 未记录时行程仍然存在，不写「还没有行程」）。 */
const val NO_PLACE_HEADLINE: String = "今天没有可识别的地点"

/** 聚合层给「没有地址也没有网络名」的定位点用的通用占位（同样不该出现在 UI 里）。 */
private const val GENERIC_PLACE_LABEL: String = "地点"

/** 主地点没有可读名字时的称呼。 */
const val TOP_PLACE_TOKEN: String = "主要停留"

/** 无定位时由使用簇合成的地点在聚合层里的内部标记（只用于展示侧改名，勿写进 UI）。 */
private const val USAGE_CLUSTER_PLACE: String = "使用中"

/** 上述标记在界面上的显示名。 */
const val DEVICE_ACTIVE_LABEL: String = "设备活跃"
