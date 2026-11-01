package com.bsfeng.monitor.debug

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.trip.FixBand
import com.bsfeng.monitor.ui.trip.TracePrecision
import com.bsfeng.monitor.ui.trip.TraceSample
import com.bsfeng.monitor.ui.trip.TripTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * 「带噪声移动」种子场景（SeedScenario.NOISE）单元测试。
 *
 * 覆盖：场景可构建 / 精度分两档（步行高置信、室内低置信）/ 抖动真的写进去了 /
 * 坐标是 6 位小数（不被当成历史粗坐标）/ 既有场景仍是 3 位小数（语义没被改动）。
 */
class DebugSeedNoiseTest {

    private val dayStart = 1_760_000_000_000L
    private val now = dayStart + 12 * 60 * 60 * 1000L

    private fun records(): List<Record> = DebugSeed.build(SeedScenario.NOISE, dayStart, now)

    private fun locations(): List<Record> = records().filter { it.type == RecordType.LOCATION }

    private fun accuracyOf(record: Record): Float? = record.detail?.get("accuracy_m")?.toFloatOrNull()

    @Test
    fun `场景生成定位与使用记录且都标记来源`() {
        val all = records()
        assertTrue("至少要有定位点", all.count { it.type == RecordType.LOCATION } > 10)
        assertTrue("室内段要带 App 使用记录", all.any { it.type == RecordType.APP_USAGE })
        assertTrue("所有记录都带 debug_seed 来源", all.all { it.detail?.get("source") == DebugSeed.SOURCE })
    }

    @Test
    fun `时间全部落在当天窗口内且升序`() {
        val timestamps = records().map { it.timestamp }
        assertEquals("时间必须升序", timestamps.sorted(), timestamps)
        assertTrue("不能早于当天 0 点", timestamps.first() >= dayStart)
        assertTrue("不能晚于窗口右端", timestamps.last() < now)
    }

    @Test
    fun `步行段高置信`() {
        val walk = locations().filter { it.detail?.get("provider") == "gps" }.mapNotNull(::accuracyOf)
        assertTrue("步行段要有 GPS 点", walk.isNotEmpty())
        assertTrue("步行段 accuracy 应为 15~60m：", walk.all { it in 15f..60f })
        assertTrue("步行段应当全在高置信档", walk.all { TracePrecision.bandOf(it) == FixBand.HIGH })
    }

    @Test
    fun `室内段低置信且能触发虚线`() {
        val indoor = locations().filter { it.detail?.get("provider") == "network" }.mapNotNull(::accuracyOf)
        assertTrue("室内段要有 network 点", indoor.isNotEmpty())
        assertTrue("室内段 accuracy 应为 250~450m", indoor.all { it in 250f..450f })
        assertTrue("室内段应当全在低置信档", indoor.all { TracePrecision.bandOf(it) == FixBand.LOW })
    }

    @Test
    fun `精度画像同时有实线段与虚线段`() {
        val profile = TracePrecision.profile(TripTrace.samples(records()))
        assertTrue("要有低置信段（室内）", profile.hasLowConfidence)
        assertTrue("也要有高置信段（步行）", profile.bands.any { it == FixBand.HIGH })
        assertEquals("分档与采样点一一对应", profile.bands.size, TripTrace.samples(records()).size)
    }

    @Test
    fun `坐标是六位小数因此不算历史粗坐标`() {
        val values = locations().flatMap { listOf(it.detail?.get("lat"), it.detail?.get("lng")) }.filterNotNull()
        assertTrue("坐标应当按 6 位小数落库：", values.all { it.substringAfter('.').length == DebugSeed.FINE_DECIMALS })
        assertFalse("带噪声场景不是历史粗坐标日", TracePrecision.isCoarseDay(TripTrace.samples(records())))
    }

    @Test
    fun `步行点带横向抖动而不是一条直线`() {
        val walk = TripTrace.samples(records()).filter { (it.accuracyM ?: 0f) <= TracePrecision.bandOf(0f).let { 60f } }
        assertTrue("步行点不足", walk.size > 5)
        val first = walk.first()
        val last = walk.last()
        val slope = (last.lat - first.lat).let { if (abs(it) < 1e-9) 0.0 else it }
        val maxResidual = walk.maxOf { sample ->
            val expectedLatRatio = if (abs(last.lat - first.lat) < 1e-9) 0.0 else (sample.lat - first.lat) / slope
            val expectedLng = first.lng + (last.lng - first.lng) * expectedLatRatio
            abs(sample.lng - expectedLng) * 111_320.0 * cos(Math.toRadians(sample.lat))
        }
        assertTrue("抖动没写进去：最大横向偏移 " + maxResidual + "m", maxResidual > 10.0)
    }

    @Test
    fun `既有场景仍是三位小数以演示历史粗坐标`() {
        val samples = TripTrace.samples(DebugSeed.build(SeedScenario.DAY, dayStart, now))
        assertTrue("既有场景语义不该被改动：仍应是历史粗坐标日", TracePrecision.isCoarseDay(samples))
    }
}
