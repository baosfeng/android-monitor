package com.bsfeng.monitor.domain.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPS 基准鲁棒化：单点漂移剔除 / 同址停留合并 / Gap 成因分级。
 *
 * 坐标全部为合成值（杭州纬度 30° 一带，0.0009° 纬度 ≈ 100m），不含任何真机地址或真实坐标。
 * 时间锚点固定为 [DAY_START]，避免随 wall clock 漂移。
 */
class TripGpsRobustnessTest {

    private val aggregator = TripAggregator(DAY_START)

    // ─────────────── 复现：同一地点的长停留被定位漂移切碎 ───────────────

    /**
     * 真机形态的合成重现：房内静止 13 小时，兜底定位周期性南跳 ~90m 又立刻跳回。
     * 旧算法在跳变点断段，断出的单点 Run 不满足停留 → 中间被标成「未记录」。
     */
    @Test
    fun `定位漂移点剔除后同一地点的长停留不再被切碎`() {
        val records = ArrayList<Record>()
        for (i in 0 until 25) {
            val ts = at(30 + i * 30)
            val lat = if (i in DRIFT_INDEXES) HOME_LAT - DRIFT_LAT else HOME_LAT
            records.add(loc(ts, lat, HOME_LNG))
        }

        val timeline = aggregator.aggregate(records)

        assertEquals("漂移点剔除后全天只剩一段停留", 1, timeline.stays.size)
        val stay = timeline.stays.single()
        assertEquals("停留起点 = 首个有效采样", at(30), stay.start)
        assertEquals("停留终点 = 末个有效采样", at(30 + 24 * 30), stay.end)
        assertEquals("不得再出现「有采样却判不出」", 0, unresolved(timeline))
        assertEquals("首尾各一段未记录", 2, timeline.gaps.size)
        assertTrue("首段 Gap 区间内无采样", timeline.gaps.first().reason == GapReason.INTERRUPTED)
        assertTrue("末段 Gap 区间内无采样", timeline.gaps.last().reason == GapReason.INTERRUPTED)
        println("漂移复现(25 点/6 跳变): " + summarize(timeline))
    }

    // ─────────────────────── 既有行为：真实移动不被合并 ───────────────────────

    @Test
    fun `真实步行五百米仍产出移动不被合并`() {
        val records = buildList {
            for (m in 0..60 step 5) add(loc(at(m), HOME_LAT, HOME_LNG))
            // 每 5 分钟走 100m，连续过渡（没有单点跳变）
            listOf(65, 70, 75, 80, 85).forEachIndexed { i, m ->
                add(loc(at(m), HOME_LAT + (i + 1) * 0.0009, HOME_LNG))
            }
            for (m in 90..150 step 5) add(loc(at(m), HOME_LAT + 5 * 0.0009, HOME_LNG))
        }

        val timeline = aggregator.aggregate(records)

        assertEquals("两段停留", 2, timeline.stays.size)
        assertEquals("中间是移动，不是被合并", 1, timeline.moves.size)
        assertEquals("两处不同地点", 2, timeline.stays.map { it.place.id }.distinct().size)
        val move = timeline.moves.single()
        assertTrue("累计距离约 500m，实际 " + move.distanceMeters, move.distanceMeters in 400.0..600.0)
    }

    @Test
    fun `两段停留真实相距四百米不合并`() {
        val records = buildList {
            for (m in 0..60 step 5) add(loc(at(m), HOME_LAT, HOME_LNG))
            add(loc(at(65), HOME_LAT + 0.0018, HOME_LNG))
            add(loc(at(70), HOME_LAT + 0.0036, HOME_LNG))
            for (m in 75..135 step 5) add(loc(at(m), HOME_LAT + 0.0036, HOME_LNG))
        }

        val timeline = aggregator.aggregate(records)

        assertEquals("真实换地点不合并", 2, timeline.stays.size)
        assertEquals(1, timeline.moves.size)
        assertEquals("中间由移动段占满，不留未记录", 0, unresolved(timeline))
    }

    // ─────────────────────── 剔除判定的边界 ───────────────────────

    @Test
    fun `一端相邻一端远的点不算离群`() {
        val points = listOf(
            LocationPoint(HOME_LAT, HOME_LNG, at(0)),
            LocationPoint(HOME_LAT + 0.0010, HOME_LNG, at(10)),
            LocationPoint(HOME_LAT + 0.0010, HOME_LNG, at(20)),
        )

        val kept = dropOutliers(points)

        assertEquals("后一点紧邻（0m），不构成跳出去又跳回", 3, kept.size)
        assertEquals("中间点原样保留", at(10), kept[1].ts)
    }

    // ─────────────────────── Gap 成因分级 ───────────────────────

    @Test
    fun `区间内有采样但判不出移动标为未解决`() {
        val records = buildList {
            for (m in 0..60 step 10) add(loc(at(m), HOME_LAT, HOME_LNG))
            add(loc(at(90), HOME_LAT + 0.0010, HOME_LNG))
            for (m in 120..180 step 10) add(loc(at(m), HOME_LAT + 0.0020, HOME_LNG))
        }

        val timeline = aggregator.aggregate(records)
        val gap = timeline.gaps.single { it.start == at(60) }

        assertEquals("净位移 222m < 300m 不判移动", 0, timeline.moves.size)
        assertEquals("区间内有定位采样 → UNRESOLVED", GapReason.UNRESOLVED, gap.reason)
    }

    // ────────────────────────────── 构造辅助 ──────────────────────────────

    private fun unresolved(timeline: DayTimeline): Int =
        timeline.gaps.count { it.reason == GapReason.UNRESOLVED }

    /** 每段一行：类型(reason) 起-止（相对 dayStart 的时:分）。 */
    private fun summarize(timeline: DayTimeline): String =
        timeline.segments.joinToString(" | ") { seg ->
            val label = when (seg) {
                is Stay -> "Stay(" + seg.place.label + ")"
                is Move -> "Move(" + seg.distanceMeters.toInt() + "m)"
                is Gap -> "Gap(" + seg.reason + ")"
            }
            label + " " + clock(seg.start) + "-" + clock(seg.end)
        }

    private fun clock(ts: Long): String {
        val minutes = (ts - DAY_START) / MIN_MS
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
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

        /** 合成基点：杭州纬度 30°，与实际居所无关。 */
        const val HOME_LAT = 30.0000
        const val HOME_LNG = 120.0000

        /** 0.00081° 纬度 ≈ 90m，对应真机兜底定位南跳的幅度。 */
        const val DRIFT_LAT = 0.00081

        /** 25 点里 6 个跳变点，彼此不相邻（相邻两点是有效回位点）。 */
        val DRIFT_INDEXES = setOf(5, 9, 13, 17, 19, 21)
    }
}
