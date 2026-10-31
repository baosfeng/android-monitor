package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TripTrace.samples 的**接线**测试：确认清洗真的接在了记录 → 采样点这条链路上。
 *
 * 与 TripDenoiseTest 的分工：那边钉纯函数的行为，这边钉「改对了函数却没接进去」
 * —— 反向验证时把 samples() 回退成 cap(dedupe(parse(...)))，本类必须变红。
 */
class TripTracePipelineTest {

    private fun location(ts: Long, lat: Double, lng: Double, accuracy: Float? = null): Record = Record(
        id = "loc-" + ts,
        type = RecordType.LOCATION,
        action = "COORDINATE",
        timestamp = ts,
        label = lat.toString() + "," + lng,
        detail = buildMap {
            put("lat", lat.toString())
            put("lng", lng.toString())
            if (accuracy != null) put("accuracy_m", accuracy.toInt().toString())
        },
    )

    @Test
    fun `漂移点不会出现在最终轨迹里`() {
        val records = listOf(
            location(0, 31.2300, 121.4700, 15f),
            location(10_000, 31.2301, 121.4701, 15f),
            location(20_000, 31.4000, 121.4701, 500f), // 十几公里外
            location(30_000, 31.2302, 121.4702, 15f),
        )
        val samples = TripTrace.samples(records)
        assertFalse(
            "接线断了：清洗后仍有漂移点",
            samples.any { it.lat > 31.3 },
        )
    }

    @Test
    fun `直线上的冗余中间点被几何抽稀掉`() {
        // 11 个点全部在同一条经线上等距排开，几何抽稀后应只剩首尾
        val records = (0..10).map { index ->
            location(index * 10_000L, 31.0 + index * 0.0003, 121.0, 10f)
        }
        val samples = TripTrace.samples(records)
        assertEquals(
            "接线断了：抽稀没生效，仍在画直线上的锯齿",
            2,
            samples.size,
        )
        assertEquals(0L, samples.first().timestamp)
        assertEquals(100_000L, samples.last().timestamp)
    }

    @Test
    fun `拐点不会被接线后的抽稀抹掉`() {
        // 每段约 200m / 10 秒（72km/h），在速度门槛内：不会被离群规则误删，只能被抽稀保留或抹掉
        val records = listOf(
            location(0, 31.0000, 121.0000, 10f),
            location(10_000, 31.0018, 121.0000, 10f), // 向北 200m，此处拐弯
            location(20_000, 31.0018, 121.0021, 10f), // 向东 200m
        )
        val samples = TripTrace.samples(records)
        assertEquals(3, samples.size)
        assertTrue(samples.any { it.timestamp == 10_000L })
    }

    @Test
    fun `没有精度信息的记录照常进入轨迹`() {
        // 缺 accuracy 时离群规则必须放行；这里用「每步都拐弯」的折线，
        // 几何抽稀也不会把它压成两个点，因此能区分「被漂移规则误删」与「被正常抽稀」
        val records = (0..5).map { index ->
            val lat = 31.0 + index * 0.002
            val lng = 121.0 + (if (index % 2 == 0) 0.0 else 0.002)
            location(index * 10_000L, lat, lng)
        }
        assertEquals(
            "缺 accuracy 的旧记录不能被当成漂移丢掉",
            6,
            TripTrace.samples(records).size,
        )
    }

    @Test
    fun `清洗后仍满足五百点上限且首尾正确`() {
        val records = (0 until 1_200).map { index ->
            location(index * 1_000L, 30.0 + index * 0.0001, 120.0 + index * 0.0001, 8f)
        }
        val samples = TripTrace.samples(records)
        assertTrue(samples.size <= TripTrace.MAX_POINTS)
        assertEquals(0L, samples.first().timestamp)
        assertEquals(1_199_000L, samples.last().timestamp)
    }
}
