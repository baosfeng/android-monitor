package com.bsfeng.monitor.debug

import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.TripAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * `seed` 造出来的数据必须**在行程页上直接看出效果**，所以这里不只测字段，还真的跑一遍
 * `TripAggregator`，断言「上午在家 / 中午移动 / 下午在公司 / 晚上回家」被聚合出来。
 *
 * 这是纯 JVM 用例：Seed 与 Aggregator 都不碰 Android API。
 */
class DebugSeedTest {

    private val now = 1_700_000_000_000L // 固定时刻，避免用例随当前时间漂移
    private val dayStart = Instant.ofEpochMilli(now)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Test
    fun `day 场景 - 聚合出四段（家 移动 公司 家）`() {
        val records = DebugSeed.build(SeedScenario.DAY, dayStart, now)
        val timeline = TripAggregator(dayStart, now).aggregate(records)

        assertEquals("应走 GPS 基准", Basis.GPS, timeline.basis)
        assertEquals("家 / 公司 / 回家 三段停留", 3, timeline.stays.size)
        assertEquals("去程 / 回程 两段移动", 2, timeline.moves.size)
    }

    @Test
    fun `day 场景 - 时间全部落在当日窗口内且升序`() {
        val records = DebugSeed.build(SeedScenario.DAY, dayStart, now)
        assertTrue("至少造出 10 条", records.size >= 10)
        assertTrue("不许出现未来时间（聚合器右端是 now，闭开区间）", records.all { it.timestamp < now })
        assertTrue("不许早于当日 00:00", records.all { it.timestamp >= dayStart })
        assertEquals("按时间升序", records.sortedBy { it.timestamp }, records)
    }

    @Test
    fun `day 场景 - 四类记录齐全（LOCATION-APP_USAGE-PAGE-SCREEN）`() {
        val types = DebugSeed.build(SeedScenario.DAY, dayStart, now).map { RecordType.toString(it.type) }.toSet()
        assertTrue(types.contains("LOCATION"))
        assertTrue(types.contains("APP_USAGE"))
        assertTrue(types.contains("PAGE"))
        assertTrue(types.contains("SCREEN"))
    }

    @Test
    fun `定位点 - 坐标量化到 3 位小数并带 accuracy`() {
        val locations = DebugSeed.build(SeedScenario.DAY, dayStart, now)
            .filter { RecordType.toString(it.type) == "LOCATION" }
        assertTrue(locations.isNotEmpty())
        locations.forEach { record ->
            val detail = record.detail!!
            assertEquals("source 标记供清理与核对", DebugSeed.SOURCE, detail["source"])
            assertTrue("lat 3 位小数", Regex("^-?\\d+\\.\\d{3}$").matches(detail.getValue("lat")))
            assertTrue("lng 3 位小数", Regex("^-?\\d+\\.\\d{3}$").matches(detail.getValue("lng")))
            assertTrue("accuracy 必须为正", detail.getValue("accuracy_m").toInt() > 0)
        }
    }

    @Test
    fun `stay 场景 - 只有一个停留段且没有移动`() {
        val timeline = TripAggregator(dayStart, now)
            .aggregate(DebugSeed.build(SeedScenario.STAY, dayStart, now))
        assertEquals(1, timeline.stays.size)
        assertEquals(0, timeline.moves.size)
    }

    @Test
    fun `move 场景 - 真移动：无长期停留 + 至少两段移动`() {
        val records = DebugSeed.build(SeedScenario.MOVE, dayStart, now)
        val timeline = TripAggregator(dayStart, now).aggregate(records)

        assertEquals("应走 GPS 基准", Basis.GPS, timeline.basis)
        assertTrue("至少 2 段移动，实际 ${timeline.moves.size}", timeline.moves.size >= 2)
        assertTrue(
            "不许出现长期停留（每段停留应只有端点短停 10 分钟），实际 ${timeline.stays.map { it.durationMs }}",
            timeline.stays.all { it.durationMs <= 20 * 60_000L },
        )
        assertTrue("已判定时长占比应够高，实际 ${timeline.coveragePct}%", timeline.coveragePct >= 60)
        println(
            "[seed-move] stays=${timeline.stays.size}（每段 ${timeline.stays.map { it.durationMs / 60_000 }} 分钟）" +
                " moves=${timeline.moves.size} gaps=${timeline.gaps.size} coverage=${timeline.coveragePct}%",
        )
    }

    @Test
    fun `move 场景 - 步进 5 分钟且单步位移不低于 300m`() {
        val points = DebugSeed.build(SeedScenario.MOVE, dayStart, now)
            .filter { RecordType.toString(it.type) == "LOCATION" }
        assertTrue("定位点太少：${points.size}", points.size >= 30)

        var movingSteps = 0
        points.zipWithNext().forEach { (a, b) ->
            val gap = b.timestamp - a.timestamp
            val meters = haversineMeters(
                a.detail!!.getValue("lat").toDouble(), a.detail!!.getValue("lng").toDouble(),
                b.detail!!.getValue("lat").toDouble(), b.detail!!.getValue("lng").toDouble(),
            )
            assertTrue("采样间隔 $gap ms 应不小于 5 分钟", gap >= 5 * 60_000L)
            if (meters > 0.0) {
                movingSteps++
                assertTrue("单步位移 ${meters}m 低于移动判据 300m", meters >= 300.0)
            }
        }
        assertTrue("移动步数太少：$movingSteps", movingSteps >= 20)
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    @Test
    fun `gap 场景 - 两段停留之间有采样空档`() {
        val timeline = TripAggregator(dayStart, now)
            .aggregate(DebugSeed.build(SeedScenario.GAP, dayStart, now))
        assertEquals(2, timeline.stays.size)
        assertEquals(">1 小时空档不算移动", 0, timeline.moves.size)
        assertTrue("空档被显式标出来", timeline.gaps.isNotEmpty())
    }
}
