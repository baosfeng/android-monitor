package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.GapReason
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.domain.trip.TRIP_DAY_MS
import com.bsfeng.monitor.domain.trip.UsageSlice
import com.bsfeng.monitor.ui.live.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行程页文案口径（纯函数）：时长 / 端点 24:00 / 结论句 / 段标题。 */
class TripFormatTest {

    private val dayStart = START
    private val home = Place("p0", "家", 30.0, 120.0, Basis.GPS)
    private val office = Place("p1", "公司", 30.01, 120.01, Basis.GPS)

    private val day = DayTimeline(
        dayStart = dayStart,
        segments = listOf(
            Stay(
                start = dayStart,
                end = dayStart + 9 * HOUR,
                place = home,
                topApps = listOf(UsageSlice("微信", "com.tencent.mm", dayStart, dayStart + HOUR, HOUR)),
            ),
            Move(
                start = dayStart + 9 * HOUR,
                end = dayStart + 9 * HOUR + 20 * MIN,
                from = home,
                to = office,
                distanceMeters = 1200.0,
            ),
            Stay(
                start = dayStart + 9 * HOUR + 20 * MIN,
                end = dayStart + 12 * HOUR,
                place = office,
            ),
            Gap(start = dayStart + 12 * HOUR, end = dayStart + TRIP_DAY_MS),
        ),
        basis = Basis.GPS,
        coveragePct = 50,
    )

    @Test
    fun `时间轴端点用 24 点收口`() {
        // 时钟口径与 LiveModels 一致（系统时区），这里只验「同日按 HH:mm、窗口终点按 24:00」
        assertEquals(formatClock(dayStart + 9 * HOUR), formatSpanClock(dayStart + 9 * HOUR, dayStart))
        assertEquals("24:00", formatSpanClock(dayStart + TRIP_DAY_MS, dayStart))
    }

    @Test
    fun `时长文案按小时-分-秒降级`() {
        assertEquals("3 小时 20 分", formatSpanText(3 * HOUR + 20 * MIN))
        assertEquals("2 小时", formatSpanText(2 * HOUR))
        assertEquals("45 分", formatSpanText(45 * MIN))
        assertEquals("40 秒", formatSpanText(40 * 1000L))
        assertEquals("0 分", formatSpanText(0L))
    }

    @Test
    fun `结论句主地点用名字且与指标格同口径`() {
        val headline = tripHeadline(day)
        assertEquals("在外 3 小时 · 在家 9 小时", headline)
        // 与指标格第一格同一个数（topPlaceMs），不再出现「最长单段」口径
        assertTrue("不得再出现最长停留口径：$headline", !headline.contains("最长停留"))
        assertTrue(
            "结论句里的主地点时长必须等于指标格第一格",
            headline.contains(formatSpanText(tripMetrics(day).let { day.summary.topPlaceMs })),
        )
    }

    @Test
    fun `主地点没有可读名字时用主要停留`() {
        val unnamed = day.copy(
            segments = listOf(
                Stay(
                    start = dayStart,
                    end = dayStart + 9 * HOUR,
                    place = Place("geo:30.000,120.000", "地点", 30.0, 120.0, Basis.GPS),
                ),
                Move(
                    start = dayStart + 9 * HOUR,
                    end = dayStart + 9 * HOUR + 20 * MIN,
                    from = null,
                    to = null,
                    distanceMeters = 1200.0,
                ),
                Stay(
                    start = dayStart + 9 * HOUR + 20 * MIN,
                    end = dayStart + 12 * HOUR,
                    place = Place("geo:30.010,120.010", "地点", 30.01, 120.01, Basis.GPS),
                ),
                Gap(start = dayStart + 12 * HOUR, end = dayStart + TRIP_DAY_MS),
            ),
        )
        assertEquals("在外 3 小时 · 主要停留 9 小时", tripHeadline(unnamed))
    }

    @Test
    fun `空时间轴给空态文案`() {
        // 文案更新（task-10）：0 处停留不再说「今天还没有行程」——有移动/未记录时行程是有的
        assertEquals(
            "今天没有可识别的地点",
            tripHeadline(DayTimeline(dayStart = dayStart)),
        )
    }

    // ─────────────── task-10 新分支：单处 / 零处 / 无定位 / 地名 ───────────────

