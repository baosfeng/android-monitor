package com.bsfeng.monitor.domain.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨天边界归并：日首 / 日尾的「未记录」在窗口外有同址定位点时应并入停留。
 *
 * 坐标全部为合成值（杭州纬度 30° 一带，0.0009° 纬度 ≈ 100m），不含任何真机地址或真实坐标。
 */
class TripBoundaryMergeTest {

    private val aggregator = TripAggregator(DAY_START)

    // ─────────────────────────── 日首 ───────────────────────────

    @Test
    fun `前一天同址点让首段未记录并入停留`() {
        val records = buildList {
            for (m in 30..120 step 10) add(loc(at(m), HOME_LAT, HOME_LNG))
        }
        // 昨天 23:00 在家：距 dayStart 1 小时，同址
        val context = listOf(loc(at(-60), HOME_LAT, HOME_LNG))

        val timeline = aggregator.aggregate(records, context)

        assertEquals("只剩一段停留", 1, timeline.stays.size)
        assertEquals("首段未记录被吞，停留从 dayStart 起", DAY_START, timeline.stays.single().start)
        assertTrue("不得再有贴边界的首段 Gap", timeline.gaps.none { it.start == DAY_START })
        println("日首吞并: " + summarize(timeline))
    }

    @Test
    fun `前一天不同址不吞首段未记录`() {
        val records = buildList {
            for (m in 30..120 step 10) add(loc(at(m), HOME_LAT, HOME_LNG))
        }
        // 昨天 23:00 在 500m 外的 B 点：真实换地点，必须留下未记录
        val context = listOf(loc(at(-60), HOME_LAT + 0.0045, HOME_LNG))

        val timeline = aggregator.aggregate(records, context)

        assertEquals("停留起点仍是首个当天采样", at(30), timeline.stays.single().start)
        val head = timeline.gaps.first()
        assertEquals("首段未记录保留", DAY_START, head.start)
        assertEquals(GapReason.INTERRUPTED, head.reason)
    }

    @Test
    fun `前一天的点距 dayStart 超过两小时不吞`() {
        val records = buildList {
            for (m in 30..120 step 10) add(loc(at(m), HOME_LAT, HOME_LNG))
        }
        // 昨天 21:00 在家：同址但距 dayStart 3 小时，中间断过，不能担保
        val context = listOf(loc(at(-180), HOME_LAT, HOME_LNG))

        val timeline = aggregator.aggregate(records, context)

        assertEquals("超窗口不吞，停留起点不变", at(30), timeline.stays.single().start)
        assertEquals("首段未记录保留", DAY_START, timeline.gaps.first().start)
    }

    // ─────────────────────────── 日尾 ───────────────────────────

    @Test
    fun `次日同址点让末段未记录并入停留`() {
        val records = buildList {
            for (m in 1320..1410 step 10) add(loc(at(m), HOME_LAT, HOME_LNG))
        }
        // 次日 00:30 仍在家：距 dayEnd 30 分钟，同址
        val context = listOf(loc(at(1470), HOME_LAT, HOME_LNG))

        val timeline = aggregator.aggregate(records, context)

        assertEquals("停留终点扩到 dayEnd", DAY_START + TRIP_DAY_MS, timeline.stays.single().end)
        assertTrue("不得再有贴边界的末段 Gap", timeline.gaps.none { it.end == DAY_START + TRIP_DAY_MS })
        println("日尾吞并: " + summarize(timeline))
    }

    @Test
    fun `次日不同址不吞末段未记录`() {
        val records = buildList {
            for (m in 1320..1410 step 10) add(loc(at(m), HOME_LAT, HOME_LNG))
        }
        val context = listOf(loc(at(1470), HOME_LAT + 0.0045, HOME_LNG))

        val timeline = aggregator.aggregate(records, context)

        assertEquals("停留终点仍是末个当天采样", at(1410), timeline.stays.single().end)
        assertEquals("末段未记录保留", DAY_START + TRIP_DAY_MS, timeline.gaps.last().end)
    }

    // ──────────────── 真机形态：今天 25 点（首 00:30:03 / 末 12:50:39）────────────────

    @Test
    fun `今天真机形态首段不再是未记录`() {
        // 时刻按首末点等分合成（真机 25 点、约 30 分钟兜底采样带抖动）；坐标全为合成值
        val records = ArrayList<Record>()
        for (i in 0 until 25) {
            val offsetSec = 1803L + (44436L * i) / 24L
            val lat = if (i in DRIFT_INDEXES) HOME_LAT - DRIFT_LAT else HOME_LAT
            records.add(loc(DAY_START + offsetSec * 1000L, lat, HOME_LNG))
        }
        // 昨天 23:00 在家：用户场景里「昨天最后在家」的那一个点
        val context = listOf(loc(at(-60), HOME_LAT, HOME_LNG))

        val timeline = aggregator.aggregate(records, context)

        assertEquals("首段不再是未记录", DAY_START, timeline.stays.first().start)
        assertTrue("首段是停留而不是 Gap", timeline.segments.first() is Stay)
        println("真机形态(25 点, 00:30:03-12:50:39): " + summarize(timeline))
    }

    // ─────────────────────────── 回归 ───────────────────────────

    @Test
    fun `没有 context 时行为与旧口径逐字一致`() {
        val records = buildList {
            for (m in 30..120 step 10) add(loc(at(m), HOME_LAT, HOME_LNG))
        }

        val legacy = aggregator.aggregate(records)
        val explicitEmpty = aggregator.aggregate(records, emptyList())

        assertEquals("默认参数与显式空 context 必须完全一致", legacy.segments, explicitEmpty.segments)
        assertEquals("首段仍是未记录", DAY_START, legacy.gaps.first().start)
        assertEquals("末段仍是未记录", DAY_START + TRIP_DAY_MS, legacy.gaps.last().end)
    }

    // ────────────────────────────── 构造辅助 ──────────────────────────────

    private fun summarize(timeline: DayTimeline): String = timeline.segments.joinToString(" | ") { seg ->
        val label = when (seg) {
            is Stay -> "Stay"
            is Move -> "Move"
            is Gap -> "Gap(" + seg.reason + ")"
        }
        val minutes = (seg.start - DAY_START) / MIN_MS
        label + " " + minutes + "m-" + ((seg.end - DAY_START) / MIN_MS) + "m"
    }

    private fun at(minutes: Int): Long = DAY_START + minutes * MIN_MS

    private fun loc(ts: Long, lat: Double, lng: Double): Record = Record(
        id = "loc-" + ts,
        type = RecordType.LOCATION,
        action = "COORDINATE",
        timestamp = ts,
        label = "",
        detail = mapOf("lat" to lat.toString(), "lng" to lng.toString()),
    )

    private companion object {
        const val MIN_MS = 60_000L
        const val DAY_START = 1_700_000_000_000L
        const val HOME_LAT = 30.0000
        const val HOME_LNG = 120.0000

        /** 0.00081° 纬度 ≈ 90m，对应真机兜底定位南跳的幅度。 */
        const val DRIFT_LAT = 0.00081

        /** 25 点里 6 个跳变点，彼此不相邻。 */
        val DRIFT_INDEXES = setOf(5, 9, 13, 17, 19, 21)
    }
}