    @Test
    fun `单处停留只写停留不重复最长停留`() {
        val single = DayTimeline(
            dayStart = dayStart,
            segments = listOf(
                Stay(dayStart, dayStart + 9 * HOUR, home),
                Gap(dayStart + 9 * HOUR, dayStart + TRIP_DAY_MS),
            ),
            basis = Basis.GPS,
            coveragePct = 37,
        )
        val headline = tripHeadline(single)
        assertEquals("停留 9 小时", headline)
        assertTrue("单处停留不得再出现「最长停留」：$headline", !headline.contains("最长停留"))
        assertTrue("单处停留也不追加主地点口径：$headline", !headline.contains("主要停留"))
        assertEquals("同一事实只出现一次", 1, Regex("9 小时").findAll(headline).count())
        assertEquals("1 处停留 · 0 段移动", tripSubSummary(single))
    }

    @Test
    fun `零处停留给没有可识别地点的结论`() {
        val moveOnly = DayTimeline(
            dayStart = dayStart,
            segments = listOf(
                Move(dayStart, dayStart + 20 * MIN, null, null, 1200.0),
                Gap(dayStart + 20 * MIN, dayStart + TRIP_DAY_MS),
            ),
            basis = Basis.GPS,
            coveragePct = 1,
        )
        assertEquals("今天没有可识别的地点", tripHeadline(moveOnly))
        assertTrue("有移动时不得说「还没有行程」", !tripHeadline(moveOnly).contains("还没有行程"))
    }

    @Test
    fun `无定位态隐藏在外格只留停留与未记录`() {
        val device = DayTimeline(
            dayStart = dayStart,
            segments = listOf(
                Stay(
                    start = dayStart,
                    end = dayStart + 21 * HOUR + 8 * MIN,
                    place = Place("p0", "使用中", source = Basis.DEVICE),
                ),
                Gap(dayStart + 21 * HOUR + 8 * MIN, dayStart + TRIP_DAY_MS),
            ),
            basis = Basis.DEVICE,
            coveragePct = 99,
        )
        val metrics = tripMetrics(device)
        assertEquals(listOf("停留", "未记录"), metrics.map { it.first })
        assertEquals("21h8m", metrics[0].second)
        assertEquals("2h52m", metrics[1].second)
        assertTrue("无定位不得出现「在外 0 分」", metrics.none { it.first == "在外" })
        assertEquals(listOf("停留", "未记录"), metrics.map { it.first })
        // 有定位时是三格，且第一格是「主要停留」
        assertEquals(listOf("主要停留", "在外", "未记录"), tripMetrics(day).map { it.first })
    }

    @Test
    fun `退化地点名显示设备活跃而真实地名保持不变`() {
        val deviceStay = Stay(dayStart, dayStart + HOUR, Place("p0", "使用中", source = Basis.DEVICE))
        assertEquals("设备活跃", segmentTitle(deviceStay))
        assertEquals("设备活跃", placeDisplayName(deviceStay))

        val ssidStay = Stay(dayStart, dayStart + HOUR, Place("p1", "家庭网络", source = Basis.DEVICE))
        assertEquals("家庭网络", segmentTitle(ssidStay))

        val gpsStay = Stay(dayStart, dayStart + HOUR, Place("p2", "家", 30.0, 120.0, Basis.GPS))
        assertEquals("家", segmentTitle(gpsStay))
    }

    @Test
    fun `段标题与副标题`() {
        val (stay, move, _, gap) = day.segments
        assertEquals("家", segmentTitle(stay))
        assertEquals("停留 9 小时", segmentSubtitle(stay))
        assertEquals("家 → 公司", segmentTitle(move))
        assertEquals("移动 20 分 · 约 1.2 公里", segmentSubtitle(move))
        assertEquals("未记录", segmentTitle(gap))
        assertTrue("未记录必须写明不做推断", segmentSubtitle(gap).contains("不做推断"))
    }

    @Test
    fun `未记录段按成因分文案`() {
        val interrupted = Gap(dayStart, dayStart + 40 * MIN, GapReason.INTERRUPTED)
        val unresolved = Gap(dayStart, dayStart + 40 * MIN, GapReason.UNRESOLVED)
        assertEquals("采集中断 40 分 · 不做推断", segmentSubtitle(interrupted))
        assertEquals("定位不足 40 分 · 不做推断", segmentSubtitle(unresolved))
        // 缺省 reason 仍是 INTERRUPTED：既有多数构造点不受影响
        assertEquals("采集中断 40 分 · 不做推断", segmentSubtitle(Gap(dayStart, dayStart + 40 * MIN)))
        // 与既有基础测试同一条未记录段（无采样）保持原文案
        assertTrue(segmentSubtitle(day.segments.last()).startsWith("采集中断 "))
    }

    @Test
    fun `未记录不足一分钟沿用秒与零分口径`() {
        assertEquals(
            "采集中断 30 秒 · 不做推断",
            segmentSubtitle(Gap(dayStart, dayStart + 30_000L, GapReason.INTERRUPTED)),
        )
        assertEquals(
            "定位不足 0 分 · 不做推断",
            segmentSubtitle(Gap(dayStart, dayStart, GapReason.UNRESOLVED)),
        )
    }

    @Test
    fun `移动缺地名时只说移动不编造地点`() {
        val move = Move(dayStart, dayStart + HOUR, null, null, 0.0)
        assertEquals("移动", segmentTitle(move))
        assertEquals("移动 1 小时", segmentSubtitle(move))
    }

    @Test
    fun `结论卡指标与次行`() {
        // 次行更新（task-10）：只留计数，最长停留交给标题；覆盖度注释同样去重
        assertEquals(
            listOf("主要停留" to "9h", "在外" to "3h", "未记录" to "12h"),
            tripMetrics(day),
        )
        assertEquals("2 处停留 · 1 段移动", tripSubSummary(day))
        assertEquals("已记录 50%", tripCoverageNote(day))
    }

    @Test
    fun `紧凑时长在 24h 内不会截断`() {
        assertEquals("24h", formatCompactSpan(TRIP_DAY_MS))
        assertEquals("15h15m", formatCompactSpan(15 * HOUR + 15 * MIN))
        assertEquals("1h2m", formatCompactSpan(HOUR + 2 * MIN))
        assertEquals("6h", formatCompactSpan(6 * HOUR))
        assertEquals("45m", formatCompactSpan(45 * MIN))
        assertEquals("<1m", formatCompactSpan(30 * 1000L))
        assertEquals("0m", formatCompactSpan(0L))
        assertTrue(
            "24h 全长的紧凑串不超过 5 个字符（三等分格子放得下）",
            formatCompactSpan(TRIP_DAY_MS).length <= 5,
        )
    }

    @Test
    fun `指标三格互斥且合计等于当天窗口`() {
        val metrics = tripMetrics(day)
        assertEquals("有定位必须是三格", 3, metrics.size)
        assertEquals(
            "主要停留 + 在外 + 未记录 = 24h（不再出现两格相加超过一天）",
            TRIP_DAY_MS / MIN,
            metrics.sumOf { compactToMinutes(it.second) },
        )
        // 同一事实只出现一次：主要停留 ≠ 全部停留（这里是 9h vs 11h40）
        assertTrue("主要停留必须小于全部停留", compactToMinutes(metrics[0].second) < day.summary.stayMs / MIN)
    }

    /** 把 `15h15m` / `6h` / `45m` 还原成分钟，用于校验三格合计。 */
    private fun compactToMinutes(text: String): Long = when {
        text.endsWith("h") -> text.dropLast(1).toLong() * 60
        text.contains("h") -> {
            val parts = text.split("h")
            parts[0].toLong() * 60 + parts[1].removeSuffix("m").toLong()
        }
        text.endsWith("m") -> text.removeSuffix("m").toLong()
        else -> 0L
    }

    @Test
    fun `抽屉期间应用列八条并汇总其他`() {
        val apps = (1..10).map { index ->
            UsageSlice(
                app = "应用" + index,
                pkg = null,
                start = dayStart,
                end = dayStart + index * MIN,
                ms = index * MIN.toLong(),
            )
        }

        assertEquals("抽屉只列前 8 条", 8, visibleApps(apps).size)
        assertEquals("应用1", visibleApps(apps).first().app)
        assertEquals(
            "其余应用折成一行，不静默丢弃",
            "其他 2 个应用 · 合计 19 分",
            otherAppsNote(apps),
        )
        assertEquals("不超过 8 条时不出现汇总行", "", otherAppsNote(apps.take(8)))
        assertEquals("应用列表全空时也没有汇总行", "", otherAppsNote(emptyList()))
    }

    @Test
    fun `基准角标`() {
        assertEquals("已定位", basisBadge(Basis.GPS))
        assertEquals("按设备活动推算", basisBadge(Basis.DEVICE))
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
